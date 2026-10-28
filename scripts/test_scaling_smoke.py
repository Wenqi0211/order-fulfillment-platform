"""Routing evidence must connect the same idempotency key to distinct replicas."""
import json
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from scaling_smoke import (EdgeResponseFailure, EdgeStabilizationTimeout, interleaved_replays,
    parse_routing_rows, post_routes, stabilize_edge)


class ScalingEvidenceTests(unittest.TestCase):
    def test_originals_and_replays_are_interleaved(self):
        self.assertEqual(interleaved_replays(3), [0, 0, 1, 1, 2, 2])

    def test_only_successful_correlated_order_posts_prove_replica_crossing(self):
        request_keys = {"run-a": 0, "run-b": 0, "run-c": 1, "run-d": 1, "run-error": 1}
        def row(request_id, upstream, **changes):
            return {"request_id": request_id, "method": "POST", "path": "/v1/orders",
                "status": 200, "upstream_addr": upstream, **changes}
        lines = [json.dumps(value) for value in [
            row("run-a", "a:8080", status=201), row("run-b", "b:8080"),
            row("run-c", "a:8080"), row("run-d", "a:8080"),
            row("run-error", "b:8080", status=409),
            row("run-get", "b:8080", method="GET"),
            row("other-run", "c:8080")]] + ["nginx startup message", "[]"]
        routes, seen = post_routes(parse_routing_rows(lines, "run-"), request_keys)
        self.assertEqual(routes, {0: {"a:8080", "b:8080"}, 1: {"a:8080"}})
        self.assertEqual(seen, {"run-a", "run-b", "run-c", "run-d"})
        self.assertEqual([key for key in routes if len(routes[key]) >= 2], [0])

    def test_failed_upstream_attempt_does_not_prove_success_on_that_replica(self):
        row = {"request_id": "run-a", "method": "POST", "path": "/v1/orders",
            "status": 201, "upstream_addr": "a:8080, b:8080"}
        routes, seen = post_routes([row], {"run-a": 0})
        self.assertEqual(routes, {0: {"b:8080"}})
        self.assertEqual(seen, {"run-a"})


class FakeTime:
    def __init__(self):
        self.now = 0.0

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class EdgeWarmupTests(unittest.TestCase):
    client = SimpleNamespace(base_url="http://127.0.0.1:8080", key="test-key")
    addresses = {"a:8080": "order-1", "b:8080": "order-2"}

    def routes(self, files, prefix, since, timeout=60):
        return [{"request_id": prefix + str(index), "status": 200, "upstream_addr": address}
            for index, address in enumerate(self.addresses)]

    def test_transient_warmup_failure_is_recorded_before_full_clean_dns_window(self):
        clock = FakeTime()
        failures = iter([EdgeResponseFailure(502), TimeoutError("probe timed out")])

        def probe(*arguments):
            error = next(failures, None)
            if error:
                raise error

        with patch("scaling_smoke.time.monotonic", clock.monotonic), \
                patch("scaling_smoke.time.sleep", clock.sleep), \
                patch("scaling_smoke.request_identity", side_effect=probe), \
                patch("scaling_smoke.routing_rows", side_effect=self.routes):
            routing, evidence = stabilize_edge(self.client, [], self.addresses, "run-", "since")
        self.assertEqual(set(routing), set(self.addresses))
        self.assertEqual(evidence["elapsedSeconds"], 6)
        self.assertEqual(evidence["requiredStableSeconds"], 5)
        self.assertEqual(len(evidence["transientFailures"]), 2)
        by_kind = {row["kind"]: row for row in evidence["transientFailures"]}
        self.assertEqual(by_kind["http"]["status"], 502)
        self.assertEqual(by_kind["transport"]["errorClass"], "TimeoutError")

    def test_persistent_failure_stops_with_bounded_timeout_and_evidence(self):
        clock = FakeTime()
        with patch("scaling_smoke.time.monotonic", clock.monotonic), \
                patch("scaling_smoke.time.sleep", clock.sleep), \
                patch("scaling_smoke.request_identity", side_effect=EdgeResponseFailure(503)), \
                patch("scaling_smoke.routing_rows", side_effect=self.routes):
            with self.assertRaises(EdgeStabilizationTimeout) as result:
                stabilize_edge(self.client, [], self.addresses, "run-", "since", timeout=3)
        self.assertEqual(clock.now, 3)
        self.assertEqual(result.exception.evidence["elapsedSeconds"], 3)
        self.assertTrue(result.exception.evidence["transientFailures"])
        self.assertTrue(all(row["status"] == 503
            for row in result.exception.evidence["transientFailures"]))

    def test_missing_backend_never_passes_even_when_all_http_probes_succeed(self):
        clock = FakeTime()
        with patch("scaling_smoke.time.monotonic", clock.monotonic), \
                patch("scaling_smoke.time.sleep", clock.sleep), \
                patch("scaling_smoke.request_identity"), \
                patch("scaling_smoke.routing_rows", return_value=[{
                    "status": 200, "upstream_addr": "a:8080"}]):
            with self.assertRaises(EdgeStabilizationTimeout) as result:
                stabilize_edge(self.client, [], self.addresses, "run-", "since", timeout=3)
        self.assertTrue(all(row["kind"] == "missing_upstream"
            for row in result.exception.evidence["transientFailures"]))

    def test_obsolete_route_restarts_the_clean_dns_window(self):
        clock = FakeTime()

        def routes(files, prefix, since, timeout=60):
            rows = self.routes(files, prefix, since, timeout)
            if clock.now == 1:
                rows.append({"status": 200, "upstream_addr": "old:8080"})
            return rows

        with patch("scaling_smoke.time.monotonic", clock.monotonic), \
                patch("scaling_smoke.time.sleep", clock.sleep), \
                patch("scaling_smoke.request_identity"), \
                patch("scaling_smoke.routing_rows", side_effect=routes):
            routing, evidence = stabilize_edge(self.client, [], self.addresses, "run-", "since")
        self.assertEqual(evidence["elapsedSeconds"], 7)
        self.assertNotIn("old:8080", routing)
        self.assertEqual(evidence["transientFailures"], [{"round": 1,
            "kind": "obsolete_upstream", "addresses": ["old:8080"]}])

    def test_authentication_or_identity_errors_are_not_transient_warmup_failures(self):
        for failure in [EdgeResponseFailure(401), AssertionError("Request ID changed at edge")]:
            with self.subTest(failure=type(failure).__name__):
                clock = FakeTime()
                with patch("scaling_smoke.time.monotonic", clock.monotonic), \
                        patch("scaling_smoke.time.sleep", clock.sleep), \
                        patch("scaling_smoke.request_identity", side_effect=failure), \
                        patch("scaling_smoke.routing_rows") as read_logs:
                    with self.assertRaises(AssertionError) as result:
                        stabilize_edge(self.client, [], self.addresses, "run-", "since")
                self.assertIs(result.exception, failure)
                self.assertEqual(clock.now, 0)
                read_logs.assert_not_called()


if __name__ == "__main__":
    unittest.main()
