package com.wenqi.fulfillment.order;

import com.fasterxml.jackson.databind.*;
import com.wenqi.fulfillment.common.Api.*;
import java.time.Instant;
import java.util.*;

public final class WarehouseValidation {
  private WarehouseValidation() {}

  private static final Set<String> STATUSES = Set.of("PACKED", "SHIPPED", "DELIVERED");

  public record Decision(String status, long sequence, String outcome, boolean changed) {}

  public static WarehouseUpdate update(WarehouseUpdate update) {
    if (update == null
        || update.eventId() == null
        || update.orderId() == null
        || update.sequence() < 1
        || update.sequence() > 9007199254740991L
        || !STATUSES.contains(Objects.toString(update.status(), "")))
      throw new WarehouseFailure("INVALID_WAREHOUSE_EVENT", false);
    return update;
  }

  public static WarehouseUpdate parse(ObjectMapper mapper, String raw) {
    try {
      if (raw == null || raw.length() > 65536) throw new IllegalArgumentException();
      JsonNode envelope;
      try (var parser = mapper.createParser(raw)) {
        parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        envelope =
            mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(parser);
      }
      if (envelope == null
          || !envelope.isObject()
          || envelope.size() != 6
          || !envelope.path("type").isTextual()
          || !"warehouse.status.updated".equals(envelope.path("type").textValue())
          || !envelope.path("schemaVersion").isIntegralNumber()
          || !envelope.path("schemaVersion").canConvertToInt()
          || envelope.path("schemaVersion").intValue() != 1
          || !envelope.path("eventId").isTextual()
          || !envelope.path("aggregateId").isTextual()
          || !envelope.path("occurredAt").isTextual()) throw new IllegalArgumentException();
      Instant.parse(envelope.path("occurredAt").textValue());
      JsonNode body = envelope.path("payload");
      if (!body.isObject()
          || body.size() != 4
          || !body.path("sequence").isIntegralNumber()
          || !body.path("sequence").canConvertToLong()
          || !body.path("eventId").isTextual()
          || !body.path("orderId").isTextual()
          || !body.path("status").isTextual()) throw new IllegalArgumentException();
      var update =
          update(
              new WarehouseUpdate(
                  uuid(body.path("eventId").textValue()),
                  uuid(body.path("orderId").textValue()),
                  body.path("sequence").longValue(),
                  body.path("status").textValue()));
      if (!uuid(envelope.path("eventId").textValue()).equals(update.eventId())
          || !uuid(envelope.path("aggregateId").textValue()).equals(update.orderId()))
        throw new IllegalArgumentException();
      return update;
    } catch (WarehouseFailure e) {
      throw e;
    } catch (Exception e) {
      throw new WarehouseFailure("INVALID_WAREHOUSE_EVENT", false);
    }
  }

  private static UUID uuid(String value) {
    if (value == null
        || !value.matches(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
      throw new IllegalArgumentException();
    return UUID.fromString(value);
  }

  public static Decision decide(OrderView order, WarehouseUpdate update) {
    if (Set.of("PENDING", "MANUAL_REVIEW").contains(order.status()))
      throw new WarehouseFailure("ORDER_NOT_CONFIRMED", true);
    if ("CANCELLED".equals(order.status())) throw new WarehouseFailure("ORDER_CANCELLED", false);
    int current = rank(order.status()), target = rank(update.status());
    if (current < 0 || target < 0) throw new WarehouseFailure("INVALID_ORDER_STATE", false);
    if (update.sequence() <= order.warehouseSequence())
      return new Decision(order.status(), order.warehouseSequence(), "IGNORED_STALE", false);
    if (target < current)
      return new Decision(order.status(), update.sequence(), "IGNORED_BACKWARD", false);
    return new Decision(
        update.status(), update.sequence(), "APPLIED", !update.status().equals(order.status()));
  }

  private static int rank(String status) {
    return switch (status) {
      case "CONFIRMED" -> 0;
      case "PACKED" -> 1;
      case "SHIPPED" -> 2;
      case "DELIVERED" -> 3;
      default -> -1;
    };
  }
}
