"""Marker extraction from a devicestatus cycle.

These are **heuristic** reason-string / device markers, not a spec. oref, AAPS and Trio
do not publish a stable machine-readable "which algorithm am I" field, so we scan the
`reason` text, the `device` string, and the key names present in the openaps payload for
tokens that each variant tends to emit.

They are deliberately declared in one editable table and must be validated against real
devicestatus corpora before the confidence numbers are trusted (DESIGN.md §4, and the
project's code-leads / verify-against-real-data methodology). Aggregation across many
cycles (see detect.py) is what makes them robust to the odd stray match.
"""

from __future__ import annotations

import re
from typing import Any

from ingestion.models import DeviceStatusCycle

# marker-name -> patterns (matched case-insensitively against the cycle haystack)
MARKER_PATTERNS: dict[str, list[str]] = {
    # --- ISF/CR mode ---
    "autoisf": [r"auto[_ ]?isf", r"acce_isf", r"bg_isf", r"pp_isf", r"dura_isf", r"delta_isf", r"\bb30\b"],
    "dynamic_isf": [r"dynamic[_ ]?isf", r"\bdynisf\b", r"variable_sens", r"dynamic isf/cr"],
    # --- forks / middleware ---
    "boost": [r"\bboost\b", r"boostv\d", r"boost_bolus", r"boostv\d_"],
    "middleware": [r"middleware", r"\bmw:"],
    # --- platform (device string) ---
    "trio_device": [r"\btrio\b", r"iphone", r"\bios\b", r"freeaps", r"\biaps\b"],
    "aaps_device": [r"\baaps\b", r"androidaps"],
}

_COMPILED: dict[str, list[re.Pattern]] = {
    name: [re.compile(p, re.IGNORECASE) for p in pats] for name, pats in MARKER_PATTERNS.items()
}


def _haystack(cycle: DeviceStatusCycle) -> str:
    """Build the searchable text for one cycle: reason + device + openaps key names.

    Key names are included because dynamic-ISF/autoISF expose themselves through fields
    (e.g. `variable_sens`, `autoISF`) even when the reason text does not spell it out.
    """
    parts: list[str] = [str(cycle.reason or ""), str(cycle.device or ""), str(cycle.device_reported_units or "")]
    raw: Any = cycle.raw_openaps or {}
    if isinstance(raw, dict):
        for block_name in ("suggested", "enacted"):
            block = raw.get(block_name)
            if isinstance(block, dict):
                parts.extend(str(k) for k in block.keys())
                r = block.get("reason")
                if isinstance(r, str):
                    parts.append(r)
    return " ".join(parts)


def extract_signals(cycle: DeviceStatusCycle) -> set[str]:
    """Return the set of marker names that match this cycle."""
    hay = _haystack(cycle)
    return {name for name, pats in _COMPILED.items() if any(p.search(hay) for p in pats)}
