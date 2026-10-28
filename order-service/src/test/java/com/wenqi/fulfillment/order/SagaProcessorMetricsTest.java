package com.wenqi.fulfillment.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenqi.fulfillment.common.Api.*;
import com.wenqi.fulfillment.common.Events;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.*;
import io.micrometer.prometheusmetrics.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

class SagaProcessorMetricsTest {
  private final OrderRepository orders = mock(OrderRepository.class);
  private final DependencyGateway gateway = mock(DependencyGateway.class);
  private final Events events = mock(Events.class);
  private final MockClock clock = new MockClock();
  private final SimpleMeterRegistry metrics = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
  private final TestTransactions transactions = new TestTransactions();
  private final SagaProcessor saga =
      new SagaProcessor(
          orders,
          gateway,
          events,
          new ObjectMapper(),
          new TransactionTemplate(transactions),
          metrics,
          "test.events",
          3,
          1);

  @AfterEach
  void close() {
    metrics.close();
  }

  private OrderView selected(String step, int attempts) {
    var order =
        new OrderView(
            UUID.randomUUID(),
            "PENDING",
            List.of(new QuotedItem("A", 2, 100, "USD")),
            200,
            "USD",
            step,
            attempts,
            null,
            0,
            Instant.now(),
            Instant.now());
    when(orders.nextDue())
        .thenReturn(Optional.of(new OrderRepository.Stored(order, "hash", "tok_approved")));
    return order;
  }

  private double attempts(String step, String outcome) {
    var counter =
        metrics.find("fulfillment.saga.attempts").tags("step", step, "outcome", outcome).counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void advancementIsCountedOnlyAfterCommitAndTimerIncludesCommit() {
    var order = selected("PAYMENT", 0);
    when(gateway.pay(eq(order.id()), any()))
        .thenAnswer(
            invocation -> {
              clock.add(Duration.ofMillis(250));
              return new PaymentView(order.id(), "APPROVED", 200, "USD", "reference");
            });
    transactions.beforeCommit =
        () -> {
          assertEquals(0, attempts("PAYMENT", "advanced"));
          clock.add(Duration.ofMillis(50));
        };
    assertTrue(saga.processNext());
    assertEquals(1, attempts("PAYMENT", "advanced"));
    var timer =
        metrics
            .get("fulfillment.saga.processing")
            .tags("step", "PAYMENT", "outcome", "advanced")
            .timer();
    assertEquals(1, timer.count());
    assertEquals(300, timer.totalTime(TimeUnit.MILLISECONDS), 0.001);
    verify(orders).advance(order, "PENDING", "COMMIT", "PAYMENT_APPROVED");
    verify(gateway, never()).release(any());
  }

  @Test
  void eventFailureCountsRollbackAndNeverReportsCommittedAdvancement() {
    var order = selected("PAYMENT", 0);
    when(gateway.pay(eq(order.id()), any()))
        .thenReturn(new PaymentView(order.id(), "APPROVED", 200, "USD", "reference"));
    doThrow(new IllegalStateException("outbox write failed"))
        .when(events)
        .append(anyString(), anyString(), any());
    assertThrows(IllegalStateException.class, saga::processNext);
    assertEquals(0, attempts("PAYMENT", "advanced"));
    assertEquals(1, attempts("PAYMENT", "rolled_back"));
    assertEquals(
        1,
        metrics.get("fulfillment.saga.processing").tags("outcome", "rolled_back").timer().count());
  }

  @Test
  void commitFailureDoesNotClaimSuccessOrKnownRollback() {
    var order = selected("PAYMENT", 0);
    when(gateway.pay(eq(order.id()), any()))
        .thenReturn(new PaymentView(order.id(), "APPROVED", 200, "USD", "reference"));
    transactions.failCommit = true;
    assertThrows(TransactionSystemException.class, saga::processNext);
    assertEquals(0, attempts("PAYMENT", "advanced"));
    assertEquals(0, attempts("PAYMENT", "rolled_back"));
    assertEquals(1, attempts("PAYMENT", "unknown"));
  }

  @Test
  void explicitDeclineStartsCompensationWhileUnknownTimeoutKeepsPaymentPhase() {
    var order = selected("PAYMENT", 0);
    when(gateway.pay(eq(order.id()), any()))
        .thenReturn(new PaymentView(order.id(), "DECLINED", 200, "USD", "reference"));
    assertTrue(saga.processNext());
    verify(orders).advance(order, "PENDING", "COMPENSATE", "PAYMENT_DECLINED");
    assertEquals(1, attempts("PAYMENT", "advanced"));

    when(gateway.pay(eq(order.id()), any()))
        .thenThrow(new DependencyFailure("DEPENDENCY_UNAVAILABLE", true, 503));
    assertTrue(saga.processNext());
    verify(orders).failure(eq(order), eq("DEPENDENCY_UNAVAILABLE"), eq(false), anyLong());
    assertEquals(1, attempts("PAYMENT", "deferred"));
    verify(gateway, never()).release(any());
  }

  @Test
  void retryExhaustionParksPersistedPhaseAndDoesNotReleaseUnknownPayment() {
    var order = selected("PAYMENT", 2);
    when(gateway.pay(eq(order.id()), any()))
        .thenThrow(new DependencyFailure("DEPENDENCY_UNAVAILABLE", true, 503));
    assertTrue(saga.processNext());
    verify(orders).failure(eq(order), eq("DEPENDENCY_UNAVAILABLE"), eq(true), anyLong());
    verify(orders, never()).advance(any(), anyString(), anyString(), anyString());
    verify(gateway, never()).release(any());
    assertEquals(1, attempts("PAYMENT", "parked"));
  }

  @Test
  void idlePollDoesNotCreateAttemptOrTimer() {
    when(orders.nextDue()).thenReturn(Optional.empty());
    assertFalse(saga.processNext());
    assertTrue(metrics.find("fulfillment.saga.attempts").counters().isEmpty());
    assertTrue(metrics.find("fulfillment.saga.processing").timers().isEmpty());
  }

  @Test
  void malformedPhaseCannotCreateAnUnboundedMetricLabel() {
    var order = selected("malformed-" + UUID.randomUUID(), 0);
    assertTrue(saga.processNext());
    verify(orders).failure(eq(order), eq("INVALID_DEPENDENCY_RESPONSE"), eq(true), anyLong());
    assertEquals(1, attempts("UNKNOWN", "parked"));
    assertTrue(
        metrics.getMeters().stream()
            .noneMatch(meter -> order.workflowStep().equals(meter.getId().getTag("step"))));
  }

  @Test
  void prometheusExportsTheNamesUsedByDashboardsAndAlerts() {
    var order = selected("PAYMENT", 0);
    when(gateway.pay(eq(order.id()), any()))
        .thenReturn(new PaymentView(order.id(), "APPROVED", 200, "USD", "reference"));
    when(orders.sagaBacklog(3)).thenReturn(List.of());
    var prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    try {
      var snapshots = new SagaBacklogMetrics(orders, prometheus, 3);
      snapshots.refresh();
      var observedSaga =
          new SagaProcessor(
              orders,
              gateway,
              events,
              new ObjectMapper(),
              new TransactionTemplate(transactions),
              prometheus,
              "test.events",
              3,
              1);
      assertTrue(observedSaga.processNext());
      String exported = prometheus.scrape();
      for (String name :
          List.of(
              "fulfillment_saga_backlog{", "fulfillment_saga_retry_due{",
              "fulfillment_saga_oldest_pending_age_seconds ",
                  "fulfillment_saga_snapshot_age_seconds ",
              "fulfillment_saga_snapshot_refresh_success ",
                  "fulfillment_saga_snapshot_refresh_failures_total ",
              "fulfillment_saga_attempts_total{", "fulfillment_saga_processing_seconds_count{",
              "fulfillment_saga_processing_seconds_sum{",
                  "fulfillment_saga_processing_seconds_bucket{"))
        assertTrue(exported.contains(name), () -> "Missing exported metric: " + name);
      assertFalse(exported.contains(order.id().toString()));
    } finally {
      prometheus.close();
    }
  }

  private static final class TestTransactions extends AbstractPlatformTransactionManager {
    Runnable beforeCommit = () -> {};
    boolean failCommit;

    protected Object doGetTransaction() {
      return new Object();
    }

    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    protected void doCommit(DefaultTransactionStatus status) {
      beforeCommit.run();
      if (failCommit) throw new TransactionSystemException("Injected unknown commit result");
    }

    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
