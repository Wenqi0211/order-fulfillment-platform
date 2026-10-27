# Order Fulfillment Platform

First time here? Follow the [step-by-step guide](docs/GETTING_STARTED.md) to open the project, then run `python3 scripts/walkthrough.py` to see the actual requests and results at each step.

An order fulfillment project built with Java 17 / Spring Boot 3.5. Order, inventory, and payment services run separately, each with its own PostgreSQL database. Kafka handles asynchronous warehouse status updates; Redis caches only product display information.

This version focuses on testable transaction boundaries, concurrency control, and failure recovery. The payment provider is an explicitly labeled local simulator. The code does not process real charges and has not run in an AWS production environment. See the [local test results](docs/LOCAL_RESULTS.md); do not present local tests as real production traffic.

Latest additions: [cache diagnosis, optimization, and nine measured load-test rounds](docs/CACHE_OPTIMIZATION.md) (normal-operation latency did not improve; gains were limited to concurrent cold-cache requests and Redis failure fallback); [AWS cost estimate and actual CI/CD status](docs/AWS_COST_ESTIMATE.md).

## Run locally

Docker Desktop must be running. The first build downloads Java/Maven and database images; subsequent builds use the cache. For this private repository, first authenticate Git with a GitHub account that has access. If you have already cloned it, go directly to the project directory.

```bash
git clone https://github.com/Wenqi0211/order-fulfillment-platform.git
cd order-fulfillment-platform
python3 scripts/init_env.py
./scripts/docker-local.sh compose up -d --build --wait --wait-timeout 180
python3 scripts/smoke.py
```

`init_env.py` generates local credentials only when they are missing; the file is not committed. The Docker wrapper uses the project's own anonymous image-download configuration to avoid changing global macOS credential settings.

Local ports: orders 8080, inventory 8081, payments 8082, PostgreSQL 5432, Redis 6379, and Kafka 9092. All bind only to the local loopback address. All business and metrics endpoints require the `APP_API_KEY` from `.env`, passed in the `X-API-Key` request header. Health checks do not require a key.

```bash
# View service status and order logs
./scripts/docker-local.sh compose ps
./scripts/docker-local.sh compose logs --tail=100 order-service
# Stop services while retaining database data
./scripts/docker-local.sh compose down
```

Do not use `down -v` unless you intend to erase all local database, Kafka, and Redis data for this project.

## Core behavior

- **Order idempotency**: The same `Idempotency-Key` and request return the same order; a changed request returns 409. Quotes come from the inventory database; clients cannot supply the purchase price.
- **Inventory consistency**: Multiple SKUs are locked in a fixed order, with reservation or rollback applied to the entire order. Database constraints prevent negative stock and excess reservations. Reservation, commit, and release operations have persistent state and audit records.
- **Payment retries**: An immutable PENDING payment intent is committed independently before calling the simulated provider with a stable order ID. Unknown outcomes remain PENDING; retries with a different amount or token return 409.
- **Persistent Saga**: `RESERVE → PAYMENT → COMMIT → DONE`. Only an explicit payment decline triggers inventory compensation; timeouts retain the current step for retry. After the retry limit, the order enters `MANUAL_REVIEW`, where an operator can resume the same step.
- **Transactional outbox**: Business records and outgoing messages commit together; messages are marked sent only after Kafka acknowledges them. A duplicate-delivery window exists after sending and before the database commit, so consumers must be idempotent.
- **Warehouse events**: Kafka updates status asynchronously, deduplicating by event ID and preventing status regression with sequence numbers. Unprocessable messages enter the DLT after bounded retries; the original offset is committed only after DLT delivery is confirmed. Replay can preserve the original event ID.
- **Redis boundaries**: Display cache entries may be briefly stale; quotes and inventory changes always use PostgreSQL. Cache-miss loads for the same SKU within one process use bounded request coalescing. Redis failures trigger a brief bypass, with a single probe confirming recovery and product queries falling back to the database.

## Code guide

| Directory | Focus |
| --- | --- |
| `common/` | API contracts, input boundaries, API-key validation, request correlation IDs, transactional outbox, metrics |
| `inventory-service/` | Products, authoritative quotes, inventory reservation/commit/release, Redis fallback |
| `payment-service/` | Persistent payment intents, provider interface, explicitly labeled payment simulation |
| `order-service/` | Order creation, persistent workflow, warehouse event consumption, DLT and recovery endpoints |
| `infra/kubernetes/` | staging/prod configuration, probes, resource limits, network policies, rolling deployments |
| `infra/aws/` | Terraform for a single learning/staging EC2 instance, VPC, and SSM; does not represent a highly available production architecture |
| `.github/workflows/` | Tests, image builds, releases by digest, and environment-protected deployment/rollback |
| `scripts/` | End-to-end validation, failure-recovery validation, load experiments, deployment tools |

## Validation and load experiments

Java unit tests require JDK 17 and Python 3. `./mvnw` downloads and verifies Maven inside the project's `.tooling` directory without changing the global Maven installation or `~/.m2`.

```bash
./mvnw test
# Integration tests against real PostgreSQL in Docker; credentials read privately from .env
python3 scripts/java_integration.py
# End-to-end HTTP + Kafka flow
python3 scripts/smoke.py
# Temporarily stop and restore this project's Redis, payment service, and Kafka
python3 scripts/fault_smoke.py
# Send and read back an actual Kafka dead-letter message
python3 scripts/dlt_smoke.py
# Compare cache on/off with the same data, request sequence, and resource limits; save raw data
python3 scripts/cache_benchmark.py --count 1000 --rate 50 --seconds 30 --repeats 3
```

Integration tests cover inventory contention, multi-SKU atomicity, concurrent idempotency, unknown payment outcomes, crash recovery, monotonic state transitions, consumer transaction rollback, and the duplicate-delivery window after outbox sending. Failure scripts restore the services they stop in `finally`; after a manual interruption, use `compose up -d --wait` to restore them.

Load tests save every request, failures, dropped requests, latency percentiles, and container resource limits. Do not put percentages, throughput, or availability figures on a resume without repeated validation; the presence of CI/CD configuration does not establish that cloud deployment has been completed.

## Deployment and limitations

See the [architecture](docs/ARCHITECTURE.md), [operations guide](docs/OPERATIONS.md), [resume claim traceability](docs/RESUME_TRACEABILITY.md), and [verification record](docs/VERIFICATION.md).

The API is designed for a single internal/B2B service identity. It does not provide consumer accounts, multitenant authorization, or security certification for real payments. Production integration still requires idempotency/reconciliation with a real provider, secret management and rotation, TLS, infrastructure capacity planning, data retention and backup/recovery drills, on-call alerts, and validation against actual traffic. Public-cloud deployments incur charges; this project has not automatically run Terraform apply or created cloud resources.

Technical implementation cannot establish past team management, Scrum participation, production deployment, or business outcomes. Related resume experience must reflect what actually happened.
