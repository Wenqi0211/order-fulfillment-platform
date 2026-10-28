package com.wenqi.fulfillment.order;

import io.micrometer.core.instrument.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scrapes read an immutable cached snapshot and never acquire a database connection. */
@Component
@ConditionalOnProperty(
    name = "app.saga.metrics.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SagaBacklogMetrics {
  private static final Logger log = LoggerFactory.getLogger(SagaBacklogMetrics.class);

  private record Key(String status, String step) {}

  private record Snapshot(
      Map<Key, Long> backlog,
      Map<String, Long> retryDue,
      Instant oldestPending,
      long refreshedAtNanos) {}

  private final OrderRepository orders;
  private final Clock clock;
  private final int queryTimeoutSeconds;
  private final Counter refreshFailures;
  private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();
  private final AtomicBoolean refreshing = new AtomicBoolean();
  private volatile double refreshSuccess;

  public SagaBacklogMetrics(
      OrderRepository orders,
      MeterRegistry metrics,
      @Value("${app.saga.metrics.query-timeout-seconds:3}") int queryTimeoutSeconds) {
    if (queryTimeoutSeconds < 1 || queryTimeoutSeconds > 30)
      throw new IllegalArgumentException("Invalid Saga metrics query timeout");
    this.orders = orders;
    this.clock = metrics.config().clock();
    this.queryTimeoutSeconds = queryTimeoutSeconds;
    refreshFailures =
        Counter.builder("fulfillment.saga.snapshot.refresh.failures")
            .description("Failed database refreshes; the last successful Saga snapshot is retained")
            .register(metrics);
    for (var status : SagaMetricTags.BACKLOG_STATUSES)
      for (var step : SagaMetricTags.STEPS)
        Gauge.builder("fulfillment.saga.backlog", this, value -> value.backlog(status, step))
            .description("Orders awaiting Saga processing or operator review at the last refresh")
            .tags("status", status, "step", step)
            .register(metrics);
    for (var step : SagaMetricTags.STEPS)
      Gauge.builder("fulfillment.saga.retry.due", this, value -> value.retryDue(step))
          .description(
              "Pending orders with a previous failure whose retry was due at the last refresh")
          .tag("step", step)
          .register(metrics);
    Gauge.builder("fulfillment.saga.oldest.pending.age", this, SagaBacklogMetrics::oldestPendingAge)
        .description(
            "Age of the oldest pending order from the last successful snapshot; zero if empty")
        .baseUnit("seconds")
        .register(metrics);
    Gauge.builder("fulfillment.saga.snapshot.age", this, SagaBacklogMetrics::snapshotAge)
        .description("Elapsed time since the last successful snapshot; NaN until the first success")
        .baseUnit("seconds")
        .register(metrics);
    Gauge.builder("fulfillment.saga.snapshot.refresh.success", this, value -> value.refreshSuccess)
        .description(
            "One if the latest database refresh succeeded, zero before success or after failure")
        .register(metrics);
  }

  @Scheduled(fixedDelayString = "${app.saga.metrics.refresh-ms:5000}")
  public void refresh() {
    if (!refreshing.compareAndSet(false, true)) return;
    try {
      var backlog = new HashMap<Key, Long>();
      var retryDue = new HashMap<String, Long>();
      Instant oldestPending = null;
      for (var row : orders.sagaBacklog(queryTimeoutSeconds)) {
        // Do not create labels from database content or include order/customer identifiers.
        if (!SagaMetricTags.BACKLOG_STATUSES.contains(row.status())
            || !SagaMetricTags.STEPS.contains(row.step())) continue;
        backlog.put(new Key(row.status(), row.step()), row.count());
        retryDue.merge(row.step(), row.retryDue(), Long::sum);
        if (row.oldestPendingCreatedAt() != null
            && (oldestPending == null || row.oldestPendingCreatedAt().isBefore(oldestPending)))
          oldestPending = row.oldestPendingCreatedAt();
      }
      snapshot.set(
          new Snapshot(
              Map.copyOf(backlog), Map.copyOf(retryDue), oldestPending, clock.monotonicTime()));
      refreshSuccess = 1;
    } catch (RuntimeException e) {
      refreshSuccess = 0;
      refreshFailures.increment();
      log.warn("Saga metrics snapshot refresh failed cause={}", e.getClass().getSimpleName());
    } finally {
      refreshing.set(false);
    }
  }

  private double backlog(String status, String step) {
    var current = snapshot.get();
    return current == null ? Double.NaN : current.backlog().getOrDefault(new Key(status, step), 0L);
  }

  private double retryDue(String step) {
    var current = snapshot.get();
    return current == null ? Double.NaN : current.retryDue().getOrDefault(step, 0L);
  }

  private double oldestPendingAge() {
    var current = snapshot.get();
    if (current == null) return Double.NaN;
    return current.oldestPending() == null
        ? 0
        : Math.max(0, (clock.wallTime() - current.oldestPending().toEpochMilli()) / 1000.0);
  }

  private double snapshotAge() {
    var current = snapshot.get();
    return current == null
        ? Double.NaN
        : Math.max(0, (clock.monotonicTime() - current.refreshedAtNanos()) / 1_000_000_000.0);
  }
}
