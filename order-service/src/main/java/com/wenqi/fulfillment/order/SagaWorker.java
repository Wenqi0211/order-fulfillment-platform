package com.wenqi.fulfillment.order;

import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true", matchIfMissing = true)
public class SagaWorker {
  private static final Logger log = LoggerFactory.getLogger(SagaWorker.class);
  private final SagaProcessor processor;

  public SagaWorker(SagaProcessor processor) {
    this.processor = processor;
  }

  @Scheduled(fixedDelayString = "${app.worker.poll-ms:250}")
  public void poll() {
    try {
      for (int i = 0; i < 8 && !Thread.currentThread().isInterrupted(); i++)
        if (!processor.processNext()) return;
    } catch (Exception e) {
      log.warn("Saga transaction deferred cause={}", e.getClass().getSimpleName());
    }
  }
}
