package com.wenqi.fulfillment.order;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnProperty(
    name = "app.warehouse-consumer-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class WarehouseKafkaConfiguration {
  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, String>
      warehouseKafkaListenerContainerFactory(ConsumerFactory<String, String> consumers) {
    var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
    factory.setConsumerFactory(consumers);
    factory.setConcurrency(3);
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    // Domain retries are counted durably in SQL. Kafka-level transport or DLT
    // outages may last indefinitely and must never cause an implicit commit.
    var errors = new DefaultErrorHandler(new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
    errors.setAckAfterHandle(false);
    errors.setCommitRecovered(false);
    factory.setCommonErrorHandler(errors);
    return factory;
  }
}
