package com.wenqi.fulfillment.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableScheduling
public class PlatformConfiguration {
  @Bean
  public Jackson2ObjectMapperBuilderCustomizer strictJson() {
    return builder ->
        builder
            .featuresToEnable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .featuresToDisable(
                DeserializationFeature.ACCEPT_FLOAT_AS_INT,
                MapperFeature.ALLOW_COERCION_OF_SCALARS,
                SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  @Bean
  public TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
    var template = new TransactionTemplate(manager);
    template.setTimeout(20);
    return template;
  }

  @Bean
  @ConditionalOnProperty(
      name = "app.kafka.topics.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public KafkaAdmin.NewTopics topics(@Value("${app.kafka.replication-factor:1}") int replicas) {
    return new KafkaAdmin.NewTopics(
        topic("fulfillment.events.v1", replicas),
        topic("warehouse.status.v1", replicas),
        topic("warehouse.status.v1.DLT", replicas));
  }

  private NewTopic topic(String name, int replicas) {
    return TopicBuilder.name(name)
        .partitions(3)
        .replicas(replicas)
        .config("min.insync.replicas", replicas > 1 ? "2" : "1")
        .config("retention.ms", "604800000")
        .build();
  }
}
