package com.wenqi.fulfillment.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class EventsTest {
  @Test
  void refusesOutboxWritesOutsideBusinessTransaction() {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var jdbc = mock(JdbcTemplate.class);
    assertThrows(
        IllegalStateException.class,
        () ->
            new Events(jdbc, mapper)
                .append("test", "key", EventEnvelope.of("test", "key", "payload", mapper)));
    verifyNoInteractions(jdbc);
  }

  @Test
  void publishesIntoSameDatabaseTransaction() {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var jdbc = mock(JdbcTemplate.class);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      new Events(jdbc, mapper)
          .append("test", "key", EventEnvelope.of("test", "key", "payload", mapper));
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    verify(jdbc).update(anyString(), any(Object[].class));
  }
}
