"""Diagnostics orchestration: a PullResult -> a DiagnosticsReport.

Diagnostics are outcome-based and honest, so they run for **every** variant, including
those the replay oracle cannot touch — they are the value delivered before (and without)
the oracle. The variant verdict is attached for context and to remind downstream layers
that prescriptive/replay steps are gated by advisability.
"""

from __future__ import annotations

from typing import Any

from ingestion.pull import PullResult

from .glycemia import glycemia_findings, summarise
from .models import DiagnosticsReport
from .patterns import nocturnal_hypos, predicted_vs_realised, smb_high_iob_overnight
from .sanity import _active_profile, profile_findings


def _timezone(pull: PullResult) -> str | None:
    p = _active_profile(pull.profiles)
    return p.timezone if p else None


def run_diagnostics(pull: PullResult, variant: dict[str, Any] | None = None) -> DiagnosticsReport:
    days = max((pull.end_ms - pull.start_ms) / 86_400_000, 1e-9)
    tz = _timezone(pull)

    summary = summarise(pull.entries, days)
    findings = []
    findings += glycemia_findings(summary)
    findings += profile_findings(pull.profiles, pull.treatments, days)
    findings += smb_high_iob_overnight(pull.treatments, pull.devicestatus, pull.entries, tz)
    findings += predicted_vs_realised(pull.devicestatus, pull.entries)
    findings += nocturnal_hypos(pull.entries, tz)

    return DiagnosticsReport(
        start_ms=pull.start_ms,
        end_ms=pull.end_ms,
        glycemia=summary,
        findings=findings,
        variant=variant,
    )
