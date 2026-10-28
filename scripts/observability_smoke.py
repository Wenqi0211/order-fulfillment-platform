#!/usr/bin/env python3
"""Validate local monitoring against real API traffic and optional dependency faults.

--faults temporarily stops/restarts only this project's payment and Redis services.
Run it without another load/fault test. Every dependency is restarted in finally.
Creates isolated test rows; payments are simulated and data volumes are retained.
"""
import argparse
import base64
from contextlib import contextmanager
from datetime import datetime, timezone
import ipaddress
import json
import math
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from uuid import uuid4

from api_client import Client, ROOT, save_report
from smoke import await_status

SERVICES = ("order-service", "inventory-service", "payment-service")


def loopback_url(value):
    parsed = urllib.parse.urlsplit(value)
    try:
        local = parsed.hostname == "localhost" or ipaddress.ip_address(parsed.hostname).is_loopback
        port = parsed.port
    except (ValueError, TypeError):
        local, port = False, None
    if (parsed.scheme not in ("http", "https") or not local or port is None
            or parsed.username or parsed.password or parsed.query or parsed.fragment
            or parsed.path not in ("", "/")):
        raise argparse.ArgumentTypeError("Use a loopback HTTP(S) URL with an explicit port")
    return value.rstrip("/")


def http(url, headers=None, expected=200):
    request = urllib.request.Request(url, headers=headers or {})
    try:
        response = urllib.request.urlopen(request, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        assert response.status == expected, f"Local HTTP check returned {response.status}, expected {expected}"
        return raw


def numeric_value(payload, missing_zero=True):
    assert payload.get("status") == "success", "Prometheus query failed"
    data = payload["data"]
    assert data["resultType"] == "vector", "Expected instant vector"
    result = data["result"]
    if not result and missing_zero:
        return 0.0
    assert len(result) == 1, "Expected a single aggregated metric sample"
    value = float(result[0]["value"][1])
    assert math.isfinite(value), "Metric sample is not finite"
    return value


class Prometheus:
    def __init__(self, url):
        self.url = url

    def value(self, expression, missing_zero=True):
        query = urllib.parse.urlencode({"query": expression})
        payload = json.loads(http(self.url + "/api/v1/query?" + query))
        return numeric_value(payload, missing_zero)

    def targets(self):
        payload = json.loads(http(self.url + "/api/v1/targets?state=active"))
        assert payload["status"] == "success"
        return [
            {"service": row["labels"].get("service"),
             "instance": row["labels"].get("instance"), "health": row["health"]}
            for row in payload["data"]["activeTargets"]
            if row["labels"].get("service") in SERVICES
        ]


def targets_ready(rows, order_replicas):
    for service in SERVICES:
        selected = [row for row in rows if row["service"] == service]
        expected = order_replicas if service == "order-service" else 1
        if (len(selected) != expected or any(row["health"] != "up" for row in selected)
                or len({row["instance"] for row in selected}) != expected):
            return False
    return True


def wait_for(description, observe, accept, seconds, interval=1):
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        try:
            last = observe()
            if accept(last):
                return last
        except (AssertionError, OSError, ValueError) as error:
            # Only record the error class; never echo HTTP auth headers or secrets.
            last = type(error).__name__
        time.sleep(interval)
    raise AssertionError(f"Timed out waiting for {description}; last observation: {last}")


class Compose:
    def __init__(self, files):
        self.files = files

    def run(self, *arguments):
        flags = [value for filename in self.files for value in ("-f", filename)]
        result = subprocess.run(
            ["bash", str(ROOT / "scripts/docker-local.sh"), "compose", *flags, *arguments],
            cwd=ROOT, capture_output=True, text=True, timeout=90,
        )
        assert result.returncode == 0, "Local Compose operation failed: " + result.stderr[-1200:]

    @contextmanager
    def stopped(self, service):
        if service not in ("payment-service", "redis"):
            raise ValueError("Only the two scoped test dependencies may be stopped")
        try:
            self.run("stop", "-t", "3", service)
            yield
        finally:
            self.run("start", service)


def metric_delta(prom, expression, before, timeout):
    after = wait_for("a scraped counter increase", lambda: prom.value(expression),
        lambda value: value >= before + 1, timeout)
    return {"before": before, "after": after, "delta": after - before}


def verify_catalog(client, sku, expected):
    _, catalog = client.request("GET", f"/v1/items/{sku}")
    assert all(catalog.get(key) == value for key, value in expected.items()), "Catalog content changed"
    return catalog


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--order-url", type=loopback_url, default="http://127.0.0.1:8080")
    parser.add_argument("--inventory-url", type=loopback_url, default="http://127.0.0.1:8081")
    parser.add_argument("--payment-url", type=loopback_url, default="http://127.0.0.1:8082")
    parser.add_argument("--prometheus-url", type=loopback_url, default="http://127.0.0.1:9090")
    parser.add_argument("--grafana-url", type=loopback_url, default="http://127.0.0.1:3000")
    parser.add_argument("--dashboard-uid", default="fulfillment-local")
    parser.add_argument("--grafana-password-file", type=Path,
        default=ROOT / ".local/observability/grafana-admin-password")
    parser.add_argument("--expected-order-replicas", type=int, default=2)
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--compose-file", action="append", dest="files")
    parser.add_argument("--faults", action="store_true",
        help="Opt in to sequential local payment/Redis outage and recovery checks")
    args = parser.parse_args()
    if not 1 <= args.expected_order_replicas <= 10 or not 30 <= args.timeout <= 300:
        parser.error("Use 1-10 expected order replicas and a timeout between 30 and 300 seconds")
    files = args.files or ["compose.yaml", "compose.scaling.yaml", "compose.observability.yaml"]
    compose = Compose(files)
    order, inventory, payment = [Client(url, timeout=10) for url in
        (args.order_url, args.inventory_url, args.payment_url)]
    for client in (order, inventory, payment):
        client.wait_ready(args.timeout)
    prom = Prometheus(args.prometheus_url)
    http(args.prometheus_url + "/-/ready")
    health = json.loads(http(args.grafana_url + "/api/health"))
    assert health.get("database") == "ok", "Grafana database is not healthy"
    password = args.grafana_password_file.read_text().strip()
    assert password, "Grafana admin password file is empty"
    authorization = "Basic " + base64.b64encode(("admin:" + password).encode()).decode()
    dashboard = json.loads(http(args.grafana_url + "/api/dashboards/uid/"
        + urllib.parse.quote(args.dashboard_uid, safe=""), {"Authorization": authorization}))
    assert dashboard["dashboard"]["uid"] == args.dashboard_uid
    assert dashboard["dashboard"].get("panels"), "Provisioned dashboard has no panels"
    targets = wait_for("all discovered application targets", prom.targets,
        lambda rows: targets_ready(rows, args.expected_order_replicas), args.timeout)
    checks = [{"check": "Prometheus and Grafana healthy; provisioned dashboard loads",
        "dashboardUid": args.dashboard_uid, "panelCount": len(dashboard["dashboard"]["panels"]),
        "targets": targets}]
    for url in (args.order_url, args.inventory_url, args.payment_url):
        http(url + "/actuator/prometheus", expected=401)
    checks.append({"check": "All service metrics reject missing API credentials", "status": 401})

    run_id = uuid4().hex[:12]
    sku = f"OBS_{run_id}"
    item = {"sku": sku, "name": "Observability Smoke", "unitPriceMinor": 1234,
        "currency": "USD", "initialStock": 10}
    inventory.request("POST", "/v1/items", item, expected=(201,))
    expected_catalog = {key: item[key] for key in ("sku", "name", "unitPriceMinor", "currency")}
    verify_catalog(inventory, sku, expected_catalog)
    errors = {str(status): f'sum(http_server_requests_seconds_count{{service="order-service",status="{status}"}})'
        for status in (400, 404, 409)}
    error_before = {status: prom.value(expression) for status, expression in errors.items()}
    order.request("GET", "/v1/orders?limit=0", expected=(400,))
    order.request("GET", f"/v1/orders/{uuid4()}", expected=(404,))
    body = {"items": [{"sku": sku, "quantity": 1}], "paymentToken": "tok_approved"}
    key = {"Idempotency-Key": f"observability-{run_id}"}
    _, created = order.request("POST", "/v1/orders", body, key, expected=(201,))
    order.request("POST", "/v1/orders", {**body, "items": [{"sku": sku, "quantity": 2}]},
        key, expected=(409,))
    await_status(order, created["id"], "CONFIRMED", args.timeout)
    error_deltas = {status: metric_delta(prom, expression, error_before[status], args.timeout)
        for status, expression in errors.items()}
    checks.append({"check": "HTTP 400/404/409 are visible in scraped application counters",
        "counters": error_deltas, "confirmedOrderId": created["id"]})
    order_ids = [created["id"]]

    if args.faults:
        deferred_expr = 'sum(fulfillment_saga_attempts_total{service="order-service",step="PAYMENT",outcome="deferred"})'
        backlog_expr = 'max(fulfillment_saga_backlog{service="order-service",status="PENDING",step="PAYMENT"})'
        deferred_before = prom.value(deferred_expr)
        backlog_before = prom.value(backlog_expr, missing_zero=False)
        with compose.stopped("payment-service"):
            _, pending = order.request("POST", "/v1/orders",
                {**body, "items": [{"sku": sku, "quantity": 2}]},
                {"Idempotency-Key": f"observability-payment-{run_id}"}, expected=(201,))
            order_ids.append(pending["id"])

            def pending_order():
                return order.request("GET", f"/v1/orders/{pending['id']}")[1]

            pending = wait_for("durable deferred PAYMENT step", pending_order,
                lambda row: row["status"] == "PENDING" and row["workflowStep"] == "PAYMENT"
                    and row["attempts"] > 0, args.timeout)
            _, held = inventory.request("GET", f"/v1/stock/{sku}")
            assert held == {"sku": sku, "onHand": 9, "reserved": 2, "available": 7}, held
            deferred = metric_delta(prom, deferred_expr, deferred_before, args.timeout)
            backlog = wait_for("scraped PAYMENT backlog increase", lambda: prom.value(backlog_expr, False),
                lambda value: value >= backlog_before + 1, args.timeout)
            checks.append({"check": "Payment outage retains reservation and exposes deferred workflow/backlog",
                "orderId": pending["id"], "status": pending["status"],
                "step": pending["workflowStep"], "attempts": pending["attempts"],
                "heldStock": held, "deferredCounter": deferred,
                "backlogBefore": backlog_before, "backlogDuring": backlog})
        payment.wait_ready(args.timeout)
        recovered = await_status(order, pending["id"], "CONFIRMED", args.timeout)
        _, released = inventory.request("GET", f"/v1/stock/{sku}")
        assert released == {"sku": sku, "onHand": 7, "reserved": 0, "available": 7}, released
        recovered_backlog = wait_for("PAYMENT backlog recovery", lambda: prom.value(backlog_expr, False),
            lambda value: value <= backlog_before, args.timeout)
        checks.append({"check": "Payment recovery confirms order once and clears reservation/backlog",
            "orderId": recovered["id"], "status": recovered["status"],
            "finalStock": released, "backlogAfter": recovered_backlog})

        bypass_expr = 'sum(inventory_cache_bypasses_total{service="inventory-service"})'
        cache_error_expr = 'sum(inventory_cache_errors_total{service="inventory-service"})'
        hit_expr = 'sum(inventory_cache_hits_total{service="inventory-service"})'
        bypass_before, cache_error_before = prom.value(bypass_expr), prom.value(cache_error_expr)
        with compose.stopped("redis"):
            started = time.monotonic()
            for _ in range(6):
                verify_catalog(inventory, sku, expected_catalog)
            bypass = metric_delta(prom, bypass_expr, bypass_before, args.timeout)
            cache_errors = metric_delta(prom, cache_error_expr, cache_error_before, args.timeout)
            hits_during = prom.value(hit_expr)
            checks.append({"check": "Redis outage preserves catalog content and exposes cache bypass/errors",
                "catalogReads": 6, "elapsedSeconds": round(time.monotonic() - started, 3),
                "bypassCounter": bypass, "errorCounter": cache_errors})

        def cache_recovery():
            verify_catalog(inventory, sku, expected_catalog)
            verify_catalog(inventory, sku, expected_catalog)
            return prom.value(hit_expr)

        hits_after = wait_for("actual Redis cache hits after recovery", cache_recovery,
            lambda value: value > hits_during, args.timeout)
        checks.append({"check": "Redis recovery returns to actual cache hits",
            "hitsBeforeRecovery": hits_during, "hitsAfterRecovery": hits_after})

    final_targets = wait_for("all application scrape targets after verification", prom.targets,
        lambda rows: targets_ready(rows, args.expected_order_replicas), args.timeout)
    report = {"timestamp": datetime.now(timezone.utc).isoformat(), "passed": True,
        "scope": "local Docker Compose; simulated payments; no throughput or cloud deployment claim",
        "faultsExercised": args.faults, "expectedOrderReplicas": args.expected_order_replicas,
        "checks": checks, "finalTargets": final_targets, "sku": sku, "orderIds": order_ids,
        "conditions": {"scrapeIntervalSeconds": 15, "sagaSnapshotRefreshSeconds": 5,
            "timeoutPerConditionSeconds": args.timeout, "otherLoadOrFaultTests": "must not run concurrently"}}
    path = save_report(f"observability-smoke-{run_id}.json", report)
    print(f"PASS: {len(checks)} monitoring checks; faults={args.faults}; evidence: {path}")


if __name__ == "__main__":
    main()
