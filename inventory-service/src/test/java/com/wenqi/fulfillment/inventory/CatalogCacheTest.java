package com.wenqi.fulfillment.inventory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenqi.fulfillment.common.Api.CatalogItem;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;

class CatalogCacheTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  @SuppressWarnings("unchecked")
  void hotLookupDeserializesTypedCatalog() throws Exception {
    var redis = mock(StringRedisTemplate.class);
    var values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    var item = new CatalogItem("coffee", "Coffee", 1599, "USD", 1);
    when(values.get("catalog:v1:coffee")).thenReturn(mapper.writeValueAsString(item));
    var registry = new SimpleMeterRegistry();
    var cache = new CatalogCache(redis, mapper, registry, true, 30);
    assertEquals(item, cache.get("coffee").orElseThrow());
    assertEquals(1, registry.get("inventory.cache.hits").counter().count());
  }

  @Test
  @SuppressWarnings("unchecked")
  void readAndFillFailuresAreCacheMissesNotBusinessFailures() {
    var redis = mock(StringRedisTemplate.class);
    var values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get(anyString())).thenThrow(new IllegalStateException("Redis unavailable"));
    doThrow(new IllegalStateException("Redis unavailable"))
        .when(values)
        .set(anyString(), anyString(), any(Duration.class));
    var registry = new SimpleMeterRegistry();
    var cache = new CatalogCache(redis, mapper, registry, true, 30);
    assertTrue(cache.get("coffee").isEmpty());
    assertDoesNotThrow(() -> cache.put(new CatalogItem("coffee", "Coffee", 1599, "USD", 1)));
    assertEquals(1, registry.get("inventory.cache.errors").counter().count());
    assertEquals(
        1, registry.get("inventory.cache.redis.skipped").tag("operation", "set").counter().count());
    verify(values, never()).set(anyString(), anyString(), any(Duration.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void malformedOrOutOfRangeCacheDataIsDiscarded() {
    var redis = mock(StringRedisTemplate.class);
    var values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get(anyString()))
        .thenReturn(
            "{bad",
            "{\"sku\":\"coffee\",\"name\":\"Coffee\",\"unitPriceMinor\":1000000000001,\"currency\":\"USD\",\"version\":1}");
    var cache = new CatalogCache(redis, mapper, new SimpleMeterRegistry(), true, 30);
    assertTrue(cache.get("coffee").isEmpty());
    assertTrue(cache.get("coffee").isEmpty());
  }

  @Test
  void disabledCacheNeverContactsRedis() {
    var redis = mock(StringRedisTemplate.class);
    var cache = new CatalogCache(redis, mapper, new SimpleMeterRegistry(), false, 30);
    assertTrue(cache.get("coffee").isEmpty());
    cache.put(new CatalogItem("coffee", "Coffee", 500, "USD", 1));
    cache.evict("coffee");
    verifyNoInteractions(redis);
  }

  @Test
  void refusesUnboundedOrNonpositiveTtl() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CatalogCache(
                mock(StringRedisTemplate.class), mapper, new SimpleMeterRegistry(), true, 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CatalogCache(
                mock(StringRedisTemplate.class), mapper, new SimpleMeterRegistry(), true, 3601));
  }
}
