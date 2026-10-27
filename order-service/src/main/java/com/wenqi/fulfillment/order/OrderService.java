package com.wenqi.fulfillment.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenqi.fulfillment.common.*;
import com.wenqi.fulfillment.common.Api.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderService {
  public record Created(OrderView order, boolean created) {}

  private final OrderRepository orders;
  private final OrderJson json;
  private final DependencyGateway gateway;
  private final Events events;
  private final ObjectMapper mapper;
  private final TransactionTemplate tx;
  private final JdbcTemplate jdbc;
  private final String topic;

  public OrderService(
      OrderRepository orders,
      OrderJson json,
      DependencyGateway gateway,
      Events events,
      ObjectMapper mapper,
      TransactionTemplate tx,
      JdbcTemplate jdbc,
      @Value("${app.events-topic:fulfillment.events.v1}") String topic) {
    this.orders = orders;
    this.json = json;
    this.gateway = gateway;
    this.events = events;
    this.mapper = mapper;
    this.tx = tx;
    this.jdbc = jdbc;
    this.topic = topic;
  }

  public Created create(String key, CreateOrderRequest input) {
    key = OrderValidation.key(key);
    var request = OrderValidation.normalize(input);
    String hash = json.fingerprint(request);
    var existing = orders.byKey(key);
    if (existing.isPresent()) return replay(existing.get(), hash);
    QuoteResponse quote;
    try {
      quote = OrderValidation.quote(request.items(), gateway.quote(request.items()));
    } catch (DependencyFailure e) {
      throw new ApiException(
          e.retryable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY,
          e.code(),
          "Unable to obtain an authoritative inventory quote");
    }
    final String stableKey = key;
    // Quoting is outside the transaction. Concurrent accepted requests converge
    // on the first immutable snapshot after acquiring the database key lock.
    return tx.execute(
        status -> {
          orders.lockKey(stableKey);
          var concurrent = orders.byKey(stableKey);
          if (concurrent.isPresent()) return replay(concurrent.get(), hash);
          var order = orders.insert(stableKey, hash, request.paymentToken(), quote);
          events.append(
              topic,
              order.id().toString(),
              EventEnvelope.of(
                  "order.created",
                  order.id().toString(),
                  Map.of(
                      "orderId",
                      order.id(),
                      "status",
                      order.status(),
                      "items",
                      order.items(),
                      "totalMinor",
                      order.totalMinor(),
                      "currency",
                      order.currency()),
                  mapper));
          return new Created(order, true);
        });
  }

  private Created replay(OrderRepository.Stored order, String hash) {
    if (!order.requestHash().equals(hash))
      throw new ApiException(
          HttpStatus.CONFLICT,
          "IDEMPOTENCY_CONFLICT",
          "Idempotency-Key already belongs to a different request");
    return new Created(order.view(), false);
  }

  public OrderView get(UUID id) {
    return orders.find(id).orElseThrow(() -> missing()).view();
  }

  public List<OrderView> list(int limit) {
    if (limit < 1 || limit > 100) throw OrderValidation.invalid("Limit must be between 1 and 100");
    return orders.list(limit);
  }

  public OrderView retry(UUID id) {
    return tx.execute(
        status -> {
          var order = orders.lock(id).orElseThrow(() -> missing()).view();
          if (!"MANUAL_REVIEW".equals(order.status()))
            throw new ApiException(
                HttpStatus.CONFLICT,
                "WORKFLOW_NOT_PARKED",
                "Only a parked workflow can be retried");
          jdbc.update(
              "UPDATE orders SET"
                  + " status='PENDING',attempts=0,failure_code=NULL,next_attempt_at=now(),updated_at=now()"
                  + " WHERE id=?",
              id);
          orders.audit(id, order.workflowStep(), "OPERATOR_RETRY", 0);
          events.append(
              topic,
              id.toString(),
              EventEnvelope.of(
                  "order.workflow.retried",
                  id.toString(),
                  Map.of("orderId", id, "workflowStep", order.workflowStep()),
                  mapper));
          return orders.find(id).orElseThrow().view();
        });
  }

  private static ApiException missing() {
    return new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order was not found");
  }
}
