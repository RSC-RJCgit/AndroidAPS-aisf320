"""Glycaemic summary and consensus-target findings.

Percentages are of valid CGM readings (the standard approximation to % of time for a
CGM at regular cadence). Coverage is reported separately by ingestion, so a thin record
is visible rather than hidden. Thresholds follow the international consensus
(Battelino et al., 2019); the absolute time-below-range limits are the project's
non-negotiable safety floors and may only ever tighten (CLAUDE.md).
"""

from __future__ import annotations

import statistics
from typing import Iterable

from ingestion.models import GlucoseReading

from .models import Finding, GlycemicSummary, Severity
from .series import to_mmol

# thresholds (mg/dL)
VERY_LOW = 54
LOW = 70
IN_RANGE_HI = 180
VERY_HIGH = 250
TING_LO, TING_HI = 63, 140

# consensus targets / safety floors (percent)
TBR54_LIMIT = 1.0      # level-2 hypo: consensus < 1%  (safety floor)
TBR70_LIMIT = 4.0      # level-1+ hypo: consensus < 4% (safety floor)
TIR_TARGET = 70.0      # consensus > 70%
TAR250_LIMIT = 5.0     # consensus < 5%
CV_LIMIT = 36.0        # consensus <= 36%


def _pct(count: int, total: int) -> float | None:
    return round(100.0 * count / total, 1) if total else None


def summarise(readings: Iterable[GlucoseReading], days: float) -> GlycemicSummary:
    sgv = [r.sgv_mgdl for r in readings if r.sgv_mgdl is not None and r.sgv_mgdl > 0]
    n = len(sgv)
    if n == 0:
        return GlycemicSummary(0, days, None, None, None, None, None, None, None, None, None, None)

    mean = statistics.fmean(sgv)
    sd = statistics.pstdev(sgv) if n > 1 else 0.0
    return GlycemicSummary(
        n_readings=n,
        days=round(days, 1),
        mean_mgdl=round(mean, 1),
        mean_mmol=to_mmol(mean),
        gmi_pct=round(3.31 + 0.02392 * mean, 1),
        cv_pct=round(100.0 * sd / mean, 1) if mean else None,
        tir_70_180=_pct(sum(1 for v in sgv if LOW <= v <= IN_RANGE_HI), n),
        tbr_lt70=_pct(sum(1 for v in sgv if v < LOW), n),
        tbr_lt54=_pct(sum(1 for v in sgv if v < VERY_LOW), n),
        tar_gt180=_pct(sum(1 for v in sgv if v > IN_RANGE_HI), n),
        tar_gt250=_pct(sum(1 for v in sgv if v > VERY_HIGH), n),
        ting_63_140=_pct(sum(1 for v in sgv if TING_LO <= v <= TING_HI), n),
    )


def glycemia_findings(s: GlycemicSummary) -> list[Finding]:
    out: list[Finding] = []
    if s.n_readings == 0:
        out.append(Finding(
            key="no_cgm",
            severity=Severity.WARNING,
            title="No CGM data in window",
            detail="No valid glucose readings were found — glycaemic diagnostics cannot run.",
            category="glycaemia",
        ))
        return out

    # --- safety floors (may only tighten) ---
    if s.tbr_lt54 is not None and s.tbr_lt54 > TBR54_LIMIT:
        out.append(Finding(
            key="tbr_lt54_over_limit",
            severity=Severity.CRITICAL,
            title="Severe hypoglycaemia exposure above the 1% limit",
            detail=(f"{s.tbr_lt54}% of readings are below 54 mg/dL (3.0 mmol/L), above the "
                    f"{TBR54_LIMIT}% consensus safety limit. This is the priority to address."),
            category="safety",
            evidence={"tbr_lt54_pct": s.tbr_lt54, "limit_pct": TBR54_LIMIT},
        ))
    if s.tbr_lt70 is not None and s.tbr_lt70 > TBR70_LIMIT:
        out.append(Finding(
            key="tbr_lt70_over_limit",
            severity=Severity.WARNING,
            title="Time below 70 mg/dL above the 4% target",
            detail=(f"{s.tbr_lt70}% of readings are below 70 mg/dL (3.9 mmol/L), above the "
                    f"{TBR70_LIMIT}% consensus target."),
            category="safety",
            evidence={"tbr_lt70_pct": s.tbr_lt70, "limit_pct": TBR70_LIMIT},
        ))

    # --- other consensus targets (informative) ---
    if s.tir_70_180 is not None and s.tir_70_180 < TIR_TARGET:
        out.append(Finding(
            key="tir_below_target",
            severity=Severity.WARNING,
            title="Time in range below 70%",
            detail=f"Time in range (70-180 mg/dL) is {s.tir_70_180}%, below the {TIR_TARGET}% target.",
            category="glycaemia",
            evidence={"tir_pct": s.tir_70_180, "target_pct": TIR_TARGET},
        ))
    if s.cv_pct is not None and s.cv_pct > CV_LIMIT:
        out.append(Finding(
            key="cv_high",
            severity=Severity.WARNING,
            title="Glucose variability above 36%",
            detail=(f"Coefficient of variation is {s.cv_pct}%, above the {CV_LIMIT}% consensus "
                    "ceiling — high variability makes stable dosing harder and raises hypo risk."),
            category="glycaemia",
            evidence={"cv_pct": s.cv_pct, "limit_pct": CV_LIMIT},
        ))
    if s.tar_gt250 is not None and s.tar_gt250 > TAR250_LIMIT:
        out.append(Finding(
            key="tar_gt250_high",
            severity=Severity.INFO,
            title="Time above 250 mg/dL above 5%",
            detail=f"{s.tar_gt250}% of readings are above 250 mg/dL (13.9 mmol/L).",
            category="glycaemia",
            evidence={"tar_gt250_pct": s.tar_gt250, "limit_pct": TAR250_LIMIT},
        ))

    # --- a positive note when the record is clean ---
    if (s.tbr_lt54 is not None and s.tbr_lt54 <= TBR54_LIMIT
            and s.tbr_lt70 is not None and s.tbr_lt70 <= TBR70_LIMIT
            and s.tir_70_180 is not None and s.tir_70_180 >= TIR_TARGET):
        out.append(Finding(
            key="meets_consensus",
            severity=Severity.INFO,
            title="Meets consensus glycaemic targets",
            detail=(f"TIR {s.tir_70_180}%, TBR<70 {s.tbr_lt70}%, TBR<54 {s.tbr_lt54}% — within "
                    "consensus targets over this window."),
            category="glycaemia",
        ))
    return out
