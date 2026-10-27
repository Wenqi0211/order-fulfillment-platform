#!/usr/bin/env python3
"""Static deployment checks. This is not a Kubernetes API-server admission test."""
from pathlib import Path
import sys

try:
    import yaml
except ImportError:
    raise SystemExit("This optional validator needs PyYAML: python3 -m pip install PyYAML==6.0.2")

ROOT = Path(__file__).resolve().parents[1]
errors = []
count = 0
for path in [ROOT / "compose.yaml", *sorted((ROOT / "infra" / "kubernetes").rglob("*.yaml")),
             *sorted((ROOT / ".github" / "workflows").glob("*.yml"))]:
    for document in yaml.safe_load_all(path.read_text()):
        count += 1
        if not isinstance(document, dict):
            errors.append(f"{path}: document is not a mapping")
            continue
        if document.get("kind") == "Deployment":
            spec = document["spec"]["template"]["spec"]
            for container in spec["containers"]:
                for field in ("resources", "readinessProbe", "livenessProbe", "startupProbe", "securityContext"):
                    if field not in container:
                        errors.append(f"{path}: {field} missing")
                if not container.get("securityContext", {}).get("readOnlyRootFilesystem"):
                    errors.append(f"{path}: writable root filesystem")
            if spec.get("automountServiceAccountToken") is not False:
                errors.append(f"{path}: service-account token unnecessarily mounted")
        if document.get("kind") == "Secret":
            errors.append(f"{path}: do not check credential manifests into source control")
if errors:
    raise SystemExit("\n".join(errors))
print(f"Parsed {count} YAML documents; deployment security/probe checks passed.")
