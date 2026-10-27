package com.wenqi.fulfillment.order;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.wenqi.fulfillment.common.Api.*;
import com.wenqi.fulfillment.common.EventEnvelope;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class OrderValidationTest {
  private final UUID id = UUID.randomUUID();

  private OrderView order(String status, long sequence) {
    return new OrderView(
        id,
        status,
        List.of(new QuotedItem("A", 2, 100, "USD")),
        200,
        "USD",
        "DONE",
        0,
        null,
        sequence,
        Instant.now(),
        Instant.now());
  }

  @Test
  void canonicalRequestSortsItemsAndRejectsDuplicateSkusAndUnsupportedTokens() {
    var normalized =
        OrderValidation.normalize(
            new CreateOrderRequest(
                List.of(new LineItem("B", 1), new LineItem("A", 2)), "tok_approved"));
    assertEquals(List.of(new LineItem("A", 2), new LineItem("B", 1)), normalized.items());
    assertThrows(
        RuntimeException.class,
        () ->
            OrderValidation.normalize(
                new CreateOrderRequest(
                    List.of(new LineItem("A", 1), new LineItem("A", 2)), "tok_approved")));
    assertThrows(
        RuntimeException.class,
        () -> OrderValidation.normalize(new CreateOrderRequest(normalized.items(), "real_card")));
    assertThrows(RuntimeException.class, () -> OrderValidation.key(" "));
  }

  @Test
  void quoteMustMatchEveryRequestedQuantityAndExactCheckedIntegerTotal() {
    var requested = List.of(new LineItem("A", 2));
    assertEquals(
        200,
        OrderValidation.quote(
                requested, new QuoteResponse(order("CONFIRMED", 0).items(), 200, "USD"))
            .totalMinor());
    assertThrows(
        DependencyFailure.class,
        () ->
            OrderValidation.quote(
                requested, new QuoteResponse(order("CONFIRMED", 0).items(), 199, "USD")));
    assertThrows(
        DependencyFailure.class,
        () ->
            OrderValidation.quote(
                requested,
                new QuoteResponse(List.of(new QuotedItem("A", 1, 100, "USD")), 100, "USD")));
    assertThrows(
        DependencyFailure.class,
        () ->
            OrderValidation.quote(
                requested,
                new QuoteResponse(
                    List.of(new QuotedItem("A", 2, 1000000000000L, "USD")),
                    2000000000000L,
                    "USD")));
  }

  @Test
  void paymentMustMatchOrderIdentityAmountAndExplicitTerminalStatus() {
    var order = order("PENDING", 0);
    OrderValidation.payment(order, new PaymentView(id, "APPROVED", 200, "USD", "fake-reference"));
    assertThrows(
        DependencyFailure.class,
        () ->
            OrderValidation.payment(
                order, new PaymentView(id, "PENDING", 200, "USD", "fake-reference")));
    assertThrows(
        DependencyFailure.class,
        () ->
            OrderValidation.payment(
                order,
                new PaymentView(UUID.randomUUID(), "APPROVED", 200, "USD", "fake-reference")));
    assertThrows(
        DependencyFailure.class,
        () ->
            OrderValidation.payment(
                order, new PaymentView(id, "APPROVED", 201, "USD", "fake-reference")));
  }

  @Test
  void reservationMustEchoExactItemsAndAllowedState() {
    var order = order("PENDING", 0);
    OrderValidation.reservation(
        order,
        new ReservationView(id, "COMMITTED", List.of(new LineItem("A", 2))),
        Set.of("RESERVED", "COMMITTED"));
    assertThrows(
        DependencyFailure.class,
        () ->
            OrderValidation.reservation(
                order,
                new ReservationView(id, "RELEASED", List.of(new LineItem("A", 2))),
                Set.of("COMMITTED")));
    assertThrows(
        DependencyFailure.class,
        () ->
            OrderValidation.reservation(
                order,
                new ReservationView(id, "COMMITTED", List.of(new LineItem("A", 1))),
                Set.of("COMMITTED")));
  }

  @Test
  void warehouseSequenceAndStatusNeverRegressAndEarlyEventsAreRetryable() {
    var update = new WarehouseUpdate(UUID.randomUUID(), id, 8, "PACKED");
    var backward = WarehouseValidation.decide(order("SHIPPED", 5), update);
    assertEquals("SHIPPED", backward.status());
    assertEquals(8, backward.sequence());
    assertEquals("IGNORED_BACKWARD", backward.outcome());
    var stale =
        WarehouseValidation.decide(
            order("DELIVERED", 8), new WarehouseUpdate(UUID.randomUUID(), id, 7, "SHIPPED"));
    assertEquals(8, stale.sequence());
    assertEquals("DELIVERED", stale.status());
    assertEquals(
        "DELIVERED",
        WarehouseValidation.decide(
                order("CONFIRMED", 0), new WarehouseUpdate(UUID.randomUUID(), id, 3, "DELIVERED"))
            .status());
    assertTrue(
        assertThrows(
                WarehouseFailure.class,
                () -> WarehouseValidation.decide(order("PENDING", 0), update))
            .retryable());
    assertFalse(
        assertThrows(
                WarehouseFailure.class,
                () -> WarehouseValidation.decide(order("CANCELLED", 0), update))
            .retryable());
  }

  @Test
  void warehouseEnvelopeRejectsMismatchedIdsDecimalSequencesAndUnknownFields() throws Exception {
    var mapper = JsonMapper.builder().findAndAddModules().build();
    UUID eventId = UUID.randomUUID();
    var payload =
        Map.of(
            "eventId",
            eventId.toString(),
            "orderId",
            id.toString(),
            "sequence",
            1,
            "status",
            "PACKED");
    var envelope = new LinkedHashMap<String, Object>();
    envelope.put("eventId", eventId.toString());
    envelope.put("type", "warehouse.status.updated");
    envelope.put("schemaVersion", 1);
    envelope.put("aggregateId", id.toString());
    envelope.put("occurredAt", Instant.now().toString());
    envelope.put("payload", payload);
    assertEquals(
        1, WarehouseValidation.parse(mapper, mapper.writeValueAsString(envelope)).sequence());
    envelope.put("eventId", UUID.randomUUID().toString());
    assertThrows(
        WarehouseFailure.class,
        () -> WarehouseValidation.parse(mapper, mapper.writeValueAsString(envelope)));
    envelope.put("eventId", eventId.toString());
    envelope.put(
        "payload",
        Map.of(
            "eventId",
            eventId.toString(),
            "orderId",
            id.toString(),
            "sequence",
            1.0,
            "status",
            "PACKED"));
    assertThrows(
        WarehouseFailure.class,
        () -> WarehouseValidation.parse(mapper, mapper.writeValueAsString(envelope)));
    envelope.put("payload", payload);
    envelope.put("unknown", true);
    assertThrows(
        WarehouseFailure.class,
        () -> WarehouseValidation.parse(mapper, mapper.writeValueAsString(envelope)));
    envelope.remove("unknown");
    String valid = mapper.writeValueAsString(envelope);
    assertThrows(WarehouseFailure.class, () -> WarehouseValidation.parse(mapper, valid + "{}"));
    assertThrows(
        WarehouseFailure.class,
        () ->
            WarehouseValidation.parse(
                mapper,
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":18446744073709551617")));
    assertThrows(
        WarehouseFailure.class,
        () ->
            WarehouseValidation.parse(
                mapper,
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1")));
  }

  @Test
  void dependencyBodySubscriberCancelsAtLimitBeforeAcceptingAnOversizeChunk() {
    var subscriber = new HttpDependencyGateway.LimitedBody(4);
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    subscriber.onSubscribe(
        new Flow.Subscription() {
          public void request(long n) {}

          public void cancel() {
            cancelled.set(true);
          }
        });
    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[5])));
    assertTrue(cancelled.get());
    assertThrows(
        CompletionException.class, () -> subscriber.getBody().toCompletableFuture().join());
  }

  @Test
  void productionEnvelopeRoundTripsWithExplicitIsoTimestampFormat() throws Exception {
    var mapper =
        JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    var update = new WarehouseUpdate(UUID.randomUUID(), id, 1, "PACKED");
    var envelope =
        new EventEnvelope(
            update.eventId(),
            "warehouse.status.updated",
            1,
            id.toString(),
            Instant.now(),
            mapper.valueToTree(update));
    String raw = mapper.writeValueAsString(envelope);
    assertTrue(mapper.readTree(raw).path("occurredAt").isTextual());
    assertEquals(update, WarehouseValidation.parse(mapper, raw));
  }
}
