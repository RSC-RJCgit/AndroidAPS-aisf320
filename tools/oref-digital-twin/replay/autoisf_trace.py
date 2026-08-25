"""Import versioned AutoISF replay traces from AAPS logs or log ZIP files."""

from __future__ import annotations

import argparse
import base64
import json
import re
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Iterator

from .autoisf_source import AUTOISF_SOURCES, build_source_manifest


BEGIN = re.compile(r"AUTOISF_REPLAY_BEGIN\s+(\S+)\s+(\d+)")
DATA = re.compile(r"AUTOISF_REPLAY_DATA\s+(\S+)\s+(\d+)/(\d+)\s+([A-Za-z0-9+/=]+)")
END = re.compile(r"AUTOISF_REPLAY_END\s+(\S+)")
SUPPORTED_SCHEMA = 1
SUPPORTED_CONTROLLER = "aaps-autoisf-ukf3426"
SOURCE_PATHS = {path.as_posix() for path in AUTOISF_SOURCES}
REQUIRED_PARAMETER_KEYS = {
    "microBolusAllowed", "currentTime", "flatBGsDetected", "autoIsfMode",
    "loop_wanted_smb", "profile_percentage", "smb_ratio", "smb_max_range_extension",
    "iob_threshold_percent", "activity_consoleLog", "auto_isf_consoleError",
    "auto_isf_consoleLog", "bg_acce", "steps180M", "steps15M", "steps5M",
    "smbInt5Sec", "smbBoostRecent", "rawDelta5Mgdl", "immediateRawDelta5Mgdl",
    "rawDelta1Mgdl", "aapsDelta1Mgdl", "rawDelta15Mgdl", "recentLowActive",
    "smbSum10Min", "smbSum30Min", "sub75HeavyDeliveryCooldown",
    "basalUpOffsetZeroActive", "fastRiseSlopeCompensationRatio", "lastBolusMinutes",
    "lastCarbMinutes", "iobChange5Min", "recentLowBG", "bmildBasicCriteriaMet",
    "acceIsfValue",
}


class AutoIsfTraceError(ValueError):
    pass


@dataclass
class _PendingTrace:
    expected_chunks: int | None = None
    chunks: dict[int, str] = field(default_factory=dict)
    ended: bool = False


@dataclass
class TraceImportReport:
    records: list[dict] = field(default_factory=list)
    incomplete_ids: list[str] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)
    marker_lines: int = 0

    @property
    def ok(self) -> bool:
        return bool(self.records) and not self.incomplete_ids and not self.errors


def _validate_record(record: object, trace_id: str) -> dict:
    if not isinstance(record, dict):
        raise AutoIsfTraceError(f"{trace_id}: decoded payload is not an object")
    if record.get("schema_version") != SUPPORTED_SCHEMA:
        raise AutoIsfTraceError(
            f"{trace_id}: unsupported schema_version {record.get('schema_version')!r}"
        )
    if record.get("controller") != SUPPORTED_CONTROLLER:
        raise AutoIsfTraceError(
            f"{trace_id}: unsupported controller {record.get('controller')!r}"
        )
    if not isinstance(record.get("captured_at"), int):
        raise AutoIsfTraceError(f"{trace_id}: captured_at must be epoch milliseconds")
    source_sha256 = record.get("source_sha256")
    if not isinstance(source_sha256, dict) or set(source_sha256) != SOURCE_PATHS:
        raise AutoIsfTraceError(f"{trace_id}: two-file source_sha256 identity is missing")
    if any(not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value)
           for value in source_sha256.values()):
        raise AutoIsfTraceError(f"{trace_id}: source_sha256 contains an invalid hash")
    inputs = record.get("inputs")
    if not isinstance(inputs, dict):
        raise AutoIsfTraceError(f"{trace_id}: inputs object is missing")
    required = {
        "glucose_status", "currenttemp", "iob_data_array", "profile",
        "autosens_data", "meal_data", "parameters", "determine_state",
    }
    missing = sorted(required - inputs.keys())
    if missing:
        raise AutoIsfTraceError(f"{trace_id}: inputs missing {', '.join(missing)}")
    parameters = inputs["parameters"]
    if not isinstance(parameters, dict):
        raise AutoIsfTraceError(f"{trace_id}: parameters must be an object")
    missing_parameters = sorted(REQUIRED_PARAMETER_KEYS - parameters.keys())
    if missing_parameters:
        raise AutoIsfTraceError(
            f"{trace_id}: parameters missing {', '.join(missing_parameters)}"
        )
    if not isinstance(record.get("result"), dict):
        raise AutoIsfTraceError(f"{trace_id}: result object is missing")
    return record


def import_trace_lines(lines: Iterable[str]) -> TraceImportReport:
    pending: dict[str, _PendingTrace] = {}
    report = TraceImportReport()

    for line in lines:
        match = BEGIN.search(line)
        if match:
            report.marker_lines += 1
            trace_id, count = match.group(1), int(match.group(2))
            item = pending.setdefault(trace_id, _PendingTrace())
            item.expected_chunks = count
            continue
        match = DATA.search(line)
        if match:
            report.marker_lines += 1
            trace_id, index, total, chunk = (
                match.group(1), int(match.group(2)), int(match.group(3)), match.group(4)
            )
            item = pending.setdefault(trace_id, _PendingTrace())
            if item.expected_chunks is not None and item.expected_chunks != total:
                report.errors.append(
                    f"{trace_id}: chunk total changed from {item.expected_chunks} to {total}"
                )
            item.expected_chunks = total
            previous = item.chunks.get(index)
            if previous is not None and previous != chunk:
                report.errors.append(f"{trace_id}: conflicting duplicate chunk {index}")
            item.chunks[index] = chunk
            continue
        match = END.search(line)
        if match:
            report.marker_lines += 1
            pending.setdefault(match.group(1), _PendingTrace()).ended = True

    for trace_id, item in sorted(pending.items()):
        count = item.expected_chunks
        if not item.ended or count is None or set(item.chunks) != set(range(1, count + 1)):
            report.incomplete_ids.append(trace_id)
            continue
        try:
            encoded = "".join(item.chunks[index] for index in range(1, count + 1))
            raw = base64.b64decode(encoded, validate=True).decode("utf-8")
            report.records.append(_validate_record(json.loads(raw), trace_id))
        except (ValueError, UnicodeDecodeError, json.JSONDecodeError) as exc:
            report.errors.append(f"{trace_id}: {exc}")

    report.records.sort(key=lambda record: record["captured_at"])
    return report


def _text_lines(path: Path) -> Iterator[str]:
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            for info in archive.infolist():
                if info.is_dir():
                    continue
                try:
                    with archive.open(info) as stream:
                        for raw in stream:
                            yield raw.decode("utf-8", errors="replace")
                except (OSError, RuntimeError):
                    continue
    else:
        with path.open("r", encoding="utf-8", errors="replace") as stream:
            yield from stream


def import_trace_file(path: Path) -> TraceImportReport:
    if not path.is_file():
        raise FileNotFoundError(path)
    return import_trace_lines(_text_lines(path))


def write_jsonl(records: Iterable[dict], output: Path) -> int:
    output.parent.mkdir(parents=True, exist_ok=True)
    count = 0
    with output.open("w", encoding="utf-8", newline="\n") as stream:
        for record in records:
            stream.write(json.dumps(record, separators=(",", ":"), sort_keys=True))
            stream.write("\n")
            count += 1
    return count


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Extract and validate UKF3426 AutoISF replay traces from an AAPS log/ZIP."
    )
    parser.add_argument("input", type=Path, help="AAPS text log or exported log ZIP")
    parser.add_argument("--output", "-o", type=Path, help="write validated records as JSON Lines")
    args = parser.parse_args(argv)

    report = import_trace_file(args.input)
    current_source = {
        item.path: item.sha256 for item in build_source_manifest().files
    }
    source_mismatches = sum(
        record.get("source_sha256") != current_source for record in report.records
    )
    if args.output:
        write_jsonl(report.records, args.output)
    print(
        f"records={len(report.records)} incomplete={len(report.incomplete_ids)} "
        f"errors={len(report.errors)} source_mismatches={source_mismatches} "
        f"marker_lines={report.marker_lines}"
    )
    for error in report.errors:
        print(f"ERROR {error}")
    for trace_id in report.incomplete_ids:
        print(f"INCOMPLETE {trace_id}")
    if not report.records:
        return 2
    if source_mismatches:
        print("ERROR captured APK controller source does not match this working tree")
    return 1 if report.errors or source_mismatches else 0


if __name__ == "__main__":
    raise SystemExit(main())
