"""Fail-closed baseline verification for the source-pinned Kotlin AutoISF adapter."""

from __future__ import annotations

import argparse
import json
import math
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable, Sequence

from .autoisf_source import AutoIsfProcessOracle, AutoIsfSourceError


@dataclass(frozen=True)
class BaselineDifference:
    record: int
    path: str
    expected: Any
    actual: Any


@dataclass
class BaselineReport:
    records: int = 0
    matched: int = 0
    adapter_errors: list[str] = field(default_factory=list)
    differences: list[BaselineDifference] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return self.records > 0 and self.matched == self.records and not self.adapter_errors


def load_trace_jsonl(path: Path) -> list[dict]:
    if not path.is_file():
        raise FileNotFoundError(path)
    records: list[dict] = []
    with path.open("r", encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            value = json.loads(line)
            if not isinstance(value, dict) or not isinstance(value.get("result"), dict):
                raise ValueError(f"{path}:{line_number}: not an AutoISF replay record")
            records.append(value)
    if not records:
        raise ValueError(f"{path}: no AutoISF replay records")
    return records


def _differences(expected: Any, actual: Any, path: str = "result") -> Iterable[tuple[str, Any, Any]]:
    if isinstance(expected, bool) or isinstance(actual, bool):
        if expected is not actual:
            yield path, expected, actual
        return
    if isinstance(expected, (int, float)) and isinstance(actual, (int, float)):
        if not math.isclose(float(expected), float(actual), rel_tol=1e-12, abs_tol=1e-12):
            yield path, expected, actual
        return
    if isinstance(expected, dict) and isinstance(actual, dict):
        for key in sorted(expected.keys() | actual.keys()):
            child = f"{path}.{key}"
            if key not in expected:
                yield child, "<missing>", actual[key]
            elif key not in actual:
                yield child, expected[key], "<missing>"
            else:
                yield from _differences(expected[key], actual[key], child)
        return
    if isinstance(expected, list) and isinstance(actual, list):
        if len(expected) != len(actual):
            yield f"{path}.length", len(expected), len(actual)
        for index, (left, right) in enumerate(zip(expected, actual)):
            yield from _differences(left, right, f"{path}[{index}]")
        return
    if expected != actual:
        yield path, expected, actual


def verify_baseline(records: list[dict], oracle: AutoIsfProcessOracle) -> BaselineReport:
    report = BaselineReport(records=len(records))
    expected_sources = {item.path: item.sha256 for item in oracle.manifest.files}
    for index, record in enumerate(records, 1):
        if record.get("source_sha256") != expected_sources:
            raise AutoIsfSourceError(
                f"record {index} source hashes do not match the Kotlin adapter working tree"
            )

    results = oracle.evaluate(records)
    for index, (record, item) in enumerate(zip(records, results), 1):
        if not item.get("ok"):
            report.adapter_errors.append(f"record {index}: {item.get('error', 'adapter failed')}")
            continue
        actual = item.get("rt")
        if not isinstance(actual, dict):
            report.adapter_errors.append(f"record {index}: adapter returned no RT result")
            continue
        found = list(_differences(record["result"], actual))
        if found:
            report.differences.extend(
                BaselineDifference(index, path, expected, observed)
                for path, expected, observed in found
            )
        else:
            report.matched += 1
    return report


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Run captured AutoISF boundaries through the Kotlin controller and compare RT exactly."
    )
    parser.add_argument("input", type=Path, help="validated AutoISF replay JSONL")
    parser.add_argument(
        "--adapter-command",
        nargs=argparse.REMAINDER,
        required=True,
        help="command that runs the Kotlin JSON-process adapter (must be the final option)",
    )
    args = parser.parse_args(argv)
    if not args.adapter_command:
        parser.error("--adapter-command requires a command")

    records = load_trace_jsonl(args.input)
    oracle = AutoIsfProcessOracle(args.adapter_command)
    report = verify_baseline(records, oracle)
    print(
        f"records={report.records} matched={report.matched} "
        f"differences={len(report.differences)} adapter_errors={len(report.adapter_errors)}"
    )
    for error in report.adapter_errors:
        print(f"ADAPTER_ERROR {error}")
    for difference in report.differences[:100]:
        print(
            f"DIFF record={difference.record} path={difference.path} "
            f"expected={difference.expected!r} actual={difference.actual!r}"
        )
    if len(report.differences) > 100:
        print(f"DIFF ... {len(report.differences) - 100} more")
    return 0 if report.ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
