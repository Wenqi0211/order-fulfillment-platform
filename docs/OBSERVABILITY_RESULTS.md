# Local deployment and observability validation

The October 27, 2026 validation ran the application in local Docker Desktop containers on Apple Silicon. Payments used the existing simulator. PostgreSQL, Kafka and Redis stayed on the same host. These checks establish local deployment, manual replica scaling and observable recovery; they do not establish cloud deployment, autoscaling, production capacity or a zero-downtime guarantee.

## Verified behavior

- The deployment ran two order-service replicas behind an NGINX edge, scaled to three and back to two. At three replicas, 24 unique checkout workflows completed with exact inventory totals; the three workers committed 8, 12 and 4 workflows. The test correlated each idempotency key's repeated POSTs with successful NGINX upstream addresses and required every key to reach at least two replicas.
- Prometheus discovered each service instance independently. Grafana loaded the provisioned 35-panel dashboard. Application metrics rejected unauthenticated requests with HTTP 401. The HTTP 400, 404 and 409 checks produced the expected application counter increments.
- While the payment service was stopped, the test order remained in the durable PAYMENT phase with reserved inventory intact. Deferred attempts and the pending backlog increased. After restart, the same order confirmed once, reserved stock cleared and the pending backlog returned to its prior value.
- While Redis was stopped, six catalog reads returned the correct data and cache error/bypass counters increased. After restart, real cache hits resumed. The elapsed time in the report includes waiting for Prometheus scrapes; it is not a cache latency benchmark.
- The existing six end-to-end HTTP/Kafka workflow checks passed on the scaled deployment, including explicit payment-decline compensation, idempotent checkout and shipment-event ordering.

## Test results

| Validation | Result |
| --- | --- |
| Java unit tests | 52 passed; zero failures, errors or skips |
| PostgreSQL integration tests | 46 passed; zero failures, errors or skips |
| Python verification/tooling tests | 40 passed; includes bounded edge-stabilization and cross-replica evidence checks |
| Prometheus configuration | Four recording rules and ten alert rules validated |
| Alert-rule tests | Eight cases passed |
| Runtime monitoring checks | Seven checks passed with payment/Redis faults enabled |
| Dashboard query syntax | All 38 expressions evaluated successfully; idle/error-only series may be empty |

The configured scrape/evaluation interval was 15 seconds; Saga database snapshots refreshed every five seconds. Shared-database backlog values are deduplicated across replicas and filtered for snapshot freshness. Gauges use cached snapshots rather than database queries in scrape callbacks; failure and stale-snapshot signals remain available separately.

## Evidence and reproduction

Sanitized retained reports are under [local operations evidence](evidence/local-operations-20261027/). Each report identifies its exact workload and checks. Request IDs, order IDs and item identifiers refer to synthetic local test data.

Run [the deployment guide](LOCAL_SCALING.md) and [the monitoring guide](OBSERVABILITY.md) to reproduce. Workloads and dependency faults must run sequentially. During manual scale-in, NGINX discovers changed replica addresses on its DNS refresh cycle; the scaling check records stabilization separately from fail-fast order workflow assertions. The test restores stopped dependencies in `finally` and does not delete application data volumes.
