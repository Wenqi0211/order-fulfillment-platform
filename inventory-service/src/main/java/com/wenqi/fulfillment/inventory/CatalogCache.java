package com.wenqi.fulfillment.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenqi.fulfillment.common.Api.CatalogItem;
import com.wenqi.fulfillment.common.ApiException;
import io.micrometer.core.instrument.*;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Display metadata only: bounded in-flight miss coalescing and fail-open Redis recovery. */
@Component
public class CatalogCache {
  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;
  private final boolean enabled;
  private final Duration ttl;
  private final Counter hits,
      misses,
      errors,
      bypasses,
      leaders,
      followers,
      admissionBypasses,
      skippedWrites;
  private final Timer redisGet, redisSet, decode;
  private final ConcurrentHashMap<String, CompletableFuture<CatalogItem>> flights =
      new ConcurrentHashMap<>();
  private final Semaphore flightSlots;
  private final AtomicLong retryAfter = new AtomicLong();
  private final AtomicBoolean recoveryProbe = new AtomicBoolean();
  private final long cooldownNanos;
  private final LongSupplier nanoClock;

  @Autowired
  public CatalogCache(
      StringRedisTemplate redis,
      ObjectMapper mapper,
      MeterRegistry registry,
      @Value("${inventory.cache.enabled:true}") boolean enabled,
      @Value("${inventory.cache.ttl-seconds:30}") long ttlSeconds,
      @Value("${inventory.cache.max-in-flight:256}") int maxInFlight,
      @Value("${inventory.cache.failure-cooldown-ms:1000}") long cooldownMillis) {
    this(
        redis,
        mapper,
        registry,
        enabled,
        ttlSeconds,
        maxInFlight,
        cooldownMillis,
        System::nanoTime);
  }

  public CatalogCache(
      StringRedisTemplate redis,
      ObjectMapper mapper,
      MeterRegistry registry,
      boolean enabled,
      long ttlSeconds) {
    this(redis, mapper, registry, enabled, ttlSeconds, 256, 1000, System::nanoTime);
  }

  CatalogCache(
      StringRedisTemplate redis,
      ObjectMapper mapper,
      MeterRegistry registry,
      boolean enabled,
      long ttlSeconds,
      int maxInFlight,
      long cooldownMillis,
      LongSupplier nanoClock) {
    if (ttlSeconds < 1
        || ttlSeconds > 3600
        || maxInFlight < 1
        || maxInFlight > 4096
        || cooldownMillis < 100
        || cooldownMillis > 10000) throw new IllegalArgumentException("Cache bounds are invalid");
    this.redis = redis;
    this.mapper = mapper;
    this.enabled = enabled;
    this.ttl = Duration.ofSeconds(ttlSeconds);
    this.flightSlots = new Semaphore(maxInFlight);
    this.cooldownNanos = TimeUnit.MILLISECONDS.toNanos(cooldownMillis);
    this.nanoClock = nanoClock;
    hits = registry.counter("inventory.cache.hits");
    misses = registry.counter("inventory.cache.misses");
    errors = registry.counter("inventory.cache.errors");
    bypasses = registry.counter("inventory.cache.bypasses");
    leaders = registry.counter("inventory.cache.singleflight", "role", "leader");
    followers = registry.counter("inventory.cache.singleflight", "role", "follower");
    admissionBypasses =
        registry.counter("inventory.cache.singleflight", "role", "admission_bypass");
    skippedWrites = registry.counter("inventory.cache.redis.skipped", "operation", "set");
    redisGet = registry.timer("inventory.cache.redis", "operation", "get");
    redisSet = registry.timer("inventory.cache.redis", "operation", "set");
    decode = registry.timer("inventory.cache.decode");
    registry.gauge("inventory.cache.inflight", flights, ConcurrentHashMap::size);
  }

  public Optional<CatalogItem> get(String sku) {
    var permit = enabled ? readPermit() : ReadPermit.DENIED;
    if (permit == ReadPermit.DENIED) {
      bypasses.increment();
      return Optional.empty();
    }
    String raw;
    try {
      raw = redisGet.record(() -> redis.opsForValue().get(key(sku)));
      // A request that began before another request opened the cooldown cannot close it.
      // Only the admitted recovery probe can establish a new healthy generation.
      if (permit == ReadPermit.PROBE) retryAfter.set(0);
    } catch (RuntimeException failure) {
      openCooldown();
      misses.increment();
      return Optional.empty();
    } finally {
      if (permit == ReadPermit.PROBE) recoveryProbe.set(false);
    }
    if (raw != null) {
      try {
        var item = decode.recordCallable(() -> mapper.readValue(raw, CatalogItem.class));
        if (item != null
            && sku.equals(item.sku())
            && item.name() != null
            && !item.name().isBlank()
            && item.name().length() <= 200
            && item.unitPriceMinor() > 0
            && item.unitPriceMinor() <= InventoryRules.MAX_MONEY
            && "USD".equals(item.currency())
            && item.version() > 0) {
          hits.increment();
          return Optional.of(item);
        }
      } catch (Exception corruptEntry) {
        errors.increment();
      }
    }
    misses.increment();
    return Optional.empty();
  }

  /** Called after the first cache miss. Completed values are never retained in this map. */
  public Loaded load(String sku, Supplier<CatalogItem> databaseLoader) {
    if (!enabled) return new Loaded(databaseLoader.get(), "database");
    var active = flights.get(sku);
    if (active != null) return follow(active);
    if (!flightSlots.tryAcquire()) {
      admissionBypasses.increment();
      return loadDirect(databaseLoader);
    }
    var own = new CompletableFuture<CatalogItem>();
    active = flights.putIfAbsent(sku, own);
    if (active != null) {
      flightSlots.release();
      return follow(active);
    }
    leaders.increment();
    try {
      // Another flight may have filled Redis between the caller's first miss and admission.
      var raced = get(sku);
      if (raced.isPresent()) {
        own.complete(raced.get());
        return new Loaded(raced.get(), "redis");
      }
      var result = loadDirect(databaseLoader);
      own.complete(result.item());
      return result;
    } catch (RuntimeException | Error error) {
      own.completeExceptionally(error);
      throw error;
    } finally {
      flights.remove(sku, own);
      flightSlots.release();
    }
  }

  private Loaded follow(CompletableFuture<CatalogItem> flight) {
    followers.increment();
    try {
      return new Loaded(flight.get(4, TimeUnit.SECONDS), "coalesced");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "CATALOG_LOAD_INTERRUPTED",
          "Catalog load interrupted; retry request");
    } catch (TimeoutException error) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "CATALOG_LOAD_BUSY",
          "Catalog load exceeded bounded wait; retry request");
    } catch (ExecutionException error) {
      if (error.getCause() instanceof RuntimeException cause) throw cause;
      if (error.getCause() instanceof Error cause) throw cause;
      throw new IllegalStateException("Catalog load failed", error.getCause());
    }
  }

  private Loaded loadDirect(Supplier<CatalogItem> loader) {
    var value = loader.get();
    put(value);
    return new Loaded(value, "database");
  }

  private enum ReadPermit {
    NORMAL,
    PROBE,
    DENIED
  }

  private ReadPermit readPermit() {
    long until = retryAfter.get();
    if (until == 0) return ReadPermit.NORMAL;
    if (nanoClock.getAsLong() - until < 0) return ReadPermit.DENIED;
    return recoveryProbe.compareAndSet(false, true) ? ReadPermit.PROBE : ReadPermit.DENIED;
  }

  private void openCooldown() {
    errors.increment();
    retryAfter.set(nanoClock.getAsLong() + cooldownNanos);
  }

  public void put(CatalogItem item) {
    if (!enabled) return;
    if (retryAfter.get() != 0) {
      skippedWrites.increment();
      return;
    }
    try {
      var raw = mapper.writeValueAsString(item);
      redisSet.record(() -> redis.opsForValue().set(key(item.sku()), raw, ttl));
    } catch (Exception failure) {
      openCooldown();
    }
  }

  public void evict(String sku) {
    if (!enabled || retryAfter.get() != 0) return;
    try {
      redis.delete(key(sku));
    } catch (RuntimeException failure) {
      openCooldown();
    }
  }

  public record Loaded(CatalogItem item, String source) {}

  int inFlightCount() {
    return flights.size();
  }

  private String key(String sku) {
    return "catalog:v1:" + sku;
  }
}
