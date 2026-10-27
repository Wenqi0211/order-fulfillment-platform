package com.wenqi.fulfillment.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenqi.fulfillment.common.*;
import com.wenqi.fulfillment.common.Api.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate intentTx, resultTx;
  private final Events events;
  private final ObjectMapper mapper;
  private final PaymentProvider provider;

  public PaymentService(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      Events events,
      ObjectMapper mapper,
      PaymentProvider provider) {
    this.jdbc = jdbc;
    this.events = events;
    this.mapper = mapper;
    this.provider = provider;
    intentTx = new TransactionTemplate(manager);
    intentTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    intentTx.setTimeout(20);
    resultTx = new TransactionTemplate(manager);
    resultTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    resultTx.setTimeout(20);
  }

  public PaymentView authorize(UUID orderId, PaymentRequest request) {
    validate(request);
    var digest = fingerprint(request);
    // This immutable intent MUST commit before any external side effect. An
    // unknown provider response must not erase the identity of the request.
    intentTx.executeWithoutResult(
        status -> {
          lock(orderId);
          var existing = find(orderId);
          if (existing.isPresent()) {
            verify(existing.get(), digest);
            return;
          }
          jdbc.update(
              "INSERT INTO payments(order_id,request_fingerprint,amount_minor,currency,status)"
                  + " VALUES(?,?,?,?,'PENDING')",
              orderId,
              digest,
              request.amountMinor(),
              request.currency());
        });
    return resultTx.execute(
        status -> {
          lock(orderId);
          var existing =
              find(orderId)
                  .orElseThrow(() -> new IllegalStateException("Persisted payment intent missing"));
          verify(existing, digest);
          if (!existing.view().status().equals("PENDING")) return existing.view();
          PaymentProvider.Decision decision;
          try {
            decision = provider.authorize(orderId, request);
          } catch (RuntimeException e) {
            throw new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "PAYMENT_OUTCOME_UNKNOWN",
                "Payment outcome is unknown; retry the identical request");
          }
          if (decision == null
              || !("APPROVED".equals(decision.status()) || "DECLINED".equals(decision.status()))
              || decision.providerReference() == null
              || decision.providerReference().isBlank()
              || decision.providerReference().length() > 100)
            throw new ApiException(
                HttpStatus.BAD_GATEWAY,
                "INVALID_PROVIDER_REPLY",
                "Payment provider returned an invalid decision");
          jdbc.update(
              "UPDATE payments SET status=?,provider_reference=?,updated_at=now() WHERE order_id=?"
                  + " AND status='PENDING'",
              decision.status(),
              decision.providerReference(),
              orderId);
          var value =
              new PaymentView(
                  orderId,
                  decision.status(),
                  request.amountMinor(),
                  request.currency(),
                  decision.providerReference());
          events.append(
              "fulfillment.events.v1",
              orderId.toString(),
              EventEnvelope.of(
                  "payment." + decision.status().toLowerCase(Locale.ROOT),
                  orderId.toString(),
                  value,
                  mapper));
          return value;
        });
  }

  private void verify(PaymentRow row, String digest) {
    if (!row.fingerprint().equals(digest))
      throw new ApiException(
          HttpStatus.CONFLICT,
          "IDEMPOTENCY_CONFLICT",
          "Payment retry has different amount, currency or token");
  }

  private Optional<PaymentRow> find(UUID id) {
    return jdbc.query("SELECT * FROM payments WHERE order_id=? FOR UPDATE", this::row, id).stream()
        .findFirst();
  }

  private PaymentRow row(ResultSet rs, int n) throws SQLException {
    return new PaymentRow(
        rs.getString("request_fingerprint"),
        new PaymentView(
            rs.getObject("order_id", UUID.class),
            rs.getString("status"),
            rs.getLong("amount_minor"),
            rs.getString("currency").trim(),
            rs.getString("provider_reference")));
  }

  private void lock(UUID id) {
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
        (rs, n) -> (Object) null,
        "payment:" + id);
  }

  private String fingerprint(PaymentRequest value) {
    // Versioned canonical encoding is independent of JSON property order/configuration.
    var canonical =
        "payment-v1\n"
            + value.amountMinor()
            + "\n"
            + value.currency()
            + "\n"
            + value.paymentToken();
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  public static void validate(PaymentRequest request) {
    if (request == null
        || request.amountMinor() < 1
        || request.amountMinor() > 1_000_000_000_000L
        || !"USD".equals(request.currency())
        || !("tok_approved".equals(request.paymentToken())
            || "tok_declined".equals(request.paymentToken())))
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "INVALID_REQUEST",
          "Expected positive integer USD cents and a supported simulated payment token");
  }

  private record PaymentRow(String fingerprint, PaymentView view) {}
}
