"""Deterministic template report.

Generated purely from the structured findings — no LLM, no network. This is the report
the user always gets: it is the safety net behind the optional LLM narration, and it runs
client-side in Pyodide. Markdown out.

Inputs are plain dicts (the `.to_dict()` outputs), because that is what crosses the
JS/Python (Pyodide) boundary.
"""

from __future__ import annotations

from typing import Any

DISCLAIMER = (
    "_This is decision-support, not medical advice, and it is advisory-only — it never "
    "changes your loop or pump. Discuss any change with your clinician and trial it "
    "deliberately._"
)


def _fmt(v: Any, suffix: str = "") -> str:
    return "—" if v is None else f"{v}{suffix}"


def _glycemia_block(g: dict) -> list[str]:
    if not g or g.get("n_readings", 0) == 0:
        return ["## Glucose", "", "No CGM data in this window."]
    return [
        "## Glucose",
        "",
        f"- Readings: {g['n_readings']} over ~{_fmt(g.get('days'))} days",
        f"- Mean: {_fmt(g.get('mean_mgdl'))} mg/dL ({_fmt(g.get('mean_mmol'))} mmol/L), "
        f"GMI {_fmt(g.get('gmi_pct'), '%')}",
        f"- Time in range (70–180): {_fmt(g.get('tir_70_180'), '%')}",
        f"- Time below 70: {_fmt(g.get('tbr_lt70'), '%')}; below 54: {_fmt(g.get('tbr_lt54'), '%')}",
        f"- Time above 180: {_fmt(g.get('tar_gt180'), '%')}; above 250: {_fmt(g.get('tar_gt250'), '%')}",
        f"- Variability (CV): {_fmt(g.get('cv_pct'), '%')}",
    ]


_SEV_HEADING = {"critical": "### Critical", "warning": "### Worth attention", "info": "### Notes"}


def _findings_block(findings: list[dict]) -> list[str]:
    if not findings:
        return ["## Findings", "", "No findings."]
    lines = ["## Findings", ""]
    for sev in ("critical", "warning", "info"):
        group = [f for f in findings if f.get("severity") == sev]
        if not group:
            continue
        lines.append(_SEV_HEADING[sev])
        lines.append("")
        for f in group:
            lines.append(f"- **{f.get('title')}** — {f.get('detail')}")
        lines.append("")
    return lines


def _variant_block(variant: dict | None) -> list[str]:
    if not variant:
        return []
    advis = variant.get("advisability")
    note = {
        "full": "A modelled controller — settings counterfactuals are available.",
        "diagnosis_only": "Middleware or a fork is in play — findings only; no settings replay.",
        "out_of_scope": "The controller could not be classified — findings only.",
    }.get(advis, "")
    lines = [
        "## Your setup",
        "",
        f"- Detected: **{variant.get('variant')}** (confidence {_fmt(variant.get('confidence'))})",
        f"- {note}",
    ]
    for n in variant.get("notes", []):
        lines.append(f"- {n}")
    return lines


def _counterfactual_block(cfs: list[dict] | None) -> list[str]:
    if not cfs:
        return []
    lines = ["## Settings experiments (decision-level)", ""]
    for cf in cfs:
        lines.append(
            f"- **{cf.get('label')}**: over {cf.get('n_evaluated')} cycles, the controller's "
            f"decision changed on {cf.get('n_changed')}; net delivery change "
            f"{_fmt(cf.get('total_delta_u'), ' U')} (mean {_fmt(cf.get('mean_delta_u'), ' U')}/cycle)."
        )
    lines.append("")
    lines.append(f"_{cfs[0].get('caveat', '')}_")
    return lines


def render_report(
    diagnostics: dict,
    variant: dict | None = None,
    counterfactuals: list[dict] | None = None,
    counterfactual_note: str | None = None,
) -> str:
    """Render a full deterministic Markdown report from structured findings."""
    counts = diagnostics.get("counts", {})
    parts: list[str] = [
        "# oref digital twin — report",
        "",
        f"Findings: {counts.get('critical', 0)} critical, {counts.get('warning', 0)} to watch, "
        f"{counts.get('info', 0)} notes.",
        "",
    ]
    parts += _variant_block(variant)
    parts.append("")
    parts += _glycemia_block(diagnostics.get("glycemia", {}))
    parts.append("")
    parts += _findings_block(diagnostics.get("findings", []))
    if counterfactuals:
        parts += _counterfactual_block(counterfactuals)
        if counterfactual_note:
            parts += ["", f"_{counterfactual_note}_"]
    elif counterfactual_note:
        parts += ["", "## Settings experiments (decision-level)", "", f"_{counterfactual_note}_"]
    parts += ["", "---", "", DISCLAIMER]
    return "\n".join(parts)
