package com.wenqi.fulfillment.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Transactional outbox: the business mutation and event commit or roll back together. */
@Component
public class Events {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public Events(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public void append(String topic, String key, EventEnvelope event) {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Outbox append requires the business database transaction");
    try {
      jdbc.update(
          "INSERT INTO outbox(event_id,topic,partition_key,payload) VALUES (?,?,?,?::jsonb)",
          event.eventId(),
          topic,
          key,
          mapper.writeValueAsString(event));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Unable to encode domain event", e);
    }
  }
}
