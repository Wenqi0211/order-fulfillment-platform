"""Monitoring-check correctness and fault restoration; no Docker or network access."""
import argparse
import unittest
from unittest.mock import patch

from observability_smoke import Compose, loopback_url, numeric_value, targets_ready


class ObservabilitySmokeTests(unittest.TestCase):
    def test_fault_restores_dependency_when_workload_raises(self):
        compose = Compose(["compose.yaml", "compose.scaling.yaml", "compose.observability.yaml"])
        with patch.object(compose, "run") as command:
            with self.assertRaisesRegex(AssertionError, "workload failure"):
                with compose.stopped("payment-service"):
                    raise AssertionError("workload failure")
        self.assertEqual([call.args for call in command.call_args_list],
            [("stop", "-t", "3", "payment-service"), ("start", "payment-service")])

    def test_stop_failure_still_attempts_restoration(self):
        compose = Compose([])
        with patch.object(compose, "run", side_effect=[RuntimeError("stop failed"), None]) as command:
            with self.assertRaisesRegex(RuntimeError, "stop failed"):
                with compose.stopped("redis"):
                    self.fail("A failed stop must not run the workload")
        self.assertEqual(command.call_args_list[-1].args, ("start", "redis"))

    def test_fault_scope_cannot_stop_database_or_unrelated_service(self):
        compose = Compose([])
        with patch.object(compose, "run") as command, self.assertRaises(ValueError):
            with compose.stopped("postgres"):
                pass
        command.assert_not_called()

    def test_missing_or_duplicate_replica_does_not_pass_target_check(self):
        rows = [{"service": "order-service", "instance": "a:8080", "health": "up"},
            {"service": "inventory-service", "instance": "b:8081", "health": "up"},
            {"service": "payment-service", "instance": "c:8082", "health": "up"}]
        self.assertFalse(targets_ready(rows, 2))
        self.assertFalse(targets_ready(rows + [dict(rows[0])], 2))
        self.assertTrue(targets_ready(rows + [{**rows[0], "instance": "d:8080"}], 2))
        self.assertFalse(targets_ready(rows + [{**rows[0], "instance": "d:8080", "health": "down"}], 2))

    def test_metric_absence_is_zero_only_when_allowed_and_nan_never_passes(self):
        payload = {"status": "success", "data": {"resultType": "vector", "result": []}}
        self.assertEqual(numeric_value(payload), 0)
        with self.assertRaises(AssertionError):
            numeric_value(payload, missing_zero=False)
        payload["data"]["result"] = [{"value": [123, "NaN"]}]
        with self.assertRaisesRegex(AssertionError, "finite"):
            numeric_value(payload)

    def test_only_explicit_loopback_urls_are_accepted(self):
        for url in ("http://127.0.0.1:9090", "http://localhost:3000/", "http://[::1]:8080"):
            self.assertEqual(loopback_url(url), url.rstrip("/"))
        for url in ("https://example.com:9090", "http://localhost", "file:///tmp/secret",
                "http://user:password@localhost:3000", "http://localhost:9090/?query=secret"):
            with self.assertRaises(argparse.ArgumentTypeError):
                loopback_url(url)


if __name__ == "__main__":
    unittest.main()
