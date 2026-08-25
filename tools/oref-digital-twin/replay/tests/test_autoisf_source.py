import json
import sys
import tempfile
import unittest
from pathlib import Path

from replay.autoisf_source import (
    AUTOISF_SOURCES,
    AutoIsfProcessOracle,
    AutoIsfSourceError,
    build_source_manifest,
    verify_embedded_source_identity,
)


def _fake_repo(tmp_path: Path) -> Path:
    for index, relative in enumerate(AUTOISF_SOURCES):
        source = tmp_path / relative
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text(f"source {index}\n", encoding="utf-8")
    return tmp_path


class AutoIsfSourceTest(unittest.TestCase):
    def test_android_embedded_identity_matches_current_controller_sources(self):
        manifest = verify_embedded_source_identity()
        self.assertEqual(len(manifest.files), 2)

    def test_manifest_pins_both_controller_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            root = _fake_repo(Path(directory))
            manifest = build_source_manifest(root)
            self.assertEqual(manifest.controller, "aaps-autoisf-ukf3426")
            self.assertEqual(
                [item.path for item in manifest.files],
                [p.as_posix() for p in AUTOISF_SOURCES],
            )
            self.assertTrue(all(len(item.sha256) == 64 for item in manifest.files))

    def test_manifest_changes_when_controller_source_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = _fake_repo(Path(directory))
            before = build_source_manifest(root)
            (root / AUTOISF_SOURCES[0]).write_text("changed\n", encoding="utf-8")
            after = build_source_manifest(root)
            self.assertNotEqual(before.files[0].sha256, after.files[0].sha256)

    def test_process_oracle_rejects_adapter_built_from_other_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = _fake_repo(Path(directory))
            adapter = root / "adapter.py"
            adapter.write_text(
                "import json,sys\n"
                "request=json.load(sys.stdin)\n"
                "print(json.dumps({'source_sha256': {}, 'results': "
                "[{'ok': True, 'rt': {}} for _ in request['requests']]}))\n",
                encoding="utf-8",
            )
            oracle = AutoIsfProcessOracle([sys.executable, str(adapter)], repo_root=root)
            with self.assertRaisesRegex(AutoIsfSourceError, "source hashes"):
                oracle.evaluate([{"cycle": 1}])


if __name__ == "__main__":
    unittest.main()
