"""Diagnostics output types.

A `Finding` is a single observation — descriptive, never prescriptive. Diagnostics
surface what happened and what is risky; they do **not** emit "set X to Y" (that is the
gated recommendation layer, DESIGN §7). A finding may point at a lever to discuss with a
clinician, but it states an observation with numbers, not an instruction.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from enum import Enum
from typing import Any


class Severity(Enum):
    INFO = "info"
    WARNING = "warning"
    CRITICAL = "critical"

    @property
    def rank(self) -> int:
        return {"info": 0, "warning": 1, "critical": 2}[self.value]


@dataclass
class Finding:
    key: str                       # stable identifier, e.g. "tbr_lt54_over_limit"
    severity: Severity
    title: str
    detail: str
    category: str                  # glycaemia | safety | sanity | pattern
    evidence: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        d = asdict(self)
        d["severity"] = self.severity.value
        return d


@dataclass
class GlycemicSummary:
    n_readings: int
    days: float
    mean_mgdl: float | None
    mean_mmol: float | None
    gmi_pct: float | None
    cv_pct: float | None
    tir_70_180: float | None       # % in range
    tbr_lt70: float | None         # % below 70 (level 1+2)
    tbr_lt54: float | None         # % below 54 (level 2)
    tar_gt180: float | None
    tar_gt250: float | None
    ting_63_140: float | None      # time in narrow range (3.5-7.8 mmol)

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class DiagnosticsReport:
    start_ms: int
    end_ms: int
    glycemia: GlycemicSummary
    findings: list[Finding] = field(default_factory=list)
    variant: dict[str, Any] | None = None

    def sorted_findings(self) -> list[Finding]:
        return sorted(self.findings, key=lambda f: f.severity.rank, reverse=True)

    def counts(self) -> dict[str, int]:
        out = {"critical": 0, "warning": 0, "info": 0}
        for f in self.findings:
            out[f.severity.value] += 1
        return out

    def to_dict(self) -> dict[str, Any]:
        return {
            "range_ms": [self.start_ms, self.end_ms],
            "glycemia": self.glycemia.to_dict(),
            "counts": self.counts(),
            "findings": [f.to_dict() for f in self.sorted_findings()],
            "variant": self.variant,
        }
