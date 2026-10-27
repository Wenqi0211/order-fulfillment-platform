#!/usr/bin/env python3
"""Verify complete HTTP -> durable saga -> Kafka warehouse update behavior."""
import argparse
from datetime import datetime, timezone
import time
from uuid import uuid4
from api_client import Client, save_report


def await_status(client, order_id, expected, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        _, order = client.request("GET", f"/v1/orders/{order_id}")
        if order["status"] == expected:
            return order
        if order["status"] in ("MANUAL_REVIEW", "CANCELLED") and order["status"] != expected:
            raise AssertionError(f"Order reached {order['status']}, expected {expected}: {order.get('failureCode')}")
        time.sleep(0.2)
    raise AssertionError(f"Order {order_id} did not reach {expected} in {timeout}s")


def await_sequence_without_regression(client, order_id, sequence, expected_status, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        _, current = client.request("GET", f"/v1/orders/{order_id}")
        assert current["status"] == expected_status, "Backward warehouse event regressed the shipment"
        if current["warehouseSequence"] >= sequence:
            return
        time.sleep(0.2)
    raise AssertionError(f"Warehouse sequence {sequence} was not observed in {timeout}s")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--order-url", default="http://127.0.0.1:8080")
    parser.add_argument("--inventory-url", default="http://127.0.0.1:8081")
    parser.add_argument("--payment-url", default="http://127.0.0.1:8082")
    parser.add_argument("--timeout", type=int, default=60)
    args = parser.parse_args()
    order, inventory, payment = (Client(url) for url in (args.order_url, args.inventory_url, args.payment_url))
    for client in (order, inventory, payment):
        client.wait_ready()
    run_id = uuid4().hex[:12]
    sku = f"SMOKE_{run_id}"
    checks = []
    inventory.request("POST", "/v1/items", {"sku": sku, "name": "Smoke Test Item", "unitPriceMinor": 1999,
        "currency": "USD", "initialStock": 20}, expected=(201,))
    Client(args.inventory_url, key="incorrect").request("GET", f"/v1/items/{sku}", expected=(401,))
    checks.append("API rejects invalid authentication")
    body = {"items": [{"sku": sku, "quantity": 2}], "paymentToken": "tok_approved"}
    headers = {"Idempotency-Key": f"smoke-{run_id}"}
    _, created = order.request("POST", "/v1/orders", body, headers, expected=(201,))
    _, replay = order.request("POST", "/v1/orders", body, headers)
    assert created["id"] == replay["id"], "Repeated idempotency key created two orders"
    order.request("POST", "/v1/orders", {**body, "items": [{"sku": sku, "quantity": 3}]}, headers, expected=(409,))
    checks.append("Identical order replay is idempotent; changed payload rejected")
    confirmed = await_status(order, created["id"], "CONFIRMED", args.timeout)
    assert confirmed["totalMinor"] == 3998
    _, paid = payment.request("PUT", f"/internal/payments/{created['id']}",
        {"amountMinor": 3998, "currency": "USD", "paymentToken": "tok_approved"})
    assert paid["status"] == "APPROVED"
    checks.append("Durable reservation/payment/commit workflow confirms order at authoritative price")
    _, stock = inventory.request("GET", f"/v1/stock/{sku}")
    assert stock == {"sku": sku, "onHand": 18, "reserved": 0, "available": 18}, stock
    shipped = {"eventId": str(uuid4()), "orderId": created["id"], "sequence": 2, "status": "SHIPPED"}
    order.request("POST", "/v1/warehouse/events", shipped, expected=(202,))
    await_status(order, created["id"], "SHIPPED", args.timeout)
    order.request("POST", "/v1/warehouse/events", shipped, expected=(202,))
    order.request("POST", "/v1/warehouse/events", {**shipped, "status": "DELIVERED"}, expected=(409,))
    order.request("POST", "/v1/warehouse/events",
        {"eventId": str(uuid4()), "orderId": created["id"], "sequence": 1, "status": "PACKED"}, expected=(202,))
    order.request("POST", "/v1/warehouse/events",
        {"eventId": str(uuid4()), "orderId": created["id"], "sequence": 3, "status": "DELIVERED"}, expected=(202,))
    await_status(order, created["id"], "DELIVERED", args.timeout)
    order.request("POST", "/v1/warehouse/events",
        {"eventId": str(uuid4()), "orderId": created["id"], "sequence": 4, "status": "PACKED"}, expected=(202,))
    await_sequence_without_regression(order, created["id"], 4, "DELIVERED", args.timeout)
    checks.append("Kafka warehouse pipeline applies delivery; duplicates/conflicts/stale/backward updates cannot regress shipment")
    _, declined = order.request("POST", "/v1/orders",
        {"items": [{"sku": sku, "quantity": 1}], "paymentToken": "tok_declined"},
        {"Idempotency-Key": f"declined-{run_id}"}, expected=(201,))
    await_status(order, declined["id"], "CANCELLED", args.timeout)
    _, stock_after = inventory.request("GET", f"/v1/stock/{sku}")
    assert stock_after["onHand"] == 18 and stock_after["reserved"] == 0, stock_after
    checks.append("Declined payment compensates reserved stock without consuming inventory")
    _, insufficient = order.request("POST", "/v1/orders",
        {"items": [{"sku": sku, "quantity": 100}], "paymentToken": "tok_approved"},
        {"Idempotency-Key": f"insufficient-{run_id}"}, expected=(201,))
    await_status(order, insufficient["id"], "CANCELLED", args.timeout)
    checks.append("Insufficient stock cancels order")
    timestamp = datetime.now(timezone.utc).isoformat()
    report = {"timestamp": timestamp, "passed": True, "checks": checks, "sku": sku,
        "orderIds": [created["id"], declined["id"], insufficient["id"]],
        "paymentProvider": "simulated; no real charge", "transport": "HTTP and actual configured Kafka"}
    report_path = save_report(f"smoke-{run_id}.json", report)
    print(f"PASS: {len(checks)} system checks. Evidence: {report_path}")


if __name__ == "__main__":
    main()
