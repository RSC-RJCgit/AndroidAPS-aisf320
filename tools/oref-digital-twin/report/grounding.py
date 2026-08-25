"""The grounding gate: verify LLM narration against the source findings.

Deterministic, dependency-free, and runs client-side (Pyodide) — no second LLM. This is
the control that lets us fire findings at an LLM for a nicer report without trusting it:
the narration is only shown if it passes, otherwise the caller falls back to the
deterministic template.

Checks (all blocking):
  * ungrounded_number   — a figure in the narrative that is not present in the source.
  * prescription        — an imperative dosing instruction (the tool is advisory-only).
  * omitted_critical    — a CRITICAL finding missing from the narrative.
  * missing_caveat      — counterfactuals mentioned without the "decision-level, not BG" caveat.
  * missing_assoc_caveat— the SMB/low pattern narrated without its association-not-causation caveat.
"""

from __future__ import annotations

import json
import re
from dataclasses import asdict, dataclass, field
from typing import Any

# small integers that appear as ordinary prose ("within 2 hours") and need no grounding.
# The exemption is withdrawn when the number is attached to a dosing/glucose unit — "3
# units" is a dose, not prose, and must be grounded like any other figure.
_STRUCTURAL = {0.0, 1.0, 2.0, 3.0, 24.0}
_NUM_RE = re.compile(r"-?\d+(?:\.\d+)?")
_UNIT_AFTER_RE = re.compile(r"\s*(?:u\b|iu\b|units?\b|u/h\b|mg/dl\b|mmol)", re.IGNORECASE)

# Dosing directives — an advisory-only tool must not emit these. Targeted at second-person
# advice, clause-initial imperatives and *hedged* advice, but NOT descriptive
# counterfactuals ("lowering max IOB to 3 changed the decision" is a description).
_DOSE_NOUNS = (r"(?:basal|isf|sensitivity|carb\s*ratio|\bcr\b|target|max[\s_]?iob|smb|dose|"
               r"insulin|correction|units?|\bu\b)")
# bare stems only — the imperative mood. Gerunds are handled by the hedged pattern below,
# so that a descriptive "Lowering max IOB…" is not mistaken for an instruction.
_IMPERATIVE_VERB = r"(?:set|increase|decrease|raise|lower|reduce|adjust|change|bump|drop|cut)"
# any inflection, used only where a hedge or an explicit quantity already signals advice
_VERB_ANY = (r"(?:set|setting|increase|increasing|decrease|decreasing|raise|raising|lower|"
             r"lowering|reduce|reducing|adjust|adjusting|change|changing|bump|bumping|drop|"
             r"dropping|cut|cutting|run|running|add|adding|try|trying|use|using)")
_CHANGE_VERB = (r"(?:set|increase|increasing|decrease|decreasing|raise|raising|lower|lowering|"
                r"reduce|reducing|adjust|adjusting|bump|bumping|drop|dropping|cut|cutting|"
                r"add|adding|try|trying)")
_HEDGE = (r"(?:consider|considering|suggest\w*|recommend\w*|advis\w+|worth|sensible|perhaps|"
          r"maybe|you\s+may\s+want|you\s+might\s+want|it\s+would\s+be\s+\w+\s+to|"
          r"it\s+may\s+be\s+\w+\s+to|i'?d\s+\w+)")
_COMPARATIVE = r"(?:more|less|higher|lower|bigger|smaller|extra)"
_QTY_UNITS = r"\d+(?:[.,]\d+)?\s*(?:u\b|iu\b|units?\b)"
_PRESCRIPTION_RES = [
    # second person: "you should/could/need to ... <dose noun>"
    re.compile(rf"\byou\s+(?:should|could|ought to|need to|must|may want to|might want to)\b[^.]*\b{_DOSE_NOUNS}\b",
               re.IGNORECASE),
    # clause-initial imperative verb + (your) <dose noun>: "Set max IOB to 8", "Lower your ISF"
    re.compile(rf"(?:^|[.;:]\s+){_IMPERATIVE_VERB}\s+(?:your\s+)?{_DOSE_NOUNS}\b", re.IGNORECASE),
    # possessive directive with a value: "your max IOB to 8"
    re.compile(rf"\byour\s+{_DOSE_NOUNS}\b[^.]*\bto\s+-?\d", re.IGNORECASE),
    # hedged advice: "consider increasing …", "I'd suggest reducing basal", "it would be
    # sensible to run more insulin". A hedge plus an action verb (or a comparative) plus a
    # dosing noun or an explicit quantity, all inside one sentence.
    re.compile(rf"\b{_HEDGE}\b[^.]{{0,80}}?\b(?:{_VERB_ANY}|{_COMPARATIVE})\b[^.]{{0,40}}?"
               rf"(?:\b{_DOSE_NOUNS}\b|{_QTY_UNITS})", re.IGNORECASE),
    # explicit quantity directive regardless of hedging: "increase by 3 units", "add 2 units"
    re.compile(rf"\b{_CHANGE_VERB}\b[^.]{{0,25}}?{_QTY_UNITS}", re.IGNORECASE),
]

_CAVEAT_MARKERS = ("decision-level", "not the resulting", "not predict", "cannot predict",
                   "not a blood glucose", "not blood glucose", "resulting blood glucose")
_ASSOC_MARKERS = ("association", "not prove", "does not prove", "not causation", "not caused",
                  "correlation")


@dataclass
class Violation:
    kind: str
    detail: str

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class GateResult:
    passed: bool
    violations: list[Violation] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {"passed": self.passed, "violations": [v.to_dict() for v in self.violations]}


def _source_numbers(source: dict) -> set[float]:
    """Every number appearing anywhere in the source (numeric leaves AND inside strings)."""
    nums: set[float] = set()
    for tok in _NUM_RE.findall(json.dumps(source)):
        try:
            nums.add(float(tok))
        except ValueError:
            pass
    return nums


# A relative tolerance alone is far too loose on large source values (1% of an epoch-ms
# timestamp is ~1.8e10), so cap it. And only collapse to integers where a half-unit drift
# is immaterial: "62.4%" may be narrated as "62%", but a source 6.4 U must NOT ground a
# narrated 5.5 U.
_ABS_TOL_CAP = 5.0
_INT_ROUND_MIN = 10.0


def _is_grounded(n: float, allowed: set[float], *, structural_ok: bool = True) -> bool:
    if structural_ok and n in _STRUCTURAL:
        return True
    for a in allowed:
        if abs(a - n) <= min(max(0.05, 0.01 * abs(a)), _ABS_TOL_CAP):
            return True
        if abs(a) >= _INT_ROUND_MIN and abs(a - n) <= 0.5 and round(a) == round(n):
            return True
    return False


def _critical_keywords(finding: dict) -> list[str]:
    """Distinctive lowercase tokens from a critical finding's title, for omission checks."""
    title = (finding.get("title") or "").lower()
    words = re.findall(r"[a-z]{5,}", title)
    stop = {"above", "below", "limit", "range", "target", "value"}
    return [w for w in words if w not in stop] or words


def check_narrative(narrative: str, source: dict) -> GateResult:
    """Verify `narrative` is grounded in `source`. Returns a GateResult; passed=no violations."""
    violations: list[Violation] = []
    text = narrative or ""
    low = text.lower()

    # 1. numbers — a figure carrying a dose/glucose unit forfeits the structural exemption
    allowed = _source_numbers(source)
    for m in _NUM_RE.finditer(text):
        tok = m.group(0)
        try:
            n = float(tok)
        except ValueError:
            continue
        unit_attached = bool(_UNIT_AFTER_RE.match(text[m.end():m.end() + 12]))
        if not _is_grounded(n, allowed, structural_ok=not unit_attached):
            violations.append(Violation("ungrounded_number", f"'{tok}' is not present in the findings."))

    # 2. prescriptions
    for rx in _PRESCRIPTION_RES:
        m = rx.search(text)
        if m:
            violations.append(Violation("prescription", f"dosing directive: '{m.group(0).strip()[:80]}'"))
            break

    findings = source.get("findings", []) if isinstance(source, dict) else []

    # 3. omitted critical findings
    for f in findings:
        if f.get("severity") == "critical":
            kws = _critical_keywords(f)
            if kws and not any(k in low for k in kws):
                violations.append(Violation("omitted_critical",
                                            f"critical finding not mentioned: '{f.get('title')}'"))

    # 4. counterfactual caveat
    if source.get("counterfactuals"):
        if not any(m in low for m in _CAVEAT_MARKERS):
            violations.append(Violation("missing_caveat",
                                        "counterfactual narrated without the decision-level/BG caveat."))

    # 5. association caveat for the SMB/low pattern
    if any(f.get("key") == "smb_high_iob_overnight" for f in findings):
        if "smb" in low and not any(m in low for m in _ASSOC_MARKERS):
            violations.append(Violation("missing_assoc_caveat",
                                        "SMB/low pattern narrated without association-not-causation caveat."))

    return GateResult(passed=not violations, violations=violations)
