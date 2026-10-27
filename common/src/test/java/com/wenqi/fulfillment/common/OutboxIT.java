package com.wenqi.fulfillment.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.*;

/** Runs against real PostgreSQL. Kafka acknowledgements are controlled to test crash windows. */
class OutboxIT {
  static JdbcTemplate jdbc, admin;
  static TransactionTemplate tx;
  static String schema;
  Events events;
  KafkaTemplate<String, String> kafka;
  OutboxRelay relay;

  @BeforeAll
  static void database() {
    String url =
        Objects.requireNonNull(
            System.getenv("TEST_DATABASE_URL"),
            "Set TEST_DATABASE_URL to a disposable PostgreSQL JDBC database");
    var source =
        new DriverManagerDataSource(
            url,
            System.getenv().getOrDefault("TEST_DATABASE_USER", "postgres"),
            System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "postgres"));
    admin = new JdbcTemplate(source);
    schema = "outbox_it_" + UUID.randomUUID().toString().replace("-", "");
    admin.execute("CREATE SCHEMA " + schema);
    var isolated =
        new DriverManagerDataSource(
            url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
            source.getUsername(),
            source.getPassword());
    Flyway.configure()
        .dataSource(isolated)
        .locations("classpath:db/common")
        .defaultSchema(schema)
        .load()
        .migrate();
    jdbc = new JdbcTemplate(isolated);
    tx = new TransactionTemplate(new DataSourceTransactionManager(isolated));
  }

  @AfterAll
  static void cleanup() {
    if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
  }

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() {
    jdbc.update("DELETE FROM outbox");
    events = new Events(jdbc, new ObjectMapper().findAndRegisterModules());
    kafka = mock(KafkaTemplate.class);
    relay = new OutboxRelay(jdbc, kafka, tx, new SimpleMeterRegistry());
  }

  void append() {
    var mapper = new ObjectMapper().findAndRegisterModules();
    events.append(
        "fulfillment.events.v1",
        "order-1",
        EventEnvelope.of("test.created", "order-1", Map.of("ok", true), mapper));
  }

  @Test
  void businessRollbackRemovesItsEvent() {
    assertThrows(
        IllegalStateException.class,
        () ->
            tx.executeWithoutResult(
                s -> {
                  append();
                  throw new IllegalStateException("crash");
                }));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class));
  }

  @Test
  void brokerFailureDefersWithoutMarkingPublished() {
    tx.executeWithoutResult(s -> append());
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(
            CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
    relay.publishBatch();
    assertEquals(1, jdbc.queryForObject("SELECT attempts FROM outbox", Integer.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox WHERE published_at IS NULL AND next_attempt_at>now()",
            Integer.class));
  }

  @Test
  void acknowledgedEventIsMarkedAndNotResentOnNormalPoll() {
    tx.executeWithoutResult(s -> append());
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));
    relay.publishBatch();
    relay.publishBatch();
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox WHERE published_at IS NOT NULL", Integer.class));
    verify(kafka, times(1)).send(anyString(), anyString(), anyString());
  }

  @Test
  void crashAfterAcknowledgementCanReplaySameEventIdentity() {
    tx.executeWithoutResult(s -> append());
    var crashOnce = new AtomicBoolean(true);
    var bodies = new ArrayList<String>();
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenAnswer(
            call -> {
              bodies.add(call.getArgument(2));
              if (crashOnce.getAndSet(false))
                TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                      @Override
                      public void beforeCommit(boolean readOnly) {
                        throw new IllegalStateException("simulated crash before DB commit");
                      }
                    });
              return CompletableFuture.<SendResult<String, String>>completedFuture(null);
            });
    relay.publishBatch();
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox WHERE published_at IS NULL", Integer.class));
    relay.publishBatch();
    assertEquals(2, bodies.size());
    assertEquals(bodies.get(0), bodies.get(1));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox WHERE published_at IS NOT NULL", Integer.class));
  }
}
