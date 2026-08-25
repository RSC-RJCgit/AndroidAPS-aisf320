"""CLI: pull a user's recent Nightscout history and print a coverage summary.

Usage:
    export NS_URL="https://your-nightscout.example"
    export NS_TOKEN="read-only-token"          # or NS_API_SECRET_SHA1
    python -m ingestion.cli --days 30 --out data/pull.json

The token is read from the environment, never taken as an argument, and never written to
the output file. The output goes under `data/` which is gitignored.
"""

from __future__ import annotations

import argparse
import json
import sys
import time

from .config import NightscoutConfig
from .pull import run_pull


def _now_ms() -> int:
    return int(time.time() * 1000)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Pull Nightscout history (read-only).")
    parser.add_argument("--days", type=int, default=30, help="how many days back to pull (default 30)")
    parser.add_argument("--window-days", type=int, default=7, help="fetch window size (default 7)")
    parser.add_argument("--out", default=None, help="write full normalised pull to this JSON path")
    parser.add_argument("--url", default=None, help="override NS_URL")
    args = parser.parse_args(argv)

    try:
        config = NightscoutConfig.from_env() if not args.url else NightscoutConfig(
            base_url=args.url,
            token=__import__("os").environ.get("NS_TOKEN"),
            api_secret_sha1=__import__("os").environ.get("NS_API_SECRET_SHA1"),
        )
    except ValueError as exc:
        print(f"config error: {exc}", file=sys.stderr)
        return 2

    end_ms = _now_ms()
    start_ms = end_ms - args.days * 86_400_000

    print(f"Pulling {args.days}d from {config}", file=sys.stderr)
    result = run_pull(config, start_ms, end_ms, window_days=args.window_days)

    json.dump(result.summary(), sys.stdout, indent=2)
    sys.stdout.write("\n")

    for w in result.warnings():
        print(f"WARNING: {w}", file=sys.stderr)

    if args.out:
        with open(args.out, "w") as fh:
            json.dump(result.to_dict(), fh)
        print(f"wrote {args.out}", file=sys.stderr)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
