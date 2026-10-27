package com.wenqi.fulfillment.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenqi.fulfillment.common.Api.*;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class HttpDependencyGateway implements DependencyGateway {
  private final ObjectMapper mapper;
  private final HttpClient http;
  private final String inventory, payment, key;
  private final int timeoutMs;

  public HttpDependencyGateway(
      ObjectMapper mapper,
      @Value("${app.inventory-base-url}") String inventory,
      @Value("${app.payment-base-url}") String payment,
      @Value("${app.api-key}") String key,
      @Value("${app.dependency-timeout-ms:2000}") int timeoutMs) {
    this.mapper = mapper;
    this.inventory = base(inventory);
    this.payment = base(payment);
    this.key = key;
    if (timeoutMs < 100 || timeoutMs > 10000)
      throw new IllegalArgumentException("Dependency timeout must be 100 to 10000ms");
    this.timeoutMs = timeoutMs;
    http =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofMillis(timeoutMs))
            .build();
  }

  private static String base(String value) {
    URI uri = URI.create(value);
    if (!Set.of("http", "https").contains(uri.getScheme())
        || uri.getHost() == null
        || uri.getRawUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null)
      throw new IllegalArgumentException("Dependency URL must be an HTTP(S) origin");
    return value.replaceAll("/+$", "");
  }

  public QuoteResponse quote(List<LineItem> items) {
    return request(
        inventory, "POST", "/internal/quotes", new QuoteRequest(items), QuoteResponse.class);
  }

  public ReservationView reserve(UUID id, List<LineItem> items) {
    return request(
        inventory,
        "PUT",
        "/internal/reservations/" + id,
        new ReserveRequest(items),
        ReservationView.class);
  }

  public ReservationView commit(UUID id) {
    return request(
        inventory, "POST", "/internal/reservations/" + id + "/commit", null, ReservationView.class);
  }

  public ReservationView release(UUID id) {
    return request(
        inventory,
        "POST",
        "/internal/reservations/" + id + "/release",
        null,
        ReservationView.class);
  }

  public PaymentView pay(UUID id, PaymentRequest request) {
    return request(payment, "PUT", "/internal/payments/" + id, request, PaymentView.class);
  }

  private <T> T request(
      String base, String method, String path, Object payload, Class<T> resultType) {
    CompletableFuture<HttpResponse<byte[]>> pending = null;
    try {
      var body =
          payload == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(payload));
      var request =
          HttpRequest.newBuilder(URI.create(base + path))
              .timeout(Duration.ofMillis(timeoutMs))
              .header("X-API-Key", key)
              .header("Content-Type", "application/json")
              .header("Accept", "application/json")
              .method(method, body)
              .build();
      pending = http.sendAsync(request, ignored -> new LimitedBody(65536));
      var response = pending.get(timeoutMs, TimeUnit.MILLISECONDS);
      int status = response.statusCode();
      if (status < 200 || status >= 300) {
        String code = "DEPENDENCY_HTTP_" + status;
        try {
          var node = mapper.readTree(response.body()).path("code");
          if (node.isTextual() && node.asText().matches("[A-Z0-9_]{1,64}")) code = node.asText();
        } catch (Exception ignored) {
        }
        throw new DependencyFailure(code, status >= 500 || status == 408 || status == 429, status);
      }
      try {
        return mapper.readValue(response.body(), resultType);
      } catch (Exception e) {
        throw DependencyFailure.invalid();
      }
    } catch (DependencyFailure e) {
      throw e;
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      if (pending != null) pending.cancel(true);
      throw new DependencyFailure("DEPENDENCY_UNAVAILABLE", true, 503);
    }
  }

  /**
   * Cancels upstream as soon as its body exceeds the bound; never allocates an unbounded response.
   */
  static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;

    LimitedBody(int limit) {
      this.limit = limit;
    }

    public CompletionStage<byte[]> getBody() {
      return result;
    }

    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      subscription.request(1);
    }

    public void onNext(List<ByteBuffer> buffers) {
      for (var buffer : buffers) {
        if (buffer.remaining() > limit - bytes.size()) {
          subscription.cancel();
          result.completeExceptionally(
              new IllegalArgumentException("Dependency body exceeds limit"));
          return;
        }
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        bytes.writeBytes(chunk);
      }
      subscription.request(1);
    }

    public void onError(Throwable error) {
      result.completeExceptionally(error);
    }

    public void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }
}
