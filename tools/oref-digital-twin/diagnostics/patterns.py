"""Out-of-sample pattern detection (DESIGN §6, layer 2).

These are honest, association-only diagnostics — they describe observed relationships in
the record and require no counterfactual. Attribution names a pattern; it does not prove
causation (proximate != causal), and each finding says so where it matters.

Patterns implemented:
  1. SMB into a high-IOB overnight tail, and whether a low followed  — the repeated
     source of lows in this domain (adding insulin into recovering highs / overnight
     bounces).
  2. Predicted vs realised BG at a fixed horizon — systematic bias in the model.
  3. Nocturnal hypoglycaemia episodes.
"""

from __future__ import annotations

from ingestion.models import DeviceStatusCycle, GlucoseReading, Treatment

from .models import Finding, Severity
from .series import TimeIndex, local_hour_and_date, percentile

NIGHT_START, NIGHT_END = 0, 6         # local hours [00:00, 06:00)
LOW_MGDL = 70
MIN_OVERNIGHT_CYCLES = 20             # need enough data for a within-subject percentile
HIGH_IOB_PCTL = 75.0                  # "high IOB" = >= user's own overnight p75
SMB_MATCH_TOL_MS = 6 * 60_000         # match an SMB to a cycle within +-6 min
FOLLOW_WINDOW_MS = 120 * 60_000       # look 2h ahead for a low
PRED_HORIZON_MIN = 30                 # predicted vs realised horizon
PRED_TOL_MS = 6 * 60_000
HYPO_EPISODE_GAP_MS = 30 * 60_000     # >30 min apart => separate episodes


def _is_night(ts_ms: int, tz: str | None) -> bool:
    hour, _ = local_hour_and_date(ts_ms, tz)
    return NIGHT_START <= hour < NIGHT_END


def smb_high_iob_overnight(
    treatments: list[Treatment],
    cycles: list[DeviceStatusCycle],
    entries: list[GlucoseReading],
    tz: str | None,
) -> list[Finding]:
    overnight_iob = sorted(c.iob for c in cycles if c.iob is not None and _is_night(c.ts_ms, tz))
    if len(overnight_iob) < MIN_OVERNIGHT_CYCLES:
        return [Finding(
            key="smb_high_iob_insufficient",
            severity=Severity.INFO,
            title="Not enough overnight data for the high-IOB SMB check",
            detail=f"Only {len(overnight_iob)} overnight loop cycles with IOB — need "
                   f"{MIN_OVERNIGHT_CYCLES}. Skipping the within-subject high-IOB SMB pattern.",
            category="pattern",
        )]

    p75 = percentile(overnight_iob, HIGH_IOB_PCTL)
    iob_idx = TimeIndex((c.ts_ms, c.iob) for c in cycles if c.iob is not None)
    bg_idx = TimeIndex((r.ts_ms, r.sgv_mgdl) for r in entries if r.sgv_mgdl is not None)

    overnight_smbs = 0
    high_iob_smbs = 0
    followed_by_low = 0
    for t in treatments:
        if not (t.is_smb and t.insulin_u and t.insulin_u > 0):
            continue
        if not _is_night(t.ts_ms, tz):
            continue
        overnight_smbs += 1
        iob_at = iob_idx.nearest(t.ts_ms, SMB_MATCH_TOL_MS)
        if iob_at is None or iob_at < p75:
            continue
        high_iob_smbs += 1
        nadir = bg_idx.min_between(t.ts_ms, t.ts_ms + FOLLOW_WINDOW_MS)
        if nadir is not None and nadir < LOW_MGDL:
            followed_by_low += 1

    if high_iob_smbs == 0:
        return [Finding(
            key="smb_high_iob_none",
            severity=Severity.INFO,
            title="No high-IOB overnight SMBs detected",
            detail=f"Of {overnight_smbs} overnight SMBs, none fired at or above your overnight "
                   f"p75 IOB ({round(p75, 2)} U).",
            category="pattern",
        )]

    rate = round(100.0 * followed_by_low / high_iob_smbs, 1)
    sev = Severity.WARNING if followed_by_low > 0 else Severity.INFO
    return [Finding(
        key="smb_high_iob_overnight",
        severity=sev,
        title="Overnight SMBs at high IOB, with lows following",
        detail=(f"{high_iob_smbs} of {overnight_smbs} overnight SMBs fired at high IOB "
                f"(>= p75 {round(p75, 2)} U); {followed_by_low} ({rate}%) were followed by "
                f"BG < {LOW_MGDL} mg/dL within 2h. Association only — this names the pattern, "
                "it does not prove the SMB caused the low."),
        category="pattern",
        evidence={
            "overnight_smbs": overnight_smbs,
            "high_iob_smbs": high_iob_smbs,
            "followed_by_low": followed_by_low,
            "low_follow_rate_pct": rate,
            "iob_p75_u": round(p75, 2),
        },
    )]


def predicted_vs_realised(cycles: list[DeviceStatusCycle], entries: list[GlucoseReading]) -> list[Finding]:
    bg_idx = TimeIndex((r.ts_ms, r.sgv_mgdl) for r in entries if r.sgv_mgdl is not None)
    horizon_ms = PRED_HORIZON_MIN * 60_000
    idx = PRED_HORIZON_MIN // 5  # pred arrays are 5-min spaced
    errors: list[float] = []
    for c in cycles:
        pred = c.pred_iob
        if not pred or len(pred) <= idx:
            continue
        actual = bg_idx.nearest(c.ts_ms + horizon_ms, PRED_TOL_MS)
        if actual is None:
            continue
        errors.append(pred[idx] - actual)   # + => model predicted higher than reality

    if len(errors) < 30:
        return [Finding(
            key="pred_vs_realised_insufficient",
            severity=Severity.INFO,
            title="Not enough paired points for prediction accuracy",
            detail=f"Only {len(errors)} cycles had a {PRED_HORIZON_MIN}-min IOB prediction paired "
                   "with a realised BG. Skipping the prediction-bias check.",
            category="pattern",
        )]

    bias = round(sum(errors) / len(errors), 1)
    mae = round(sum(abs(e) for e in errors) / len(errors), 1)
    direction = "higher than realised" if bias > 0 else "lower than realised"
    sev = Severity.WARNING if abs(bias) >= 15 else Severity.INFO
    return [Finding(
        key="pred_vs_realised",
        severity=sev,
        title=f"{PRED_HORIZON_MIN}-min IOB prediction bias {bias:+} mg/dL",
        detail=(f"Over {len(errors)} cycles the IOB-only prediction ran {abs(bias)} mg/dL "
                f"{direction} on average (MAE {mae}). A large systematic bias points at model "
                "calibration; it is descriptive, not a dosing instruction."),
        category="pattern",
        evidence={"n": len(errors), "bias_mgdl": bias, "mae_mgdl": mae, "horizon_min": PRED_HORIZON_MIN},
    )]


def nocturnal_hypos(entries: list[GlucoseReading], tz: str | None) -> list[Finding]:
    lows = sorted(r.ts_ms for r in entries
                  if r.sgv_mgdl is not None and r.sgv_mgdl < LOW_MGDL and _is_night(r.ts_ms, tz))
    if not lows:
        return []
    episodes = 1
    for a, b in zip(lows, lows[1:]):
        if b - a > HYPO_EPISODE_GAP_MS:
            episodes += 1
    nights = {local_hour_and_date(ts, tz)[1] for ts in lows}
    return [Finding(
        key="nocturnal_hypos",
        severity=Severity.WARNING,
        title="Nocturnal hypoglycaemia episodes",
        detail=(f"{episodes} overnight (00:00-06:00) low episode(s) below {LOW_MGDL} mg/dL "
                f"across {len(nights)} night(s). Overnight lows are the domain's repeated risk; "
                "cross-check against overnight SMBs and IOB."),
        category="pattern",
        evidence={"episodes": episodes, "nights_with_lows": len(nights)},
    )]
