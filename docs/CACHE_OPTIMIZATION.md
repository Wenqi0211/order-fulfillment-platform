# Cache Bottleneck Diagnosis and Optimization Record · 2026-10-24

The repository includes a compact evidence set in [docs/evidence](evidence/README.md), preserving the original reports' relative hierarchy under `reports/`. Measurements, timestamps, image IDs, and historical path metadata in the copied files have not been changed. Complete raw JSONL, logs, Maven XML, metric snapshots, and baseline source code remain in the original computer's local `reports/` directory and are not uploaded with the Git repository. Original absolute paths in the JSON identify historical run locations and cannot be accessed directly in a new clone.

This investigation used real local Docker services, PostgreSQL, and Redis, generating HTTP load and saving per-request records. Products and requests are synthetic test data. The numbers are actual measurements; they do not represent real users, production capacity, or live-service SLOs.

Confirmed benefits are concentrated in two areas: **reducing duplicate database queries for concurrent cold-cache requests, and avoiding two repeated timeout waits per request when Redis is unavailable.** The nine completed regular-load test rounds did not show faster normal queries. Both benefits and costs are retained below.

## Experiment identity and environment

The inventory service ran as a single instance in local Docker on macOS arm64, limited to **1 CPU and 768 MiB**. Cache TTL was 30 seconds. The following two records form the formal diagnostic comparison; earlier optimization trial runs are not treated as the final results in this section.

| Version | Immutable container image ID | Evidence |
| --- | --- | --- |
| baseline | `sha256:717a9f3ed767e0a9e13c213646de22f780742fc93b026378b70efd72f1a9a73e` | [Diagnostic summary](evidence/cache-diagnostics/20261024T234859Z-baseline/summary.json) |
| optimized-final | `sha256:f1a0b65d6a425aff52b794090434e835b1f24e4c96e37d9de4de068b42f7d818` | [Diagnostic summary](evidence/cache-diagnostics/20261024T235426Z-optimized-final/summary.json) |

Each experiment directory in the original computer's `reports/` contains the dataset, raw JSON/JSONL, before/after metric snapshots, and container resource settings. The repository's `docs/evidence/` contains only the listed compact copies. Product content was validated for every successful timed request; failed and unsent requests were counted separately. Percentiles use the nearest-rank method, and latency statistics include only HTTP 200 requests that returned the correct content. The baseline represents the old behavior with the same basic timing metrics added. The corresponding [baseline source hashes](evidence/cache-baseline-source/hashes.json) are included; the complete source snapshot remains in the original computer's `reports/cache-baseline-source/`. The `sourceAtStart/sourceAtEnd` experiment metadata describes the current working directory when the scripts ran. The old application is identified by that baseline snapshot and immutable image ID; current source hashes cannot substitute for the old source.

## Diagnose before choosing changes

Product-query instrumentation was added for Redis GET, Redis SET, cached JSON decoding, actual database reads, and the entire product-query path, along with counts for database reads, cache bypasses, and coalesced requests. No artificial delay was added to SQL or the network.

The healthy-service diagnostic used 100 SKUs, a fixed 500 requests/second, 15 seconds, and a concurrency limit of 64, preceded by a 15-second warmup. All 7,500 baseline requests succeeded, with 0 failures and 0 dropped requests. Metric differences showed:

| Segment | Calls | Mean duration per call |
| --- | ---: | ---: |
| Redis GET | 7,500 | Approximately 0.117 ms |
| Actual product database SELECT | 74 | Approximately 0.108 ms |
| Cached JSON decoding | 7,426 | Approximately 3.6 µs |
| Redis SET | 74 | Approximately 0.136 ms |

These are averages for different segments in that run, not performance ceilings for standalone services. They show that, with this small dataset and local database, the product SELECT itself was fast, Redis round trips showed no clear speed advantage, and JSON decoding was not a major cost. A cache miss originally required GET → SELECT → SET in sequence.

HTTP results from the short healthy-service diagnostic also did not support faster normal queries:

| Version | Mean response ms | p95 ms | p99 ms | Failed/dropped |
| --- | ---: | ---: | ---: | ---: |
| baseline | 0.831 | 1.200 | 2.588 | 0 / 0 |
| optimized-final | 0.868 | 1.423 | 3.241 | 0 / 0 |

This was only one 15-second diagnostic per version. Regular-load performance should be assessed alongside the longer, repeated experiments with alternating order described later. The 30-second TTL also means “warm” describes the starting condition; expiration and refill can still occur during a round, so it does not mean a 100% hit rate.

## Optimization 1: Coalesce concurrent misses for the same SKU

In the original code, after a product's cache entry expired, multiple requests could discover the miss simultaneously, each querying PostgreSQL and refilling Redis. The new implementation coalesces only **product loads currently in progress**:

- Within the same process and for the same SKU, one request performs the load; other requests wait for the same result or exception.
- The default limit is 256 in-flight keys. At capacity, requests fall back to the normal database path without expanding the in-flight table.
- Following requests wait at most 4 seconds, then return a retryable 503. Interruption preserves the thread's interrupt status without canceling the request responsible for loading.
- On completion or failure, the in-flight entry is removed and its slot released. The result is not retained as a long-term local cache.
- After becoming the loader, the request checks Redis again to handle the race in which another load has just completed its refill.

This protection is limited to the current service process. Multiple replicas may still perform one load each; it cannot be described as a single global database query across instances.

Three actual diagnostic rounds used 64 concurrent requests for the same SKU, starting with a cold cache. All 64 requests in each round returned the correct result:

| Round | baseline database reads | optimized-final database reads | baseline p95 ms | optimized-final p95 ms |
| --- | ---: | ---: | ---: | ---: |
| 1 | 6 | 1 | 9.936 | 10.797 |
| 2 | 2 | 1 | 10.730 | 10.837 |
| 3 | 5 | 1 | 10.365 | 11.907 |

The evidence establishes that total database reads across the three rounds fell from 13 to 3. **The p95 of these bursts did not improve.** Fewer reads cannot be reported as an equivalent response-time improvement. Coalescing and the second check have a cost; an isolated miss also incurs an extra Redis GET.

Raw per-request results are in `burst-1-raw.json`, `burst-2-raw.json`, and `burst-3-raw.json` in the two diagnostic directories. Read counts come from the corresponding metric-snapshot differences.

## Optimization 2: Redis failure bypass and controlled recovery

In the baseline failure diagnostic, after Redis stopped, all 750 requests still attempted one GET and one SET each. Each operation waited approximately 251 ms on average. Requests still waited for an unavailable Redis after falling back to PostgreSQL, producing a product-query path of approximately 504 ms.

The new implementation briefly enters bypass mode after a Redis operation exception, with a default 1-second cooldown:

- During cooldown, requests query the database directly and skip Redis refill, avoiding another write-timeout wait.
- After cooldown, only one recovery probe may access Redis; other requests continue falling back to the database.
- A successful probe restores cache access; a failed probe restarts cooldown.
- Previously issued normal requests cannot clear a cooldown started by other requests, even if they later succeed. Recovery is confirmed by the authorized probe.

The failure diagnostic actually stopped and restored this project's Redis, keeping the same **50 requests/second, 15 seconds, and 750 requests**:

| Metric | baseline | optimized-final |
| --- | ---: | ---: |
| Successful requests | 750 | 750 |
| Failed / dropped | 0 / 0 | 0 / 0 |
| Mean HTTP response ms | 505.607 | 10.642 |
| p95 ms | 510.643 | 6.581 |
| p99 ms | 513.519 | 254.960 |
| Actual Redis GET attempts | 750 | 24 |
| Actual Redis SET attempts | 750 | 0 |
| Actual database reads | 750 | 750 |
| Actual cache hits observed after Redis recovery | Yes | Yes |

The [baseline failure statistics](evidence/cache-diagnostics/20261024T234859Z-baseline/redis-outage/20261024T234948674993Z-warm-1.json) and [final optimized failure statistics](evidence/cache-diagnostics/20261024T235426Z-optimized-final/redis-outage/20261024T235515087032Z-warm-1.json) contain the full statistics. JSONL in the corresponding directories of the original computer's `reports/` retains every request.

The improvement here is **degraded-mode response when Redis is unavailable**. p99 remains approximately 255 ms because the first failing requests and recovery probes may still wait for the configured timeout. This is not a normal cache-hit speed improvement or a guarantee of production high availability or availability rates.

## Correctness and metric interpretation

- The cache serves only product display. Purchase quotes, stock queries, reservation, commit, and release continue to use PostgreSQL as the authority.
- Disabling the cache also disables request coalescing and any hidden L1 result cache; the database baseline still performs actual database reads.
- JSON content is still validated for SKU, price, currency, and version; invalid cache entries continue to fall back to the database.
- Request-coalescing tests cover concurrent waiting, failure propagation, retry after cleanup, capacity limits, parallel requests for different keys, thread interruption, and bypass when caching is disabled. Recovery protection includes a test ensuring that an old request cannot close a newer cooldown state.
- The current clean build passed **41 Java unit tests and 44 PostgreSQL integration tests**, with 0 failures, errors, or skips. **23 Python tooling tests** passed. After load testing, **6 system flow checks and 9 HTTP boundary checks** passed again, and the optimized inventory service was restored to healthy status.
- `inventory.cache.hits / (hits + misses)` measures the hit ratio of cache checks, including the leader's second check. For HTTP request source proportions, use the `redis`, `database`, and `coalesced` counts in `inventory.catalog.lookup`. Cooldown bypass may also be checked twice per leader, so bypass counts cannot be treated directly as HTTP request counts.

## Completed regular-load comparison

Method: the same 1,000-SKU dataset, fixed 500 requests/second, concurrency limit 64, random seed 2027, and a 60-second measurement per round, preceded by a separate 15-second warmup. The baseline's cache-off and warm modes each ran three times with alternating mode order; optimized-final warm mode ran three times. Each round restarted inventory, warmed up the JVM, and reset this experiment's product cache; warm groups refilled the cache before formal timing. TTL remained 30 seconds, with normal expiration allowed during measurement.

The nine rounds contained **270,000 formal measurement requests, all with correct content, 0 failures, and 0 dropped requests**. All rounds are listed below, without selecting only the fastest:

| Version and mode | Round | Successful requests | Mean ms | p95 ms | p99 ms | Database SELECT calls |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| baseline, cache off | 1 | 30,000 | 0.881 | 1.236 | 2.402 | 30,000 |
| baseline, cache on | 1 | 30,000 | 0.865 | 1.208 | 1.953 | 1,250 |
| baseline, cache on | 2 | 30,000 | 0.871 | 1.203 | 2.127 | 1,271 |
| baseline, cache off | 2 | 30,000 | 0.824 | 1.254 | 2.194 | 30,000 |
| baseline, cache off | 3 | 30,000 | 0.781 | 1.144 | 1.705 | 30,000 |
| baseline, cache on | 3 | 30,000 | 0.899 | 1.262 | 2.608 | 1,238 |
| optimized-final, cache on | 1 | 30,000 | 0.906 | 1.336 | 2.317 | 1,268 |
| optimized-final, cache on | 2 | 30,000 | 0.903 | 1.286 | 2.300 | 1,261 |
| optimized-final, cache on | 3 | 30,000 | 0.885 | 1.270 | 2.130 | 1,267 |

| Group | Weighted mean of successful responses ms | Per-round p95 range ms | Total SELECT calls across three rounds |
| --- | ---: | ---: | ---: |
| baseline, cache off | 0.829 | 1.144–1.254 | 90,000 |
| baseline, cache on | 0.878 | 1.203–1.262 | 3,759 |
| optimized-final, cache on | 0.898 | 1.270–1.336 | 3,796 |

Two conclusions must be distinguished:

- **Effect of the existing cache**: With the same baseline image, turning the cache on reduced SELECT calls by approximately **95.8%**, while mean HTTP latency increased by approximately **6.0%**. Fewer query calls do not imply that database CPU, disk I/O, or costs also fell by 95.8%.
- **Effect of this new optimization**: Compared with the existing cache, the optimized cache showed an approximately **2.2%** increase in mean latency and no reduction in SQL calls. For ordinary uniformly distributed requests against this already-fast local database, there is no evidence of faster normal responses. The clear benefits of the new protection come from the same-key cold-concurrency and Redis failure experiments above. The existing cache's 95.8% reduction cannot be attributed to this new optimization.

These are observed local differences, not evidence of statistical significance. The baseline alternated cache on/off order internally; the three optimized rounds ran afterward, so cross-version comparisons may still be affected by time and fluctuations in shared host resources. The client and services share one machine; these results do not represent production capacity. Per-round p95 values were not averaged and presented as an overall p95.

The nine separately designated JVM warmups planned **67,500 requests**, with **59,575 successes**, **0 failures**, and **7,925 dropped requests**. Dropped requests here were not sent by the load client because of scheduling/concurrency limits; they were not 7,925 HTTP errors returned by the service. They still show that the same target load could not be sustained during cold startup. Warmup was excluded from formal latency measurements, but all per-round results are retained; a lossless cold start cannot be claimed.

Compact evidence is available in the [all-round and warmup summary](evidence/cache-study-final/summary.md), [machine-readable summary](evidence/cache-study-final/summary.json), [baseline execution plan](evidence/cache-ab/20261024T235551478707Z-baseline-v2/experiment.json), and [optimized execution plan](evidence/cache-ab/20261025T000457900776Z-optimized-v2/experiment.json). The summary script checks that experiments are COMPLETE, planned rounds are complete, request sequences and datasets match, CPU/memory/TTL/duration match, and timing windows do not overlap. Raw JSONL, scheduling delays, metric differences, and image identities are retained.

Reproduction scripts are `scripts/cache_diagnostics.py`, `scripts/cache_benchmark.py`, and `scripts/load_test.py`; the summary script is `scripts/summarize_cache_study.py`. Diagnostics temporarily stop and restore this project's Redis; regular comparisons restart inventory, so run them in a local environment where interruption is acceptable. Repeated execution creates new timestamped directories and does not guarantee identical values.

## Engineering results that can currently be described accurately

Supported description: used segmented timing to identify duplicate database fallback and double timeouts during failures; implemented bounded in-flight request coalescing for the same SKU and Redis cooldown bypass/single-probe recovery; validated behavior with real dependencies, concurrency, and failure experiments, retaining raw data.

Degraded-mode latency figures must include the scope: “local Redis unavailable, 50 requests/second, 15 seconds.” Consistent regular-load speedup percentages, production-scale throughput, and actual live-service availability still require corresponding evidence. This work did not perform cloud deployment or create AWS resources for measurement.
