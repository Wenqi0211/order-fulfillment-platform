# Operations runbook

## Scope and credentials

This is an engineering implementation with a simulated payment adapter. Local Compose has one PostgreSQL server with independent service databases and roles, one Kafka broker and one Redis instance. It proves application behavior; it does not provide infrastructure high availability. Kubernetes and Terraform files are deployment templates, not evidence of a completed cloud deployment.

Run all commands from the repository root. Use Docker Desktop/Engine with Compose v2, Python 3.11+, and a Java 17 JDK. `./mvnw` downloads the project-pinned Maven distribution. Generate independent development secrets with `python3 scripts/init_env.py`. The resulting `.env` is owner-readable and ignored by Git. Do not commit it, print it in CI, or use its values for production.

The single API key represents a trusted internal operator/service. All non-health APIs, including metrics, require `X-API-Key`. This implementation is not a public consumer authentication or multitenant authorization system. Use a reviewed identity gateway and per-resource authorization before public consumer exposure.

## Start and inspect the local stack

```sh
python3 scripts/init_env.py
docker compose config --quiet
docker compose up --build -d --wait --wait-timeout 360
python3 scripts/smoke.py
docker compose ps
```

On macOS, if a Docker command prompts for Keychain access or cannot read the global credential helper, use the isolated project wrapper instead:

```sh
scripts/docker-local.sh compose config --quiet
scripts/docker-local.sh compose up --build -d --wait --wait-timeout 360
scripts/docker-local.sh compose ps
```

The wrapper uses `.tooling/docker/config.json`, supplies an explicit anonymous Docker Hub entry, and points at the local Unix socket. It preserves an existing project configuration and never changes the global Docker configuration or Keychain. Start Docker Desktop first. This is an anonymous public-image pull configuration; private registry credentials, if needed, must be configured separately in the isolated project directory. The cache experiment automatically uses this wrapper. It refuses a TCP/remote Docker host.

Only loopback ports are published: order 8080, inventory 8081, payment 8082, PostgreSQL 5432, Kafka 9092 and Redis 6379. Existing occupied ports must be resolved before startup. Each application has a separate database role. The administrative PostgreSQL role only initializes the databases; applications do not use it. Changing `.env` database passwords after an existing volume was initialized does not rotate those database credentials; perform an explicit SQL rotation and coordinated app-secret update instead.

Health probes are `/actuator/health/liveness` and `/actuator/health/readiness`. Readiness includes PostgreSQL; Redis and Kafka failures can be absorbed by database fallback/outbox buffering and therefore do not immediately remove the API from service. This also means green readiness alone does not prove warehouse consumption or event publication is current. Check backlog and broker/consumer signals.

```sh
docker compose logs --tail 200 order-service inventory-service payment-service
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --describe
```

`docker compose stop` preserves data. `docker compose down --volumes` destroys the development databases, Kafka log and Redis data. Use that command only when intentionally resetting this disposable environment.

## Kafka and durable backlog

`kafka-init` explicitly creates `fulfillment.events.v1`, `warehouse.status.v1` and `warehouse.status.v1.DLT`, each with three partitions and seven-day retention. Local replication factor and minimum in-sync replicas are 1 because there is one broker. Automatic broker topic creation and application topic provisioning are disabled in Compose after the initialization job creates topics.

Production requires separately managed PostgreSQL, Kafka and Redis with backups, credentials/ACLs and certificate verification. Provision Kafka topics through the infrastructure owner: at least three brokers, replication factor 3, `min.insync.replicas=2`, and `acks=all`. Do not apply the single-broker development topic settings to production. Kafka partition keys are aggregate/order IDs, and consumers still enforce database idempotency and monotonic shipment sequence.

`GET /v1/operations/backlog` on order-service returns `outboxPending`, `oldestOutboxCreatedAt`, `pendingWorkflows`, `parkedWorkflows`, `consumerFailures`, and `deadLetteredMessages`. The first four describe current pending work; `consumerFailures` and `deadLetteredMessages` count durable raw-message failure history and remain after a successful replay. They are audit counts, not unresolved-only queue sizes. Monitor their rate/change and inspect outcomes rather than assuming every historic failure remains pending. Monitor the trend and age, not just a nonzero count. `/actuator/prometheus` includes JVM/HTTP/database metrics and application counters; the warehouse-consumer gauge is `fulfillment_warehouse_consumer_up` (zero is expected if the consumer is deliberately disabled). Alert on sustained consumer-down state, growing oldest-outbox age, parked workflows, dead letters, database pool saturation and HTTP error rate. Calibrate thresholds against the workload and response-time objective rather than using arbitrary values as an SLO.

The order database backlog is scoped to the order service. Inspect the inventory/payment outbox queues in their own databases as well:

```sql
SELECT count(*) AS pending, min(created_at) AS oldest
FROM outbox WHERE published_at IS NULL;
```

A relay sends before marking a row published. A crash after broker acknowledgment can therefore cause a duplicate; consumer idempotency handles it. Never delete a pending outbox row to silence an alert. Restore broker access, inspect `attempts`, `next_attempt_at`, and bounded `last_error`, then verify eventual publication. Avoid logging API keys, JDBC passwords or payment tokens while diagnosing.

## Workflow recovery and dead letters

A timeout is an unknown payment outcome, not a confirmed decline. Retries use the same order ID and durable payment fingerprint. Do not manually release inventory or create a second payment while an order is pending. Inspect workflow phase/state, dependency availability and stored error first. After fixing a dependency or configuration, an authorized operator may retry a parked workflow using `POST /v1/orders/{orderId}/retry`. Record the operator action and order ID outside application debug logs as appropriate.

Warehouse delivery retries persist their failure counts. After the bounded attempt limit, the consumer waits for the DLT send acknowledgment before acknowledging the source offset. A failed DLT publish leaves the source uncommitted and retryable. Inspect the original envelope and validation failure using an authorized operational tool. Fix the cause, then call `POST /v1/warehouse/events/{eventId}/replay` for an event accepted by the warehouse ingress. Replay retains the original event ID and payload and records an audit; it is not a way to edit previously accepted business facts. Duplicate/stale deliveries still cannot regress an order. External Kafka events without an ingress receipt require a reviewed repair/replay tool; do not fabricate a matching ingress record.

A DLT is an operational queue, not a guarantee that an event has been repaired. Keep access controlled, preserve the original evidence, and confirm the order state and consumer receipt after replay.

## Cache experiment

Redis caches `GET /v1/items/{sku}` catalog display metadata at `catalog:v1:{sku}` with a bounded TTL. Stock reads, reservation and checkout quoting use PostgreSQL. `INVENTORY_CACHE_ENABLED=false` disables the cache; `INVENTORY_CACHE_TTL_SECONDS` controls the TTL. The cache is an optimization, so an unavailable Redis falls back to the database.

Run the experiment only against the local Compose stack. It recreates inventory-service to switch cache modes and deletes only the benchmark SKU prefix from Redis; it never performs `FLUSHALL`.

```sh
python3 scripts/cache_benchmark.py --count 1000 --rate 50 --seconds 30 --repeats 3 --concurrency 32
```

The reports contain the same seeded dataset and deterministic request sequence for off/cold/warm cases, raw per-request timings/status/correctness/scheduling delay, mean/p50/p95/p99, error/drop counts, metrics and resource settings. A cold run starts with an empty benchmark prefix and warms naturally; a warm run prefills the dataset before timing. A cache improvement is not guaranteed: localhost overhead or a small working set may make the database baseline faster. Quote only measured results with their environment and workload, and report errors/drops alongside latency. For application latency comparisons, use successful request latency plus the failure/drop rate, not a deceptively lower all-attempt average caused by fast errors.

## Backups and restore

Use provider-managed encrypted backups and point-in-time recovery for production PostgreSQL. Kafka replication is not a substitute for an archival/retention policy, and Redis persistence is not authoritative inventory storage. Store backups outside the host and restrict access. Set a recovery-point and recovery-time objective before production use, then prove a restore to an isolated environment.

For a development logical backup, create an owner-only directory and run each database's `pg_dump` from the PostgreSQL container using that service role. Do not place dumps in a public artifact or Git repository. Example for orders:

```sh
umask 077
mkdir -p .local/backups
docker compose exec -T postgres sh -c 'PGPASSWORD="$ORDER_DB_PASSWORD" exec pg_dump -U order_app -d orders -Fc' > .local/backups/orders.dump
```

Repeat with inventory/payment and their roles. Restore into a newly created isolated database with the matching role and `pg_restore --exit-on-error`; do not restore over a live production database. Preserve idempotency records, payment intents, reservation states, outboxes and consumer receipts together with domain tables. Coordinate a multi-service recovery point and reconcile provider payment outcomes before resuming workers. Restore order receipts without matching Kafka offsets can cause re-delivery; the database idempotency boundary must remain intact. Validate counts, constraints and the smoke scenarios on the isolated restored stack before declaring the recovery usable.

## Kubernetes environments

`infra/kubernetes/base` contains ClusterIP services, restricted non-root pods, dedicated tokenless service accounts, startup/readiness/liveness probes, resource limits, rolling update strategy, HPA and PDB. Staging uses one replica; the production template uses two or more. HPA needs metrics-server. A PDB and multiple replicas on one host do not make the host highly available. Multi-node production scheduling, external data services, ingress/TLS and availability-zone coverage must be designed and operated separately.

A platform administrator creates the target namespace once, using `infra/kubernetes/overlays/{staging|prod}/namespace.yaml`, and supplies out-of-band secrets. The deploy workflow deliberately does not have cluster-wide namespace-write access. Configure these secret names in each namespace:

| Secret | Keys |
|---|---|
| `platform-shared` | `APP_API_KEY`, `KAFKA_BOOTSTRAP_SERVERS`; for managed Kafka: `KAFKA_SECURITY_PROTOCOL=SASL_SSL`, `KAFKA_SASL_MECHANISM`, `KAFKA_SASL_JAAS_CONFIG` |
| `inventory-database` | `DB_URL` (JDBC with verified TLS), `DB_USER`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` |
| `payment-database` | `DB_URL`, `DB_USER`, `DB_PASSWORD` |
| `order-database` | `DB_URL`, `DB_USER`, `DB_PASSWORD` |

The administrator can start from `infra/kubernetes/admin/deployer-role.yaml`, change its namespace for the environment and bind it to the deployment identity through the cluster identity provider. The role intentionally cannot read/write Secrets or modify namespaces; Kubernetes workload write access is nevertheless privileged within that namespace, so use dedicated namespaces and trusted reviewers. Use a secret manager/controller or owner-only files outside the repository. Kafka must verify TLS certificates; use a provider with a JVM-trusted certificate chain or explicitly mount/configure a reviewed private-CA truststore. Provider-specific IAM/OAuth authentication plugins are not included by default. Do not put actual secret values in rendered manifests or workflow logs. If GHCR images are private, provision a read-only image-pull credential on each service account, independently from the CI publishing credential. `REDIS_SSL_ENABLED=true` is the Kubernetes default. The network policy's data-service CIDR is a reference private range; narrow it to the actual routed service subnets and review DNS/ingress labels before use. The repo does not create a public ingress or expose the trusted API key to browsers.

The configured CI workflow runs Java unit/PostgreSQL integration tests, validates manifests, builds the real Compose stack and runs the Kafka system smoke. Those are the workflow steps; completion requires successful runs in an actual GitHub repository. Only successful main-branch CI publishes service images, SBOM/provenance, and exact digest artifacts. Deployment is manual, accepts a full commit SHA, checks successful main CI, and applies those recorded image digests. It never deploys a tag whose contents may have changed.

Create GitHub environments named `staging` and `prod`; configure required reviewer approval on `prod` and restrict deployment branches. Required-reviewer settings are repository configuration, not a guarantee provided by the YAML file alone. Use a dedicated trusted self-hosted runner with the `order-deploy` label and private cluster connectivity. Environment secrets: `KUBECONFIG_B64` (namespace-scoped deploy identity) and `APP_API_KEY`; environment variable: `EXPECTED_KUBE_CONTEXT`. The kubeconfig must select the exact expected context and explicitly set namespace `order-staging` or `order-prod`. The workflow checks these before taking a revision snapshot. Existing service rollouts must be healthy before the snapshot is accepted; repair an already broken baseline before starting a new release. Coordinate manual cluster changes with this single deployment controller.

The workflow waits for all three deployments and verifies read-only readiness, metrics and order listing through temporary port forwards. If apply, rollout or smoke fails, it attempts to restore **every** captured deployment revision; a newly created deployment is removed. A failed rollback still fails the workflow and needs operator attention. ConfigMaps, Services, policies and HPA are not automatically rolled back; review those changes separately. Database migrations are not rolled back. Use backward-compatible expand/contract migrations that support the preceding app revision, and review schema changes before approving production.

## Optional EC2 learning/staging host

`infra/aws` is a concrete Terraform template for a VPC, subnet, internet gateway, encrypted Ubuntu 24.04 ARM64 EC2 host and SSM instance profile. It has no inbound security-group rules, no SSH key and no public application or Kubernetes listener rule. The public IPv4 address enables outbound HTTPS without a NAT gateway; EC2, EBS and public IPv4 are billable. No resources were created by generating these files.

Review your AWS account/region, expected cost, IAM permissions, current AMI and Terraform plan before applying. Keep Terraform state encrypted and access controlled; it can contain infrastructure-sensitive values. `data_service_cidrs=[]` denies outbound database/broker/cache access until explicitly configured, and routing/peering to external data services must be supplied separately. A single host with local k3s SQLite state is a learning/staging setup, not an HA production cluster.

```sh
terraform -chdir=infra/aws init
terraform -chdir=infra/aws fmt -check
terraform -chdir=infra/aws validate
terraform -chdir=infra/aws plan -out=reviewed.tfplan
```

Only apply a reviewed plan when you intend to create paid resources. After provisioning, use the output SSM session command. Copy the reviewed `scripts/bootstrap-k3s.sh` to the host through an authenticated channel and run it manually as root. It pins `v1.34.10+k3s1`, downloads the ARM64 binary and published checksums over HTTPS, verifies the selected checksum and installs a systemd unit. It does not execute a remotely downloaded installer. Upgrade this version through a reviewed compatibility test rather than changing the script blindly.

Use the Terraform output `kubernetes_port_forward_command` to forward local 16443 to host 6443 through SSM. Retrieve `/etc/rancher/k3s/k3s.yaml` through your authenticated administrative session into an owner-only local file, keep its embedded client key private, and change only the server address to `https://127.0.0.1:16443`. Preserve the CA and certificate verification; do not use insecure TLS skip flags. Give CI a separately scoped deployment identity rather than this cluster-admin kubeconfig. The runner must reach this private cluster through an approved channel. Do not open SSH/6443/app ports to the internet to make the workflow convenient.

The bootstrap does not install data services or mark the app deployed. Configure the managed database/Kafka/Redis endpoints and secrets, namespace, registry access and network paths before applying staging manifests. Run the smoke/failure suite against a disposable staging dataset, record real results, and rehearse restore/rollback before describing an EC2 deployment as completed. Destroy only resources from this reviewed state when finished; take needed backups first.
