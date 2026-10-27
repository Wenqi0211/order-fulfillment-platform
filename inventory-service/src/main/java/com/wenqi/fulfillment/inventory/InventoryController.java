package com.wenqi.fulfillment.inventory;

import com.wenqi.fulfillment.common.Api.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
public class InventoryController {
  private final InventoryService inventory;

  public InventoryController(InventoryService inventory) {
    this.inventory = inventory;
  }

  @PostMapping("/v1/items")
  public ResponseEntity<CatalogItem> create(@Valid @RequestBody ItemInput body) {
    return ResponseEntity.status(HttpStatus.CREATED).body(inventory.create(body));
  }

  @GetMapping("/v1/items/{sku}")
  public CatalogItem item(@PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String sku) {
    return inventory.item(sku);
  }

  @GetMapping("/v1/stock/{sku}")
  public StockView stock(@PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String sku) {
    return inventory.stock(sku);
  }

  @PostMapping("/internal/quotes")
  public QuoteResponse quote(@Valid @RequestBody QuoteRequest body) {
    return inventory.quote(body);
  }

  @PutMapping("/internal/reservations/{orderId}")
  public ReservationView reserve(
      @PathVariable UUID orderId, @Valid @RequestBody ReserveRequest body) {
    return inventory.reserve(orderId, body);
  }

  @PostMapping("/internal/reservations/{orderId}/commit")
  public ReservationView commit(@PathVariable UUID orderId) {
    return inventory.commit(orderId);
  }

  @PostMapping("/internal/reservations/{orderId}/release")
  public ReservationView release(@PathVariable UUID orderId) {
    return inventory.release(orderId);
  }
}
