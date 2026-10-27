package com.wenqi.fulfillment.payment;

import com.wenqi.fulfillment.common.Api.PaymentRequest;
import java.util.UUID;

/** Replacement adapters must implement durable provider-side idempotency for this key. */
@FunctionalInterface
public interface PaymentProvider {
  Decision authorize(UUID idempotencyKey, PaymentRequest request);

  record Decision(String status, String providerReference) {}
}
