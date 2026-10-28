#!/usr/bin/env python3
"""Verify the local edge, replica routing, and concurrent durable order workflows.

Creates one isolated SKU and simulated approved orders. Does not scale services,
stop containers, delete data, or contact a real payment provider.
"""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
import re
import subprocess
import time
import urllib.error
import urllib.request
from uuid import uuid4

from api_client import Client, ROOT, save_report


def docker(*arguments, timeout=60):
    result = subprocess.run(
        ["bash", str(ROOT / "scripts/docker-local.sh"), *arguments],
        cwd=ROOT, capture_output=True, text=True, timeout=timeout,
    )
    if result.returncode:
        raise AssertionError(f"Docker operation failed: {result.stderr[-1500:]}")
    return result.stdout


def compose(files, *arguments, timeout=60):
    flags = [value for path in files for value in ("-f", path)]
    return docker("compose", *flags, *arguments, timeout=timeout)


def replicas(files, expected, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        ids = compose(files, "ps", "-q", "order-service").split()
        result = []
        for identifier in ids:
            # Select only operational metadata; never inspect/print container env.
            template = ('{"name":{{json .Name}},"state":{{json .State.Status}},'
                        '"health":{{json .State.Health.Status}},'
                        '"networks":{{json .NetworkSettings.Networks}}}')
            details = json.loads(docker("inspect", "--format", template, identifier))
            details["containerId"] = identifier
            result.append(details)
        if len(result) == expected and all(
                row["state"] == "running" and row["health"] == "healthy" for row in result):
            return result
        time.sleep(1)
    raise AssertionError(f"Expected {expected} healthy order replicas within {timeout}s")


class EdgeResponseFailure(AssertionError):
    def __init__(self, status):
        self.status = status
        super().__init__(f"Edge returned HTTP {status}")


class EdgeStabilizationTimeout(AssertionError):
    def __init__(self, evidence):
        self.evidence = evidence
        super().__init__(f"Edge did not stabilize within {evidence['timeoutSeconds']}s; "
            f"transient failures: {len(evidence['transientFailures'])}")


def request_identity(client, request_id, expected=200):
    request = urllib.request.Request(
        client.base_url + "/v1/orders?limit=1",
        headers={"X-API-Key": client.key, "X-Request-ID": request_id},
    )
    try:
        response = urllib.request.urlopen(request, timeout=client.timeout)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        response.read()
        if response.status != expected:
            raise EdgeResponseFailure(response.status)
        assert response.headers.get("X-Request-ID") == request_id, "Request ID changed at edge"


def parse_routing_rows(lines, prefix):
    rows = []
    for line in lines:
        try:
            row = json.loads(line)
        except ValueError:
            continue
        if isinstance(row, dict) and str(row.get("request_id", "")).startswith(prefix):
            rows.append(row)
    return rows


def routing_rows(files, prefix, since, timeout=60):
    return parse_routing_rows(compose(files, "logs", "--no-log-prefix", "--no-color",
        "--since", since, "edge", timeout=timeout).splitlines(), prefix)


def final_upstream(row):
    # Successful final upstream; NGINX separates retry attempts with commas.
    return row.get("upstream_addr", "").split(",")[-1].strip()


def routing_counts(files, prefix, since):
    return Counter(final_upstream(row) for row in routing_rows(files, prefix, since)
        if row.get("status") == 200)


def post_routes(rows, request_keys):
    routes = {}
    seen = set()
    for row in rows:
        request_id = row.get("request_id")
        if (request_id in request_keys and row.get("method") == "POST"
                and row.get("path") == "/v1/orders" and row.get("status") in (200, 201)):
            upstream = final_upstream(row)
            if upstream:
                routes.setdefault(request_keys[request_id], set()).add(upstream)
                seen.add(request_id)
    return routes, seen


def interleaved_replays(order_count):
    return [index for index in range(order_count) for _ in range(2)]


def stabilize_edge(client, files, addresses, prefix, since, timeout=30, stable_seconds=5):
    """Retry read-only probes only, before the fail-fast authentication/workflow checks."""
    started = time.monotonic()
    deadline = started + timeout
    stable_since = None
    failures = []
    routing = Counter()
    rounds = 0

    def evidence():
        return {"timeoutSeconds": timeout, "requiredStableSeconds": stable_seconds,
            "elapsedSeconds": round(time.monotonic() - started, 3), "rounds": rounds,
            "transientFailures": failures}

    while time.monotonic() < deadline:
        round_prefix = f"{prefix}warm-{rounds}-"

        def probe(index):
            request_id = f"{round_prefix}{index}"
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return {"requestId": request_id, "kind": "warmup_deadline"}
            warmup_client = Client(client.base_url, key=client.key, timeout=min(2, remaining))
            try:
                request_identity(warmup_client, request_id)
            except EdgeResponseFailure as error:
                if error.status not in (502, 503, 504):
                    raise
                return {"requestId": request_id, "kind": "http", "status": error.status}
            except OSError as error:
                return {"requestId": request_id, "kind": "transport",
                    "errorClass": type(error).__name__}
            return None

        with ThreadPoolExecutor(max_workers=8) as pool:
            round_failures = [failure for failure in pool.map(probe, range(len(addresses) * 16))
                if failure is not None]
        failures.extend({"round": rounds, **failure} for failure in round_failures)
        current = Counter()
        remaining = deadline - time.monotonic()
        if remaining > 0:
            try:
                current = Counter(final_upstream(row) for row in routing_rows(
                    files, round_prefix, since, timeout=remaining) if row.get("status") == 200)
            except subprocess.TimeoutExpired:
                failures.append({"round": rounds, "kind": "routing_log_timeout"})
                round_failures.append({"kind": "routing_log_timeout"})
        obsolete = set(current) - set(addresses)
        missing = set(addresses) - set(current)
        if obsolete:
            failures.append({"round": rounds, "kind": "obsolete_upstream",
                "addresses": sorted(obsolete)})
        if missing:
            failures.append({"round": rounds, "kind": "missing_upstream",
                "addresses": sorted(missing)})
        routing.update({address: count for address, count in current.items() if address in addresses})
        rounds += 1
        now = time.monotonic()
        if not round_failures and not obsolete and not missing:
            if stable_since is None:
                stable_since = now
            if now - stable_since >= stable_seconds:
                return routing, evidence()
        else:
            stable_since = None
        time.sleep(min(1, max(0, deadline - now)))
    raise EdgeStabilizationTimeout(evidence())


def worker_commits(identifier):
    metrics = docker("exec", identifier, "sh", "-c",
        'curl --fail --silent --max-time 5 -H "X-API-Key: $APP_API_KEY" '
        'http://127.0.0.1:8080/actuator/prometheus')
    total = 0.0
    found = False
    for line in metrics.splitlines():
        if re.match(r"fulfillment_saga_attempts_total\{", line):
            found = True
            if 'outcome="advanced"' in line and 'step="COMMIT"' in line:
                total += float(line.split()[-1])
    return total, found


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--order-url", default="http://127.0.0.1:8080")
    parser.add_argument("--inventory-url", default="http://127.0.0.1:8081")
    parser.add_argument("--expected-replicas", type=int, default=2)
    parser.add_argument("--orders", type=int, default=24)
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--edge-warmup-timeout", type=int, default=30,
        help="Read-only edge stabilization budget in seconds, before strict workload checks")
    parser.add_argument("--compose-file", action="append", dest="files",
        help="Repeat for each active Compose file; defaults to base + scaling")
    parser.add_argument("--require-worker-distribution", action="store_true",
        help="Also require a measured committed workflow on every replica")
    args = parser.parse_args()
    if args.expected_replicas < 2 or not 2 <= args.orders <= 100:
        parser.error("Use at least two replicas and between 2 and 100 orders")
    if not 10 <= args.edge_warmup_timeout <= 120:
        parser.error("Use an edge warmup timeout between 10 and 120 seconds")
    files = args.files or ["compose.yaml", "compose.scaling.yaml"]
    order, inventory = Client(args.order_url), Client(args.inventory_url)
    order.wait_ready(args.timeout)
    inventory.wait_ready(args.timeout)
    instances = replicas(files, args.expected_replicas, args.timeout)
    addresses = {
        f"{network['IPAddress']}:8080": row["name"].lstrip("/")
        for row in instances for network in row["networks"].values() if network.get("IPAddress")
    }
    run_id = uuid4().hex[:12]
    prefix = f"scaling-{run_id}-"
    since = datetime.now(timezone.utc).isoformat()
    try:
        routing, warmup = stabilize_edge(order, files, addresses, prefix, since,
            timeout=args.edge_warmup_timeout)
    except EdgeStabilizationTimeout as error:
        path = save_report(f"scaling-smoke-{run_id}.json", {
            "timestamp": datetime.now(timezone.utc).isoformat(), "passed": False,
            "phase": "edge_warmup", "replicas": len(instances), "edgeWarmup": error.evidence})
        raise AssertionError(f"{error}; evidence: {path}") from error
    # Authentication and every mutation/workflow assertion below fail immediately on error.
    request_identity(Client(args.order_url, key="incorrect"), prefix + "unauthorized", 401)
    before = {row["containerId"]: worker_commits(row["containerId"])[0] for row in instances}

    sku = f"SCALE_{run_id}"
    inventory.request("POST", "/v1/items", {
        "sku": sku, "name": "Local Scaling Smoke", "unitPriceMinor": 1299,
        "currency": "USD", "initialStock": args.orders + 10,
    }, expected=(201,))
    body = {"items": [{"sku": sku, "quantity": 1}], "paymentToken": "tok_approved"}

    def submit(index):
        key = f"scaling-{run_id}-{index}"
        request_id = f"{prefix}post-{index}-{uuid4().hex[:8]}"
        _, created = order.request("POST", "/v1/orders", body,
            {"Idempotency-Key": key, "X-Request-ID": request_id}, expected=(200, 201))
        return index, created["id"], request_id

    # Adjacent duplicate keys exercise concurrent claims, rather than queuing all originals first.
    with ThreadPoolExecutor(max_workers=8) as pool:
        created = list(pool.map(submit, interleaved_replays(args.orders)))
    by_key = {}
    request_keys = {}
    for index, identifier, request_id in created:
        assert by_key.setdefault(index, identifier) == identifier, "Cross-replica replay created duplicate order"
        request_keys[request_id] = index
    assert len(set(by_key.values())) == args.orders, "Distinct keys did not produce distinct orders"

    # Round-robin routing alone does not prove a replay crossed replicas. Correlate every POST
    # with its successful upstream and replay missing keys until each has crossed a replica.
    replay_deadline = time.monotonic() + 30
    key_routes = {}
    while time.monotonic() < replay_deadline:
        key_routes, seen = post_routes(routing_rows(files, prefix, since), request_keys)
        unexpected = set().union(*key_routes.values()) - set(addresses) if key_routes else set()
        assert not unexpected, "Order POST reached an obsolete replica address"
        missing = [index for index in by_key if len(key_routes.get(index, set())) < 2]
        if not missing:
            break
        if len(seen) < len(request_keys):
            time.sleep(0.2)
            continue
        with ThreadPoolExecutor(max_workers=8) as pool:
            for index, identifier, request_id in pool.map(submit, missing):
                assert by_key[index] == identifier, "Cross-replica replay created duplicate order"
                request_keys[request_id] = index
    assert all(len(key_routes.get(index, set())) >= 2 for index in by_key), (
        "Not every idempotency key was observed on two replicas: "
        + str({index: sorted(key_routes.get(index, set())) for index in by_key}))
    order.request("POST", "/v1/orders", {**body, "items": [{"sku": sku, "quantity": 2}]},
        {"Idempotency-Key": f"scaling-{run_id}-0"}, expected=(409,))
    pending = set(by_key.values())
    deadline = time.monotonic() + args.timeout
    while pending and time.monotonic() < deadline:
        for identifier in tuple(pending):
            _, current = order.request("GET", f"/v1/orders/{identifier}")
            assert current["status"] not in ("CANCELLED", "MANUAL_REVIEW"), current["status"]
            if current["status"] == "CONFIRMED":
                assert current["totalMinor"] == 1299
                pending.remove(identifier)
        if pending:
            time.sleep(0.2)
    assert not pending, f"{len(pending)} workflows did not confirm before timeout"
    _, stock = inventory.request("GET", f"/v1/stock/{sku}")
    assert stock == {"sku": sku, "onHand": 10, "reserved": 0, "available": 10}, stock
    worker_deltas = {}
    metric_seen = False
    for row in instances:
        after, found = worker_commits(row["containerId"])
        metric_seen |= found
        worker_deltas[row["name"].lstrip("/")] = after - before[row["containerId"]]
    if args.require_worker_distribution:
        assert metric_seen, "Rebuild order-service with saga metrics before verifying worker distribution"
        assert all(value > 0 for value in worker_deltas.values()), worker_deltas

    report = {
        "timestamp": datetime.now(timezone.utc).isoformat(), "passed": True,
        "mode": "local Docker Compose manual horizontal scaling", "replicas": len(instances),
        "edgeWarmup": warmup,
        "routingRequests": {addresses[address]: count for address, count in sorted(routing.items())},
        "confirmedOrders": args.orders, "idempotentReplays": len(request_keys) - args.orders,
        "crossReplicaReplayKeys": len(key_routes),
        "idempotencyRouting": {str(index): sorted(addresses[address] for address in key_routes[index])
            for index in sorted(by_key)},
        "sagaCommitDeltaByReplica": worker_deltas,
        "workerDistributionRequired": args.require_worker_distribution,
        "stock": stock, "sku": sku, "orderIds": sorted(by_key.values()),
        "checks": ["all replicas healthy", "all replicas routed through edge",
            "API key rejection and request ID propagation", "cross-replica idempotency",
            "concurrent durable workflows confirmed", "exact stock and no leaked reservations"],
        "paymentProvider": "simulated; no real charge",
    }
    path = save_report(f"scaling-smoke-{run_id}.json", report)
    print(f"PASS: {len(instances)} routed replicas, {args.orders} confirmed orders; evidence: {path}")
    print("Committed workflow deltas by replica: " + json.dumps(worker_deltas, sort_keys=True))


if __name__ == "__main__":
    main()
