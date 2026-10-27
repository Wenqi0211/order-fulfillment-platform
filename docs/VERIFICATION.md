# Verification and evidence

Completed local results: [2026-10-24 report](LOCAL_RESULTS.md), including actual counts, failure drills, raw cache measurements and remaining scope.

Latest cache update: [diagnosis and completed nine-case study](CACHE_OPTIMIZATION.md). The updated clean Java build passed 41 unit tests and 44 PostgreSQL integration tests without failures or skips; 23 Python tool/release tests and the post-benchmark 6-check system smoke plus 9-check HTTP boundary smoke passed. This supplements, rather than rewrites, the earlier local results. Normal healthy latency did not improve; the measured benefits concern concurrent same-SKU cold misses and Redis-outage fallback. Cloud costs and workflow execution limits are documented in [AWS/CI-CD status](AWS_COST_ESTIMATE.md).

Commands in this document describe reproducible checks. Their presence is not a claim that every test or cloud deployment has run. Consult the actual Maven output and timestamped `reports/` generated on the current checkout for completed results. Do not replace failed measurements with invented success numbers.

Local infrastructure checks completed on 2026-10-24: Docker Compose configuration validation, YAML parsing/security checks, staging and production Kustomize rendering, shell syntax checks, Python compilation and the six release-safety tests passed. `terraform fmt -check` and `terraform validate` passed for `infra/aws`; the successful provider-backed validation output is saved in `.tooling/terraform-validate.log`. These checks did not create AWS resources or run a Kubernetes rollout. Actual application/integration/system test outcomes are recorded separately by the current build and smoke reports.

## Java tests

```sh
./mvnw -B -ntp test
```

Real PostgreSQL integration tests require a **disposable** database. Set `TEST_DATABASE_URL` to a JDBC URL plus `TEST_DATABASE_USER` and `TEST_DATABASE_PASSWORD`, then run:

```sh
./mvnw -B -ntp -Pintegration verify
```

Test fixtures may create/drop their isolated test schemas. Never point these variables at a live application or production database. Integration coverage should demonstrate transaction rollback, concurrent same-order idempotency and oversell prevention, persisted workflow recovery and unknown payment outcomes, consumer deduplication/monotonic sequence, and commit/ack failure boundaries. Read the actual test names and results rather than assuming a configured profile proves each property.

## Configuration and full transport checks

```sh
python3 -m py_compile scripts/*.py
python3 -m unittest discover -s scripts -p 'test_*.py'
bash -n infra/local/init-databases.sh
bash -n scripts/bootstrap-k3s.sh
python3 -m pip install PyYAML==6.0.2
python3 scripts/check_manifests.py
kubectl kustomize infra/kubernetes/overlays/staging > /tmp/order-staging.yaml
kubectl kustomize infra/kubernetes/overlays/prod > /tmp/order-prod.yaml
python3 scripts/init_env.py
docker compose config --quiet
docker compose up --build -d --wait --wait-timeout 360
python3 scripts/smoke.py
python3 scripts/api_contract_smoke.py
python3 scripts/fault_smoke.py
python3 scripts/dlt_smoke.py
```

The system smoke creates uniquely named disposable catalog entries and three orders. It checks invalid authentication, order request replay and payload conflict, authoritative pricing, successful payment/inventory commit, an actual asynchronous Kafka warehouse update, duplicate/conflicting/stale warehouse inputs, decline compensation and insufficient-stock cancellation. It saves a JSON report only after all checks pass. Its payment provider is simulated; no real card network is contacted. A unit test mocking Kafka is not a substitute for this transport check.

Manifest parsing/rendering verifies syntax and intended generated configuration. It does not replace API-server admission, image startup, CNI/network-policy enforcement, cloud networking checks or a real protected deployment. Terraform validation also does not create a host or prove that IAM/AMI/network resources are currently deployable in an account.

## Failure drills on the local disposable stack

Capture before/after order IDs, database state, logs and metrics for each drill. Restore the stack and verify it drains before the next drill. Use independent idempotency keys per new order, and reuse the same key/payload for retries of that order.

1. **Redis unavailable:** stop Redis, retrieve catalog metadata and create an order, then restart Redis. Verify successful database fallback and authoritative inventory. A healthy response should not imply a cache hit.
2. **Inventory unavailable:** first submit and persist an order, then interrupt inventory-service before its reservation phase. Restart the dependency and verify the same persisted order resumes without duplicate reservations or charges. When inventory is already down before initial submission, authoritative quotation fails and the API returns a transient error without creating an order; retry that submission with the same key after recovery. If it parks after bounded retries, use the explicit retry endpoint after repair.
3. **Kafka unavailable:** stop Kafka, submit a warehouse event and verify HTTP acceptance records the durable ingress/outbox while the warehouse order status has not changed; restart Kafka and verify eventual application plus backlog drainage. Readiness can remain green while backlog grows.
4. **Process death between phases:** while orders are pending, kill/restart order-service. Verify reservation/payment/commit endpoints converge for the same order IDs. For a deterministic after-effect/before-state-persistence case, use the focused integration test; timing a manual kill alone does not prove that exact window was hit.
5. **Duplicate and stale warehouse events:** repeat one accepted event and send a lower sequence with a new event ID. Verify the final shipment state cannot regress and the consumer receipt prevents duplicate effects. Then test an envelope that cannot be processed, capture bounded failure/DLT evidence, repair the cause and use the replay procedure.
6. **Concurrent stock pressure:** run the real PostgreSQL concurrency test with more requested units than available. Verify final counts/constraints and that successful reservations/commits do not exceed initial stock; report the actual test result, not an inferred throughput number.
7. **Deployment failure:** in a disposable staging namespace, use a deliberately invalid image or failing readiness revision and verify the workflow attempts to restore all three prior workload revisions. Record rollback failures as failures. This drill requires a real cluster and protected workflow setup; it is not covered by local static rendering.

For Kafka outage drills, use `docker compose stop kafka` then `docker compose start kafka`; do not delete its data volume. For dependency drills, operate only on this named local Compose project, not an arbitrary remote Docker context. Monitor pending payment outcomes and avoid manual compensation while the outcome is unknown.

## Performance claims

```sh
python3 scripts/cache_benchmark.py --count 1000 --rate 50 --seconds 30 --repeats 3 --concurrency 32
```

This preserves raw request observations and actual mean/p95/p99/error/drop counts for cache-off, cold-start and warm-cache cases with matching dataset/request sequence. The script reconfigures inventory-service and resets only its benchmark SKU cache prefix. It is a controlled local experiment, not proof of holiday traffic in a production system. Run several rates around the intended workload, keep resource limits and replicas constant, and compare repetitions. Include hardware, container limits, dataset size, TTL, rate, concurrency, duration, Git revision, errors/drops and raw evidence with any resume number.

For Kafka warehouse throughput, cache GET latency is insufficient evidence: use an explicit accepted-event workload, measure acceptance and end-to-end application lag separately, and reconcile every accepted event ID to a receipt/outcome. No warehouse-capacity number is prefilled in this repository.

## Deployment-template checks

```sh
terraform -chdir=infra/aws fmt -check
terraform -chdir=infra/aws init -backend=false
terraform -chdir=infra/aws validate
```

`init` downloads providers but does not create infrastructure. `plan` may query AWS and needs authorized account credentials; `apply` creates billable resources and is intentionally not part of local verification. See OPERATIONS.md for the single-host limitations, private SSM access, external data services and deployment environment protections.
