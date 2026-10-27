package com.wenqi.fulfillment.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.*;

class WarehouseRecordHandlerTest {
  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

  private final WarehouseService warehouse = mock(WarehouseService.class);
  private final ConsumerFailureStore failures = mock(ConsumerFailureStore.class);
  private final ConsumerRecord<String, String> record =
      new ConsumerRecord<>("warehouse.status.v1", 1, 17, "order-id", "raw-payload");

  private WarehouseRecordHandler handler() {
    return new WarehouseRecordHandler(warehouse, failures, kafka, new SimpleMeterRegistry(), 5);
  }

  @Test
  void retryableFailureBeforeLimitNeverAcknowledgesOrPublishesDeadLetter() {
    when(warehouse.apply(anyString())).thenThrow(new WarehouseFailure("ORDER_NOT_CONFIRMED", true));
    when(failures.record(anyString(), anyString())).thenReturn(4);
    var acknowledgment = mock(Acknowledgment.class);
    var listener = new WarehouseListener(handler(), new SimpleMeterRegistry());
    assertThrows(WarehouseFailure.class, () -> listener.consume(record, acknowledgment));
    verifyNoInteractions(kafka, acknowledgment);
  }

  @Test
  void sourceAcknowledgementWaitsForDeadLetterBrokerAcknowledgement() throws Exception {
    when(warehouse.apply(anyString()))
        .thenThrow(new WarehouseFailure("INVALID_WAREHOUSE_EVENT", false));
    when(failures.record(anyString(), anyString())).thenReturn(1);
    var delivery = new CompletableFuture<SendResult<String, String>>();
    when(kafka.send(any(ProducerRecord.class))).thenReturn(delivery);
    var acknowledgment = mock(Acknowledgment.class);
    var listener = new WarehouseListener(handler(), new SimpleMeterRegistry());
    var running = CompletableFuture.runAsync(() -> listener.consume(record, acknowledgment));
    verify(kafka, timeout(2000)).send(any(ProducerRecord.class));
    verifyNoInteractions(acknowledgment);
    verify(failures, never()).deadLettered(anyString());
    delivery.complete(null);
    running.get();
    verify(failures).deadLettered("raw-payload");
    verify(acknowledgment).acknowledge();
    @SuppressWarnings("rawtypes")
    ArgumentCaptor<ProducerRecord> captured = ArgumentCaptor.forClass(ProducerRecord.class);
    verify(kafka).send(captured.capture());
    assertEquals("warehouse.status.v1.DLT", captured.getValue().topic());
    assertEquals("raw-payload", captured.getValue().value());
    assertNotNull(captured.getValue().headers().lastHeader("original-offset"));
  }

  @Test
  void failedDeadLetterSendLeavesOriginalOffsetUnacknowledged() {
    when(warehouse.apply(anyString())).thenThrow(new WarehouseFailure("ORDER_NOT_CONFIRMED", true));
    when(failures.record(anyString(), anyString())).thenReturn(5);
    when(kafka.send(any(ProducerRecord.class)))
        .thenReturn(
            CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
    var acknowledgment = mock(Acknowledgment.class);
    var listener = new WarehouseListener(handler(), new SimpleMeterRegistry());
    assertEquals(
        "DLT_DELIVERY_FAILED",
        assertThrows(WarehouseFailure.class, () -> listener.consume(record, acknowledgment))
            .code());
    verifyNoInteractions(acknowledgment);
    verify(failures, never()).deadLettered(anyString());
  }

  @Test
  void redeliveryAlwaysTriesBusinessCommitFirstEvenAfterEarlierDeadLetterAttempts() {
    when(warehouse.apply("raw-payload")).thenReturn("APPLIED");
    var acknowledgment = mock(Acknowledgment.class);
    new WarehouseListener(handler(), new SimpleMeterRegistry()).consume(record, acknowledgment);
    verify(acknowledgment).acknowledge();
    verifyNoInteractions(failures, kafka);
  }
}
