package com.wenqi.fulfillment.payment;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenqi.fulfillment.common.*;
import com.wenqi.fulfillment.common.Api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;

/**
 * These tests require actual PostgreSQL; they are not silently skipped if the integration profile
 * is selected.
 */
@Timeout(60)
class PaymentIT {
  static JdbcTemplate admin, jdbc;
  static DataSourceTransactionManager manager;
  static ObjectMapper mapper;
  static Events events;
  static String schema;
  final PaymentRequest input = new PaymentRequest(1500, "USD", "tok_approved");

  @BeforeAll
  static void connect() {
    var url = System.getenv("TEST_DATABASE_URL");
    assertNotNull(
        url,
        "TEST_DATABASE_URL must point to a disposable PostgreSQL JDBC database; integration tests"
            + " were not run");
    assertTrue(
        url.startsWith("jdbc:postgresql:"), "TEST_DATABASE_URL must be a PostgreSQL JDBC URL");
    var user = System.getenv().getOrDefault("TEST_DATABASE_USER", "fulfillment");
    var password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "");
    admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
    var newSchema = "payment_it_" + UUID.randomUUID().toString().replace("-", "");
    admin.execute("CREATE SCHEMA " + newSchema);
    schema = newSchema;
    var source =
        new DriverManagerDataSource(
            url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .locations("classpath:db/common", "classpath:db/migration")
        .load()
        .migrate();
    jdbc = new JdbcTemplate(source);
    manager = new DataSourceTransactionManager(source);
    mapper = new ObjectMapper().findAndRegisterModules();
    events = new Events(jdbc, mapper);
  }

  @AfterAll
  static void disconnect() {
    if (admin != null && schema != null)
      admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
  }

  @BeforeEach
  void clear() {
    jdbc.execute("TRUNCATE payments");
    jdbc.update("DELETE FROM outbox");
  }

  PaymentService service(PaymentProvider provider) {
    return new PaymentService(jdbc, manager, events, mapper, provider);
  }

  @Test
  void twentyConcurrentRetriesInvokeProviderAndPublishResultOnce() throws Exception {
    var calls = new AtomicInteger();
    var fake = new FakePaymentProvider();
    var service =
        service(
            (id, body) -> {
              calls.incrementAndGet();
              return fake.authorize(id, body);
            });
    var id = UUID.randomUUID();
    var executor = Executors.newFixedThreadPool(16);
    try {
      var tasks = new ArrayList<Callable<PaymentView>>();
      for (int i = 0; i < 20; i++) tasks.add(() -> service.authorize(id, input));
      for (var future : executor.invokeAll(tasks)) assertEquals("APPROVED", future.get().status());
    } finally {
      executor.shutdownNow();
    }
    assertEquals(1, calls.get());
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM payments", Integer.class));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class));
  }

  @Test
  void changedAmountOrTokenConflictsWithDurableIdentity() {
    var service = service(new FakePaymentProvider());
    var id = UUID.randomUUID();
    service.authorize(id, input);
    assertEquals(
        "IDEMPOTENCY_CONFLICT",
        assertThrows(
                ApiException.class,
                () -> service.authorize(id, new PaymentRequest(1600, "USD", "tok_approved")))
            .code());
    assertEquals(
        "IDEMPOTENCY_CONFLICT",
        assertThrows(
                ApiException.class,
                () -> service.authorize(id, new PaymentRequest(1500, "USD", "tok_declined")))
            .code());
  }

  @Test
  void declinedDecisionReplaysAfterServiceRestartWithoutProviderCall() {
    var id = UUID.randomUUID();
    var request = new PaymentRequest(1500, "USD", "tok_declined");
    var first = service(new FakePaymentProvider()).authorize(id, request);
    assertEquals("DECLINED", first.status());
    assertEquals(
        first,
        service(
                (key, body) -> {
                  throw new AssertionError("Provider must not be called again");
                })
            .authorize(id, request));
  }

  @Test
  void unknownOutcomePreservesPendingIntentAndRejectsChangedRetryBeforeProviderCall() {
    var first = new AtomicBoolean(true);
    var keys = new ArrayList<UUID>();
    var fake = new FakePaymentProvider();
    var service =
        service(
            (id, body) -> {
              keys.add(id);
              var result = fake.authorize(id, body);
              if (first.getAndSet(false)) throw new IllegalStateException("injected lost response");
              return result;
            });
    var id = UUID.randomUUID();
    var failure = assertThrows(ApiException.class, () -> service.authorize(id, input));
    assertEquals("PAYMENT_OUTCOME_UNKNOWN", failure.code());
    assertEquals(503, failure.status().value());
    assertEquals(
        "PENDING",
        jdbc.queryForObject("SELECT status FROM payments WHERE order_id=?", String.class, id));
    assertNull(
        jdbc.queryForObject(
            "SELECT provider_reference FROM payments WHERE order_id=?", String.class, id));
    assertEquals(
        "IDEMPOTENCY_CONFLICT",
        assertThrows(
                ApiException.class,
                () -> service.authorize(id, new PaymentRequest(1600, "USD", "tok_approved")))
            .code());
    assertEquals(
        "IDEMPOTENCY_CONFLICT",
        assertThrows(
                ApiException.class,
                () -> service.authorize(id, new PaymentRequest(1500, "USD", "tok_declined")))
            .code());
    assertEquals(List.of(id), keys);
    assertEquals("APPROVED", service.authorize(id, input).status());
    assertEquals(List.of(id, id), keys);
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class));
  }

  @Test
  void outboxFailureRollsBackFinalDecisionButKeepsIntentForRecovery() {
    var service = service(new FakePaymentProvider());
    var id = UUID.randomUUID();
    jdbc.execute(
        "CREATE FUNCTION reject_outbox() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE"
            + " EXCEPTION 'injected outbox failure'; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_outbox BEFORE INSERT ON outbox FOR EACH ROW EXECUTE FUNCTION"
            + " reject_outbox()");
    try {
      assertThrows(
          org.springframework.dao.DataAccessException.class, () -> service.authorize(id, input));
    } finally {
      jdbc.execute("DROP TRIGGER reject_outbox ON outbox");
      jdbc.execute("DROP FUNCTION reject_outbox()");
    }
    assertEquals(
        "PENDING",
        jdbc.queryForObject("SELECT status FROM payments WHERE order_id=?", String.class, id));
    assertEquals("APPROVED", service.authorize(id, input).status());
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class));
  }

  @Test
  void invalidProviderReplyCannotPersistAFinalDecision() {
    var service = service((id, body) -> new PaymentProvider.Decision("APPROVED", null));
    var id = UUID.randomUUID();
    assertEquals(
        "INVALID_PROVIDER_REPLY",
        assertThrows(ApiException.class, () -> service.authorize(id, input)).code());
    assertEquals(
        "PENDING",
        jdbc.queryForObject("SELECT status FROM payments WHERE order_id=?", String.class, id));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class));
  }

  @Test
  void databaseRejectsInvalidAmountsAndInconsistentDecisionReferences() {
    var id = UUID.randomUUID();
    assertThrows(
        ApiException.class,
        () ->
            service(
                    (key, body) -> {
                      throw new IllegalStateException("injected timeout");
                    })
                .authorize(id, input));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE payments SET amount_minor=0 WHERE order_id=?", id));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE payments SET status='APPROVED' WHERE order_id=?", id));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "UPDATE payments SET provider_reference='unexpected' WHERE order_id=?", id));
    assertEquals(
        "PENDING",
        jdbc.queryForObject("SELECT status FROM payments WHERE order_id=?", String.class, id));
  }
}
