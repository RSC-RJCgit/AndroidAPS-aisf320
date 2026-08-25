"""Python bridge to the Node oref oracle.

Runs the real oref0 determine-basal (see oracle/determine.js). The subprocess runner is
injectable so the counterfactual logic can be unit-tested without Node or oref0 present —
the same pattern as ingestion's HTTP transport.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Callable

ORACLE_DIR = Path(__file__).parent / "oracle"

# runner(requests) -> list of result dicts, each {"ok": bool, "rt": {...}} or {"ok": False, "error": str}
Runner = Callable[[list[dict]], list[dict]]


class OracleUnavailable(RuntimeError):
    pass


class OracleError(RuntimeError):
    pass


def _node_runner(oracle_dir: Path, node_bin: str = "node", timeout_s: float = 60.0) -> Runner:
    import subprocess  # lazy: not available under Pyodide, and only the CLI/server path needs it

    script = oracle_dir / "determine.js"

    def run(requests: list[dict]) -> list[dict]:
        if not script.exists():
            raise OracleUnavailable(f"oracle script missing: {script}")
        try:
            proc = subprocess.run(
                [node_bin, str(script)],
                input=json.dumps({"requests": requests}),
                capture_output=True, text=True, cwd=str(oracle_dir), timeout=timeout_s,
            )
        except FileNotFoundError as exc:
            raise OracleUnavailable(f"node not found ({node_bin})") from exc
        except subprocess.TimeoutExpired as exc:
            raise OracleError("oracle timed out") from exc
        if not proc.stdout.strip():
            raise OracleError(f"oracle produced no output (stderr: {proc.stderr[:400]})")
        payload = json.loads(proc.stdout)
        if "error" in payload:
            raise OracleError(f"oracle error: {payload['error']}")
        return payload.get("results", [])

    return run


class OrefOracle:
    def __init__(self, runner: Runner | None = None, oracle_dir: Path = ORACLE_DIR):
        self._runner = runner or _node_runner(oracle_dir)

    def evaluate(self, requests: list[dict]) -> list[dict]:
        """Return the raw result records (one per request), preserving ok/error per item."""
        if not requests:
            return []
        return self._runner(requests)

    def enacted(self, requests: list[dict]) -> list[dict | None]:
        """Return just the rT decision per request, or None where that cycle errored."""
        out: list[dict | None] = []
        for r in self.evaluate(requests):
            out.append(r.get("rt") if r.get("ok") else None)
        return out
