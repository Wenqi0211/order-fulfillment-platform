"""Deployment guard/rollback tests; no cluster or credentials are accessed."""
import contextlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import rollout


class ReleaseSafetyTests(unittest.TestCase):
    def invoke(self, *arguments):
        with patch.object(sys, "argv", ["rollout.py", *arguments]), contextlib.redirect_stdout(io.StringIO()):
            rollout.main()

    def test_rollback_attempts_every_service_when_the_first_undo_fails(self):
        with tempfile.TemporaryDirectory() as temporary:
            snapshot = Path(temporary) / "revisions.json"
            snapshot.write_text(json.dumps({"namespace": "order-staging",
                "revisions": {name: 7 for name in rollout.SERVICES}}))
            calls = []

            def fake_kubectl(*arguments):
                calls.append(arguments)
                if "undo" in arguments and "deployment/inventory-service" in arguments:
                    raise subprocess.CalledProcessError(1, "kubectl", stderr="simulated undo failure")
                return ""

            with patch.object(rollout, "kubectl", side_effect=fake_kubectl):
                with self.assertRaisesRegex(SystemExit, "inventory-service"):
                    self.invoke("rollback", "--namespace", "order-staging", "--snapshot", str(snapshot))
            undo_targets = [call[4] for call in calls if "undo" in call]
            self.assertEqual(undo_targets, [f"deployment/{name}" for name in rollout.SERVICES])

    def test_rollback_rejects_a_snapshot_for_a_different_namespace(self):
        with tempfile.TemporaryDirectory() as temporary:
            snapshot = Path(temporary) / "revisions.json"
            snapshot.write_text(json.dumps({"namespace": "order-prod",
                "revisions": {name: 7 for name in rollout.SERVICES}}))
            with patch.object(rollout, "kubectl") as command:
                with self.assertRaisesRegex(SystemExit, "does not match"):
                    self.invoke("rollback", "--namespace", "order-staging", "--snapshot", str(snapshot))
                command.assert_not_called()

    def test_new_deployment_is_removed_but_existing_revisions_are_restored(self):
        with tempfile.TemporaryDirectory() as temporary:
            snapshot = Path(temporary) / "revisions.json"
            snapshot.write_text(json.dumps({"namespace": "order-staging", "revisions": {
                "inventory-service": None, "payment-service": 2, "order-service": 3}}))
            with patch.object(rollout, "kubectl", return_value="") as command:
                self.invoke("rollback", "--namespace", "order-staging", "--snapshot", str(snapshot))
                calls = [call.args for call in command.call_args_list]
            self.assertIn(("-n", "order-staging", "delete", "deployment", "inventory-service", "--ignore-not-found"), calls)
            self.assertTrue(any("--to-revision=2" in call for call in calls))
            self.assertTrue(any("--to-revision=3" in call for call in calls))

    def test_release_uses_exact_ci_digest_for_every_service(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "manifests"
            directory.mkdir()
            digests = Path(temporary) / "digests"
            digests.mkdir()
            for index, name in enumerate(rollout.SERVICES):
                (directory / f"{name}.yaml").write_text(
                    f"image: ghcr.io/REPLACE_OWNER/order-fulfillment-platform/{name}:REQUIRED_IMAGE_TAG\n")
                (digests / f"{name}.digest").write_text("sha256:" + str(index + 1) * 64 + "\n")
            self.invoke("render", "--directory", str(directory), "--repository", "ghcr.io/Owner/Repository",
                "--commit", "a" * 40, "--digests", str(digests))
            for index, name in enumerate(rollout.SERVICES):
                self.assertEqual((directory / f"{name}.yaml").read_text(),
                    f"image: ghcr.io/owner/repository/{name}@sha256:" + str(index + 1) * 64 + "\n")

    def test_snapshot_refuses_an_unhealthy_prior_release_before_applying_changes(self):
        with tempfile.TemporaryDirectory() as temporary:
            snapshot = Path(temporary) / "revisions.json"
            def fake_kubectl(*arguments):
                if "get" in arguments:
                    return json.dumps({"metadata": {"annotations": {"deployment.kubernetes.io/revision": "7"}}})
                raise subprocess.CalledProcessError(1, "kubectl", stderr="readiness timeout")
            with patch.object(rollout, "kubectl", side_effect=fake_kubectl):
                with self.assertRaisesRegex(SystemExit, "Baseline inventory-service is not healthy"):
                    self.invoke("snapshot", "--namespace", "order-staging", "--output", str(snapshot))
            self.assertFalse(snapshot.exists())

    def test_arbitrary_namespace_is_rejected_before_cluster_access(self):
        with patch.object(rollout, "kubectl") as command, contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                self.invoke("wait", "--namespace", "default")
            command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
