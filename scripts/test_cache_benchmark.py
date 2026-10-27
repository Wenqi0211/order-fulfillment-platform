"""Offline checks for benchmark evidence integrity; no services or artificial latencies."""
import hashlib
import json
import unittest
from cache_benchmark import make_schedule, metric_delta, parse_prometheus, parse_service_env
from load_test import generate_sequence, percentile, select_working_set, summarize, validate_dataset


class BenchmarkEvidenceTest(unittest.TestCase):
    def test_reproducible_workloads_and_hot_key_skew(self):
        skus = [f'P_{i:06}' for i in range(100)]
        first = generate_sequence(skus, 10000, 2027, 'zipf', 1.2)
        self.assertEqual(first, generate_sequence(skus, 10000, 2027, 'zipf', 1.2))
        self.assertNotEqual(first, generate_sequence(skus, 10000, 2028, 'zipf', 1.2))
        self.assertGreater(sum(item in skus[:10] for item in first), 5000)
        uniform = generate_sequence(skus, 10000, 2027)
        self.assertLess(sum(item in skus[:10] for item in uniform), 1500)

    def test_hotset_is_explicit_and_bounded(self):
        self.assertEqual(['a'], select_working_set(['a', 'b'], 1))
        self.assertEqual(['a', 'b'], select_working_set(['a', 'b'], 0))
        with self.assertRaises(ValueError): select_working_set(['a'], 2)

    def test_invalid_or_duplicate_dataset_cannot_be_used(self):
        skus = ['a', 'a']
        digest = hashlib.sha256(json.dumps(skus,separators=(',', ':')).encode()).hexdigest()
        with self.assertRaises(ValueError): validate_dataset({'skus': skus, 'sha256': digest})
        with self.assertRaises(ValueError): validate_dataset({'skus': ['a'], 'sha256': 'tampered'})

    def test_failed_and_dropped_requests_never_become_fast_successes(self):
        rows = [
            {'startedAtSeconds': 0, 'status': 200, 'correct': True, 'elapsedMs': 10, 'schedulerDelayMs': 2},
            {'startedAtSeconds': 1, 'status': 200, 'correct': False, 'elapsedMs': 1},
            {'startedAtSeconds': 2, 'status': 503, 'correct': False, 'elapsedMs': 1},
            {'startedAtSeconds': None, 'error': 'inflight_limit_drop'}]
        result = summarize(rows, 4, 4)
        self.assertEqual(1, result['successfulRequests'])
        self.assertEqual(2, result['failedRequests'])
        self.assertEqual(1, result['droppedRequests'])
        self.assertEqual(10, result['meanMs'])
        self.assertEqual(12, result['meanScheduledLatencyMs'])
        self.assertEqual(0.25, result['successfulRequestsPerSecond'])

    def test_nearest_rank_percentile_is_defined_for_small_samples(self):
        self.assertEqual(4, percentile([4, 1, 3, 2], 95))
        self.assertEqual(2, percentile([4, 1, 3, 2], 50))
        self.assertIsNone(percentile([], 95))

    def test_alternating_schedule_reverses_order_without_changing_workloads(self):
        schedule = make_schedule(['off', 'warm'], [100, 500], 3, 'alternating', 2027)
        self.assertEqual(['off', 'warm', 'warm', 'off', 'off', 'warm'], [x['mode'] for x in schedule[:6]])
        self.assertEqual(6, sum(x['rate'] == 100 for x in schedule))
        self.assertEqual(6, sum(x['rate'] == 500 for x in schedule))
        self.assertEqual(make_schedule(['off','cold','warm'],[100],3,'random',42),make_schedule(['off','cold','warm'],[100],3,'random',42))

    def test_timer_deltas_preserve_label_and_compute_actual_interval_mean(self):
        before = 'inventory_cache_redis_seconds_count{operation="get"} 10\ninventory_cache_redis_seconds_sum{operation="get"} 0.10\ninventory_cache_hits_total 8\n'
        after = 'inventory_cache_redis_seconds_count{operation="get"} 14\ninventory_cache_redis_seconds_sum{operation="get"} 0.12\ninventory_cache_hits_total 11\n'
        value = metric_delta(before, after)
        self.assertEqual(3, value['counterAndTimerDeltas']['inventory_cache_hits_total'])
        self.assertAlmostEqual(5, value['timerMeanMilliseconds']['inventory_cache_redis_mean_ms{operation="get"}'])
        with self.assertRaises(ValueError):metric_delta(after,before)

    def test_prometheus_nonfinite_data_is_not_silently_reported_as_zero(self):
        self.assertEqual({'inventory_hits_total': 3}, parse_prometheus('# comment\ninventory_unknown NaN\ninventory_hits_total 3\n'))

    def test_overrides_never_accept_credentials_or_unrelated_env(self):
        self.assertEqual({'INVENTORY_IMAGE':'local:test','INVENTORY_CACHE_TTL_SECONDS':'30'},parse_service_env(['INVENTORY_IMAGE=local:test','INVENTORY_CACHE_TTL_SECONDS=30']))
        with self.assertRaises(ValueError):parse_service_env(['APP_API_KEY=not-allowed'])


if __name__ == '__main__':unittest.main()
