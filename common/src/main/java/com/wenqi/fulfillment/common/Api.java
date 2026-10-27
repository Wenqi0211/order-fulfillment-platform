package com.wenqi.fulfillment.common;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Wire contracts. Monetary values are minor units; no floating point amounts. */
public final class Api {
  private Api() {}

  public record LineItem(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String sku,
      @Min(1) @Max(10000) int quantity) {}

  public record CatalogItem(
      String sku, String name, long unitPriceMinor, String currency, long version) {}

  public record ItemInput(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String sku,
      @NotBlank @Size(max = 200) String name,
      @Min(1) @Max(1000000000000L) long unitPriceMinor,
      @Pattern(regexp = "USD") @NotNull String currency,
      @Min(0) @Max(1000000000) int initialStock) {}

  public record StockView(String sku, int onHand, int reserved, int available) {}

  public record ReserveRequest(@NotEmpty @Size(max = 100) List<@Valid @NotNull LineItem> items) {}

  public record ReservationView(UUID orderId, String status, List<LineItem> items) {}

  public record QuoteRequest(@NotEmpty @Size(max = 100) List<@Valid @NotNull LineItem> items) {}

  public record QuotedItem(String sku, int quantity, long unitPriceMinor, String currency) {}

  public record QuoteResponse(List<QuotedItem> items, long totalMinor, String currency) {}

  public record PaymentRequest(
      @Min(1) @Max(1000000000000L) long amountMinor,
      @NotNull @Pattern(regexp = "USD") String currency,
      @NotNull @Pattern(regexp = "tok_approved|tok_declined") String paymentToken) {}

  public record PaymentView(
      UUID orderId, String status, long amountMinor, String currency, String providerReference) {}

  public record CreateOrderRequest(
      @NotEmpty @Size(max = 100) List<@Valid @NotNull LineItem> items,
      @NotNull @Pattern(regexp = "tok_approved|tok_declined") String paymentToken) {}

  public record OrderView(
      UUID id,
      String status,
      List<QuotedItem> items,
      long totalMinor,
      String currency,
      String workflowStep,
      int attempts,
      String failureCode,
      long warehouseSequence,
      Instant createdAt,
      Instant updatedAt) {}

  public record WarehouseUpdate(
      @NotNull UUID eventId,
      @NotNull UUID orderId,
      @Min(1) @Max(9007199254740991L) long sequence,
      @NotNull @Pattern(regexp = "PACKED|SHIPPED|DELIVERED") String status) {}
}
