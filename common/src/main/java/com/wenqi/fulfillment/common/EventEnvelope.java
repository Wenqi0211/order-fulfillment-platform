package com.wenqi.fulfillment.common;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.*;
import java.time.Instant;
import java.util.UUID;

public record EventEnvelope(
    UUID eventId,
    String type,
    int schemaVersion,
    String aggregateId,
    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt,
    JsonNode payload) {
  public static EventEnvelope of(
      String type, String aggregateId, Object payload, ObjectMapper mapper) {
    return new EventEnvelope(
        UUID.randomUUID(), type, 1, aggregateId, Instant.now(), mapper.valueToTree(payload));
  }
}
