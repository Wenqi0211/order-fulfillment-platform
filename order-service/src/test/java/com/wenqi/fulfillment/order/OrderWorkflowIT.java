package com.wenqi.fulfillment.order;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.wenqi.fulfillment.common.*;
import com.wenqi.fulfillment.common.Api.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/** Uses actual PostgreSQL for transaction rollback, advisory locks and SKIP LOCKED. */
class OrderWorkflowIT {
  private static PGSimpleDataSource dataSource, admin;
  private static PostgreSQLContainer<?> postgres;
  private static String schema;
  private JdbcTemplate jdbc;
  private OrderRepository repository;
  private OrderService orders;
  private SagaProcessor saga;
  private WarehouseService warehouse;
  private ConsumerFailureStore failures;
  private FakeGateway gateway;
  private FaultEvents events;
  private ObjectMapper mapper;
  private TransactionTemplate tx;
  private SimpleMeterRegistry metrics;

  @BeforeAll
  static void database() {
    String url = System.getenv("TEST_DATABASE_URL"),
        user = System.getenv("TEST_DATABASE_USER"),
        password = System.getenv("TEST_DATABASE_PASSWORD");
    if (url == null || url.isBlank()) {
      postgres = new PostgreSQLContainer<>("postgres:16-alpine");
      postgres.start();
      url = postgres.getJdbcUrl();
      user = postgres.getUsername();
      password = postgres.getPassword();
    }
    admin = new PGSimpleDataSource();
    admin.setURL(url);
    admin.setUser(user == null ? "postgres" : user);
    admin.setPassword(password == null ? "postgres" : password);
    schema = "order_it_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(admin).execute("CREATE SCHEMA " + schema);
    dataSource = new PGSimpleDataSource();
    dataSource.setURL(url);
    dataSource.setUser(admin.getUser());
    dataSource.setPassword(admin.getPassword());
    dataSource.setCurrentSchema(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .defaultSchema(schema)
        .schemas(schema)
        .locations("classpath:db/common", "classpath:db/migration")
        .load()
        .migrate();
  }

  @AfterAll
  static void cleanup() {
    if (admin != null && schema != null)
      new JdbcTemplate(admin).execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    if (postgres != null) postgres.stop();
  }

  @BeforeEach
  void setup() {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute(
        "TRUNCATE"
            + " orders,outbox,order_workflow_audit,warehouse_ingress,warehouse_receipts,consumer_failures"
            + " RESTART IDENTITY CASCADE");
    tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    // Match Spring Boot's production wire format. Warehouse envelopes require
    // RFC3339 timestamps rather than Jackson's standalone numeric default.
    mapper =
        JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    var json = new OrderJson(mapper);
    repository = new OrderRepository(jdbc, json);
    gateway = new FakeGateway();
    events = new FaultEvents(jdbc, mapper);
    metrics = new SimpleMeterRegistry();
    orders =
        new OrderService(
            repository, json, gateway, events, mapper, tx, jdbc, "fulfillment.events.v1");
    saga =
        new SagaProcessor(
            repository, gateway, events, mapper, tx, metrics, "fulfillment.events.v1", 3, 1);
    warehouse =
        new WarehouseService(
            jdbc,
            repository,
            json,
            tx,
            events,
            mapper,
            metrics,
            "warehouse.status.v1",
            "fulfillment.events.v1");
    failures = new ConsumerFailureStore(jdbc);
  }

  @AfterEach
  void closeMetrics() {
    metrics.close();
  }

  private CreateOrderRequest request(String token) {
    return new CreateOrderRequest(List.of(new LineItem("A", 2)), token);
  }

  private OrderView create() {
    return orders.create(UUID.randomUUID().toString(), request("tok_approved")).order();
  }

  private void due(UUID id) {
    jdbc.update("UPDATE orders SET next_attempt_at=now()-interval '1 second' WHERE id=?", id);
  }

  private void step(UUID id) {
    due(id);
    assertTrue(saga.processNext());
  }

  private OrderView confirm() {
    var order = create();
    step(order.id());
    step(order.id());
    step(order.id());
    assertEquals("CONFIRMED", orders.get(order.id()).status());
    return orders.get(order.id());
  }

  private long count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
  }

  private String raw(WarehouseUpdate update) throws Exception {
    return mapper.writeValueAsString(
        new EventEnvelope(
            update.eventId(),
            "warehouse.status.updated",
            1,
            update.orderId().toString(),
            Instant.now(),
            mapper.valueToTree(update)));
  }

  @Test
  void concurrentSameKeyCreatesOneOrderAndOneInitialEvent() throws Exception {
    var executor = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    try {
      Callable<OrderService.Created> call =
          () -> {
            start.await();
            return orders.create("same-key", request("tok_approved"));
          };
      var first = executor.submit(call);
      var second = executor.submit(call);
      start.countDown();
      var a = first.get(10, TimeUnit.SECONDS);
      var b = second.get(10, TimeUnit.SECONDS);
      assertEquals(a.order().id(), b.order().id());
      assertNotEquals(a.created(), b.created());
      assertEquals(1, count("orders"));
      assertEquals(1, count("outbox"));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void changedPayloadRejectsBeforeAnotherQuoteAndInputOrderingIsCanonical() {
    var input =
        new CreateOrderRequest(List.of(new LineItem("B", 1), new LineItem("A", 2)), "tok_approved");
    var created = orders.create("canonical", input);
    assertEquals(
        created.order().id(),
        orders
            .create(
                "canonical",
                new CreateOrderRequest(
                    List.of(new LineItem("A", 2), new LineItem("B", 1)), "tok_approved"))
            .order()
            .id());
    assertEquals(
        "IDEMPOTENCY_CONFLICT",
        assertThrows(ApiException.class, () -> orders.create("canonical", request("tok_declined")))
            .code());
    assertEquals(1, gateway.quoteCalls.get());
  }

  @Test
  void createOutboxFailureRollsBackOrderAndIdempotencyClaim() {
    events.failType.set("order.created");
    assertThrows(
        IllegalStateException.class, () -> orders.create("rollback", request("tok_approved")));
    assertEquals(0, count("orders"));
    assertEquals(0, count("outbox"));
    assertTrue(orders.create("rollback", request("tok_approved")).created());
  }

  @Test
  void successfulSagaRunsExactlyOneDurablePhasePerTransaction() {
    var order = create();
    step(order.id());
    assertEquals("PAYMENT", orders.get(order.id()).workflowStep());
    assertEquals(0, gateway.paymentCalls.get());
    step(order.id());
    assertEquals("COMMIT", orders.get(order.id()).workflowStep());
    assertEquals("PENDING", orders.get(order.id()).status());
    step(order.id());
    assertEquals("CONFIRMED", orders.get(order.id()).status());
    assertEquals("DONE", orders.get(order.id()).workflowStep());
    assertEquals(1, gateway.paymentEffects.get());
    assertEquals(0, gateway.releaseCalls.get());
  }

  @Test
  void outerRollbackDoesNotReportCommittedPhaseAndRetainsUnknownPaymentForRetry() {
    var order = create();
    step(order.id());
    tx.executeWithoutResult(
        status -> {
          assertTrue(saga.processNext());
          assertNull(
              metrics
                  .find("fulfillment.saga.attempts")
                  .tags("step", "PAYMENT", "outcome", "advanced")
                  .counter());
          status.setRollbackOnly();
        });
    assertEquals("PAYMENT", orders.get(order.id()).workflowStep());
    assertEquals(0, orders.get(order.id()).attempts());
    assertEquals(1, gateway.paymentEffects.get());
    assertEquals(0, gateway.releaseCalls.get());
    assertEquals(
        1,
        metrics
            .get("fulfillment.saga.attempts")
            .tags("step", "PAYMENT", "outcome", "rolled_back")
            .counter()
            .count());
    assertNull(
        metrics
            .find("fulfillment.saga.attempts")
            .tags("step", "PAYMENT", "outcome", "advanced")
            .counter());
    step(order.id());
    step(order.id());
    assertEquals("CONFIRMED", orders.get(order.id()).status());
    assertEquals(1, gateway.paymentEffects.get());
  }

  @Test
  void sagaSnapshotCountsPersistedActiveStatesAndOnlyRetriesAlreadyDue() {
    var duePayment = create();
    var delayedPayment = create();
    var manualReview = create();
    var terminal = create();
    create();
    jdbc.update(
        "UPDATE orders SET workflow_step='PAYMENT',attempts=1, created_at=now()-interval '2"
            + " minutes',next_attempt_at=now()-interval '1 second' WHERE id=?",
        duePayment.id());
    jdbc.update(
        "UPDATE orders SET workflow_step='PAYMENT',attempts=2,"
            + " next_attempt_at=now()+interval '1 hour' WHERE id=?",
        delayedPayment.id());
    jdbc.update(
        "UPDATE orders SET status='MANUAL_REVIEW',workflow_step='PAYMENT',attempts=3 WHERE id=?",
        manualReview.id());
    jdbc.update(
        "UPDATE orders SET status='CONFIRMED',workflow_step='DONE',"
            + " created_at=now()-interval '1 day' WHERE id=?",
        terminal.id());
    var snapshots = new SagaBacklogMetrics(repository, metrics, 3);
    snapshots.refresh();
    assertEquals(
        2,
        metrics
            .get("fulfillment.saga.backlog")
            .tags("status", "PENDING", "step", "PAYMENT")
            .gauge()
            .value());
    assertEquals(
        1,
        metrics
            .get("fulfillment.saga.backlog")
            .tags("status", "MANUAL_REVIEW", "step", "PAYMENT")
            .gauge()
            .value());
    assertEquals(
        1,
        metrics
            .get("fulfillment.saga.backlog")
            .tags("status", "PENDING", "step", "RESERVE")
            .gauge()
            .value());
    assertEquals(
        1, metrics.get("fulfillment.saga.retry.due").tag("step", "PAYMENT").gauge().value());
    assertEquals(
        0, metrics.get("fulfillment.saga.retry.due").tag("step", "RESERVE").gauge().value());
    assertEquals(
        0,
        metrics
            .get("fulfillment.saga.backlog")
            .tags("status", "PENDING", "step", "DONE")
            .gauge()
            .value());
    double age = metrics.get("fulfillment.saga.oldest.pending.age").gauge().value();
    assertTrue(age >= 119 && age < 130, "Pending age excludes old terminal orders");

    jdbc.update("UPDATE orders SET status='CANCELLED',workflow_step='DONE'");
    snapshots.refresh();
    assertEquals(0, metrics.get("fulfillment.saga.oldest.pending.age").gauge().value());
    assertTrue(
        metrics.find("fulfillment.saga.backlog").gauges().stream().allMatch(g -> g.value() == 0));
    assertTrue(
        metrics.find("fulfillment.saga.retry.due").gauges().stream().allMatch(g -> g.value() == 0));
  }

  @Test
  void insufficientStockCancelsWithoutCallingPayment() {
    gateway.shortage = true;
    var order = create();
    step(order.id());
    assertEquals("CANCELLED", orders.get(order.id()).status());
    assertEquals(0, gateway.paymentCalls.get());
  }

  @Test
  void paymentDeclineRemainsPendingUntilReleaseIsDurablyCompleted() {
    var order = orders.create("decline", request("tok_declined")).order();
    step(order.id());
    step(order.id());
    assertEquals("COMPENSATE", orders.get(order.id()).workflowStep());
    assertEquals("PENDING", orders.get(order.id()).status());
    assertEquals(0, gateway.releaseCalls.get());
    step(order.id());
    assertEquals("CANCELLED", orders.get(order.id()).status());
    assertEquals(1, gateway.releaseCalls.get());
  }

  @Test
  void lostPaymentResponseRetriesStableOrderIdAndNeverReleasesUnknownOutcome() {
    var order = create();
    step(order.id());
    gateway.losePaymentResponse.set(true);
    step(order.id());
    assertEquals("PAYMENT", orders.get(order.id()).workflowStep());
    assertEquals(1, orders.get(order.id()).attempts());
    assertEquals(0, gateway.releaseCalls.get());
    assertEquals(1, gateway.paymentEffects.get());
    step(order.id());
    step(order.id());
    assertEquals("CONFIRMED", orders.get(order.id()).status());
    assertEquals(2, gateway.paymentCalls.get());
    assertEquals(1, gateway.paymentEffects.get());
    assertEquals(0, gateway.releaseCalls.get());
  }

  @Test
  void crashAfterExternalEffectRollsBackPhaseThenRetryReusesPayment() {
    var order = create();
    step(order.id());
    events.failType.set("order.workflow.advanced");
    assertThrows(IllegalStateException.class, () -> step(order.id()));
    assertEquals("PAYMENT", orders.get(order.id()).workflowStep());
    assertEquals(0, orders.get(order.id()).attempts());
    assertEquals(1, gateway.paymentEffects.get());
    step(order.id());
    step(order.id());
    assertEquals("CONFIRMED", orders.get(order.id()).status());
    assertEquals(1, gateway.paymentEffects.get());
  }

  @Test
  void crashAfterReservationRecoversSameReservationWithoutPaymentBeforePersistedPhase() {
    var order = create();
    events.failType.set("order.workflow.advanced");
    assertThrows(IllegalStateException.class, () -> step(order.id()));
    assertEquals("RESERVE", orders.get(order.id()).workflowStep());
    assertEquals(1, gateway.reservations.size());
    assertEquals(0, gateway.paymentCalls.get());
    step(order.id());
    step(order.id());
    step(order.id());
    assertEquals("CONFIRMED", orders.get(order.id()).status());
    assertEquals(1, gateway.reservations.size());
  }

  @Test
  void crashAfterInventoryCommitRetriesCommittedReservationWithoutAnotherPayment() {
    var order = create();
    step(order.id());
    step(order.id());
    events.failType.set("order.workflow.advanced");
    assertThrows(IllegalStateException.class, () -> step(order.id()));
    assertEquals("COMMIT", orders.get(order.id()).workflowStep());
    assertEquals("COMMITTED", gateway.reservations.get(order.id()).status());
    step(order.id());
    assertEquals("CONFIRMED", orders.get(order.id()).status());
    assertEquals(1, gateway.paymentCalls.get());
    assertEquals(0, gateway.releaseCalls.get());
  }

  @Test
  void crashAfterReleaseKeepsCompensationPhaseUntilIdempotentRetryCompletes() {
    var order = orders.create("release-crash", request("tok_declined")).order();
    step(order.id());
    step(order.id());
    events.failType.set("order.workflow.advanced");
    assertThrows(IllegalStateException.class, () -> step(order.id()));
    assertEquals("COMPENSATE", orders.get(order.id()).workflowStep());
    assertEquals("RELEASED", gateway.reservations.get(order.id()).status());
    step(order.id());
    assertEquals("CANCELLED", orders.get(order.id()).status());
    assertEquals(1, gateway.paymentCalls.get());
  }

  @Test
  void skipLockedDoesNotExecuteSameInFlightOrderFromAnotherWorker() throws Exception {
    var order = create();
    gateway.reserveEntered = new CountDownLatch(1);
    gateway.reserveProceed = new CountDownLatch(1);
    var executor = Executors.newSingleThreadExecutor();
    try {
      var first = executor.submit(saga::processNext);
      assertTrue(gateway.reserveEntered.await(5, TimeUnit.SECONDS));
      assertFalse(saga.processNext());
      gateway.reserveProceed.countDown();
      assertTrue(first.get(10, TimeUnit.SECONDS));
      assertEquals(1, gateway.reserveCalls.get());
      assertEquals("PAYMENT", orders.get(order.id()).workflowStep());
    } finally {
      gateway.reserveProceed.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void exhaustedRetriesParkAndOperatorResumesPersistedPaymentPhase() {
    var order = create();
    step(order.id());
    gateway.unavailablePayment = true;
    step(order.id());
    step(order.id());
    step(order.id());
    var parked = orders.get(order.id());
    assertEquals("MANUAL_REVIEW", parked.status());
    assertEquals("PAYMENT", parked.workflowStep());
    assertEquals(3, parked.attempts());
    assertEquals(0, gateway.releaseCalls.get());
    var retry = orders.retry(order.id());
    assertEquals("PAYMENT", retry.workflowStep());
    assertEquals(0, retry.attempts());
    gateway.unavailablePayment = false;
    step(order.id());
    step(order.id());
    assertEquals("CONFIRMED", orders.get(order.id()).status());
    assertEquals(
        "WORKFLOW_NOT_PARKED",
        assertThrows(ApiException.class, () -> orders.retry(order.id())).code());
  }

  @Test
  void malformedPaymentResponseParksImmediatelyInsteadOfConfirmingOrCompensating() {
    var order = create();
    step(order.id());
    gateway.wrongPaymentAmount = true;
    step(order.id());
    assertEquals("MANUAL_REVIEW", orders.get(order.id()).status());
    assertEquals("INVALID_DEPENDENCY_RESPONSE", orders.get(order.id()).failureCode());
    assertEquals("PAYMENT", orders.get(order.id()).workflowStep());
    assertEquals(0, gateway.releaseCalls.get());
  }

  @Test
  void warehouseIngressIsAsynchronousImmutableAndTransactional() {
    var order = confirm();
    UUID event = UUID.randomUUID();
    var update = new WarehouseUpdate(event, order.id(), 1, "PACKED");
    assertEquals(event, warehouse.ingest(update).get("eventId"));
    warehouse.ingest(update);
    assertEquals("CONFIRMED", orders.get(order.id()).status());
    assertEquals(1, count("warehouse_ingress"));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox WHERE topic='warehouse.status.v1'", Long.class));
    assertEquals(
        "IDEMPOTENCY_CONFLICT",
        assertThrows(
                ApiException.class,
                () -> warehouse.ingest(new WarehouseUpdate(event, order.id(), 2, "SHIPPED")))
            .code());
  }

  @Test
  void duplicateOutOfOrderAndBackwardEventsHaveMonotonicStateAndSequence() throws Exception {
    var order = confirm();
    var shipped = new WarehouseUpdate(UUID.randomUUID(), order.id(), 5, "SHIPPED");
    assertEquals("APPLIED", warehouse.apply(raw(shipped)));
    assertEquals("DUPLICATE", warehouse.apply(raw(shipped)));
    assertEquals(
        "IGNORED_STALE",
        warehouse.apply(raw(new WarehouseUpdate(UUID.randomUUID(), order.id(), 4, "PACKED"))));
    assertEquals(
        "IGNORED_BACKWARD",
        warehouse.apply(raw(new WarehouseUpdate(UUID.randomUUID(), order.id(), 8, "PACKED"))));
    assertEquals("SHIPPED", orders.get(order.id()).status());
    assertEquals(8, orders.get(order.id()).warehouseSequence());
    warehouse.apply(raw(new WarehouseUpdate(UUID.randomUUID(), order.id(), 9, "DELIVERED")));
    assertEquals("DELIVERED", orders.get(order.id()).status());
    assertEquals(4, count("warehouse_receipts"));
  }

  @Test
  void consumerOutboxFailureRollsBackBothStatusAndReceipt() throws Exception {
    var order = confirm();
    var update = new WarehouseUpdate(UUID.randomUUID(), order.id(), 1, "PACKED");
    events.failType.set("order.fulfillment.updated");
    assertThrows(IllegalStateException.class, () -> warehouse.apply(raw(update)));
    assertEquals("CONFIRMED", orders.get(order.id()).status());
    assertEquals(0, count("warehouse_receipts"));
    assertEquals(0, orders.get(order.id()).warehouseSequence());
    assertEquals("APPLIED", warehouse.apply(raw(update)));
    assertEquals(1, count("warehouse_receipts"));
  }

  @Test
  void earlyWarehouseUpdateLeavesNoReceiptAndCanApplyAfterConfirmation() throws Exception {
    var order = create();
    var update = new WarehouseUpdate(UUID.randomUUID(), order.id(), 3, "DELIVERED");
    String raw = raw(update);
    assertTrue(assertThrows(WarehouseFailure.class, () -> warehouse.apply(raw)).retryable());
    assertEquals(0, count("warehouse_receipts"));
    step(order.id());
    step(order.id());
    step(order.id());
    assertEquals("APPLIED", warehouse.apply(raw));
    assertEquals("DELIVERED", orders.get(order.id()).status());
  }

  @Test
  void processedEventIdWithChangedContentIsPermanentCollision() throws Exception {
    var order = confirm();
    UUID id = UUID.randomUUID();
    warehouse.apply(raw(new WarehouseUpdate(id, order.id(), 1, "PACKED")));
    var failure =
        assertThrows(
            WarehouseFailure.class,
            () -> warehouse.apply(raw(new WarehouseUpdate(id, order.id(), 2, "SHIPPED"))));
    assertEquals("EVENT_ID_COLLISION", failure.code());
    assertFalse(failure.retryable());
    assertEquals("PACKED", orders.get(order.id()).status());
  }

  @Test
  void operatorReplayKeepsOriginalEnvelopeAndReceiptPreventsRepeatedEffect() throws Exception {
    var order = confirm();
    var update = new WarehouseUpdate(UUID.randomUUID(), order.id(), 1, "PACKED");
    warehouse.ingest(update);
    String original =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox WHERE event_id=?", String.class, update.eventId());
    warehouse.apply(original);
    jdbc.update("UPDATE outbox SET published_at=now() WHERE event_id=?", update.eventId());
    warehouse.replay(update.eventId());
    assertEquals(
        original,
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox WHERE event_id=?", String.class, update.eventId()));
    assertNull(
        jdbc.queryForObject(
            "SELECT published_at FROM outbox WHERE event_id=?",
            java.sql.Timestamp.class,
            update.eventId()));
    assertEquals("DUPLICATE", warehouse.apply(original));
    assertEquals(1, count("warehouse_receipts"));
  }

  @Test
  void consumerFailureCountSurvivesNewStoreInstanceAndMarksOnlyConfirmedDlt() {
    assertEquals(1, failures.record("record", "ORDER_NOT_CONFIRMED"));
    assertEquals(2, new ConsumerFailureStore(jdbc).record("record", "ORDER_NOT_CONFIRMED"));
    assertNull(
        jdbc.queryForObject(
            "SELECT dead_lettered_at FROM consumer_failures", java.sql.Timestamp.class));
    failures.deadLettered("record");
    assertNotNull(
        jdbc.queryForObject(
            "SELECT dead_lettered_at FROM consumer_failures", java.sql.Timestamp.class));
  }

  private static final class FaultEvents extends Events {
    final AtomicReference<String> failType = new AtomicReference<>();

    FaultEvents(JdbcTemplate jdbc, ObjectMapper mapper) {
      super(jdbc, mapper);
    }

    @Override
    public void append(String topic, String key, EventEnvelope envelope) {
      if (envelope.type().equals(failType.get()) && failType.compareAndSet(envelope.type(), null))
        throw new IllegalStateException("Injected crash before transaction commit");
      super.append(topic, key, envelope);
    }
  }

  private static final class FakeGateway implements DependencyGateway {
    final AtomicInteger quoteCalls = new AtomicInteger(),
        reserveCalls = new AtomicInteger(),
        paymentCalls = new AtomicInteger(),
        paymentEffects = new AtomicInteger(),
        releaseCalls = new AtomicInteger();
    final Map<UUID, ReservationView> reservations = new ConcurrentHashMap<>();
    final Map<UUID, PaymentView> payments = new ConcurrentHashMap<>();
    final AtomicBoolean losePaymentResponse = new AtomicBoolean();
    boolean shortage, unavailablePayment, wrongPaymentAmount;
    CountDownLatch reserveEntered, reserveProceed;

    public QuoteResponse quote(List<LineItem> items) {
      quoteCalls.incrementAndGet();
      return new QuoteResponse(
          items.stream().map(i -> new QuotedItem(i.sku(), i.quantity(), 100, "USD")).toList(),
          items.stream().mapToLong(i -> i.quantity() * 100L).sum(),
          "USD");
    }

    public ReservationView reserve(UUID id, List<LineItem> items) {
      reserveCalls.incrementAndGet();
      if (reserveEntered != null) {
        reserveEntered.countDown();
        try {
          if (!reserveProceed.await(10, TimeUnit.SECONDS))
            throw new IllegalStateException("Test gate timed out");
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      }
      if (shortage) throw new DependencyFailure("INSUFFICIENT_STOCK", false, 409);
      return reservations.computeIfAbsent(id, k -> new ReservationView(id, "RESERVED", items));
    }

    public ReservationView commit(UUID id) {
      return reservations.compute(
          id, (key, current) -> new ReservationView(id, "COMMITTED", current.items()));
    }

    public ReservationView release(UUID id) {
      releaseCalls.incrementAndGet();
      return reservations.compute(
          id, (key, current) -> new ReservationView(id, "RELEASED", current.items()));
    }

    public PaymentView pay(UUID id, PaymentRequest request) {
      paymentCalls.incrementAndGet();
      if (unavailablePayment) throw new DependencyFailure("DEPENDENCY_UNAVAILABLE", true, 503);
      var result =
          payments.computeIfAbsent(
              id,
              k -> {
                paymentEffects.incrementAndGet();
                return new PaymentView(
                    id,
                    "tok_declined".equals(request.paymentToken()) ? "DECLINED" : "APPROVED",
                    request.amountMinor(),
                    request.currency(),
                    "simulated-" + id);
              });
      if (losePaymentResponse.compareAndSet(true, false))
        throw new DependencyFailure("DEPENDENCY_UNAVAILABLE", true, 503);
      return wrongPaymentAmount
          ? new PaymentView(
              id,
              result.status(),
              result.amountMinor() + 1,
              result.currency(),
              result.providerReference())
          : result;
    }
  }
}
