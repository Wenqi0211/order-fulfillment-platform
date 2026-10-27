package com.wenqi.fulfillment.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConsumerFailureStore {
  private final JdbcTemplate jdbc;

  public ConsumerFailureStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public int record(String raw, String code) {
    return jdbc.queryForObject(
        "INSERT INTO consumer_failures(message_hash,attempts,failure_code) VALUES(?,1,?) ON"
            + " CONFLICT(message_hash) DO UPDATE SET"
            + " attempts=consumer_failures.attempts+1,failure_code=EXCLUDED.failure_code,last_failed_at=now()"
            + " RETURNING attempts",
        Integer.class,
        OrderJson.sha256(raw),
        code);
  }

  public void deadLettered(String raw) {
    jdbc.update(
        "UPDATE consumer_failures SET dead_lettered_at=now() WHERE message_hash=?",
        OrderJson.sha256(raw));
  }
}
