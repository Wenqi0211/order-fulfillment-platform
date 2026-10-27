package com.wenqi.fulfillment.payment;

import com.wenqi.fulfillment.common.Api.*;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class PaymentController {
  private final PaymentService service;

  public PaymentController(PaymentService service) {
    this.service = service;
  }

  @PutMapping("/internal/payments/{orderId}")
  public PaymentView authorize(
      @PathVariable UUID orderId, @Valid @RequestBody PaymentRequest body) {
    return service.authorize(orderId, body);
  }
}
