package com.wenqi.fulfillment.inventory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenqi.fulfillment.common.Api.CatalogItem;
import com.wenqi.fulfillment.common.ApiException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;

class CatalogCacheRecoveryTest {
  final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  final ValueOperations<String, String> values = mock(ValueOperations.class);

  final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
  final CatalogItem item = new CatalogItem("sku", "Product", 100, "USD", 1);

  CatalogCacheRecoveryTest() {
    when(redis.opsForValue()).thenReturn(values);
  }

  CatalogCache cache(int capacity, AtomicLong clock) {
    return new CatalogCache(
        redis, new ObjectMapper(), metrics, true, 30, capacity, 1000, clock::get);
  }

  static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(1);
    assertTrue(condition.getAsBoolean(), "Concurrent operation did not reach expected state");
  }

  static void gate(CountDownLatch latch) {
    try {
      if (!latch.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test gate timed out");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  @Test
  void simultaneousMissesShareOneDatabaseLoadAndFill() throws Exception {
    var cache = cache(16, new AtomicLong(1));
    var calls = new AtomicInteger();
    var release = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(16);
    try {
      var futures = new ArrayList<Future<CatalogCache.Loaded>>();
      for (int i = 0; i < 16; i++)
        futures.add(
            pool.submit(
                () ->
                    cache.load(
                        "sku",
                        () -> {
                          calls.incrementAndGet();
                          gate(release);
                          return item;
                        })));
      await(
          () ->
              metrics.get("inventory.cache.singleflight").tag("role", "follower").counter().count()
                  == 15);
      release.countDown();
      for (var future : futures) assertEquals(item, future.get(3, TimeUnit.SECONDS).item());
      assertEquals(1, calls.get());
      assertEquals(0, cache.inFlightCount());
      verify(values, times(1)).set(eq("catalog:v1:sku"), anyString(), eq(Duration.ofSeconds(30)));
      cache.load(
          "sku",
          () -> {
            calls.incrementAndGet();
            return item;
          });
      assertEquals(2, calls.get(), "Completed result must not become an unbounded local cache");
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void differentSkusLoadConcurrently() throws Exception {
    var cache = cache(2, new AtomicLong(1));
    var both = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var a =
          pool.submit(
              () ->
                  cache.load(
                      "a",
                      () -> {
                        both.countDown();
                        gate(release);
                        return new CatalogItem("a", "A", 100, "USD", 1);
                      }));
      var b =
          pool.submit(
              () ->
                  cache.load(
                      "b",
                      () -> {
                        both.countDown();
                        gate(release);
                        return new CatalogItem("b", "B", 100, "USD", 1);
                      }));
      assertTrue(both.await(3, TimeUnit.SECONDS));
      release.countDown();
      a.get(3, TimeUnit.SECONDS);
      b.get(3, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void failureFansOutCleansAdmissionAndAllowsRetry() throws Exception {
    var cache = cache(1, new AtomicLong(1));
    var failure = new IllegalStateException("database unavailable");
    var release = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(8);
    try {
      var futures = new ArrayList<Future<CatalogCache.Loaded>>();
      for (int i = 0; i < 8; i++)
        futures.add(
            pool.submit(
                () ->
                    cache.load(
                        "sku",
                        () -> {
                          gate(release);
                          throw failure;
                        })));
      await(
          () ->
              metrics.get("inventory.cache.singleflight").tag("role", "follower").counter().count()
                  == 7);
      release.countDown();
      for (var f : futures)
        assertSame(
            failure,
            assertThrows(ExecutionException.class, () -> f.get(3, TimeUnit.SECONDS)).getCause());
      assertEquals(0, cache.inFlightCount());
      assertEquals(item, cache.load("sku", () -> item).item());
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void admissionIsBoundedAndOtherKeysStillReturnData() throws Exception {
    var cache = cache(1, new AtomicLong(1));
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var pool = Executors.newSingleThreadExecutor();
    try {
      var a =
          pool.submit(
              () ->
                  cache.load(
                      "sku",
                      () -> {
                        started.countDown();
                        gate(release);
                        return item;
                      }));
      assertTrue(started.await(3, TimeUnit.SECONDS));
      var second = new CatalogItem("other", "Other", 200, "USD", 1);
      assertEquals(second, cache.load("other", () -> second).item());
      assertEquals(1, cache.inFlightCount());
      assertEquals(
          1,
          metrics
              .get("inventory.cache.singleflight")
              .tag("role", "admission_bypass")
              .counter()
              .count());
      release.countDown();
      a.get(3, TimeUnit.SECONDS);
      assertEquals(0, cache.inFlightCount());
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void redisReadFailureSkipsWriteAndCooldownEndsOnSuccessfulProbe() {
    var clock = new AtomicLong(1);
    var cache = cache(4, clock);
    when(values.get(anyString()))
        .thenThrow(new IllegalStateException("Redis unavailable"))
        .thenReturn(null);
    assertTrue(cache.get("sku").isEmpty());
    cache.put(item);
    assertTrue(cache.get("sku").isEmpty());
    verify(values, times(1)).get(anyString());
    verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
    assertTrue(cache.get("sku").isEmpty());
    cache.put(item);
    verify(values, times(2)).get(anyString());
    verify(values, times(1)).set(anyString(), anyString(), any(Duration.class));
  }

  @Test
  void halfOpenAllowsOneProbeWhileOthersUseDatabaseFallback() throws Exception {
    var clock = new AtomicLong(1);
    var cache = cache(4, clock);
    var probeStarted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    when(values.get(anyString()))
        .thenAnswer(
            call -> {
              if (calls.incrementAndGet() == 1) throw new IllegalStateException("down");
              probeStarted.countDown();
              gate(release);
              return null;
            });
    cache.get("sku");
    clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
    var pool = Executors.newSingleThreadExecutor();
    try {
      var probe = pool.submit(() -> cache.get("sku"));
      assertTrue(probeStarted.await(3, TimeUnit.SECONDS));
      for (int i = 0; i < 30; i++) assertTrue(cache.get("other").isEmpty());
      assertEquals(2, calls.get());
      release.countDown();
      probe.get(3, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void olderSuccessfulReadCannotCloseNewerFailureCooldown() throws Exception {
    var clock = new AtomicLong(1);
    var cache = cache(4, clock);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    var pool = Executors.newSingleThreadExecutor();
    when(values.get(anyString()))
        .thenAnswer(
            call -> {
              if (calls.incrementAndGet() == 1) {
                started.countDown();
                gate(release);
                return null;
              }
              throw new IllegalStateException("Redis failed after first request started");
            });
    try {
      var older = pool.submit(() -> cache.get("sku"));
      assertTrue(started.await(3, TimeUnit.SECONDS));
      cache.get("other");
      release.countDown();
      older.get(3, TimeUnit.SECONDS);
      cache.get("sku");
      assertEquals(2, calls.get(), "Old success must not cause extra Redis calls during cooldown");
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void disabledCacheDoesNotCoalesceDatabaseReads() {
    var cache = new CatalogCache(redis, new ObjectMapper(), metrics, false, 30);
    var calls = new AtomicInteger();
    for (int i = 0; i < 3; i++)
      cache.load(
          "sku",
          () -> {
            calls.incrementAndGet();
            return item;
          });
    assertEquals(3, calls.get());
    assertEquals(0, cache.inFlightCount());
    verifyNoInteractions(redis);
  }

  @Test
  void interruptedFollowerPreservesInterruptAndDoesNotCancelLeader() throws Exception {
    var cache = cache(4, new AtomicLong(1));
    var release = new CountDownLatch(1);
    var pool = Executors.newSingleThreadExecutor();
    var interrupted = new AtomicBoolean();
    var error = new AtomicReference<Throwable>();
    try {
      var leader =
          pool.submit(
              () ->
                  cache.load(
                      "sku",
                      () -> {
                        gate(release);
                        return item;
                      }));
      await(() -> cache.inFlightCount() == 1);
      var waiter =
          new Thread(
              () -> {
                try {
                  cache.load("sku", () -> item);
                } catch (Throwable e) {
                  error.set(e);
                  interrupted.set(Thread.currentThread().isInterrupted());
                }
              });
      waiter.start();
      await(
          () ->
              metrics.get("inventory.cache.singleflight").tag("role", "follower").counter().count()
                  == 1);
      waiter.interrupt();
      waiter.join(1000);
      assertFalse(waiter.isAlive());
      assertTrue(interrupted.get());
      assertInstanceOf(ApiException.class, error.get());
      release.countDown();
      assertEquals(item, leader.get(3, TimeUnit.SECONDS).item());
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }
}
