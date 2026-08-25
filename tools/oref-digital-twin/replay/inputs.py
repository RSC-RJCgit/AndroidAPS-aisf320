"""Reconstruct oref determine-basal requests from normalised data.

This is the honest limit of devicestatus-based replay: the loop logs its *decision*, not
every input. So reconstruction is best-effort and each request carries fidelity warnings.
Two inputs cannot be recovered faithfully from devicestatus alone and are approximated:

  * `currenttemp` — the temp basal running at decision time (assumed none);
  * insulin `activity` — the IOB curve's instantaneous activity (assumed 0), which
    degrades bgi/eventualBG.

High-fidelity replay recomputes IOB (activity included) and glucose_status from raw
entries/treatments via oref0's own iob/glucose libs — a documented follow-up. What IS
faithful here: the profile (from the Nightscout profile + the user's settings) and the
counterfactual *diff*, since the same approximations apply to baseline and altered runs,
so they cancel in the delta.
"""

from __future__ import annotations

from datetime import datetime, timezone

from ingestion.models import DeviceStatusCycle, GlucoseReading, ProfileSnapshot

# oref profile fields that must come from settings (not the Nightscout profile)
REQUIRED_SETTINGS = ("max_iob",)


def _seconds_of_day(ts_ms: int, tz: str | None) -> int:
    tzinfo = timezone.utc
    if tz:
        try:
            from zoneinfo import ZoneInfo

            tzinfo = ZoneInfo(tz)
        except Exception:
            tzinfo = timezone.utc
    dt = datetime.fromtimestamp(ts_ms / 1000, tz=tzinfo)
    return dt.hour * 3600 + dt.minute * 60 + dt.second


def _block_value_at(blocks, sod: int, default: float | None) -> float | None:
    if not blocks:
        return default
    ordered = sorted(blocks, key=lambda b: b.seconds_from_midnight)
    chosen = ordered[0].value
    for b in ordered:
        if b.seconds_from_midnight <= sod:
            chosen = b.value
        else:
            break
    return chosen


def build_glucose_status(entries: list[GlucoseReading], at_ms: int) -> dict | None:
    """oref glucose_status from the CGM stream around `at_ms` (oref field names)."""
    pts = sorted((r.ts_ms, r.sgv_mgdl) for r in entries if r.sgv_mgdl is not None and r.ts_ms <= at_ms)
    if not pts:
        return None
    cur_ts, cur = pts[-1]

    def at_offset(minutes: int) -> float | None:
        target = cur_ts - minutes * 60_000
        best, best_d = None, 4 * 60_000  # within 4 min
        for ts, v in reversed(pts):
            d = abs(ts - target)
            if d <= best_d:
                best, best_d = v, d
            if ts < target - best_d:
                break
        return best

    g5, g15, g45 = at_offset(5), at_offset(15), at_offset(45)
    delta = round(cur - g5, 1) if g5 is not None else 0.0
    short_avg = round((cur - g15) / 3.0, 1) if g15 is not None else delta
    long_avg = round((cur - g45) / 9.0, 1) if g45 is not None else short_avg
    return {
        "glucose": cur,
        "delta": delta,
        "short_avgdelta": short_avg,
        "long_avgdelta": long_avg,
        "date": cur_ts,
    }


def build_profile(snapshot: ProfileSnapshot, settings: dict, at_ms: int) -> tuple[dict, list[str]]:
    """Assemble an oref profile from the Nightscout profile + the user's settings.

    Returns (profile, warnings). Missing required settings are reported, not guessed.
    """
    warnings: list[str] = []
    tz = snapshot.timezone
    sod = _seconds_of_day(at_ms, tz)

    basal = _block_value_at(snapshot.basal, sod, None)
    sens = _block_value_at(snapshot.isf_mgdl, sod, None)
    cr = _block_value_at(snapshot.carb_ratio, sod, None)
    low = _block_value_at(snapshot.target_low_mgdl, sod, None)
    high = _block_value_at(snapshot.target_high_mgdl, sod, None)
    target = None
    if low is not None and high is not None:
        target = round((low + high) / 2.0, 1)

    for name, val in (("basal", basal), ("sens", sens), ("carb_ratio", cr), ("target", target)):
        if val is None:
            warnings.append(f"profile is missing {name} — replay for this cycle is unreliable.")

    for key in REQUIRED_SETTINGS:
        if settings.get(key) is None:
            warnings.append(f"settings missing '{key}' — required for faithful replay.")

    max_basal = settings.get("max_basal", (basal or 0.0) * 4)
    # Clamp exactly as replay.settings_delta does. Otherwise a negative baseline is compared
    # against a clamped altered run and the counterfactual diff is meaningless while still
    # rendering as a real delivery delta.
    max_iob = settings.get("max_iob")
    if max_iob is not None and float(max_iob) < 0:
        warnings.append(f"max_iob {max_iob} is negative — clamped to 0 for replay.")
        max_iob = 0.0
    if max_basal is not None and float(max_basal) < 0:
        warnings.append(f"max_basal {max_basal} is negative — clamped to 0 for replay.")
        max_basal = 0.0
    profile = {
        "dia": snapshot.dia_h or 6.0,
        "current_basal": basal or 0.0,
        "max_basal": max_basal,
        "max_daily_basal": basal or 0.0,
        "max_daily_safety_multiplier": settings.get("max_daily_safety_multiplier", 3),
        "current_basal_safety_multiplier": settings.get("current_basal_safety_multiplier", 4),
        "max_iob": max_iob,
        "sens": sens,
        "carb_ratio": cr,
        "min_bg": low if low is not None else target,
        "max_bg": high if high is not None else target,
        "target_bg": target,
        "min_5m_carbimpact": settings.get("min_5m_carbimpact", 8),
        "type": "current",
        "enableSMB_always": bool(settings.get("enable_smb", False)),
        "enableSMB_with_COB": bool(settings.get("enable_smb", False)),
        "enableSMB_after_carbs": bool(settings.get("enable_smb", False)),
        "enableSMB_uam": bool(settings.get("enable_smb_uam", settings.get("enable_smb", False))),
        "maxSMBBasalMinutes": settings.get("max_smb_minutes", 30),
        "maxUAMSMBBasalMinutes": settings.get("max_uam_minutes", 30),
    }

    # These defaults are permissive: absent an SMB cap we assume 30 minutes, and absent a
    # max basal we assume 4x the scheduled rate. If SMB is on and the real caps are unknown,
    # the replay may allow more insulin than the user's own configuration would. Say so —
    # a substituted dosing bound must never pass silently.
    if profile["enableSMB_always"] or profile["enableSMB_uam"]:
        for friendly, oref_field in (("max_smb_minutes", "maxSMBBasalMinutes"),
                                     ("max_uam_minutes", "maxUAMSMBBasalMinutes")):
            if settings.get(friendly) is None:
                warnings.append(f"{friendly} unknown — assumed {profile[oref_field]} min; "
                                f"replay may permit more SMB than your settings allow.")
    if settings.get("max_basal") is None:
        warnings.append(f"max_basal unknown — assumed {max_basal} U/h (4x scheduled basal).")

    return profile, warnings


def _num0(v: Any) -> float:
    return float(v) if isinstance(v, (int, float)) and not isinstance(v, bool) else 0.0


def _iob_data_from_cycle(cycle: DeviceStatusCycle) -> tuple[dict, bool]:
    """Real iob_data (iob, activity, basaliob, bolusiob) from openaps.iob when present.

    Returns (iob_data, activity_known). Using the logged iob_data — activity included —
    makes bgi/eventualBG faithful instead of assuming activity 0.
    """
    raw = cycle.raw_openaps or {}
    ib = raw.get("iob")
    if isinstance(ib, list) and ib:
        ib = ib[0]
    if isinstance(ib, dict) and ib.get("iob") is not None:
        return ({
            "iob": _num0(ib.get("iob")),
            "activity": _num0(ib.get("activity")),
            "basaliob": _num0(ib.get("basaliob")),
            "bolusiob": _num0(ib.get("bolusiob")),
            "time": cycle.ts_ms,
        }, True)
    return ({"iob": cycle.iob or 0.0, "activity": 0.0, "basaliob": 0.0, "bolusiob": 0.0,
             "time": cycle.ts_ms}, False)


# fidelity warning inherent to devicestatus-based reconstruction
_INHERENT_FIDELITY = [
    "currenttemp unknown from devicestatus — assumed none.",
]


def from_cycle(
    cycle: DeviceStatusCycle,
    snapshot: ProfileSnapshot,
    entries: list[GlucoseReading],
    settings: dict,
    *,
    micro_bolus_allowed: bool = True,
) -> tuple[dict | None, list[str]]:
    """Build a determine-basal request for one cycle. Returns (request|None, warnings)."""
    warnings: list[str] = []
    gs = build_glucose_status(entries, cycle.ts_ms)
    if gs is None:
        gs = ({"glucose": cycle.bg_mgdl, "delta": 0.0, "short_avgdelta": 0.0,
               "long_avgdelta": 0.0, "date": cycle.ts_ms} if cycle.bg_mgdl else None)
        warnings.append("no CGM around this cycle — glucose_status approximated from devicestatus.")
    if gs is None:
        return None, warnings + ["no usable glucose for this cycle."]

    profile, pwarn = build_profile(snapshot, settings, cycle.ts_ms)
    warnings += pwarn
    if profile["max_iob"] is None or profile["target_bg"] is None:
        return None, warnings + ["cannot build a faithful profile (missing max_iob/target)."]

    warnings += _INHERENT_FIDELITY
    iob_data, activity_known = _iob_data_from_cycle(cycle)
    if not activity_known:
        warnings.append("insulin activity unknown — assumed 0; bgi/eventualBG approximate.")
    request = {
        "glucose_status": gs,
        "currenttemp": {"duration": 0, "rate": 0, "temp": "absolute"},
        "iob_data": iob_data,
        "profile": profile,
        "autosens_data": {"ratio": cycle.sensitivity_ratio or 1.0},
        "meal_data": {"carbs": 0, "mealCOB": cycle.cob or 0},
        "microBolusAllowed": micro_bolus_allowed,
        "currentTime": cycle.ts_ms,
    }
    return request, warnings
