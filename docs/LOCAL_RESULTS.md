# Local Verification Results · 2026-10-24

The repository includes a compact evidence set in [docs/evidence](evidence/README.md), preserving the original reports' relative hierarchy under `reports/`. Measurements, timestamps, image IDs, and historical path metadata in the copied files have not been changed. Complete raw JSONL, logs, Maven XML, metric snapshots, and baseline source code remain in the original computer's local `reports/` directory and are not uploaded with the Git repository. Original absolute paths in the JSON identify historical run locations and cannot be accessed directly in a new clone.

The final application uses Java 17 / Spring Boot 3.5.16. Databases and messaging run in real Docker containers; the payment provider is a local simulator.

## Executed and passed

- Maven run from cleaned build directories: **32 unit tests and 44 PostgreSQL integration tests**, with 0 failures, errors, or skips.
- 6 end-to-end HTTP/Kafka flow checks: idempotency and conflicts, authoritative quotes, inventory commit, warehouse status, compensation after payment decline, and cancellation for insufficient stock.
- 9 real HTTP input-boundary checks: missing headers, non-integer/string quantities, unknown/duplicate fields, body size, and 415/405/404.
- 3 dependency failure-recovery checks: Redis database fallback, resuming the same order after payment-service recovery, and outbox delivery plus warehouse status updates after Kafka recovery.
- 3 real Kafka DLT checks: send an invalid source message, confirm the persistent DLT result, and actually read the original message back from the DLT.
- 6 deployment-tool safety tests; 31 YAML checks; staging/prod Kustomize rendering; Terraform formatting and provider validation.

The [machine-readable verification summary](evidence/verification-summary.json) and [source hash manifest](evidence/source-hashes.json) are saved. Paths in the JSON retain the original run-record locations. The repository includes the corresponding small result JSON files; Maven XML remains in the original computer's `reports/java-tests/`.

## Cache measurements

Environment: macOS arm64, local Docker; one inventory instance limited to 1 CPU / 768 MiB. 500 SKUs; fixed 100 requests/second; 10 seconds per round; concurrency limit 32; identical data and deterministic request sequences; cache TTL 30 seconds. Three rounds per category. cold means this experiment's cache was cleared at the start of each round and gradually warmed during the round.

| Round | Mean ms | p95 ms | Failed | Dropped | Notes |
| --- | ---: | ---: | ---: | ---: | --- |
| off-1 | 2.087 | 2.939 | 0 | 0 | Concurrent system checks; excluded from comparison |
| off-2 | 1.305 | 2.055 | 0 | 0 | — |
| off-3 | 1.349 | 2.174 | 0 | 0 | — |
| cold-1 | 19.757 | 18.433 | 0 | 30 | Dropped requests; not a healthy load |
| cold-2 | 1.445 | 2.127 | 0 | 0 | — |
| cold-3 | 1.383 | 2.118 | 0 | 0 | — |
| warm-1 | 1.482 | 2.167 | 0 | 0 | — |
| warm-2 | 1.463 | 2.182 | 0 | 0 | — |
| warm-3 | 1.266 | 1.886 | 0 | 0 | — |

Means and percentiles include only HTTP 200 requests that returned the correct content. Approximately the first 3.1 seconds of off-1 overlapped with system checks; the raw data is retained, but that round is excluded from performance conclusions. cold-1 dropped 30 requests because of the concurrency limit, so its successful-response latency cannot represent healthy capacity.

**This local experiment did not establish that Redis consistently reduces response times.** Dataset size, database distance, service startup state, and load all affect the results. It is currently accurate to describe the implemented cache, database fallback, and reproducible experiment; these results do not support “latency reduced by X%” or production holiday-traffic claims.

The [complete experiment record](evidence/cache-ab/20261024T233035Z/experiment.json) retains each round's JSONL, statistics, before/after metrics, and resource settings. Measurement failures and dropped requests were not removed.

## Not yet verified

At the time of this 2026-10-24 local verification, no AWS resources had been created, no actual Kubernetes deployment/rollback had been run, and no GitHub-hosted workflow had been executed. The related configuration and local static checks are complete. There are no real payments, real business users, production availability figures, or production capacity results. This project cannot establish past team management, meetings, or historical responsibilities.
