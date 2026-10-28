package com.wenqi.fulfillment.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.*;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class SagaBacklogMetricsTest {
  private final OrderRepository orders = mock(OrderRepository.class);
  private final MockClock clock = new MockClock();
  private final SimpleMeterRegistry metrics = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
  private final SagaBacklogMetrics snapshots = new SagaBacklogMetrics(orders, metrics, 3);

  private double gauge(String name, String... tags) {
    return metrics.get(name).tags(tags).gauge().value();
  }

  @AfterEach
  void close() {
    metrics.close();
  }

  @Test
  void scrapesUseOnlyCachedDatabaseCountsAndBoundedLabels() {
    assertTrue(
        Double.isNaN(gauge("fulfillment.saga.backlog", "status", "PENDING", "step", "PAYMENT")));
    assertTrue(Double.isNaN(gauge("fulfillment.saga.snapshot.age")));
    assertEquals(0, gauge("fulfillment.saga.snapshot.refresh.success"));
    verifyNoInteractions(orders);

    Instant oldest = Instant.ofEpochMilli(clock.wallTime()).minusSeconds(90);
    when(orders.sagaBacklog(3))
        .thenReturn(
            List.of(
                new OrderRepository.SagaBacklog("PENDING", "PAYMENT", 4, 2, oldest),
                new OrderRepository.SagaBacklog("MANUAL_REVIEW", "PAYMENT", 3, 0, null),
                new OrderRepository.SagaBacklog("PENDING", "COMMIT", 1, 0, oldest.plusSeconds(20)),
                new OrderRepository.SagaBacklog("PENDING", "untrusted-order-id", 99, 99, oldest)));
    snapshots.refresh();
    clearInvocations(orders);

    for (int i = 0; i < 3; i++) {
      assertEquals(4, gauge("fulfillment.saga.backlog", "status", "PENDING", "step", "PAYMENT"));
      assertEquals(
          3, gauge("fulfillment.saga.backlog", "status", "MANUAL_REVIEW", "step", "PAYMENT"));
      assertEquals(0, gauge("fulfillment.saga.backlog", "status", "PENDING", "step", "COMPENSATE"));
      assertEquals(2, gauge("fulfillment.saga.retry.due", "step", "PAYMENT"));
      assertEquals(90, gauge("fulfillment.saga.oldest.pending.age"));
    }
    assertEquals(10, metrics.find("fulfillment.saga.backlog").gauges().size());
    assertEquals(5, metrics.find("fulfillment.saga.retry.due").gauges().size());
    assertTrue(
        metrics.getMeters().stream()
            .noneMatch(m -> m.getId().getTags().toString().contains("untrusted")));
    verifyNoInteractions(orders);
  }

  @Test
  void failedRefreshRetainsLastGoodSnapshotAndExposesStalenessUntilRecovery() {
    Instant oldest = Instant.ofEpochMilli(clock.wallTime()).minusSeconds(12);
    when(orders.sagaBacklog(3))
        .thenReturn(List.of(new OrderRepository.SagaBacklog("PENDING", "PAYMENT", 2, 1, oldest)))
        .thenThrow(new IllegalStateException("database unavailable"))
        .thenReturn(List.of());
    snapshots.refresh();
    clock.add(Duration.ofSeconds(7));
    snapshots.refresh();
    assertEquals(0, gauge("fulfillment.saga.snapshot.refresh.success"));
    assertEquals(1, metrics.get("fulfillment.saga.snapshot.refresh.failures").counter().count());
    assertEquals(7, gauge("fulfillment.saga.snapshot.age"));
    assertEquals(19, gauge("fulfillment.saga.oldest.pending.age"));
    assertEquals(2, gauge("fulfillment.saga.backlog", "status", "PENDING", "step", "PAYMENT"));

    snapshots.refresh();
    assertEquals(1, gauge("fulfillment.saga.snapshot.refresh.success"));
    assertEquals(0, gauge("fulfillment.saga.snapshot.age"));
    assertEquals(0, gauge("fulfillment.saga.oldest.pending.age"));
    assertEquals(0, gauge("fulfillment.saga.backlog", "status", "PENDING", "step", "PAYMENT"));
    assertEquals(0, gauge("fulfillment.saga.retry.due", "step", "PAYMENT"));
  }

  @Test
  void overlappingRefreshDoesNotAcquireAnotherDatabaseConnection() throws Exception {
    var entered = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    when(orders.sagaBacklog(3))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              if (!proceed.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("Test timeout");
              return List.of();
            });
    var executor = Executors.newSingleThreadExecutor();
    try {
      var first = executor.submit(snapshots::refresh);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      snapshots.refresh();
      assertTrue(Double.isNaN(gauge("fulfillment.saga.snapshot.age")));
      proceed.countDown();
      first.get(5, TimeUnit.SECONDS);
      verify(orders, times(1)).sagaBacklog(3);
    } finally {
      proceed.countDown();
      executor.shutdownNow();
    }
  }
}
