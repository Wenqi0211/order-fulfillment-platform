# Resume statement → implementation → evidence

These are implementation mappings, not a certification that historical resume statements were already true. Keep original dates/responsibilities separate from work performed on this newly generated repository.

| Resume point | Repository implementation | Evidence to collect before claiming the outcome |
|---|---|---|
| Independent inventory, orders and payment microservices, Docker, EC2 | `inventory-service/`, `order-service/`, `payment-service/`, independent databases/roles, `Dockerfile`, `compose.yaml`; single EC2 learning/staging host Terraform and Kubernetes deployment assets | Actual local containers can be verified with system smoke tests. EC2 is only a template until deployed and inspected on the user's AWS account. |
| Kafka event-driven pipeline and asynchronous warehouse updates under load | Transactional outboxes, actual Kafka topic/consumer, durable receipts/failure counts, monotonic warehouse updates and DLT replay | Run the full Kafka smoke/failure scenarios and workload tests. There is no prewritten “production scale” throughput number. |
| Redis inventory lookup cache improves average latency under simulated holiday traffic | Bounded-stale catalog cache with fail-open behavior; authoritative stock/quote endpoints bypass it; reproducible benchmark scripts | Compare cache-off/cold/warm with the same dataset, request sequence, rate, resources and repetitions; preserve raw observations, errors and drops. No improvement is asserted until measured. |
| Kubernetes and GitHub Actions automate tests and staging/prod rollouts | Namespace overlays, probes/resources/HPA/PDB/network policy; CI unit/PG/system tests; commit-tagged images deployed using CI-recorded image digests; manual protected-environment rollout and explicit workload rollback | Workflows and manifests exist locally. A GitHub repository, protected environments, runner, cluster, credentials, registry and successful runs must exist before saying it has been deployed. |
| REST APIs and two-week Agile/Scrum, peer reviews, leading engineers | Authenticated APIs, PR review template and proposed sprint plan | REST behavior is testable. Templates do not create real teammates, reviews, meetings or leadership experience. Record actual collaboration only. |

## Honest wording after implementation

Use the technologies you can explain and the behavior you have personally verified. A meaningful bullet connects an actual problem, your design choice and the measured or tested result. Do not copy the generated repository into a claim of independent historical authorship or substitute suggested workloads for measured outcomes.

Examples requiring evidence before use:

- Implemented transactional outbox delivery and idempotent Kafka warehouse consumers; verified duplicate, stale and crash-retry cases against PostgreSQL and Kafka.
- Built a resumable order workflow with idempotent inventory and payment commands; tested recovery when a dependency times out after a successful effect.
- Benchmarked catalog caching under [actual rate] using [actual environment], recording [actual mean/p95] and [actual error/drop counts] against the cache-off baseline.

The bracketed fields must come from saved reports. Replace a performance-improvement statement with a factual benchmark statement if the cache does not improve the measured workload.
