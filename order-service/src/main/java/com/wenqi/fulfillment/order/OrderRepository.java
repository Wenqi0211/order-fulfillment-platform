package com.wenqi.fulfillment.order;

import com.wenqi.fulfillment.common.Api.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {
  public record Stored(OrderView view, String requestHash, String paymentToken) {}

  private final JdbcTemplate jdbc;
  private final OrderJson json;
  private final RowMapper<Stored> row;

  public OrderRepository(JdbcTemplate jdbc, OrderJson json) {
    this.jdbc = jdbc;
    this.json = json;
    row =
        (rs, n) ->
            new Stored(
                new OrderView(
                    rs.getObject("id", UUID.class),
                    rs.getString("status"),
                    json.items(rs.getString("items_json")),
                    rs.getLong("total_minor"),
                    rs.getString("currency"),
                    rs.getString("workflow_step"),
                    rs.getInt("attempts"),
                    rs.getString("failure_code"),
                    rs.getLong("warehouse_sequence"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant()),
                rs.getString("request_hash"),
                rs.getString("payment_token"));
  }

  public Optional<Stored> byKey(String key) {
    return one("SELECT * FROM orders WHERE idempotency_key=?", key);
  }

  public Optional<Stored> find(UUID id) {
    return one("SELECT * FROM orders WHERE id=?", id);
  }

  public Optional<Stored> lock(UUID id) {
    return one("SELECT * FROM orders WHERE id=? FOR UPDATE", id);
  }

  public Optional<Stored> nextDue() {
    return one(
        "SELECT * FROM orders WHERE status='PENDING' AND next_attempt_at<=now() ORDER BY"
            + " next_attempt_at,created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED");
  }

  private Optional<Stored> one(String sql, Object... params) {
    return jdbc.query(sql, row, params).stream().findFirst();
  }

  public void lockKey(String key) {
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", Object.class, "order-create:" + key);
  }

  public OrderView insert(String key, String hash, String token, QuoteResponse quote) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " orders(id,idempotency_key,request_hash,payment_token,items_json,total_minor,currency,status,workflow_step)"
            + " VALUES(?,?,?,?,?::jsonb,?,?,'PENDING','RESERVE')",
        id,
        key,
        hash,
        token,
        json.write(quote.items()),
        quote.totalMinor(),
        quote.currency());
    return find(id).orElseThrow().view();
  }

  public List<OrderView> list(int limit) {
    return jdbc
        .query("SELECT * FROM orders ORDER BY created_at DESC,id LIMIT ?", row, limit)
        .stream()
        .map(Stored::view)
        .toList();
  }

  public void advance(OrderView order, String status, String step, String outcome) {
    audit(order.id(), order.workflowStep(), outcome, order.attempts() + 1);
    jdbc.update(
        "UPDATE orders SET"
            + " status=?,workflow_step=?,attempts=0,failure_code=NULL,next_attempt_at=now(),updated_at=now()"
            + " WHERE id=?",
        status,
        step,
        order.id());
  }

  public void failure(OrderView order, String code, boolean park, long delayMs) {
    audit(order.id(), order.workflowStep(), code, order.attempts() + 1);
    jdbc.update(
        "UPDATE orders SET status=?,attempts=attempts+1,failure_code=?,next_attempt_at=now()+(? *"
            + " interval '1 millisecond'),updated_at=now() WHERE id=?",
        park ? "MANUAL_REVIEW" : "PENDING",
        code,
        delayMs,
        order.id());
  }

  public void audit(UUID id, String step, String outcome, int attempt) {
    jdbc.update(
        "INSERT INTO order_workflow_audit(order_id,step,outcome,attempt) VALUES(?,?,?,?)",
        id,
        step,
        outcome,
        attempt);
  }
}
