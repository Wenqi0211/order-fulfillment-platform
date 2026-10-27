package com.wenqi.fulfillment.order;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.event.*;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "app.warehouse-consumer-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class WarehouseListener {
  private final WarehouseRecordHandler handler;
  private final AtomicInteger running = new AtomicInteger();

  public WarehouseListener(WarehouseRecordHandler handler, MeterRegistry metrics) {
    this.handler = handler;
    metrics.gauge("fulfillment.warehouse.consumer.up", running, value -> value.get() > 0 ? 1 : 0);
  }

  @KafkaListener(
      id = "warehouse-status",
      topics = "${app.warehouse-topic:warehouse.status.v1}",
      groupId = "${app.warehouse-group:orders-warehouse-v1}",
      containerFactory = "warehouseKafkaListenerContainerFactory",
      autoStartup = "${spring.kafka.listener.auto-startup:true}")
  public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
    running.updateAndGet(n -> Math.max(1, n));
    handler.handle(record);
    acknowledgment.acknowledge();
  }

  @EventListener
  public void started(ConsumerStartedEvent event) {
    running.incrementAndGet();
  }

  @EventListener
  public void stopped(ConsumerStoppedEvent event) {
    running.updateAndGet(n -> Math.max(0, n - 1));
  }

  @EventListener
  public void failed(ConsumerFailedToStartEvent event) {
    running.set(0);
  }

  @EventListener
  public void nonResponsive(NonResponsiveConsumerEvent event) {
    running.set(0);
  }
}
