import json
import sys
import tempfile
import unittest
from pathlib import Path

from replay.autoisf_baseline import _differences, load_trace_jsonl, verify_baseline
from replay.autoisf_source import AUTOISF_SOURCES, AutoIsfProcessOracle


def _repo(root: Path) -> Path:
    for index, relative in enumerate(AUTOISF_SOURCES):
        source = root / relative
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text(f"source {index}\n", encoding="utf-8")
    return root


def _adapter(root: Path, alter: bool = False) -> Path:
    path = root / "adapter.py"
    path.write_text(
        "import hashlib,json,pathlib,sys\n"
        "e=json.load(sys.stdin); root=pathlib.Path(e['manifest']['source_root'])\n"
        "s={i['path']:hashlib.sha256((root/i['path']).read_bytes()).hexdigest() "
        "for i in e['manifest']['files']}\n"
        "r=[]\n"
        "for q in e['requests']:\n"
        "  value=dict(q['result'])\n"
        + ("  value['bg']=value.get('bg',0)+1\n" if alter else "")
        + "  r.append({'ok':True,'rt':value})\n"
        "print(json.dumps({'source_sha256':s,'results':r}))\n",
        encoding="utf-8",
    )
    return path


class AutoIsfBaselineTest(unittest.TestCase):
    def test_recursive_comparison_has_numeric_tolerance_but_finds_real_change(self):
        self.assertEqual(list(_differences({"x": 1.0}, {"x": 1.0 + 1e-13})), [])
        found = list(_differences({"x": [1, 2]}, {"x": [1, 3]}))
        self.assertEqual(found[0][0], "result.x[1]")

    def test_matching_adapter_unlocks_baseline(self):
        with tempfile.TemporaryDirectory() as directory:
            root = _repo(Path(directory))
            oracle = AutoIsfProcessOracle([sys.executable, str(_adapter(root))], repo_root=root)
            sources = {item.path: item.sha256 for item in oracle.manifest.files}
            record = {"source_sha256": sources, "result": {"bg": 100.0, "reason": "ok"}}
            report = verify_baseline([record], oracle)
            self.assertTrue(report.ok)
            self.assertEqual(report.matched, 1)

    def test_changed_result_keeps_baseline_blocked(self):
        with tempfile.TemporaryDirectory() as directory:
            root = _repo(Path(directory))
            oracle = AutoIsfProcessOracle(
                [sys.executable, str(_adapter(root, alter=True))], repo_root=root
            )
            sources = {item.path: item.sha256 for item in oracle.manifest.files}
            report = verify_baseline(
                [{"source_sha256": sources, "result": {"bg": 100.0}}], oracle
            )
            self.assertFalse(report.ok)
            self.assertEqual(report.differences[0].path, "result.bg")

    def test_jsonl_loader_rejects_non_trace_rows(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bad.jsonl"
            path.write_text(json.dumps({"not_result": True}) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "not an AutoISF replay record"):
                load_trace_jsonl(path)


if __name__ == "__main__":
    unittest.main()
