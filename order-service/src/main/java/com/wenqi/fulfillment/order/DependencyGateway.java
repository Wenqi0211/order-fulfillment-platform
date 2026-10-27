package com.wenqi.fulfillment.order;

import com.wenqi.fulfillment.common.Api.*;
import java.util.List;
import java.util.UUID;

public interface DependencyGateway {
  QuoteResponse quote(List<LineItem> items);

  ReservationView reserve(UUID orderId, List<LineItem> items);

  ReservationView commit(UUID orderId);

  ReservationView release(UUID orderId);

  PaymentView pay(UUID orderId, PaymentRequest request);
}
