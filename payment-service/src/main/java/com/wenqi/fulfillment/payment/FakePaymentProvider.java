package com.wenqi.fulfillment.payment;

import com.wenqi.fulfillment.common.Api.PaymentRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Explicit local simulator. No card handling, real charges, or PCI compliance claim. */
@Component
@ConditionalOnProperty(
    name = "app.payment.provider",
    havingValue = "simulated",
    matchIfMissing = true)
public class FakePaymentProvider implements PaymentProvider {
  @Override
  public Decision authorize(UUID idempotencyKey, PaymentRequest request) {
    PaymentService.validate(request);
    try {
      var reference =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(idempotencyKey.toString().getBytes(StandardCharsets.UTF_8)))
              .substring(0, 32);
      return new Decision(
          request.paymentToken().equals("tok_approved") ? "APPROVED" : "DECLINED",
          "sim_" + reference);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
