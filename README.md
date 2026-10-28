# Order Fulfillment Platform

A distributed order-processing backend with durable checkout recovery, transactional event delivery, and concurrency-safe inventory reservations. Three Spring Boot services own separate PostgreSQL databases; Kafka carries asynchronous warehouse updates, and Redis caches product metadata.

**Stack:** Java 17 · Spring Boot 3.5 · PostgreSQL · Apache Kafka · Redis · Docker · Kubernetes · Terraform · GitHub Actions

## Architecture

| Component | Responsibility | State and dependencies |
| --- | --- | --- |
| Order service | Order APIs, request idempotency, persisted checkout Saga, warehouse ingress and consumption | Orders PostgreSQL database; HTTP calls to inventory and payment; Kafka |
| Inventory service | Authoritative quotes, atomic stock reservations, product metadata | Inventory PostgreSQL database; Redis display cache; Kafka outbox |
| Payment service | Durable payment intents and idempotent provider requests | Payments PostgreSQL database; simulated provider; Kafka outbox |
| Event transport | Asynchronous domain events and warehouse status delivery | Kafka topics, transactional outbox relays, consumer receipts, and dead-letter delivery |

Checkout follows `Client → Orders → Inventory / Payments`. Warehouse updates follow `Event ingress → Order outbox → Kafka → Warehouse consumer → Orders database`.

Services communicate through HTTP APIs and Kafka rather than cross-database joins. Each service has its own migrations, database role, and transaction boundary. The local Compose stack hosts the three databases in one PostgreSQL instance.

## Key design decisions

- **Recoverable checkout:** A persisted Saga advances through `RESERVE → PAYMENT → COMMIT → DONE`. Steps resume after a restart with the same order identity. Explicit payment declines trigger inventory compensation; timeouts retain the current phase because the payment outcome may be unknown. Bounded retries and persisted backoff lead to `MANUAL_REVIEW` when intervention is required.
- **Inventory correctness:** SKU rows are locked in a fixed order to avoid lock-order deadlocks. Multi-SKU reservations commit or roll back atomically; database constraints prevent negative inventory and excess reservations. Reserve, commit, and release are idempotent transitions.
- **Idempotent order and payment APIs:** An `Idempotency-Key` binds an order request to a stored result; conflicting payloads return HTTP 409. Payment intents are persisted before provider calls, and retries reuse the same order identity. Authoritative quotes and integer minor-unit amounts keep checkout independent of cached display prices.
- **Reliable event delivery:** Domain changes and outbox records commit in one PostgreSQL transaction. Relays mark events published after Kafka acknowledgement. Consumers deduplicate event IDs and apply receipts with state changes in one transaction. Sequence checks prevent out-of-order warehouse updates from regressing shipment status. Failed messages use bounded retries and dead-letter delivery; events accepted by the warehouse ingress can be replayed with their original identity.
- **Cache failure isolation:** Redis serves product metadata; stock reservations and checkout quotes always use PostgreSQL. Bounded same-SKU request coalescing reduces duplicate cold-cache reads. A cooldown bypass and single recovery probe avoid repeated Redis timeouts while database fallback continues serving requests.

Core implementations: [SagaProcessor](order-service/src/main/java/com/wenqi/fulfillment/order/SagaProcessor.java), [InventoryService](inventory-service/src/main/java/com/wenqi/fulfillment/inventory/InventoryService.java), [PaymentService](payment-service/src/main/java/com/wenqi/fulfillment/payment/PaymentService.java), [OutboxRelay](common/src/main/java/com/wenqi/fulfillment/common/OutboxRelay.java), and [WarehouseService](order-service/src/main/java/com/wenqi/fulfillment/order/WarehouseService.java).

## Verification

Recorded validation includes:

| Layer | Results and coverage |
| --- | --- |
| Java unit and PostgreSQL integration | 41 unit tests and 44 integration tests passed with no failures, errors, or skips; inventory contention, atomic reservations, concurrent idempotency, unknown payment outcomes, workflow recovery, and consumer/outbox transaction boundaries |
| End-to-end HTTP and Kafka | 6 workflow checks and 9 HTTP boundary checks passed; order replay, authoritative totals, inventory commit, shipment events, payment-decline compensation, and insufficient-stock cancellation |
| Dependency recovery and dead-letter delivery | 3 recovery checks and 3 Kafka DLT checks passed; Redis fallback, payment-service recovery, Kafka backlog delivery, and dead-letter readback |
| Tooling and configuration | 23 Python tests passed; 31 YAML documents checked, both Kubernetes overlays rendered, and Terraform formatting/provider validation completed |

See the [verification record](docs/VERIFICATION.md) and [retained evidence](docs/evidence/README.md) for test scope and reports.

### Cache experiments

Measurements used a single inventory-service container limited to 1 CPU / 768 MiB on local Apple Silicon Docker, with PostgreSQL and Redis on the same host.

| Scenario | Conditions | Observed result |
| --- | --- | --- |
| Concurrent cold-cache lookup | 64 requests for the same SKU, three rounds per version | Total database reads fell from 13 to 3; all requests returned correct content. Burst p95 did not improve. |
| Redis outage | 50 requests/second for 15 seconds per version, 750 requests each | Cooldown bypass and controlled recovery reduced p95 from approximately 510.6 ms to 6.6 ms versus the existing per-request GET/SET timeout fallback; both versions had zero failed or dropped requests. |
| Steady-state lookup | 1,000 SKUs, 500 requests/second, nine 60-second rounds | All 270,000 measured requests returned correct content with zero failures or drops. The added cache protections did not improve normal-operation latency in this environment. |

These are local experiments. The outage comparison measures degraded-mode behavior, and the steady-state counts exclude separately recorded warmups. [Methods, per-run results, and warmup data](docs/CACHE_OPTIMIZATION.md).

## Quick start

**Requirements:** Docker with Compose, Python 3.12+, and access to this repository. Java tests outside Docker also require JDK 17 and `curl`; `./mvnw` downloads and verifies Maven locally.

```bash
git clone https://github.com/Wenqi0211/order-fulfillment-platform.git
cd order-fulfillment-platform
python3 scripts/init_env.py
./scripts/docker-local.sh compose up -d --build --wait --wait-timeout 180
python3 scripts/smoke.py
```

The initialization script creates local credentials in the Git-ignored `.env`. Business and metrics endpoints require its `APP_API_KEY` in the `X-API-Key` header; readiness endpoints are unauthenticated.

| Service | Local address |
| --- | --- |
| Orders | `http://localhost:8080` |
| Inventory | `http://localhost:8081` |
| Payments | `http://localhost:8082` |

Each exposes `/actuator/health/readiness`. Published database, broker, cache, and application ports bind to loopback. Stop the stack with `./scripts/docker-local.sh compose down`; persistent volumes are retained.

## Run checks

```bash
# Java unit tests
./mvnw test

# PostgreSQL integration tests against the running local stack
python3 scripts/java_integration.py

# Python tooling tests
python3 -m unittest discover -s scripts -p 'test_*.py'

# HTTP contracts, dependency recovery, and Kafka dead-letter delivery
python3 scripts/api_contract_smoke.py
python3 scripts/fault_smoke.py
python3 scripts/dlt_smoke.py
```

Run failure checks sequentially on the local stack: they temporarily stop and restore project dependencies. [Full verification procedures](docs/VERIFICATION.md).

## CI/CD and deployment

[GitHub Actions CI](.github/workflows/ci.yml) runs Java/PostgreSQL tests, Python checks, manifest validation, Kubernetes overlay rendering, and Compose-based HTTP/Kafka recovery checks. Successful `main` builds publish commit-tagged `linux/amd64` and `linux/arm64` service images to GHCR and retain their digests.

The [manual release workflow](.github/workflows/deploy.yml) checks CI status and image digests, targets staging or production namespaces, verifies rollouts and endpoints, and attempts to restore previous application revisions on failure. Kubernetes manifests and an EC2/VPC/SSM Terraform template are included. Cloud provisioning and actual Kubernetes rollout/rollback have not been executed.

## Scope

The payment adapter is simulated. API-key authentication represents one trusted internal/B2B service identity. The local stack uses one PostgreSQL instance and a single Kafka broker; it is not a highly available multi-host deployment. Cache entries may be stale within their TTL, while inventory and checkout prices remain database-authoritative.

## Documentation

- [Architecture and invariants](docs/ARCHITECTURE.md)
- [Operations and deployment](docs/OPERATIONS.md)
- [Local setup and API walkthrough](docs/GETTING_STARTED.md)
- [Verification and evidence](docs/VERIFICATION.md)
- [Cache diagnosis and measurement](docs/CACHE_OPTIMIZATION.md)
