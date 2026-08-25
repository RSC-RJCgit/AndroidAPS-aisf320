"""Runs the Node WebCrypto round-trip self-test for the AAPS decryptor.

Skips when Node is unavailable, so the Python suite stays green in environments without it.
"""

import shutil
import subprocess
from pathlib import Path

import pytest

DECRYPT_DIR = Path(__file__).resolve().parents[1] / "decrypt"

pytestmark = pytest.mark.skipif(shutil.which("node") is None, reason="node not installed")


def test_aaps_decrypt_roundtrip_node():
    result = subprocess.run(
        ["node", "--test", "roundtrip.test.mjs"],
        cwd=str(DECRYPT_DIR), capture_output=True, text=True, timeout=120,
    )
    assert result.returncode == 0, f"node tests failed:\n{result.stdout}\n{result.stderr}"
    assert "pass 4" in result.stdout or "# pass 4" in result.stdout
