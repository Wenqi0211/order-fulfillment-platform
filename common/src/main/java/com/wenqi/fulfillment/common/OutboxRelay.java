package com.wenqi.fulfillment.common;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** At-least-once relay. A crash after broker acknowledgement can replay an event. */
@Component
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {
  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
  private final JdbcTemplate jdbc;
  private final KafkaTemplate<String, String> kafka;
  private final TransactionTemplate tx;
  private final MeterRegistry metrics;

  public OutboxRelay(
      JdbcTemplate jdbc,
      KafkaTemplate<String, String> kafka,
      TransactionTemplate tx,
      MeterRegistry metrics) {
    this.jdbc = jdbc;
    this.kafka = kafka;
    this.tx = tx;
    this.metrics = metrics;
    metrics.gauge(
        "fulfillment.outbox.pending",
        this,
        relay -> {
          try {
            return jdbc.queryForObject(
                "SELECT count(*) FROM outbox WHERE published_at IS NULL", Long.class);
          } catch (Exception e) {
            return Double.NaN;
          }
        });
    metrics.gauge(
        "fulfillment.outbox.oldest.age.seconds",
        this,
        relay -> {
          try {
            return jdbc.queryForObject(
                "SELECT coalesce(extract(epoch FROM now()-min(created_at)),0) FROM outbox WHERE"
                    + " published_at IS NULL",
                Double.class);
          } catch (Exception e) {
            return Double.NaN;
          }
        });
  }

  @Scheduled(fixedDelayString = "${app.outbox.poll-ms:500}")
  public void publishBatch() {
    try {
      for (int index = 0; index < 10; index++) {
        boolean delivered =
            Boolean.TRUE.equals(
                tx.execute(
                    status -> {
                      var rows =
                          jdbc.queryForList(
                              "SELECT event_id,topic,partition_key,payload::text,attempts FROM"
                                  + " outbox WHERE published_at IS NULL AND next_attempt_at<=now()"
                                  + " ORDER BY created_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED");
                      if (rows.isEmpty()) return false;
                      for (var row : rows) {
                        UUID id = (UUID) row.get("event_id");
                        try {
                          kafka
                              .send(
                                  (String) row.get("topic"),
                                  (String) row.get("partition_key"),
                                  (String) row.get("payload"))
                              .get(10, TimeUnit.SECONDS);
                          jdbc.update(
                              "UPDATE outbox SET published_at=now(),last_error=NULL WHERE"
                                  + " event_id=?",
                              id);
                          metrics.counter("fulfillment.outbox.published").increment();
                        } catch (Exception e) {
                          if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                          int attempts = ((Number) row.get("attempts")).intValue() + 1;
                          long delay = Math.min(60, 1L << Math.min(attempts, 6));
                          jdbc.update(
                              "UPDATE outbox SET attempts=?,next_attempt_at=now()+(? * interval '1"
                                  + " second'),last_error=? WHERE event_id=?",
                              attempts,
                              delay,
                              e.getClass().getSimpleName(),
                              id);
                          metrics.counter("fulfillment.outbox.failed").increment();
                          log.warn(
                              "Outbox delivery deferred eventId={} cause={}",
                              id,
                              e.getClass().getSimpleName());
                          return false;
                        }
                      }
                      return true;
                    }));
        if (!delivered) break;
      }
    } catch (Exception e) {
      log.warn("Outbox poll failed cause={}", e.getClass().getSimpleName());
    }
  }
}
