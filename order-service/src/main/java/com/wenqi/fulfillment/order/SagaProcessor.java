package com.wenqi.fulfillment.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenqi.fulfillment.common.*;
import com.wenqi.fulfillment.common.Api.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SagaProcessor {
  private final OrderRepository orders;
  private final DependencyGateway gateway;
  private final Events events;
  private final ObjectMapper mapper;
  private final TransactionTemplate tx;
  private final MeterRegistry metrics;
  private final String topic;
  private final int maxAttempts;
  private final long baseBackoff;

  public SagaProcessor(
      OrderRepository orders,
      DependencyGateway gateway,
      Events events,
      ObjectMapper mapper,
      TransactionTemplate tx,
      MeterRegistry metrics,
      @Value("${app.events-topic:fulfillment.events.v1}") String topic,
      @Value("${app.worker.max-attempts:20}") int maxAttempts,
      @Value("${app.worker.backoff-ms:1000}") long baseBackoff) {
    if (maxAttempts < 1 || maxAttempts > 100 || baseBackoff < 1 || baseBackoff > 30000)
      throw new IllegalArgumentException("Invalid worker retry limits");
    this.orders = orders;
    this.gateway = gateway;
    this.events = events;
    this.mapper = mapper;
    this.tx = tx;
    this.metrics = metrics;
    this.topic = topic;
    this.maxAttempts = maxAttempts;
    this.baseBackoff = baseBackoff;
  }

  public boolean processNext() {
    return Boolean.TRUE.equals(
        tx.execute(
            status -> {
              var selected = orders.nextDue();
              if (selected.isEmpty()) return false;
              var stored = selected.get();
              var order = stored.view();
              try {
                switch (order.workflowStep()) {
                  case "RESERVE" -> {
                    var reservation = gateway.reserve(order.id(), OrderValidation.requested(order));
                    OrderValidation.reservation(
                        order, reservation, Set.of("RESERVED", "COMMITTED"));
                    advance(order, "PENDING", "PAYMENT", "INVENTORY_RESERVED");
                  }
                  case "PAYMENT" -> {
                    var payment =
                        gateway.pay(
                            order.id(),
                            new PaymentRequest(
                                order.totalMinor(), order.currency(), stored.paymentToken()));
                    OrderValidation.payment(order, payment);
                    advance(
                        order,
                        "PENDING",
                        "APPROVED".equals(payment.status()) ? "COMMIT" : "COMPENSATE",
                        "PAYMENT_" + payment.status());
                  }
                  case "COMMIT" -> {
                    OrderValidation.reservation(
                        order, gateway.commit(order.id()), Set.of("COMMITTED"));
                    advance(order, "CONFIRMED", "DONE", "CONFIRMED");
                  }
                  case "COMPENSATE" -> {
                    OrderValidation.reservation(
                        order, gateway.release(order.id()), Set.of("RELEASED"));
                    advance(order, "CANCELLED", "DONE", "PAYMENT_DECLINED");
                  }
                  default -> throw DependencyFailure.invalid();
                }
              } catch (DependencyFailure e) {
                if ("RESERVE".equals(order.workflowStep())
                    && e.status() == 409
                    && "INSUFFICIENT_STOCK".equals(e.code())) {
                  advance(order, "CANCELLED", "DONE", "INSUFFICIENT_STOCK");
                } else {
                  boolean park = !e.retryable() || order.attempts() + 1 >= maxAttempts;
                  orders.failure(order, e.code(), park, delay(order.attempts()));
                  metrics
                      .counter(
                          "fulfillment.saga.attempts",
                          "step",
                          order.workflowStep(),
                          "outcome",
                          park ? "parked" : "deferred")
                      .increment();
                  if (park)
                    emit(
                        order,
                        "order.workflow.parked",
                        Map.of(
                            "orderId",
                            order.id(),
                            "workflowStep",
                            order.workflowStep(),
                            "failureCode",
                            e.code()));
                }
              }
              // Database/event failures intentionally escape and roll back the phase.
              // Retrying the same order ID recovers a completed dependency side effect.
              return true;
            }));
  }

  long delay(int previousAttempts) {
    long ceiling = Math.min(30000, baseBackoff * (1L << Math.min(previousAttempts, 15)));
    return ceiling
        + ThreadLocalRandom.current().nextLong(Math.max(1, Math.min(1000, 30001 - ceiling)));
  }

  private void advance(OrderView order, String status, String step, String outcome) {
    orders.advance(order, status, step, outcome);
    emit(
        order,
        "order.workflow.advanced",
        Map.of(
            "orderId",
            order.id(),
            "previousStep",
            order.workflowStep(),
            "workflowStep",
            step,
            "status",
            status,
            "outcome",
            outcome));
    metrics
        .counter("fulfillment.saga.attempts", "step", order.workflowStep(), "outcome", "advanced")
        .increment();
  }

  private void emit(OrderView order, String type, Object payload) {
    events.append(
        topic,
        order.id().toString(),
        EventEnvelope.of(type, order.id().toString(), payload, mapper));
  }
}
