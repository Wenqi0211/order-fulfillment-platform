# Local deployment and horizontal scaling

`compose.scaling.yaml` runs two independent order-service containers behind an NGINX edge on `http://127.0.0.1:8080`. Each instance serves HTTP requests and runs the durable saga/outbox workers. PostgreSQL, Kafka, Redis, inventory-service, and payment-service remain shared single instances. This is a local deployment with **manual horizontal scaling**. Docker Compose does not provide CPU-based autoscaling, Kubernetes HPA, a cloud deployment, or high availability across hosts.

```text
localhost:8080 -> NGINX -> order-service replica 1 --+
                       -> order-service replica 2 --+-> PostgreSQL / Kafka
                                                   +-> inventory / payment
Prometheus -----------------> each application replica's own metrics endpoint
```

## Start the local stack

Use Docker Engine/Desktop with Compose 2.24.4 or later. The overlay clears the original fixed order-service host port with Compose's `!reset` tag. Reserve at least 6 GiB of Docker memory for the infrastructure, four application JVMs, and monitoring; actual requirements depend on workload. Keep the generated `.env` file private.

Run from the repository root:

```bash
python3 scripts/init_env.py
python3 scripts/init_observability.py

dc() {
  bash scripts/docker-local.sh compose \
    -f compose.yaml \
    -f compose.scaling.yaml \
    -f compose.observability.yaml "$@"
}

# Validate the merged model without printing interpolated credentials.
dc config --quiet
dc up -d --build --wait --wait-timeout 300
dc ps
dc exec -T edge nginx -t

python3 scripts/deployment_smoke.py \
  --order-url http://127.0.0.1:8080 \
  --inventory-url http://127.0.0.1:8081 \
  --payment-url http://127.0.0.1:8082

python3 scripts/scaling_smoke.py --expected-replicas 2 --require-worker-distribution
```

For deployment without dashboards, omit `-f compose.observability.yaml` from the `dc` function. Continue using both the base and scaling files for all stack operations. Applying only the base file reinstates the direct order-service host port and conflicts with the edge on port 8080.

Inventory and payment retain their existing localhost-only ports 8081 and 8082. The scaled order containers publish no host ports. The edge passes API keys and request IDs to the application, which retains authentication and input validation. No backend address is added to API responses.

## Demonstrate adding and removing a replica

```bash
# Start an additional order JVM and wait for its health check.
dc up -d --no-build --wait --wait-timeout 180 --scale order-service=3
python3 scripts/scaling_smoke.py --expected-replicas 3 --require-worker-distribution

# Return to the normal two-replica local configuration.
dc up -d --no-build --wait --wait-timeout 180 --scale order-service=2
python3 scripts/scaling_smoke.py --expected-replicas 2 --require-worker-distribution
```

The NGINX upstream uses Docker's internal DNS resolver (`127.0.0.11`) with a five-second refresh and a shared upstream zone. It discovers replica address changes without restarting the edge. Compose waits for container health, but NGINX may briefly retain a removed address or an existing upstream connection. During removal, Spring waits up to its configured graceful shutdown period for active requests and workers; this does not provide coordinated load-balancer draining. An in-flight request can still fail, and a new address may become discoverable before its application is ready. This demonstration makes no zero-downtime claim.

Before checking authentication or creating orders, the scaling smoke runs read-only authenticated GET probes for up to 30 seconds. It requires successful routing to every current backend with no transient failures or obsolete successful upstreams for a full five-second DNS window. Transient HTTP 502/503/504, transport errors and routing gaps during this warmup are recorded in the report. Persistent failure stops the check and writes a failed warmup report. Use `--edge-warmup-timeout 60` if a slower local host needs a longer bounded warmup. Once warmup finishes, authentication, order requests and workflow assertions fail immediately on errors; the workload does not hide failed mutations with retries.

NGINX can try up to three peers on connection errors or timeouts within its four-second retry budget. The configuration does not opt into replaying non-idempotent requests after they reach an upstream. Clients must use documented idempotency keys when recovering a failed mutating request and inspect its persisted outcome before deciding how to retry.

Scaling more JVMs increases shared PostgreSQL connection use (12 maximum per application instance by default), Kafka consumers, memory, and downstream load. Three order replicas can consume the warehouse topic's three partitions; more replicas do not increase that consumer group's partition parallelism. Measure these limits before changing capacity beyond this demonstration.

## What the scaling smoke verifies

The standard-library script creates one uniquely named test SKU, then concurrently submits 24 approved orders and replays all 24 idempotency keys through the edge. The payment provider remains simulated. It checks:

- Exactly the requested number of healthy order containers.
- Read-only edge stabilization completes, followed by successful authenticated routing to every replica using request IDs and NGINX JSON routing logs.
- Invalid API credentials receive HTTP 401 and caller request IDs survive the proxy.
- Interleaved original/replay requests return the same order ID. Each key must be observed on at least two current replicas using per-POST request IDs and NGINX logs; bounded additional idempotent replays fill routing gaps. A changed payload returns HTTP 409.
- Every workflow reaches `CONFIRMED`, consumes stock once, and leaves no reserved stock.
- With `--require-worker-distribution`, each instance's local `fulfillment_saga_attempts_total{step="COMMIT",outcome="advanced"}` counter increases during the workload.

Workers coordinate through the shared database's `FOR UPDATE SKIP LOCKED` transaction claims. The workload checks the resulting stock and order invariants; it is a bounded smoke check, not a formal proof of all race conditions or a throughput benchmark. Worker scheduling is not a fairness guarantee. If one replica processes no commits during a small run, the strict check fails; inspect its health and logs and repeat with `--orders 48` before drawing a capacity conclusion.

Reports are saved under the ignored `reports/scaling-smoke-<run-id>.json` path. They contain warmup timing and transient failures, replica routing counts, per-key replay routing, actual replay counts, per-instance committed-workflow deltas, order IDs, and final stock. They contain no API keys. Test SKU and order rows are intentionally retained for inspection; the script does not reset or delete volumes.

## Monitor and inspect

See [OBSERVABILITY.md](OBSERVABILITY.md) for dashboard URLs, credentials, scrape checks, and alerts. Prometheus must discover and scrape every order-service IP directly; scraping only the edge mixes instance counters and cannot establish individual replica health. Shared database backlog gauges describe the same queue from each instance and must not be summed across replicas.

```bash
dc logs --tail 30 edge
dc logs --tail 50 order-service
dc exec -T edge nginx -t
```

Edge logs include the application request ID, upstream address/status, and request timings. They omit API keys, request bodies, and query strings. These addresses provide local operational evidence without exposing instance identity in the public HTTP API.

To stop containers while retaining state, run `dc stop`. `dc up -d --wait` starts them again with the overlay's default of two order replicas. Avoid `down --volumes` unless intentionally deleting all local data.

## References

- [Docker Compose merge rules and `!reset`](https://docs.docker.com/reference/compose-file/merge/)
- [Docker Compose service networking and scaling](https://docs.docker.com/compose/how-tos/networking/)
- [NGINX shared upstream DNS resolution](https://nginx.org/en/docs/http/ngx_http_upstream_module.html#resolve)
