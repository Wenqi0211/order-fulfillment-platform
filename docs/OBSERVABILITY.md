# Local observability

The optional Compose overlay adds Prometheus at <http://127.0.0.1:9090> and Grafana at <http://127.0.0.1:3000>. Grafana provisions the **Order Fulfillment — Local Operations** dashboard and its Prometheus data source automatically. The dashboard covers HTTP request rate, p95/p99 and mean latency, 5xx, Saga progress/retries/manual review, cache fallback, JVM/GC, Hikari pools, and the event outbox.

This is a local operating setup. Starting it does not deploy to AWS or Kubernetes, establish production capacity, configure autoscaling, or demonstrate availability across machines. Redis panels show application-observed cache operations; no Redis server, PostgreSQL server, Kafka broker, or container host exporter is included.

## Start

From the repository root, with Docker Engine/Desktop running. The project Docker wrapper uses an isolated anonymous client configuration, avoiding macOS Keychain helper errors without changing your global Docker settings:

```sh
python3 scripts/init_env.py
python3 scripts/init_observability.py
./scripts/docker-local.sh compose -f compose.yaml -f compose.observability.yaml config --quiet
./scripts/docker-local.sh compose -f compose.yaml -f compose.observability.yaml up -d --build
```

Sign in to Grafana as `admin`, using the generated password in `.local/observability/grafana-admin-password`. Open **Dashboards → Order Fulfillment → Order Fulfillment — Local Operations**, or <http://127.0.0.1:3000/d/fulfillment-local>. The private password file is the source for the initial administrator password; Grafana stores its initialized account in its data directory, so replacing the password file alone does not reset an existing account.

`init_observability.py` copies the current `APP_API_KEY` into the private scrape-key file and preserves an existing Grafana password. The runtime files are ignored by Git. Both monitoring containers run as the generated host UID/GID, so secret files can remain owner-readable rather than becoming world-readable. Re-run initialization after an API-key change, then recreate the monitoring containers. Moving the checkout to a different account requires reviewing the stored UID/GID and local state ownership before initialization. All monitoring host ports bind to loopback. Application endpoints continue to require `X-API-Key`, including `/actuator/prometheus`; Prometheus reads that header from a read-only runtime file. Tracked Prometheus configuration contains only the file path.

The monitoring overlay also works with the [local scaling overlay](LOCAL_SCALING.md):

```sh
./scripts/docker-local.sh compose -f compose.yaml -f compose.scaling.yaml -f compose.observability.yaml \
  up -d --build --scale order-service=3
```

Each application job uses Docker DNS discovery every ten seconds. Every returned A record becomes a separate `instance` target, including every order-service replica. No Docker socket is mounted. Scaling down intentionally removes a replica from discovery; an entirely missing service has its own alert, but this setup does not enforce an expected replica count.

## Validate data before interpreting it

Start with <http://127.0.0.1:9090/targets>. All three application jobs should be **UP**; scaled order-service should have one target per replica. A `401` indicates the scrape-key file and application key disagree. A DNS error points to the service name or `backend` network. Application startup may take longer than monitoring startup.

Check the local services and query current target counts:

```sh
curl --fail --silent http://127.0.0.1:9090/-/ready
curl --fail --silent http://127.0.0.1:3000/api/health
curl --fail --silent --get http://127.0.0.1:9090/api/v1/query \
  --data-urlencode 'query=count by(service)(up{service!=""} == 1)'
```

Generate normal catalog and order traffic with the repository's smoke or load scripts, then allow at least two scrapes for rates and several minutes for a useful latency distribution. The dashboard uses a 30-minute window by default. Use the service and instance selectors to inspect individual JVMs; shared database backlog panels deliberately ignore the instance selector.

The [observability smoke script](../scripts/observability_smoke.py) checks health, the provisioned dashboard, all three service jobs, replica discovery, and real API status counters. For the single-replica startup above:

```sh
python3 scripts/observability_smoke.py --expected-order-replicas 1 \
  --compose-file compose.yaml --compose-file compose.observability.yaml
```

Set `--expected-order-replicas` to the number actually started. Its optional fault exercise temporarily stops/restarts this project's payment service and Redis, checks Saga retries/recovery and cache fallback, and writes sanitized local evidence. Run it without another load or fault exercise:

```sh
python3 scripts/observability_smoke.py --expected-order-replicas 1 --faults \
  --compose-file compose.yaml --compose-file compose.observability.yaml
```

Useful queries in Prometheus:

| Question | PromQL |
| --- | --- |
| Reachable app replicas | `up{service!=""}` |
| Pending orders, deduplicated across replicas | `sum(fulfillment:saga_backlog{status="PENDING"})` |
| Orders needing manual review | `sum(fulfillment:saga_backlog{status="MANUAL_REVIEW"})` |
| Failed Saga attempts | `sum by(step,outcome)(rate(fulfillment_saga_attempts_total{outcome!="advanced"}[5m]))` |
| Snapshot refresh health | `fulfillment_saga_snapshot_refresh_success` |
| Redis cache errors | `sum(rate(inventory_cache_errors_total[5m]))` |
| Catalog request sources | `sum by(source)(rate(inventory_catalog_lookup_seconds_count[5m]))` |

**No data is not zero.** Some counters and timers are created only after their first event. An idle service can have no matching 5xx/Saga outcome series and no meaningful latency quantile. Check target health, exercise the relevant route, and inspect the raw metric name before interpreting an empty panel. HTTP panels exclude Actuator routes. Histograms are enabled in the shared application configuration; p95/p99 use timer buckets and are estimates.

Saga backlog and retry-due counts come from cached database snapshots refreshed by each order-service process. Before the first successful refresh, database gauges are unknown. After a failed refresh the last snapshot remains available, with a failed-refresh flag and increasing snapshot age. The dashboard and alerts use recording rules that include only successful snapshots no older than 60 seconds. If every snapshot is unhealthy, shared backlog panels show no data and the separate snapshot-health alert fires. Raw gauges retain the last values for diagnosis. Replicas observe the same database, so the recording rules take the maximum per status/step across eligible replicas, then the dashboard and alerts sum steps. This prevents multiplying counts by the number of replicas, but can briefly overestimate during differing snapshot refresh times.

Saga `advanced`, `deferred`, and `parked` outcomes are counted after transaction commit. `rolled_back` and `unknown` describe unsuccessful/uncertain transaction completion separately; they are not committed transitions. Processing duration covers a Saga phase and its dependency call, not the end-to-end customer order duration.

Cache hits, misses, errors, and bypasses count cache checks. A singleflight leader can check twice, so a bypass ratio against HTTP requests is misleading. Use the catalog lookup `source` timer for request-source proportions. Disabled cache, admission pressure, and Redis cooldown can all produce bypasses. Purchase quotes and stock reservations continue to use the database as authority.

## Alerts and rule validation

Prometheus evaluates the provisioned rules every 15 seconds. Open <http://127.0.0.1:9090/alerts> for pending/firing state. No Alertmanager or notification destination is configured; these alerts are visible locally only.

| Alert | Local threshold and hold time |
| --- | --- |
| ApplicationTargetDown | Existing target scrape fails for 2 minutes |
| ApplicationTargetsMissing | An application's entire discovered target set is absent for 2 minutes |
| HttpServerErrorsHigh | More than 5% 5xx over 5 minutes, at least 0.1 application request/s, for 2 minutes |
| SagaManualReviewRequired | At least one manual-review order for 1 minute |
| SagaBacklogHigh | More than 100 pending orders for 5 minutes |
| SagaPendingTooOld | Oldest pending order above 120 seconds for 2 minutes |
| SagaSnapshotUnhealthy | Last refresh failed or snapshot age exceeds 60 seconds for 1 minute |
| CatalogCacheBypassing | More than 0.1 bypass checks/s over 5 minutes, for 2 minutes |
| CatalogCacheErrors | At least one cache error in the last 5 minutes, for 2 minutes |
| OutboxDeliveryStalled | Oldest unpublished event above 120 seconds for 2 minutes |

These thresholds are starting points for local exercises. Validate expected workload and recovery timing before using them as production policy. A green scrape alone does not establish database, broker, or workflow health.

Validate the Prometheus configuration and run deterministic alert-rule tests with the pinned image:

```sh
./scripts/docker-local.sh compose -f compose.yaml -f compose.observability.yaml run --rm --no-deps \
  --entrypoint /bin/promtool prometheus check config /etc/prometheus/prometheus.yml
./scripts/docker-local.sh run --rm --network none --read-only --tmpfs /tmp \
  --mount "type=bind,src=$PWD/infra/observability/prometheus,dst=/etc/prometheus,readonly" \
  --workdir /etc/prometheus --entrypoint /bin/promtool \
  prom/prometheus:v3.15.1 test rules alerts.test.yml
```

The rule tests cover failed scrapes, DNS target disappearance, replica-safe backlog aggregation, exclusion of failed snapshots, application 5xx independent of Actuator traffic, idle HTTP traffic, stale snapshots, and sustained cache bypass. Passing them verifies rule behavior against synthetic series; it does not verify live metric collection. `./scripts/docker-local.sh compose ... config --quiet` validates the Compose model without printing interpolated application credentials.

## Storage and operation

Prometheus retains 24 hours or approximately 512 MB of compacted blocks, whichever is reached first. Its write-ahead log and active head also use disk; the retention setting is not a hard filesystem quota. Data lives in `.local/observability/prometheus-data`. Grafana stores accounts/settings in `.local/observability/grafana-data`. Both containers have 512 MB memory, 0.5 CPU, 128-process limits, capped container logs, a read-only root filesystem, and a 64 MB temporary filesystem. Watch host free disk during longer load runs.

Stop only monitoring while leaving the applications running:

```sh
./scripts/docker-local.sh compose -f compose.yaml -f compose.observability.yaml stop prometheus grafana
```

After changing tracked Prometheus rules/configuration, restart Prometheus. Grafana reloads dashboard files every 30 seconds; provisioning data-source changes require a Grafana restart. The dashboard is provisioned read-only so the repository remains its source of truth.

## Pinned upstream references

The overlay pins official `prom/prometheus:v3.15.1` and `grafana/grafana:13.2.4` images. Versions are explicit for reproducibility; review newer patches deliberately.

- [Prometheus 3.15.1 release](https://github.com/prometheus/prometheus/releases/tag/v3.15.1)
- [Prometheus configuration, DNS discovery, and file-backed HTTP headers](https://prometheus.io/docs/prometheus/3.15/configuration/configuration/)
- [Prometheus alert rule unit tests](https://prometheus.io/docs/prometheus/3.15/configuration/unit_testing_rules/)
- [Grafana 13.2.4 release download](https://grafana.com/grafana/download/13.2.4?edition=oss)
- [Official Grafana Docker images](https://grafana.com/docs/grafana/latest/setup-grafana/installation/docker/)
- [Grafana configuration through secret files](https://grafana.com/docs/grafana/latest/setup-grafana/configure-docker/)
