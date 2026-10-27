package com.wenqi.fulfillment.order;

import com.wenqi.fulfillment.common.Api.*;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class OrderController {
  private final OrderService orders;
  private final WarehouseService warehouse;
  private final JdbcTemplate jdbc;

  public OrderController(OrderService orders, WarehouseService warehouse, JdbcTemplate jdbc) {
    this.orders = orders;
    this.warehouse = warehouse;
    this.jdbc = jdbc;
  }

  @PostMapping("/v1/orders")
  public ResponseEntity<OrderView> create(
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody CreateOrderRequest request) {
    var result = orders.create(key, request);
    return ResponseEntity.status(result.created() ? 201 : 200)
        .location(URI.create("/v1/orders/" + result.order().id()))
        .body(result.order());
  }

  @GetMapping("/v1/orders/{id}")
  public OrderView get(@PathVariable UUID id) {
    return orders.get(id);
  }

  @GetMapping("/v1/orders")
  public List<OrderView> list(@RequestParam(defaultValue = "20") int limit) {
    return orders.list(limit);
  }

  @PostMapping("/v1/orders/{id}/retry")
  public OrderView retry(@PathVariable UUID id) {
    return orders.retry(id);
  }

  @PostMapping("/v1/warehouse/events")
  public ResponseEntity<Map<String, UUID>> warehouse(@Valid @RequestBody WarehouseUpdate update) {
    return ResponseEntity.accepted().body(warehouse.ingest(update));
  }

  @PostMapping("/v1/warehouse/events/{eventId}/replay")
  public ResponseEntity<Map<String, UUID>> replay(@PathVariable UUID eventId) {
    return ResponseEntity.accepted().body(warehouse.replay(eventId));
  }

  @GetMapping("/v1/operations/backlog")
  public Map<String, Object> backlog() {
    var result = new LinkedHashMap<String, Object>();
    result.put("outboxPending", count("SELECT count(*) FROM outbox WHERE published_at IS NULL"));
    var oldest =
        jdbc.queryForObject(
            "SELECT min(created_at) FROM outbox WHERE published_at IS NULL",
            java.sql.Timestamp.class);
    result.put("oldestOutboxCreatedAt", oldest == null ? null : oldest.toInstant());
    result.put("pendingWorkflows", count("SELECT count(*) FROM orders WHERE status='PENDING'"));
    result.put(
        "parkedWorkflows", count("SELECT count(*) FROM orders WHERE status='MANUAL_REVIEW'"));
    result.put("consumerFailures", count("SELECT count(*) FROM consumer_failures"));
    result.put(
        "deadLetteredMessages",
        count("SELECT count(*) FROM consumer_failures WHERE dead_lettered_at IS NOT NULL"));
    return result;
  }

  private long count(String sql) {
    return jdbc.queryForObject(sql, Long.class);
  }
}
