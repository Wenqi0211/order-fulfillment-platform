package com.wenqi.fulfillment.payment;

import static org.junit.jupiter.api.Assertions.*;

import com.wenqi.fulfillment.common.Api.PaymentRequest;
import com.wenqi.fulfillment.common.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentRulesTest {
  @Test
  void acceptsOnlyBoundedIntegerUsdAmountsAndExplicitTestTokens() {
    assertDoesNotThrow(
        () -> PaymentService.validate(new PaymentRequest(500, "USD", "tok_approved")));
    assertThrows(
        ApiException.class,
        () -> PaymentService.validate(new PaymentRequest(0, "USD", "tok_approved")));
    assertThrows(
        ApiException.class,
        () ->
            PaymentService.validate(new PaymentRequest(1_000_000_000_001L, "USD", "tok_approved")));
    assertThrows(
        ApiException.class,
        () -> PaymentService.validate(new PaymentRequest(500, "EUR", "tok_approved")));
    assertThrows(
        ApiException.class,
        () -> PaymentService.validate(new PaymentRequest(500, "USD", "4242424242424242")));
  }

  @Test
  void simulatedProviderReplaysStableDecisionsAcrossInstances() {
    var id = UUID.randomUUID();
    var request = new PaymentRequest(1500, "USD", "tok_approved");
    var first = new FakePaymentProvider().authorize(id, request);
    assertEquals(first, new FakePaymentProvider().authorize(id, request));
    assertEquals("APPROVED", first.status());
    assertTrue(first.providerReference().matches("sim_[a-f0-9]{32}"));
  }

  @Test
  void declineIsAnExplicitBusinessResult() {
    assertEquals(
        "DECLINED",
        new FakePaymentProvider()
            .authorize(UUID.randomUUID(), new PaymentRequest(1500, "USD", "tok_declined"))
            .status());
  }
}
