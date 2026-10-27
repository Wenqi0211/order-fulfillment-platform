package com.wenqi.fulfillment.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenqi.fulfillment.common.*;
import com.wenqi.fulfillment.common.Api.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WarehouseService {
  private final JdbcTemplate jdbc;
  private final OrderRepository orders;
  private final OrderJson json;
  private final TransactionTemplate tx;
  private final Events events;
  private final ObjectMapper mapper;
  private final MeterRegistry metrics;
  private final String warehouseTopic, eventsTopic;

  public WarehouseService(
      JdbcTemplate jdbc,
      OrderRepository orders,
      OrderJson json,
      TransactionTemplate tx,
      Events events,
      ObjectMapper mapper,
      MeterRegistry metrics,
      @Value("${app.warehouse-topic:warehouse.status.v1}") String warehouseTopic,
      @Value("${app.events-topic:fulfillment.events.v1}") String eventsTopic) {
    this.jdbc = jdbc;
    this.orders = orders;
    this.json = json;
    this.tx = tx;
    this.events = events;
    this.mapper = mapper;
    this.metrics = metrics;
    this.warehouseTopic = warehouseTopic;
    this.eventsTopic = eventsTopic;
  }

  public Map<String, UUID> ingest(WarehouseUpdate input) {
    final WarehouseUpdate update;
    try {
      update = WarehouseValidation.update(input);
    } catch (WarehouseFailure e) {
      throw OrderValidation.invalid("Invalid warehouse update");
    }
    String hash = json.fingerprint(update);
    return tx.execute(
        status -> {
          eventLock("ingress:", update.eventId());
          var previous =
              jdbc.queryForList(
                  "SELECT request_hash FROM warehouse_ingress WHERE event_id=?", update.eventId());
          if (!previous.isEmpty()) {
            if (!hash.equals(previous.get(0).get("request_hash")))
              throw new ApiException(
                  HttpStatus.CONFLICT,
                  "IDEMPOTENCY_CONFLICT",
                  "Event ID already belongs to another update");
            return Map.of("eventId", update.eventId());
          }
          if (orders.find(update.orderId()).isEmpty())
            throw new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order was not found");
          jdbc.update(
              "INSERT INTO warehouse_ingress(event_id,order_id,request_hash,payload_json)"
                  + " VALUES(?,?,?,?::jsonb)",
              update.eventId(),
              update.orderId(),
              hash,
              json.write(update));
          var envelope =
              new EventEnvelope(
                  update.eventId(),
                  "warehouse.status.updated",
                  1,
                  update.orderId().toString(),
                  Instant.now(),
                  mapper.valueToTree(update));
          events.append(warehouseTopic, update.orderId().toString(), envelope);
          return Map.of("eventId", update.eventId());
        });
  }

  public Map<String, UUID> replay(UUID id) {
    return tx.execute(
        status -> {
          var found =
              jdbc.queryForList(
                  "SELECT event_id FROM warehouse_ingress WHERE event_id=? FOR UPDATE", id);
          if (found.isEmpty())
            throw new ApiException(
                HttpStatus.NOT_FOUND,
                "WAREHOUSE_EVENT_NOT_FOUND",
                "Only an originally ingested event can be replayed");
          int count =
              jdbc.update(
                  "UPDATE outbox SET"
                      + " published_at=NULL,attempts=0,next_attempt_at=now(),last_error=NULL WHERE"
                      + " event_id=? AND topic=?",
                  id,
                  warehouseTopic);
          if (count != 1)
            throw new ApiException(
                HttpStatus.CONFLICT,
                "OUTBOX_EVENT_NOT_FOUND",
                "The original outbox event is unavailable");
          jdbc.update(
              "UPDATE warehouse_ingress SET replay_count=replay_count+1,last_replayed_at=now()"
                  + " WHERE event_id=?",
              id);
          return Map.of("eventId", id);
        });
  }

  public String apply(String raw) {
    var update = WarehouseValidation.parse(mapper, raw);
    String hash = json.fingerprint(update);
    String outcome =
        tx.execute(
            status -> {
              // Event lock also serializes a collision that references a different
              // order, so receipt uniqueness never becomes a transient SQL failure.
              eventLock("receipt:", update.eventId());
              var previous =
                  jdbc.queryForList(
                      "SELECT request_hash FROM warehouse_receipts WHERE event_id=?",
                      update.eventId());
              if (!previous.isEmpty()) {
                if (!hash.equals(previous.get(0).get("request_hash")))
                  throw new WarehouseFailure("EVENT_ID_COLLISION", false);
                return "DUPLICATE";
              }
              var order =
                  orders
                      .lock(update.orderId())
                      .orElseThrow(() -> new WarehouseFailure("ORDER_NOT_FOUND", true))
                      .view();
              var decision = WarehouseValidation.decide(order, update);
              jdbc.update(
                  "UPDATE orders SET status=?,warehouse_sequence=?,updated_at=now() WHERE id=?",
                  decision.status(),
                  decision.sequence(),
                  order.id());
              jdbc.update(
                  "INSERT INTO warehouse_receipts(event_id,order_id,request_hash,outcome)"
                      + " VALUES(?,?,?,?)",
                  update.eventId(),
                  order.id(),
                  hash,
                  decision.outcome());
              if (decision.changed())
                events.append(
                    eventsTopic,
                    order.id().toString(),
                    EventEnvelope.of(
                        "order.fulfillment.updated",
                        order.id().toString(),
                        Map.of(
                            "orderId",
                            order.id(),
                            "previousStatus",
                            order.status(),
                            "status",
                            decision.status(),
                            "warehouseSequence",
                            decision.sequence()),
                        mapper));
              return decision.outcome();
            });
    metrics.counter("fulfillment.warehouse.events", "outcome", outcome).increment();
    return outcome;
  }

  private void eventLock(String scope, UUID id) {
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
        Object.class,
        "warehouse-" + scope + id);
  }
}
