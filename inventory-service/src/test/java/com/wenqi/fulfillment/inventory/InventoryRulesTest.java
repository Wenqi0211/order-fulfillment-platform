package com.wenqi.fulfillment.inventory;

import static org.junit.jupiter.api.Assertions.*;

import com.wenqi.fulfillment.common.Api.*;
import com.wenqi.fulfillment.common.ApiException;
import java.util.*;
import org.junit.jupiter.api.Test;

class InventoryRulesTest {
  @Test
  void normalizesWithoutChangingQuantities() {
    assertEquals(
        List.of(new LineItem("a", 2), new LineItem("z", 1)),
        InventoryRules.normalize(List.of(new LineItem("z", 1), new LineItem("a", 2))));
  }

  @Test
  void rejectsDuplicateSkusInsteadOfAccidentallyReservingTwice() {
    assertEquals(
        "DUPLICATE_SKU",
        assertThrows(
                ApiException.class,
                () -> InventoryRules.normalize(List.of(new LineItem("a", 1), new LineItem("a", 2))))
            .code());
  }

  @Test
  void rejectsEmptyInvalidOrExcessiveItemSets() {
    assertThrows(ApiException.class, () -> InventoryRules.normalize(List.of()));
    assertThrows(
        ApiException.class, () -> InventoryRules.normalize(List.of(new LineItem("../bad", 1))));
    assertThrows(ApiException.class, () -> InventoryRules.normalize(List.of(new LineItem("a", 0))));
    assertThrows(
        ApiException.class,
        () -> InventoryRules.normalize(Collections.nCopies(101, new LineItem("a", 1))));
  }

  @Test
  void validatesCatalogPriceCurrencyAndStock() {
    assertEquals(
        "Coffee",
        InventoryRules.validate(new ItemInput("coffee", " Coffee ", 500, "USD", 10)).name());
    assertThrows(
        ApiException.class,
        () -> InventoryRules.validate(new ItemInput("coffee", "Coffee", 0, "USD", 10)));
    assertThrows(
        ApiException.class,
        () -> InventoryRules.validate(new ItemInput("coffee", "Coffee", 500, "EUR", 10)));
    assertThrows(
        ApiException.class,
        () -> InventoryRules.validate(new ItemInput("coffee", "Coffee", 500, "USD", -1)));
  }

  @Test
  void computesIntegerCentsAndRejectsOversizedTotals() {
    assertEquals(4797, InventoryRules.total(List.of(new QuotedItem("coffee", 3, 1599, "USD"))));
    assertThrows(
        ApiException.class,
        () ->
            InventoryRules.total(List.of(new QuotedItem("a", 2, InventoryRules.MAX_MONEY, "USD"))));
    assertThrows(
        ApiException.class,
        () -> InventoryRules.total(List.of(new QuotedItem("a", 2, Long.MAX_VALUE, "USD"))));
  }
}
