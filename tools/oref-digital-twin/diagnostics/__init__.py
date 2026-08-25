"""Diagnostics: glycaemic summary, safety-floor checks, profile sanity, pattern detection.

Public surface:
    run_diagnostics, DiagnosticsReport, Finding, Severity, GlycemicSummary
"""

from .models import DiagnosticsReport, Finding, GlycemicSummary, Severity
from .report import run_diagnostics

__all__ = [
    "run_diagnostics",
    "DiagnosticsReport",
    "Finding",
    "Severity",
    "GlycemicSummary",
]
