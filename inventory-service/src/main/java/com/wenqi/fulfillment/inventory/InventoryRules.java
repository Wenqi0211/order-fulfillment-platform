package com.wenqi.fulfillment.inventory;

import com.wenqi.fulfillment.common.Api.*;
import com.wenqi.fulfillment.common.ApiException;
import java.util.*;
import org.springframework.http.HttpStatus;

public final class InventoryRules {
  public static final long MAX_MONEY = 1_000_000_000_000L;

  private InventoryRules() {}

  public static List<LineItem> normalize(List<LineItem> items) {
    if (items == null || items.isEmpty() || items.size() > 100)
      throw invalid("Supply 1–100 distinct items");
    var seen = new HashSet<String>();
    var result = new ArrayList<LineItem>();
    for (var item : items) {
      if (item == null
          || item.sku() == null
          || !item.sku().matches("[A-Za-z0-9_-]{1,64}")
          || item.quantity() < 1
          || item.quantity() > 10000) throw invalid("Invalid SKU or quantity");
      if (!seen.add(item.sku()))
        throw new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_SKU", "Each SKU must occur once");
      result.add(item);
    }
    result.sort(Comparator.comparing(LineItem::sku));
    return List.copyOf(result);
  }

  public static ItemInput validate(ItemInput input) {
    if (input == null
        || input.sku() == null
        || !input.sku().matches("[A-Za-z0-9_-]{1,64}")
        || input.name() == null
        || input.name().isBlank()
        || input.name().length() > 200
        || input.unitPriceMinor() < 1
        || input.unitPriceMinor() > MAX_MONEY
        || !"USD".equals(input.currency())
        || input.initialStock() < 0
        || input.initialStock() > 1_000_000_000) throw invalid("Invalid catalog item");
    return new ItemInput(
        input.sku(),
        input.name().trim(),
        input.unitPriceMinor(),
        input.currency(),
        input.initialStock());
  }

  public static long total(List<QuotedItem> items) {
    long total = 0;
    try {
      for (var item : items) {
        total = Math.addExact(total, Math.multiplyExact(item.unitPriceMinor(), item.quantity()));
        if (total > MAX_MONEY) throw invalid("Order exceeds the maximum supported amount");
      }
    } catch (ArithmeticException e) {
      throw invalid("Order amount overflow");
    }
    return total;
  }

  private static ApiException invalid(String detail) {
    return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", detail);
  }
}
