package com.wenqi.fulfillment.order;

import java.util.List;

/** Metric labels are a fixed vocabulary, even if a malformed state reaches the worker. */
final class SagaMetricTags {
  static final List<String> STEPS = List.of("RESERVE", "PAYMENT", "COMMIT", "COMPENSATE", "DONE");
  static final List<String> BACKLOG_STATUSES = List.of("PENDING", "MANUAL_REVIEW");

  private SagaMetricTags() {}

  static String step(String value) {
    return value != null && STEPS.contains(value) ? value : "UNKNOWN";
  }
}
