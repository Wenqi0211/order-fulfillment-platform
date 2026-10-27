package com.wenqi.fulfillment.inventory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenqi.fulfillment.common.*;
import com.wenqi.fulfillment.common.Api.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class InventoryService {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Events events;
  private final ObjectMapper mapper;
  private final CatalogCache cache;
  private final MeterRegistry metrics;
  private final Counter catalogDatabaseReads;
  private final Timer catalogDatabaseTime;

  public InventoryService(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      Events events,
      ObjectMapper mapper,
      CatalogCache cache,
      MeterRegistry metrics) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.events = events;
    this.mapper = mapper;
    this.cache = cache;
    this.metrics = metrics;
    this.catalogDatabaseReads = metrics.counter("inventory.catalog.database.reads");
    this.catalogDatabaseTime = metrics.timer("inventory.catalog.database");
  }

  public CatalogItem create(ItemInput input) {
    var item = InventoryRules.validate(input);
    var value =
        tx.execute(
            status -> {
              var rows =
                  jdbc.query(
                      "INSERT INTO catalog_items(sku,name,unit_price_minor,currency)"
                          + " VALUES(?,?,?,?) ON CONFLICT DO NOTHING RETURNING *",
                      this::catalog,
                      item.sku(),
                      item.name(),
                      item.unitPriceMinor(),
                      item.currency());
              if (rows.isEmpty())
                throw error(HttpStatus.CONFLICT, "ITEM_EXISTS", "Item already exists");
              jdbc.update(
                  "INSERT INTO stock(sku,on_hand) VALUES(?,?)", item.sku(), item.initialStock());
              publish("inventory.item.created", item.sku(), rows.get(0));
              return rows.get(0);
            });
    cache.evict(item.sku());
    return value;
  }

  public CatalogItem item(String sku) {
    var sample = Timer.start(metrics);
    String source = "database";
    try {
      var cached = cache.get(sku);
      if (cached.isPresent()) {
        source = "redis";
        return cached.get();
      }
      var loaded =
          cache.load(
              sku,
              () -> {
                catalogDatabaseReads.increment();
                var rows =
                    catalogDatabaseTime.record(
                        () ->
                            jdbc.query(
                                "SELECT * FROM catalog_items WHERE sku=?", this::catalog, sku));
                if (rows.isEmpty())
                  throw error(HttpStatus.NOT_FOUND, "ITEM_NOT_FOUND", "Item not found");
                return rows.get(0);
              });
      source = loaded.source();
      return loaded.item();
    } finally {
      sample.stop(metrics.timer("inventory.catalog.lookup", "source", source));
    }
  }

  public StockView stock(String sku) {
    var rows =
        jdbc.query(
            "SELECT sku,on_hand,reserved FROM stock WHERE sku=?",
            (rs, n) ->
                new StockView(
                    rs.getString("sku"),
                    rs.getInt("on_hand"),
                    rs.getInt("reserved"),
                    rs.getInt("on_hand") - rs.getInt("reserved")),
            sku);
    if (rows.isEmpty()) throw error(HttpStatus.NOT_FOUND, "ITEM_NOT_FOUND", "Item not found");
    return rows.get(0);
  }

  public QuoteResponse quote(QuoteRequest request) {
    var items = InventoryRules.normalize(request.items());
    var args = items.stream().map(LineItem::sku).toArray();
    var rows =
        jdbc.query(
            "SELECT * FROM catalog_items WHERE sku IN (" + placeholders(items.size()) + ")",
            this::catalog,
            args);
    var bySku = new HashMap<String, CatalogItem>();
    rows.forEach(row -> bySku.put(row.sku(), row));
    var quoted = new ArrayList<QuotedItem>();
    for (var item : items) {
      var row = bySku.get(item.sku());
      if (row == null)
        throw error(HttpStatus.NOT_FOUND, "ITEM_NOT_FOUND", "Item " + item.sku() + " not found");
      quoted.add(new QuotedItem(item.sku(), item.quantity(), row.unitPriceMinor(), row.currency()));
    }
    return new QuoteResponse(List.copyOf(quoted), InventoryRules.total(quoted), "USD");
  }

  public ReservationView reserve(UUID orderId, ReserveRequest request) {
    var items = InventoryRules.normalize(request.items());
    var fingerprint = fingerprint(items);
    return tx.execute(
        status -> {
          lock(orderId);
          var previous = findReservation(orderId);
          if (previous.isPresent()) {
            if (!previous.get().fingerprint().equals(fingerprint))
              throw error(
                  HttpStatus.CONFLICT,
                  "IDEMPOTENCY_CONFLICT",
                  "Reservation retry has different items");
            return previous.get().view();
          }
          // Every competing operation locks the complete SKU set in database sort order.
          var args = items.stream().map(LineItem::sku).toArray();
          var stocks =
              jdbc.query(
                  "SELECT sku,on_hand,reserved FROM stock WHERE sku IN ("
                      + placeholders(items.size())
                      + ") ORDER BY sku FOR UPDATE",
                  (rs, n) ->
                      new StockView(
                          rs.getString("sku"),
                          rs.getInt("on_hand"),
                          rs.getInt("reserved"),
                          rs.getInt("on_hand") - rs.getInt("reserved")),
                  args);
          var bySku = new HashMap<String, StockView>();
          stocks.forEach(row -> bySku.put(row.sku(), row));
          for (var item : items) {
            var row = bySku.get(item.sku());
            if (row == null)
              throw error(
                  HttpStatus.NOT_FOUND, "ITEM_NOT_FOUND", "Item " + item.sku() + " not found");
            if (row.available() < item.quantity())
              throw error(
                  HttpStatus.CONFLICT,
                  "INSUFFICIENT_STOCK",
                  "Insufficient available stock for " + item.sku());
          }
          for (var item : items)
            jdbc.update(
                "UPDATE stock SET reserved=reserved+? WHERE sku=?", item.quantity(), item.sku());
          jdbc.update(
              "INSERT INTO reservations(order_id,request_fingerprint,status,items)"
                  + " VALUES(?,?,'RESERVED',?::jsonb)",
              orderId,
              fingerprint,
              json(items));
          jdbc.update(
              "INSERT INTO reservation_audit(order_id,to_status) VALUES(?,'RESERVED')", orderId);
          var value = new ReservationView(orderId, "RESERVED", items);
          publish("inventory.reserved", orderId.toString(), value);
          return value;
        });
  }

  public ReservationView commit(UUID orderId) {
    return transition(orderId, "COMMITTED");
  }

  public ReservationView release(UUID orderId) {
    return transition(orderId, "RELEASED");
  }

  private ReservationView transition(UUID orderId, String target) {
    return tx.execute(
        status -> {
          lock(orderId);
          var row =
              findReservation(orderId)
                  .orElseThrow(
                      () ->
                          error(
                              HttpStatus.NOT_FOUND,
                              "RESERVATION_NOT_FOUND",
                              "Reservation not found"));
          if (row.view().status().equals(target)) return row.view();
          if (!row.view().status().equals("RESERVED"))
            throw error(
                HttpStatus.CONFLICT,
                "RESERVATION_STATE_CONFLICT",
                "Reservation already reached a different terminal state");
          var items = row.view().items();
          jdbc.query(
              "SELECT sku FROM stock WHERE sku IN ("
                  + placeholders(items.size())
                  + ") ORDER BY sku FOR UPDATE",
              (rs, n) -> rs.getString(1),
              items.stream().map(LineItem::sku).toArray());
          for (var item : items) {
            if (target.equals("COMMITTED"))
              jdbc.update(
                  "UPDATE stock SET on_hand=on_hand-?,reserved=reserved-? WHERE sku=?",
                  item.quantity(),
                  item.quantity(),
                  item.sku());
            else
              jdbc.update(
                  "UPDATE stock SET reserved=reserved-? WHERE sku=?", item.quantity(), item.sku());
          }
          jdbc.update(
              "UPDATE reservations SET status=?,updated_at=now() WHERE order_id=?",
              target,
              orderId);
          jdbc.update(
              "INSERT INTO reservation_audit(order_id,from_status,to_status)"
                  + " VALUES(?,'RESERVED',?)",
              orderId,
              target);
          var value = new ReservationView(orderId, target, items);
          publish(
              target.equals("COMMITTED") ? "inventory.committed" : "inventory.released",
              orderId.toString(),
              value);
          return value;
        });
  }

  private Optional<ReservationRow> findReservation(UUID id) {
    var rows =
        jdbc.query(
            "SELECT * FROM reservations WHERE order_id=? FOR UPDATE",
            (rs, n) ->
                new ReservationRow(
                    rs.getString("request_fingerprint"),
                    new ReservationView(
                        rs.getObject("order_id", UUID.class),
                        rs.getString("status"),
                        readItems(rs.getString("items")))),
            id);
    return rows.stream().findFirst();
  }

  private void lock(UUID id) {
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
        (rs, n) -> (Object) null,
        "reservation:" + id);
  }

  private CatalogItem catalog(ResultSet rs, int n) throws SQLException {
    return new CatalogItem(
        rs.getString("sku"),
        rs.getString("name"),
        rs.getLong("unit_price_minor"),
        rs.getString("currency").trim(),
        rs.getLong("version"));
  }

  private String placeholders(int count) {
    return String.join(",", Collections.nCopies(count, "?"));
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Unable to encode reservation", e);
    }
  }

  private List<LineItem> readItems(String json) {
    try {
      return mapper.readValue(json, new TypeReference<List<LineItem>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored reservation", e);
    }
  }

  private String fingerprint(List<LineItem> items) {
    // Explicit canonical encoding survives unrelated Jackson configuration changes.
    // SKU validation excludes the delimiters, and items are already sorted.
    var canonical = new StringBuilder("reservation-v1\n");
    for (var item : items)
      canonical.append(item.sku()).append(':').append(item.quantity()).append('\n');
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private void publish(String type, String key, Object value) {
    events.append("fulfillment.events.v1", key, EventEnvelope.of(type, key, value, mapper));
  }

  private static ApiException error(HttpStatus status, String code, String message) {
    return new ApiException(status, code, message);
  }

  private record ReservationRow(String fingerprint, ReservationView view) {}
}
