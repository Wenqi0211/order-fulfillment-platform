package com.wenqi.fulfillment.inventory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenqi.fulfillment.common.*;
import com.wenqi.fulfillment.common.Api.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL contention and transaction tests; no H2 or database mocks. */
@Timeout(60)
class InventoryIT {
  static JdbcTemplate admin, jdbc;
  static InventoryService inventory;
  static String schema;

  @BeforeAll
  static void connect() {
    String url = System.getenv("TEST_DATABASE_URL");
    assertNotNull(
        url,
        "TEST_DATABASE_URL must point to a disposable PostgreSQL JDBC database; integration tests"
            + " were not run");
    assertTrue(
        url.startsWith("jdbc:postgresql:"), "TEST_DATABASE_URL must be a PostgreSQL JDBC URL");
    var user = System.getenv().getOrDefault("TEST_DATABASE_USER", "fulfillment");
    var password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "");
    admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
    var newSchema = "inventory_it_" + UUID.randomUUID().toString().replace("-", "");
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
    var mapper = new ObjectMapper().findAndRegisterModules();
    inventory =
        new InventoryService(
            jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(source)),
            new Events(jdbc, mapper),
            mapper,
            new CatalogCache(
                mock(StringRedisTemplate.class), mapper, new SimpleMeterRegistry(), false, 30),
            new SimpleMeterRegistry());
  }

  @AfterAll
  static void disconnect() {
    if (admin != null && schema != null)
      admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
  }

  @BeforeEach
  void clear() {
    jdbc.execute("TRUNCATE reservation_audit,reservations,stock,catalog_items CASCADE");
    jdbc.update("DELETE FROM outbox");
  }

  void seed(String sku, int count) {
    inventory.create(new ItemInput(sku, "Product " + sku, 500, "USD", count));
  }

  ReserveRequest items(String sku, int count) {
    return new ReserveRequest(List.of(new LineItem(sku, count)));
  }

  @Test
  void fiftyConcurrentOrdersCannotReserveMoreThanTenUnits() throws Exception {
    seed("coffee", 10);
    var outcomes =
        race(
            50,
            () -> {
              try {
                inventory.reserve(UUID.randomUUID(), items("coffee", 1));
                return "ok";
              } catch (ApiException error) {
                return error.code();
              }
            });
    assertEquals(10, outcomes.stream().filter("ok"::equals).count());
    assertEquals(40, outcomes.stream().filter("INSUFFICIENT_STOCK"::equals).count());
    assertEquals(new StockView("coffee", 10, 10, 0), inventory.stock("coffee"));
  }

  @Test
  void twentyDuplicateRequestsMutateStockAndWriteEventOnce() throws Exception {
    seed("coffee", 10);
    var id = UUID.randomUUID();
    var results = race(20, () -> inventory.reserve(id, items("coffee", 3)));
    assertTrue(
        results.stream()
            .allMatch(result -> result.orderId().equals(id) && result.status().equals("RESERVED")));
    assertEquals(3, inventory.stock("coffee").reserved());
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM reservations", Integer.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox WHERE payload->>'type'='inventory.reserved'",
            Integer.class));
  }

  @Test
  void insufficientSecondSkuRollsBackWholeReservation() {
    seed("a", 10);
    seed("z", 1);
    var request = new ReserveRequest(List.of(new LineItem("a", 2), new LineItem("z", 2)));
    assertEquals(
        "INSUFFICIENT_STOCK",
        assertThrows(ApiException.class, () -> inventory.reserve(UUID.randomUUID(), request))
            .code());
    assertEquals(0, inventory.stock("a").reserved());
    assertEquals(0, inventory.stock("z").reserved());
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reservations", Integer.class));
  }

  @Test
  void changedRetryCannotReserveMoreUnits() {
    seed("coffee", 10);
    var id = UUID.randomUUID();
    inventory.reserve(id, items("coffee", 2));
    assertEquals(
        "IDEMPOTENCY_CONFLICT",
        assertThrows(ApiException.class, () -> inventory.reserve(id, items("coffee", 3))).code());
    assertEquals(2, inventory.stock("coffee").reserved());
  }

  @Test
  void commitRetriesDecrementPhysicalStockOnlyOnce() throws Exception {
    seed("coffee", 10);
    var id = UUID.randomUUID();
    inventory.reserve(id, items("coffee", 3));
    race(10, () -> inventory.commit(id));
    assertEquals(new StockView("coffee", 7, 0, 7), inventory.stock("coffee"));
    assertEquals(
        "RESERVATION_STATE_CONFLICT",
        assertThrows(ApiException.class, () -> inventory.release(id)).code());
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM reservation_audit WHERE order_id=?", Integer.class, id));
  }

  @Test
  void releaseRetriesRestoreAvailabilityOnlyOnce() throws Exception {
    seed("coffee", 10);
    var id = UUID.randomUUID();
    inventory.reserve(id, items("coffee", 3));
    race(10, () -> inventory.release(id));
    assertEquals(new StockView("coffee", 10, 0, 10), inventory.stock("coffee"));
    assertEquals(
        "RESERVATION_STATE_CONFLICT",
        assertThrows(ApiException.class, () -> inventory.commit(id)).code());
  }

  @Test
  void equivalentItemOrderIsSameIdempotentRequest() {
    seed("a", 10);
    seed("z", 10);
    var id = UUID.randomUUID();
    var first =
        inventory.reserve(
            id, new ReserveRequest(List.of(new LineItem("z", 2), new LineItem("a", 1))));
    assertEquals(
        first,
        inventory.reserve(
            id, new ReserveRequest(List.of(new LineItem("a", 1), new LineItem("z", 2)))));
  }

  @Test
  void commitVersusReleaseRaceHasExactlyOneTerminalWinner() throws Exception {
    seed("coffee", 10);
    var id = UUID.randomUUID();
    inventory.reserve(id, items("coffee", 4));
    var executor = Executors.newFixedThreadPool(2);
    try {
      var results =
          executor.invokeAll(
              List.<Callable<String>>of(
                  () -> terminal(() -> inventory.commit(id)),
                  () -> terminal(() -> inventory.release(id))));
      var outcomes = List.of(results.get(0).get(), results.get(1).get());
      assertEquals(1, outcomes.stream().filter("RESERVATION_STATE_CONFLICT"::equals).count());
      assertEquals(0, inventory.stock("coffee").reserved());
      assertEquals(outcomes.contains("COMMITTED") ? 6 : 10, inventory.stock("coffee").onHand());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void outboxInsertFailureRollsBackReservationAuditAndStock() {
    seed("coffee", 10);
    jdbc.execute(
        "CREATE FUNCTION reject_outbox() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE"
            + " EXCEPTION 'injected outbox failure'; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_outbox BEFORE INSERT ON outbox FOR EACH ROW EXECUTE FUNCTION"
            + " reject_outbox()");
    try {
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> inventory.reserve(UUID.randomUUID(), items("coffee", 2)));
    } finally {
      jdbc.execute("DROP TRIGGER reject_outbox ON outbox");
      jdbc.execute("DROP FUNCTION reject_outbox()");
    }
    assertEquals(0, inventory.stock("coffee").reserved());
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reservations", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reservation_audit", Integer.class));
  }

  @Test
  void databaseRejectsOversoldOrNegativeStockIndependentlyOfApplication() {
    seed("coffee", 10);
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE stock SET reserved=11 WHERE sku='coffee'"));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE stock SET on_hand=-1 WHERE sku='coffee'"));
  }

  @Test
  void quotesReadAuthoritativeDatabasePrices() {
    seed("coffee", 10);
    jdbc.update(
        "UPDATE catalog_items SET unit_price_minor=700,version=version+1 WHERE sku='coffee'");
    var cache = mock(CatalogCache.class);
    var mapper = new ObjectMapper().findAndRegisterModules();
    var source = jdbc.getDataSource();
    var service =
        new InventoryService(
            jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(source)),
            new Events(jdbc, mapper),
            mapper,
            cache,
            new SimpleMeterRegistry());
    assertEquals(
        new QuoteResponse(List.of(new QuotedItem("coffee", 3, 700, "USD")), 2100, "USD"),
        service.quote(new QuoteRequest(List.of(new LineItem("coffee", 3)))));
    verifyNoInteractions(cache);
  }

  @Test
  void overlappingSkuSetsWithReversedInputOrderCannotDeadlockOrOversell() throws Exception {
    seed("a", 10);
    seed("z", 10);
    var sequence = new java.util.concurrent.atomic.AtomicInteger();
    var outcomes =
        race(
            30,
            () -> {
              var lines =
                  sequence.getAndIncrement() % 2 == 0
                      ? List.of(new LineItem("a", 1), new LineItem("z", 1))
                      : List.of(new LineItem("z", 1), new LineItem("a", 1));
              try {
                inventory.reserve(UUID.randomUUID(), new ReserveRequest(lines));
                return "ok";
              } catch (ApiException error) {
                return error.code();
              }
            });
    assertEquals(10, outcomes.stream().filter("ok"::equals).count());
    assertEquals(20, outcomes.stream().filter("INSUFFICIENT_STOCK"::equals).count());
    assertEquals(10, inventory.stock("a").reserved());
    assertEquals(10, inventory.stock("z").reserved());
  }

  private String terminal(Supplier<ReservationView> operation) {
    try {
      return operation.get().status();
    } catch (ApiException e) {
      return e.code();
    }
  }

  private <T> List<T> race(int count, Supplier<T> action) throws Exception {
    var executor = Executors.newFixedThreadPool(16);
    try {
      var tasks = new ArrayList<Callable<T>>();
      for (int i = 0; i < count; i++) tasks.add(action::get);
      var results = new ArrayList<T>();
      for (var future : executor.invokeAll(tasks)) results.add(future.get());
      return results;
    } finally {
      executor.shutdownNow();
    }
  }
}
