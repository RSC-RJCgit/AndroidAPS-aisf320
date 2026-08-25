"""Deterministic profile sanity checks (DESIGN §6, layer 1).

Hard-limit style checks on the active profile plus a rough daily-insulin estimate. Cheap,
safe, no statistics. These flag values that are implausible or internally contradictory;
they do not prescribe replacements.
"""

from __future__ import annotations

from typing import Iterable

from ingestion.models import ProfileSnapshot, Treatment

from .models import Finding, Severity

# plausibility bounds
DIA_MIN_H = 5.0                 # oref recommends >= 5h for modern rapid analogues
TARGET_MIN, TARGET_MAX = 70, 180
ISF_MIN, ISF_MAX = 5, 500       # mg/dL per U
CR_MIN, CR_MAX = 2, 150         # g per U


def _active_profile(profiles: list[ProfileSnapshot]) -> ProfileSnapshot | None:
    if not profiles:
        return None
    return max(profiles, key=lambda p: (p.valid_from_ms or 0))


def _daily_basal_u(p: ProfileSnapshot) -> float | None:
    blocks = sorted(p.basal, key=lambda b: b.seconds_from_midnight)
    if not blocks:
        return None
    total = 0.0
    for i, b in enumerate(blocks):
        start = b.seconds_from_midnight
        end = blocks[i + 1].seconds_from_midnight if i + 1 < len(blocks) else 86400
        total += b.value * (end - start) / 3600.0
    return round(total, 2)


def profile_findings(profiles: list[ProfileSnapshot], treatments: Iterable[Treatment],
                     days: float) -> list[Finding]:
    out: list[Finding] = []
    p = _active_profile(profiles)
    if p is None:
        out.append(Finding(
            key="no_profile",
            severity=Severity.WARNING,
            title="No profile available",
            detail="No Nightscout profile document was found — basal/ISF/CR/target checks "
                   "cannot run, and the replay oracle would have no profile to feed.",
            category="sanity",
        ))
        return out

    if p.dia_h is not None and p.dia_h < DIA_MIN_H:
        out.append(Finding(
            key="dia_short",
            severity=Severity.WARNING,
            title="DIA shorter than recommended",
            detail=f"DIA is {p.dia_h}h; oref expects >= {DIA_MIN_H}h for modern rapid analogues. "
                   "A short DIA under-counts tail IOB and can drive over-correction.",
            category="sanity",
            evidence={"dia_h": p.dia_h},
        ))

    # contradictory targets
    for lo, hi in zip(p.target_low_mgdl, p.target_high_mgdl):
        if lo.value > hi.value:
            out.append(Finding(
                key="target_inverted",
                severity=Severity.CRITICAL,
                title="Target low above target high",
                detail=f"A target block has low {round(lo.value)} > high {round(hi.value)} mg/dL.",
                category="sanity",
                evidence={"low_mgdl": lo.value, "high_mgdl": hi.value},
            ))
            break

    def _range_flag(blocks, lo, hi, key, label, unit):
        bad = [round(b.value, 1) for b in blocks if not (lo <= b.value <= hi)]
        if bad:
            out.append(Finding(
                key=key,
                severity=Severity.WARNING,
                title=f"{label} outside plausible range",
                detail=f"{label} value(s) {bad} {unit} fall outside {lo}-{hi} {unit}.",
                category="sanity",
                evidence={"values": bad, "min": lo, "max": hi},
            ))

    _range_flag(p.target_low_mgdl + p.target_high_mgdl, TARGET_MIN, TARGET_MAX,
                "target_out_of_range", "Target", "mg/dL")
    _range_flag(p.isf_mgdl, ISF_MIN, ISF_MAX, "isf_out_of_range", "ISF", "mg/dL/U")
    _range_flag(p.carb_ratio, CR_MIN, CR_MAX, "cr_out_of_range", "Carb ratio", "g/U")

    if any(b.value < 0 for b in p.basal):
        out.append(Finding(
            key="basal_negative",
            severity=Severity.CRITICAL,
            title="Negative basal rate",
            detail="A basal block has a negative rate — the profile is malformed.",
            category="sanity",
        ))

    # rough daily-insulin picture (bolus+SMB from treatments; basal from profile)
    daily_basal = _daily_basal_u(p)
    bolus_total = sum(t.insulin_u for t in treatments if t.insulin_u)
    daily_bolus = round(bolus_total / days, 1) if days > 0 else None
    if daily_basal is not None or daily_bolus is not None:
        tdd = None
        if daily_basal is not None and daily_bolus is not None:
            tdd = round(daily_basal + daily_bolus, 1)
        out.append(Finding(
            key="daily_insulin",
            severity=Severity.INFO,
            title="Estimated daily insulin",
            detail=(f"~{tdd} U/day estimated (profile basal ~{daily_basal} U + bolus/SMB "
                    f"~{daily_bolus} U/day). Rough: basal is the scheduled profile, not "
                    "delivered basal, and temp basals are not integrated."),
            category="sanity",
            evidence={"daily_basal_u": daily_basal, "daily_bolus_u": daily_bolus, "tdd_est_u": tdd},
        ))
    return out
