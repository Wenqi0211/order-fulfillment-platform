"""Small standard-library client shared by local verification scripts."""
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def read_env(path=ROOT / ".env"):
    values = {}
    if Path(path).exists():
        for line in Path(path).read_text().splitlines():
            if line.strip() and not line.lstrip().startswith("#"):
                key, separator, value = line.partition("=")
                if separator:
                    values[key.strip()] = value.strip()
    return {**values, **os.environ}


def api_key():
    value = read_env().get("APP_API_KEY", "")
    if len(value) < 32:
        raise SystemExit("Set APP_API_KEY or run python3 scripts/init_env.py first.")
    return value


class Client:
    def __init__(self, base_url, key=None, timeout=5):
        self.base_url = base_url.rstrip("/")
        self.key = api_key() if key is None else key
        self.timeout = timeout

    def request(self, method, path, body=None, headers=None, expected=(200,)):
        raw = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.base_url + path, data=raw, method=method,
            headers={"X-API-Key": self.key, "Content-Type": "application/json", **(headers or {})})
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                status, text = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, text = error.code, error.read()
        try:
            result = json.loads(text) if text else None
        except ValueError:
            result = {"nonJsonResponse": text[:200].decode(errors="replace")}
        if status not in expected:
            # Error text is intentionally bounded, and API keys are never printed.
            raise AssertionError(f"{method} {path}: HTTP {status}, expected {expected}: {str(result)[:300]}")
        return status, result

    def wait_ready(self, seconds=90):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            try:
                self.request("GET", "/actuator/health/readiness")
                return
            except (AssertionError, OSError):
                time.sleep(0.5)
        raise AssertionError(f"Service did not become ready within {seconds}s: {self.base_url}")


def save_report(name, report):
    directory = ROOT / "reports"
    directory.mkdir(exist_ok=True)
    path = directory / name
    path.write_text(json.dumps(report, indent=2) + "\n")
    return path
