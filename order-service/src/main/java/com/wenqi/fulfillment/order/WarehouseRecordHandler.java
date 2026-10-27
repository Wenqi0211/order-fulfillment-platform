package com.wenqi.fulfillment.order;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class WarehouseRecordHandler {
  private static final Logger log = LoggerFactory.getLogger(WarehouseRecordHandler.class);
  private final WarehouseService warehouse;
  private final ConsumerFailureStore failures;
  private final KafkaTemplate<String, String> kafka;
  private final MeterRegistry metrics;
  private final int maxAttempts;

  public WarehouseRecordHandler(
      WarehouseService warehouse,
      ConsumerFailureStore failures,
      KafkaTemplate<String, String> kafka,
      MeterRegistry metrics,
      @Value("${app.warehouse-max-attempts:5}") int maxAttempts) {
    if (maxAttempts < 1 || maxAttempts > 100)
      throw new IllegalArgumentException("Invalid warehouse attempt limit");
    this.warehouse = warehouse;
    this.failures = failures;
    this.kafka = kafka;
    this.metrics = metrics;
    this.maxAttempts = maxAttempts;
  }

  /** Returns only after business commit or DLT broker acknowledgement plus failure bookkeeping. */
  public void handle(ConsumerRecord<String, String> record) {
    String raw = record.value() == null ? "" : record.value();
    try {
      warehouse.apply(raw);
      return;
    } catch (Exception error) {
      String code =
          error instanceof WarehouseFailure failure
              ? failure.code()
              : "PROCESSING_TRANSACTION_FAILED";
      int attempts = failures.record(raw, code);
      boolean permanent = error instanceof WarehouseFailure failure && !failure.retryable();
      if (!permanent && attempts < maxAttempts) throw new WarehouseFailure(code, true);
      var dead =
          new ProducerRecord<String, String>(record.topic() + ".DLT", record.key(), record.value());
      header(dead, "original-topic", record.topic());
      header(dead, "original-partition", Integer.toString(record.partition()));
      header(dead, "original-offset", Long.toString(record.offset()));
      header(dead, "failure-code", code);
      header(dead, "failure-attempts", Integer.toString(attempts));
      try {
        kafka.send(dead).get(15, TimeUnit.SECONDS);
        failures.deadLettered(raw);
      } catch (Exception delivery) {
        if (delivery instanceof InterruptedException) Thread.currentThread().interrupt();
        // Do not return/ack when the dead letter is not durably published.
        throw new WarehouseFailure("DLT_DELIVERY_FAILED", true);
      }
      metrics.counter("fulfillment.warehouse.events", "outcome", "DEAD_LETTER").increment();
      log.warn(
          "Warehouse record published to DLT topic={} partition={} offset={} code={} attempts={}",
          record.topic(),
          record.partition(),
          record.offset(),
          code,
          attempts);
    }
  }

  private static void header(ProducerRecord<String, String> record, String name, String value) {
    record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
  }
}
