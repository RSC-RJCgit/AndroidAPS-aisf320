"""Apply a settings change to an oref determine-basal request.

A `delta` is a mapping of friendly keys to new values; each maps to one or more oref
profile fields (using oref0's exact field names). Applying a delta deep-copies the request
so the baseline is never mutated. Unknown keys raise — we never silently ignore a setting
the counterfactual cannot model.
"""

from __future__ import annotations

import copy
from typing import Any, Callable

# friendly key -> (oref profile fields, coercion)
DELTA_FIELDS: dict[str, tuple[list[str], Callable[[Any], Any]]] = {
    "max_iob": (["max_iob"], lambda v: max(0.0, float(v))),
    "sens": (["sens"], float),                                   # ISF, mg/dL per U
    "carb_ratio": (["carb_ratio"], float),                       # g per U
    "target_bg": (["min_bg", "max_bg", "target_bg"], float),     # mg/dL
    "max_basal": (["max_basal"], lambda v: max(0.0, float(v))),
    "current_basal": (["current_basal"], lambda v: max(0.0, float(v))),
    "max_smb_minutes": (["maxSMBBasalMinutes"], lambda v: max(0.0, float(v))),
    "max_uam_minutes": (["maxUAMSMBBasalMinutes"], lambda v: max(0.0, float(v))),
    "enable_smb": (
        ["enableSMB_always", "enableSMB_with_COB", "enableSMB_after_carbs", "enableSMB_uam"],
        bool,
    ),
}


def known_keys() -> list[str]:
    return sorted(DELTA_FIELDS)


def apply_delta(request: dict, delta: dict[str, Any]) -> dict:
    """Return a deep-copied request with the delta applied to its profile."""
    unknown = [k for k in delta if k not in DELTA_FIELDS]
    if unknown:
        raise ValueError(f"unknown settings-delta keys: {unknown}; known: {known_keys()}")
    req = copy.deepcopy(request)
    profile = req.get("profile")
    if not isinstance(profile, dict):
        raise ValueError("request has no profile to modify")
    for key, value in delta.items():
        fields, coerce = DELTA_FIELDS[key]
        for field in fields:
            profile[field] = coerce(value)
    return req
