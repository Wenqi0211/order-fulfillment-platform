#!/usr/bin/env python3
"""Create a repeatable benchmark catalog through the real authenticated API."""
import argparse
import hashlib
import json
from pathlib import Path
import re
from api_client import Client, ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:8081")
    parser.add_argument("--count", type=int, default=1000)
    parser.add_argument("--prefix", default="BENCH")
    parser.add_argument("--output", type=Path, default=ROOT / "reports" / "benchmark-dataset.json")
    args = parser.parse_args()
    if not 10 <= args.count <= 100000 or not re.fullmatch(r"[A-Za-z0-9_-]{1,40}", args.prefix):
        parser.error("count must be 10..100000 and prefix must be a short valid SKU prefix")
    client = Client(args.url)
    client.wait_ready()
    skus = []
    items = {}
    for index in range(args.count):
        item = {"sku": f"{args.prefix}_{index:06}", "name": f"Benchmark product {index:06}",
            "unitPriceMinor": 100 + index % 10000, "currency": "USD", "initialStock": 10000}
        status, _ = client.request("POST", "/v1/items", item, expected=(201, 409))
        if status == 409:
            _, existing = client.request("GET", f"/v1/items/{item['sku']}")
            assert existing["name"] == item["name"] and existing["unitPriceMinor"] == item["unitPriceMinor"], "Existing catalog differs; choose another prefix"
        skus.append(item["sku"])
        items[item["sku"]] = {field: item[field] for field in ("name", "unitPriceMinor", "currency")}
    encoded = json.dumps(skus, separators=(",", ":")).encode()
    manifest = {"skus": skus, "sha256": hashlib.sha256(encoded).hexdigest(), "count": len(skus), "items": items}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Catalog ready: {len(skus)} products. Manifest: {args.output}")


if __name__ == "__main__":
    main()
