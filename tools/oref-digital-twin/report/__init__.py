"""Report: deterministic template + the grounding gate + the Pyodide entrypoint.

Public surface:
    render_report, check_narrative, GateResult, build_report, abstracted_findings, gate_narrative
"""

from .browser import abstracted_findings, build_report, gate_narrative
from .grounding import GateResult, Violation, check_narrative
from .template import render_report

__all__ = [
    "render_report",
    "check_narrative",
    "GateResult",
    "Violation",
    "build_report",
    "abstracted_findings",
    "gate_narrative",
]
