import base64
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from replay.autoisf_trace import (
    REQUIRED_PARAMETER_KEYS,
    SOURCE_PATHS,
    import_trace_file,
    import_trace_lines,
    write_jsonl,
)


def _record(captured_at=1_700_000_000_000):
    return {
        "schema_version": 1,
        "controller": "aaps-autoisf-ukf3426",
        "captured_at": captured_at,
        "build": {"version": "test"},
        "source_sha256": {
            path: character * 64
            for path, character in zip(sorted(SOURCE_PATHS), ("a", "b"))
        },
        "profile_name": "Synthetic",
        "preference_snapshot": "x = true",
        "inputs": {
            "glucose_status": {},
            "currenttemp": {},
            "iob_data_array": [],
            "profile": {},
            "autosens_data": {},
            "meal_data": {},
            "parameters": {key: 0 for key in REQUIRED_PARAMETER_KEYS},
            "determine_state": {},
        },
        "result": {"units": 0.1},
    }


def _lines(record=None, trace_id="trace-1", size=37):
    encoded = base64.b64encode(json.dumps(record or _record()).encode()).decode()
    chunks = [encoded[i:i + size] for i in range(0, len(encoded), size)]
    lines = [f"prefix AUTOISF_REPLAY_BEGIN {trace_id} {len(chunks)}\n"]
    lines += [
        f"prefix AUTOISF_REPLAY_DATA {trace_id} {i + 1}/{len(chunks)} {chunk}\n"
        for i, chunk in enumerate(chunks)
    ]
    lines.append(f"prefix AUTOISF_REPLAY_END {trace_id}\n")
    return lines


class AutoIsfTraceTest(unittest.TestCase):
    def test_reassembles_and_validates_chunked_record(self):
        report = import_trace_lines(_lines())
        self.assertTrue(report.ok)
        self.assertEqual(report.records[0]["result"]["units"], 0.1)

    def test_reports_incomplete_record(self):
        lines = _lines()[:-2]
        report = import_trace_lines(lines)
        self.assertEqual(report.incomplete_ids, ["trace-1"])
        self.assertFalse(report.records)

    def test_rejects_wrong_controller(self):
        record = _record()
        record["controller"] = "stock-oref0"
        report = import_trace_lines(_lines(record))
        self.assertIn("unsupported controller", report.errors[0])

    def test_reads_zip_and_writes_jsonl(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "logs.zip"
            with zipfile.ZipFile(archive, "w") as stream:
                stream.writestr("AndroidAPS.log", "".join(_lines()))
            report = import_trace_file(archive)
            output = root / "records.jsonl"
            self.assertEqual(write_jsonl(report.records, output), 1)
            self.assertEqual(json.loads(output.read_text()), _record())


if __name__ == "__main__":
    unittest.main()
