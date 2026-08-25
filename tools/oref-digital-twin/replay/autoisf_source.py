"""Source identity and process boundary for the AAPS AutoISF controller.

The UKF3426 controller is not the stock oref0 JavaScript function.  Its decision is
split between ``OpenAPSAutoISFPlugin.kt`` (state/input preparation) and
``DetermineBasalAutoISF.kt`` (the final dosing calculation).  A replay is therefore
valid only when it is backed by an adapter built from those exact sources.

This module deliberately does not translate the dosing algorithm into Python.  It
pins the source and provides the JSON process protocol used by the future Kotlin
adapter, so callers fail closed rather than accidentally using the stock oracle.
"""

from __future__ import annotations

import hashlib
import json
import re
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Sequence


AUTOISF_SOURCES = (
    Path("plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAutoISF/DetermineBasalAutoISF.kt"),
    Path("plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAutoISF/OpenAPSAutoISFPlugin.kt"),
)
IDENTITY_SOURCE = Path(
    "plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAutoISF/AutoIsfReplaySourceIdentity.kt"
)


class AutoIsfSourceError(RuntimeError):
    """The requested AutoISF controller source cannot be identified safely."""


class AutoIsfOracleUnavailable(RuntimeError):
    """No executable adapter for the pinned AutoISF source is available."""


@dataclass(frozen=True)
class SourceFile:
    path: str
    sha256: str
    bytes: int


@dataclass(frozen=True)
class AutoIsfSourceManifest:
    controller: str
    source_root: str
    files: tuple[SourceFile, ...]

    def to_dict(self) -> dict:
        result = asdict(self)
        result["files"] = [asdict(item) for item in self.files]
        return result


def default_android_repo_root() -> Path:
    """Return the Android repository containing ``tools/oref-digital-twin``."""
    return Path(__file__).resolve().parents[3]


def build_source_manifest(repo_root: Path | None = None) -> AutoIsfSourceManifest:
    root = (repo_root or default_android_repo_root()).resolve()
    files: list[SourceFile] = []
    for relative in AUTOISF_SOURCES:
        source = root / relative
        if not source.is_file():
            raise AutoIsfSourceError(f"required AutoISF source is missing: {source}")
        payload = source.read_bytes()
        files.append(SourceFile(
            path=relative.as_posix(),
            sha256=hashlib.sha256(payload).hexdigest(),
            bytes=len(payload),
        ))
    return AutoIsfSourceManifest(
        controller="aaps-autoisf-ukf3426",
        source_root=str(root),
        files=tuple(files),
    )


def verify_embedded_source_identity(repo_root: Path | None = None) -> AutoIsfSourceManifest:
    """Verify the hashes embedded into Android replay traces match the working sources."""
    root = (repo_root or default_android_repo_root()).resolve()
    identity_path = root / IDENTITY_SOURCE
    if not identity_path.is_file():
        raise AutoIsfSourceError(f"replay source identity is missing: {identity_path}")
    text = identity_path.read_text(encoding="utf-8")
    names = ("DETERMINE_BASAL_SHA256", "OPENAPS_PLUGIN_SHA256")
    embedded: list[str] = []
    for name in names:
        match = re.search(rf'const val {name}\s*=\s*"([0-9a-f]{{64}})"', text)
        if not match:
            raise AutoIsfSourceError(f"replay source identity has no valid {name}")
        embedded.append(match.group(1))
    manifest = build_source_manifest(root)
    actual = [item.sha256 for item in manifest.files]
    if embedded != actual:
        raise AutoIsfSourceError(
            "embedded replay source identity is stale; regenerate it before building the APK"
        )
    return manifest


class AutoIsfProcessOracle:
    """Call an executable Kotlin adapter that implements the AutoISF JSON protocol.

    The adapter must accept ``{"manifest": ..., "requests": [...]}`` on stdin and
    return the same per-cycle result envelope as :class:`replay.OrefOracle`.  The
    adapter must echo ``source_sha256`` for both source files; otherwise its result is
    rejected as coming from a different controller build.
    """

    def __init__(self, command: Sequence[str], *, repo_root: Path | None = None,
                 timeout_s: float = 60.0):
        if not command:
            raise AutoIsfOracleUnavailable("AutoISF adapter command is empty")
        self.command = tuple(command)
        self.manifest = build_source_manifest(repo_root)
        self.timeout_s = timeout_s

    def evaluate(self, requests: list[dict]) -> list[dict]:
        if not requests:
            return []
        try:
            proc = subprocess.run(
                self.command,
                input=json.dumps({"manifest": self.manifest.to_dict(), "requests": requests}),
                capture_output=True,
                text=True,
                timeout=self.timeout_s,
                cwd=self.manifest.source_root,
            )
        except (FileNotFoundError, PermissionError) as exc:
            raise AutoIsfOracleUnavailable(
                f"AutoISF adapter is unavailable: {self.command[0]}"
            ) from exc
        except subprocess.TimeoutExpired as exc:
            raise AutoIsfOracleUnavailable("AutoISF adapter timed out") from exc

        if proc.returncode != 0 or not proc.stdout.strip():
            raise AutoIsfOracleUnavailable(
                f"AutoISF adapter failed: {(proc.stderr or 'no output')[:400]}"
            )
        payload = json.loads(proc.stdout)
        expected = {item.path: item.sha256 for item in self.manifest.files}
        if payload.get("source_sha256") != expected:
            raise AutoIsfSourceError(
                "AutoISF adapter source hashes do not match the current UKF3426 sources"
            )
        results = payload.get("results")
        if not isinstance(results, list) or len(results) != len(requests):
            raise AutoIsfSourceError("AutoISF adapter returned an invalid result envelope")
        return results

    def enacted(self, requests: list[dict]) -> list[dict | None]:
        return [item.get("rt") if item.get("ok") else None for item in self.evaluate(requests)]
