package com.wenqi.fulfillment.order;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenqi.fulfillment.common.Api.QuotedItem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class OrderJson {
  private final ObjectMapper mapper;

  public OrderJson(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot encode persisted order data", e);
    }
  }

  public List<QuotedItem> items(String json) {
    try {
      return mapper.readValue(json, new TypeReference<List<QuotedItem>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("Cannot decode persisted order data", e);
    }
  }

  public String fingerprint(Object value) {
    return sha256(write(value));
  }

  public static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
