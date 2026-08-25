"""Pyodide entrypoint: raw Nightscout JSON -> report, all client-side.

The browser does the Nightscout `fetch` (token + CORS stay on the device) and hands the
raw arrays here. This runs the whole read-only pipeline — normalise, classify variant,
diagnose, optionally run decision-level counterfactuals via real oref0 (in the browser),
and render the deterministic report — in Pyodide.

`abstracted_findings` is the ONLY thing that may be sent to the narration Worker.
"""

from __future__ import annotations

import json
import re
from typing import Any, Callable

from diagnostics import run_diagnostics
from ingestion.models import ProfileSnapshot
from ingestion.pull import pull_from_raw
from replay import OrefOracle, apply_delta, from_cycle, run_counterfactual
from variant import detect_variant

from .grounding import check_narrative
from .template import render_report

MAX_CYCLES = 400                       # cap oref calls for browser responsiveness

# maxIOB parsing modelled on the Boost analyser (`maxIOB: ?([0-9.,]+)`), generalised:
#   - case-insensitive; "maxIOB" | "max_iob" | "max iob"
#   - separator ":" | "=" | JSON quote+colon | bare space
#   - decimal "." OR "," (European locale / some AAPS builds) -> normalised to "."
# Covers AAPS console/reason ("maxIOB: 8.0", "maxIOB 1,0") and Trio/oref JSON ("max_iob":8).
_MAXIOB_RE = re.compile(r"max[\s_]?iob[\"']?\s*[:=]?\s*([0-9]+(?:[.,][0-9]+)?)", re.IGNORECASE)


def _parse_max_iob(text: str | None) -> float | None:
    if not text:
        return None
    m = _MAXIOB_RE.search(text)
    if not m:
        return None
    try:
        return float(m.group(1).replace(",", "."))
    except ValueError:
        return None


def _active_profile(profiles: list[ProfileSnapshot]) -> ProfileSnapshot | None:
    return max(profiles, key=lambda p: (p.valid_from_ms or 0)) if profiles else None


def infer_settings(pull) -> tuple[dict[str, Any], list[str]]:
    """Best-effort replay settings from devicestatus + treatments (not in the NS profile).

    max_iob is read from the most recent reason string ("maxIOB 6.0"); SMB is inferred from
    whether SMBs were actually delivered. Both are flagged as inferred.
    """
    notes: list[str] = []
    max_iob = None
    for c in reversed(pull.devicestatus):          # most recent first
        # search the reason AND the whole openaps blob (the value may live in a nested
        # field, a console line, or the reason text depending on AAPS/Trio build).
        haystack = c.reason or ""
        if c.raw_openaps:
            haystack += " " + json.dumps(c.raw_openaps)
        max_iob = _parse_max_iob(haystack)
        if max_iob is not None:
            break
    if max_iob is None:
        notes.append("Could not infer max_iob from devicestatus — counterfactuals skipped.")
    enable_smb = any(t.is_smb for t in pull.treatments)
    return {"max_iob": max_iob, "enable_smb": enable_smb, "max_smb_minutes": 30}, notes


def _default_deltas(settings: dict[str, Any], profile: ProfileSnapshot | None) -> list[tuple[str, dict]]:
    """Illustrative, conservative 'what if' levers computed from the user's baseline."""
    out: list[tuple[str, dict]] = []
    max_iob = settings.get("max_iob")
    if max_iob and max_iob > 1.5:
        lowered = round(max_iob - 1.0, 1)
        out.append((f"max_iob {max_iob} → {lowered}", {"max_iob": lowered}))
    if profile and profile.target_low_mgdl and profile.target_high_mgdl:
        base = (profile.target_low_mgdl[0].value + profile.target_high_mgdl[0].value) / 2.0
        raised = round(base + 18.0, 0)             # ~ +1 mmol/L
        out.append((f"target {round(base)} → {round(raised)} mg/dL", {"target_bg": raised}))
    return out


def _run_counterfactuals(pull, settings, profile, runner, deltas) -> tuple[list[dict], dict]:
    from ingestion.models import GlucoseReading

    # BG for glucose_status: real CGM plus each cycle's own bg (devicestatus is dense), so a
    # sparse entries feed doesn't starve request-building.
    merged_entries = list(pull.entries) + [
        GlucoseReading(ts_ms=c.ts_ms, sgv_mgdl=c.bg_mgdl)
        for c in pull.devicestatus if c.bg_mgdl is not None
    ]
    cycles = pull.devicestatus[-MAX_CYCLES:]
    stats = {"considered": len(cycles), "no_iob": 0, "no_bg": 0, "no_request": 0, "built": 0}
    requests, ts = [], []
    for c in cycles:
        if c.iob is None:
            stats["no_iob"] += 1
            continue
        if c.bg_mgdl is None:
            stats["no_bg"] += 1
            continue
        req, _w = from_cycle(c, profile, merged_entries, settings)
        if req is None:
            stats["no_request"] += 1
            continue
        requests.append(req)
        ts.append(c.ts_ms)
    stats["built"] = len(requests)
    if not requests:
        return [], stats
    oracle = OrefOracle(runner=runner)
    # probe baseline AND altered (first lever) to see which side errors, and capture the message
    base_probe = oracle.evaluate(requests)
    stats["oref_ok"] = sum(1 for r in base_probe if isinstance(r, dict) and r.get("ok"))
    err = next((r.get("error") for r in base_probe if isinstance(r, dict) and not r.get("ok")), None)
    stats["first_error"] = f"baseline: {err}" if err else None
    if deltas:
        alt_probe = oracle.evaluate([apply_delta(r, deltas[0][1]) for r in requests])
        stats["oref_ok_altered"] = sum(1 for r in alt_probe if isinstance(r, dict) and r.get("ok"))
        aerr = next((r.get("error") for r in alt_probe if isinstance(r, dict) and not r.get("ok")), None)
        if aerr and not stats["first_error"]:
            stats["first_error"] = f"altered({deltas[0][0]}): {aerr}"
    results = []
    for label, delta in deltas:
        cf = run_counterfactual(oracle, requests, delta, label=label, ts_of=ts)
        results.append(cf.to_dict())
    return results, stats


def _openaps_shape(pull) -> str:
    """Compact description of the most-recent cycle's openaps fields, for diagnosing parse gaps."""
    if not pull.devicestatus:
        return "no devicestatus"
    c = pull.devicestatus[-1]
    raw = c.raw_openaps or {}
    sug = list((raw.get("suggested") or {}).keys())
    ena = list((raw.get("enacted") or {}).keys())
    top = list(raw.keys())
    return (f"parsed bg={c.bg_mgdl} iob={c.iob}; openaps={top}; "
            f"suggested={sug[:20]}; enacted={ena[:20]}")


def build_report(
    raw: dict[str, Any],
    *,
    oref_runner: Callable | None = None,
    settings: dict[str, Any] | None = None,
    deltas: list[tuple[str, dict]] | None = None,
    max_iob_override: float | None = None,
) -> dict[str, Any]:
    """raw: {base_url, start_ms, end_ms, entries, treatments, devicestatus, profiles}.

    If `oref_runner` is provided (the browser injects one backed by oref0-in-WASM), run
    decision-level counterfactuals; otherwise produce the diagnostic report alone.
    """
    pull = pull_from_raw(
        raw.get("base_url", ""), int(raw["start_ms"]), int(raw["end_ms"]),
        raw.get("entries", []), raw.get("treatments", []),
        raw.get("devicestatus", []), raw.get("profiles", []),
    )
    verdict = detect_variant(pull.devicestatus, dropped_no_oref=pull.dropped.get("devicestatus", 0))
    diagnostics = run_diagnostics(pull, variant=verdict.to_dict())

    counterfactuals: list[dict] = []
    cf_note: str | None = None
    n_loop = len(pull.devicestatus)
    if oref_runner is None:
        cf_note = "Settings experiments skipped: the in-browser oref engine did not load."
    else:
        profile = _active_profile(pull.profiles)
        if settings is None:
            settings, _notes = infer_settings(pull)
        if max_iob_override is not None:
            settings = {**settings, "max_iob": float(max_iob_override)}
        if profile is None:
            cf_note = "Settings experiments skipped: no Nightscout profile found."
        elif settings.get("max_iob") is None:
            recent = next((c.reason for c in reversed(pull.devicestatus) if c.reason), None)
            snippet = (recent[:120] + "…") if recent else "(no reason text present)"
            cf_note = (f"Settings experiments skipped: max IOB isn't in your Nightscout data "
                       f"({n_loop} cycles). Enter Max IOB above to run them. "
                       f"Sample reason: {snippet}")
        else:
            deltas = deltas or _default_deltas(settings, profile)
            if not deltas:
                cf_note = "Settings experiments skipped: no applicable levers for this profile."
            else:
                try:
                    counterfactuals, stats = _run_counterfactuals(
                        pull, settings, profile, oref_runner, deltas)
                    built = stats.get("built", 0)
                    oref_ok = stats.get("oref_ok", 0)
                    if built < 20:
                        cf_note = (f"Only {built} of {stats.get('considered')} recent cycles were "
                                   f"usable (no-iob {stats.get('no_iob')}, no-bg {stats.get('no_bg')}, "
                                   f"no-request {stats.get('no_request')}). Diagnostic — "
                                   f"{_openaps_shape(pull)}")
                    elif oref_ok == 0 or stats.get("oref_ok_altered") == 0:
                        counterfactuals = []
                        cf_note = (f"oref evaluated baseline {oref_ok}/{built}, "
                                   f"altered {stats.get('oref_ok_altered')}/{built}. "
                                   f"First error: {stats.get('first_error')}")
                    elif counterfactuals and all(c.get("n_changed", 0) == 0 for c in counterfactuals):
                        cf_note = (f"Ran over {built} cycles ({oref_ok} evaluated): none of these "
                                   "levers changed the controller's decision — they don't bind at "
                                   "your settings.")
                except Exception as exc:  # surface, never break the report
                    counterfactuals = []
                    cf_note = f"Settings experiments errored: {type(exc).__name__}: {exc}"

    diag_d = diagnostics.to_dict()
    variant_d = verdict.to_dict()
    report_md = render_report(diag_d, variant_d, counterfactuals, counterfactual_note=cf_note)

    return {
        "report_md": report_md,
        "diagnostics": diag_d,
        "variant": variant_d,
        "counterfactuals": counterfactuals,
        "counterfactual_note": cf_note,
        "coverage": {"cgm": pull.cgm.to_dict(), "loop": pull.loop.to_dict(), "warnings": pull.warnings()},
    }


def settings_from_raw(raw: dict[str, Any]) -> dict[str, Any]:
    """Validate uploaded raw key/values (AAPS prefs / Trio JSON) into replay settings.

    Returns the replay-lever settings plus any validation issues / values needing
    confirmation, so the caller can feed `settings` into build_report and warn the user.
    """
    from settings import resolve_alias, validate

    v = validate(raw)
    # keys that mention IOB but map to nothing — helps diagnose an unknown build's naming
    unmapped_iob = [k for k in raw if "iob" in str(k).lower() and resolve_alias(k) is None]
    return {
        "settings": v.replay_settings(),
        "all_values": v.values,
        "needs_confirm": v.needs_confirm,
        "blocked": v.blocked,          # parsed but withheld from replay until confirmed
        "issues": [i.to_dict() for i in v.issues],
        "unmapped_iob_keys": unmapped_iob,
    }


def make_js_oref_runner():
    """A runner backed by globalThis.orefDetermine (oref0-in-WASM). Pyodide-only."""
    import js
    from pyodide.ffi import to_js

    def runner(requests: list[dict]) -> list[dict]:
        js_req = to_js(requests, dict_converter=js.Object.fromEntries)
        return js.orefDetermine(js_req).to_py()

    return runner


def _strip_timestamps(cfs: list[dict]) -> list[dict]:
    """Drop per-cycle wall-clock timestamps from counterfactual examples.

    `ts_ms` pins the exact minute a loop decision was taken. That is personal health data
    under the standard this project holds itself to (DESIGN §9), and the narrator has no
    use for it — it needs the magnitudes, not when they happened.
    """
    out = []
    for cf in cfs:
        c = dict(cf)
        c["examples"] = [{k: v for k, v in ex.items() if k != "ts_ms"}
                         for ex in cf.get("examples", [])]
        out.append(c)
    return out


def abstracted_findings(result: dict[str, Any]) -> dict[str, Any]:
    """The only payload allowed to leave the browser for narration — no raw data/token."""
    diag = result.get("diagnostics", {})
    return {
        "counts": diag.get("counts", {}),
        "glycemia": diag.get("glycemia", {}),
        "findings": diag.get("findings", []),
        "variant": result.get("variant", {}),
        "counterfactuals": _strip_timestamps(result.get("counterfactuals", [])),
    }


def gate_narrative(narrative: str, source: dict[str, Any]) -> dict[str, Any]:
    """Run the grounding gate; the browser shows the narrative only if passed is True."""
    return check_narrative(narrative, source).to_dict()
