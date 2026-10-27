# Cache experiment summary

Only the two experiments with COMPLETE status are merged. The dataset, expected responses, request sequence, CPU/memory, TTL, duration, warmup, and repetition count have been checked. All predeclared measurement runs are retained.

## Measurements by run

| Variant | Mode | Run | RPS | Successful | Failed | Dropped | Mean ms | p95 ms | p99 ms |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| baseline | off | 1 | 500.000 | 30000 | 0 | 0 | 0.881 | 1.236 | 2.402 |
| baseline | warm | 1 | 500.000 | 30000 | 0 | 0 | 0.865 | 1.208 | 1.953 |
| baseline | warm | 2 | 500.000 | 30000 | 0 | 0 | 0.871 | 1.203 | 2.127 |
| baseline | off | 2 | 500.000 | 30000 | 0 | 0 | 0.824 | 1.254 | 2.194 |
| baseline | off | 3 | 500.000 | 30000 | 0 | 0 | 0.781 | 1.144 | 1.705 |
| baseline | warm | 3 | 500.000 | 30000 | 0 | 0 | 0.899 | 1.262 | 2.608 |
| optimized | warm | 1 | 500.000 | 30000 | 0 | 0 | 0.906 | 1.336 | 2.317 |
| optimized | warm | 2 | 500.000 | 30000 | 0 | 0 | 0.903 | 1.286 | 2.300 |
| optimized | warm | 3 | 500.000 | 30000 | 0 | 0 | 0.885 | 1.270 | 2.130 |

Means and percentiles include only successful responses with correct content. Failures and drops are also listed; successful-response latency must not conceal an unhealthy workload.

## Database and cache counts

| Variant | Mode | Run | Database SELECT calls | cache.get hits | cache.get misses | HTTP source: Redis | HTTP source: database | HTTP source: coalesced wait |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| baseline | off | 1 | 30000.000 | 0.000 | 0.000 | 0 | 30000.000 | 0 |
| baseline | warm | 1 | 1250.000 | 28750.000 | 1250.000 | 28750.000 | 1250.000 | 0 |
| baseline | warm | 2 | 1271.000 | 28729.000 | 1271.000 | 28729.000 | 1271.000 | 0 |
| baseline | off | 2 | 30000.000 | 0.000 | 0.000 | 0 | 30000.000 | 0 |
| baseline | off | 3 | 30000.000 | 0.000 | 0.000 | 0 | 30000.000 | 0 |
| baseline | warm | 3 | 1238.000 | 28762.000 | 1238.000 | 28762.000 | 1238.000 | 0 |
| optimized | warm | 1 | 1268.000 | 28732.000 | 2536.000 | 28732.000 | 1268.000 | 0.000 |
| optimized | warm | 2 | 1261.000 | 28739.000 | 2522.000 | 28739.000 | 1261.000 | 0.000 |
| optimized | warm | 3 | 1267.000 | 28733.000 | 2534.000 | 28733.000 | 1267.000 | 0.000 |

Hit/miss counts refer to cache-read calls; the optimized version may recheck a request, so this ratio cannot be treated directly as the HTTP hit rate.

## Aggregates and two comparisons

| Variant | Mode | RPS | Weighted successful-response mean ms | Per-run p95 range ms | Median of run p95 values ms | Measurement failures/drops |
| --- | --- | ---: | ---: | --- | ---: | --- |
| baseline | off | 500.000 | 0.829 | 1.144–1.254 | 1.236 | 0/0 |
| baseline | warm | 500.000 | 0.878 | 1.203–1.262 | 1.208 | 0/0 |
| optimized | warm | 500.000 | 0.898 | 1.270–1.336 | 1.286 | 0/0 |

At 500.000 RPS:
- Same baseline image, cache disabled → enabled: relative reduction in weighted successful-response mean -5.952%; relative reduction in database SELECT calls 95.823%; both measured groups have no failures/drops: True.
- Cache-enabled baseline → optimized cache: relative reduction in weighted successful-response mean -2.232%; relative reduction in database SELECT calls -0.984%; both measured groups have no failures/drops: True.

Positive values mean a reduction; negative values mean an increase. These are observed changes and do not establish statistical significance. Only the second comparison measures the added optimization; the full benefit of caching itself must not be attributed to that optimization.

Run p95 values are not averaged and presented as an overall p95; the table shows the per-run range and the median of run p95 values.

## Warmup results retained separately

| Variant | Mode | Run | Successful | Failed | Dropped |
| --- | --- | ---: | ---: | ---: | ---: |
| baseline | off | 1 | 7082 | 0 | 418 |
| baseline | warm | 1 | 6330 | 0 | 1170 |
| baseline | warm | 2 | 6590 | 0 | 910 |
| baseline | off | 2 | 6988 | 0 | 512 |
| baseline | off | 3 | 6961 | 0 | 539 |
| baseline | warm | 3 | 6053 | 0 | 1447 |
| optimized | warm | 1 | 6622 | 0 | 878 |
| optimized | warm | 2 | 6304 | 0 | 1196 |
| optimized | warm | 3 | 6645 | 0 | 855 |

Warmup totals: 0 failed, 7925 dropped. These belong to the separately declared warmup phase and are excluded from measured latency, but are fully retained; they do not support a claim that cold starts are also healthy.

The local client and services share machine resources; these results do not establish production capacity. See the JSON summary in the same directory for raw file paths, per-run metrics, image IDs, controlled variables, and all warmup reports.
