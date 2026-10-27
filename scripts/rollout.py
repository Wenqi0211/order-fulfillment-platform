#!/usr/bin/env python3
"""Explicit release rollout and workload rollback with captured revisions."""
import argparse
import json
from pathlib import Path
import re
import subprocess

SERVICES = ("inventory-service", "payment-service", "order-service")


def kubectl(*args):
    return subprocess.run(["kubectl", *args], text=True, capture_output=True, check=True).stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    snapshot = commands.add_parser("snapshot")
    snapshot.add_argument("--namespace", required=True)
    snapshot.add_argument("--output", type=Path, required=True)
    render = commands.add_parser("render")
    render.add_argument("--directory", type=Path, required=True)
    render.add_argument("--repository", required=True)
    render.add_argument("--commit", required=True)
    render.add_argument("--digests", type=Path, help="CI artifact directory with SERVICE.digest files")
    wait = commands.add_parser("wait")
    wait.add_argument("--namespace", required=True)
    rollback = commands.add_parser("rollback")
    rollback.add_argument("--namespace", required=True)
    rollback.add_argument("--snapshot", type=Path, required=True)
    args = parser.parse_args()
    if hasattr(args, "namespace") and not re.fullmatch(r"order-(staging|prod)", args.namespace):
        parser.error("namespace must be order-staging or order-prod")
    if args.command == "render":
        if not re.fullmatch(r"[0-9a-f]{40}", args.commit):
            parser.error("release must be a full commit SHA")
        repository = args.repository.lower().rstrip("/")
        if not re.fullmatch(r"ghcr\.io/[a-z0-9_.-]+/[a-z0-9_.-]+", repository):
            parser.error("repository must be ghcr.io/owner/repository")
        digests = {}
        if args.digests:
            for name in SERVICES:
                digest = (args.digests / f"{name}.digest").read_text().strip()
                if not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
                    raise SystemExit(f"Invalid release digest for {name}")
                digests[name] = digest
        changed = 0
        for path in args.directory.rglob("*.yaml"):
            text = path.read_text()
            updated = text.replace("ghcr.io/REPLACE_OWNER/order-fulfillment-platform", repository)
            for name, digest in digests.items():
                updated = updated.replace(f"{repository}/{name}:REQUIRED_IMAGE_TAG", f"{repository}/{name}@{digest}")
            updated = updated.replace("REQUIRED_IMAGE_TAG", args.commit)
            if text != updated:
                path.write_text(updated)
                changed += 1
        if changed != 3:
            raise SystemExit(f"Expected exactly three image manifests, found {changed}")
        print("Rendered three service images using " + ("verified CI digests." if digests else "commit tags (local preview only)."))
        return
    if args.command == "snapshot":
        revisions = {}
        for name in SERVICES:
            output = kubectl("-n", args.namespace, "get", "deployment", name, "--ignore-not-found", "-o", "json")
            if not output.strip():
                revisions[name] = None
            else:
                try:
                    kubectl("-n", args.namespace, "rollout", "status", f"deployment/{name}", "--timeout=30s")
                except subprocess.CalledProcessError as error:
                    raise SystemExit(f"Baseline {name} is not healthy; resolve its rollout before deploying: {error.stderr.strip()[:300]}")
                # Read the revision after the health check, not before a prior rollout finishes.
                deployment = json.loads(kubectl("-n", args.namespace, "get", "deployment", name, "-o", "json"))
                revision = deployment["metadata"].get("annotations", {}).get("deployment.kubernetes.io/revision")
                if revision is None:
                    raise SystemExit(f"{name} has no stable revision; resolve its rollout before deploying")
                revisions[name] = int(revision)
        args.output.write_text(json.dumps({"namespace": args.namespace, "revisions": revisions}, indent=2) + "\n")
        print("Captured pre-deployment workload revisions.")
        return
    errors = []
    if args.command == "rollback":
        saved = json.loads(args.snapshot.read_text())
        if saved.get("namespace") != args.namespace or set(saved["revisions"]) != set(SERVICES):
            raise SystemExit("Snapshot does not match target namespace/service set")
        for name in SERVICES:
            try:
                previous = saved["revisions"][name]
                if previous is None:
                    kubectl("-n", args.namespace, "delete", "deployment", name, "--ignore-not-found")
                else:
                    kubectl("-n", args.namespace, "rollout", "undo", f"deployment/{name}", f"--to-revision={int(previous)}")
                    kubectl("-n", args.namespace, "rollout", "status", f"deployment/{name}", "--timeout=180s")
            except subprocess.CalledProcessError as error:
                errors.append(f"{name}: {error.stderr.strip()[:500]}")
    else:
        for name in SERVICES:
            try:
                kubectl("-n", args.namespace, "rollout", "status", f"deployment/{name}", "--timeout=180s")
            except subprocess.CalledProcessError as error:
                errors.append(f"{name}: {error.stderr.strip()[:500]}")
    if errors:
        raise SystemExit("\n".join(errors))
    print(f"{args.command}: all service workloads verified.")


if __name__ == "__main__":
    main()
