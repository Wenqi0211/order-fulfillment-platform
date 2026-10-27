#!/usr/bin/env python3
"""Generate local-only credentials without printing them or overwriting a file."""
import argparse
import os
from pathlib import Path
import secrets

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / ".env")
    args = parser.parse_args()
    values = {name: secrets.token_hex(24) for name in (
        "APP_API_KEY", "POSTGRES_PASSWORD", "INVENTORY_DB_PASSWORD",
        "PAYMENT_DB_PASSWORD", "ORDER_DB_PASSWORD", "REDIS_PASSWORD")}
    values.update(INVENTORY_CACHE_ENABLED="true")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    try:
        descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        parser.exit(0, f"Preserved existing {args.output}. No credentials changed.\n")
    with os.fdopen(descriptor, "w") as stream:
        stream.write("# Generated local development credentials; never commit this file.\n")
        stream.writelines(f"{name}={value}\n" for name, value in values.items())
    print(f"Created {args.output} with owner-only permissions; credentials not displayed.")


if __name__ == "__main__":
    main()
