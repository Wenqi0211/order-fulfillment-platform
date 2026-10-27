package com.wenqi.fulfillment.order;

import com.wenqi.fulfillment.common.Api.*;
import com.wenqi.fulfillment.common.ApiException;
import java.util.*;
import org.springframework.http.HttpStatus;

public final class OrderValidation {
  private OrderValidation() {}

  public static String key(String key) {
    if (key == null || !key.matches("[!-~]{1,128}"))
      throw invalid("Idempotency-Key must contain 1 to 128 visible ASCII characters");
    return key;
  }

  public static CreateOrderRequest normalize(CreateOrderRequest request) {
    if (request == null
        || !Set.of("tok_approved", "tok_declined")
            .contains(Objects.toString(request.paymentToken(), "")))
      throw invalid("Use a supported simulated payment token");
    return new CreateOrderRequest(lines(request.items()), request.paymentToken());
  }

  public static List<LineItem> lines(List<LineItem> items) {
    if (items == null || items.isEmpty() || items.size() > 100)
      throw invalid("Provide between 1 and 100 items");
    var result = new ArrayList<LineItem>();
    var seen = new HashSet<String>();
    for (var item : items) {
      if (item == null
          || item.sku() == null
          || !item.sku().matches("[A-Za-z0-9_-]{1,64}")
          || item.quantity() < 1
          || item.quantity() > 10000
          || !seen.add(item.sku()))
        throw invalid("Items must have distinct valid SKUs and positive bounded quantities");
      result.add(item);
    }
    result.sort(Comparator.comparing(LineItem::sku));
    return List.copyOf(result);
  }

  public static QuoteResponse quote(List<LineItem> requested, QuoteResponse quote) {
    try {
      if (quote == null
          || !"USD".equals(quote.currency())
          || quote.items() == null
          || quote.items().size() != requested.size()) throw DependencyFailure.invalid();
      var items = new ArrayList<>(quote.items());
      items.sort(Comparator.comparing(QuotedItem::sku));
      long total = 0;
      for (int i = 0; i < requested.size(); i++) {
        var want = requested.get(i);
        var item = items.get(i);
        if (!want.sku().equals(item.sku())
            || want.quantity() != item.quantity()
            || !"USD".equals(item.currency())
            || item.unitPriceMinor() < 1
            || item.unitPriceMinor() > 1000000000000L) throw DependencyFailure.invalid();
        total = Math.addExact(total, Math.multiplyExact(item.unitPriceMinor(), item.quantity()));
      }
      if (total < 1 || total > 1000000000000L || quote.totalMinor() != total)
        throw DependencyFailure.invalid();
      return new QuoteResponse(List.copyOf(items), total, "USD");
    } catch (DependencyFailure e) {
      throw e;
    } catch (RuntimeException e) {
      throw DependencyFailure.invalid();
    }
  }

  public static List<LineItem> requested(OrderView order) {
    return order.items().stream().map(i -> new LineItem(i.sku(), i.quantity())).toList();
  }

  public static void reservation(
      OrderView order, ReservationView reservation, Set<String> allowed) {
    try {
      if (reservation == null
          || !order.id().equals(reservation.orderId())
          || !allowed.contains(reservation.status())
          || !requested(order).equals(lines(reservation.items())))
        throw DependencyFailure.invalid();
    } catch (DependencyFailure e) {
      throw e;
    } catch (RuntimeException e) {
      throw DependencyFailure.invalid();
    }
  }

  public static void payment(OrderView order, PaymentView payment) {
    if (payment == null
        || !order.id().equals(payment.orderId())
        || !Set.of("APPROVED", "DECLINED").contains(Objects.toString(payment.status(), ""))
        || payment.amountMinor() != order.totalMinor()
        || !"USD".equals(payment.currency())
        || payment.providerReference() == null
        || payment.providerReference().isBlank()
        || payment.providerReference().length() > 200) throw DependencyFailure.invalid();
  }

  public static ApiException invalid(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
  }
}
