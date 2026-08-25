"""The bounded, typed settings schema.

Settings extraction (screenshots or client-side decrypt) fills a *fixed* set of typed
slots, each with a valid range and the source names it appears under in AAPS/Trio. This is
what makes screenshot extraction tractable and validatable (DESIGN §5.2): we are not
reading arbitrary text, we are filling ~two dozen known slots and rejecting anything that
does not fit.

`replay_lever=True` marks the settings the replay oracle can actually vary (they map onto
`replay.settings_delta` keys); the rest are advisory-only inputs to diagnostics.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


def _to_bool(v: Any) -> bool:
    if isinstance(v, bool):
        return v
    if isinstance(v, (int, float)):
        return v != 0
    return str(v).strip().lower() in ("true", "1", "yes", "on", "enabled")


@dataclass(frozen=True)
class SettingSpec:
    key: str
    kind: str                       # "float" | "int" | "bool"
    unit: str | None
    aliases: tuple[str, ...]        # source names (AAPS pref keys, screen labels, Trio names)
    replay_lever: bool
    description: str
    min: float | None = None
    max: float | None = None
    # Is a zero reading implausible for this setting? Only true where withholding the value
    # is SAFE — i.e. where the replay refuses to run rather than substituting a default.
    # For the caps (max_smb_minutes, max_uam_minutes, max_basal) zero is a real, deliberate
    # configuration meaning "off", and withholding it would make build_profile substitute a
    # *more permissive* default, simulating insulin the real configuration forbids.
    zero_suspect: bool = False

    def coerce(self, v: Any) -> tuple[Any, bool]:
        try:
            if self.kind == "bool":
                return _to_bool(v), True
            if self.kind == "int":
                return int(round(float(v))), True
            return float(v), True
        except (TypeError, ValueError):
            return None, False

    def in_range(self, v: Any) -> bool:
        if self.kind == "bool":
            return True
        if self.min is not None and v < self.min:
            return False
        if self.max is not None and v > self.max:
            return False
        return True


SETTINGS: dict[str, SettingSpec] = {
    s.key: s for s in [
        # --- replay levers (map to replay.settings_delta) ---
        SettingSpec("max_iob", "float", "U",
                    ("max_iob", "Max IOB", "maxIOB",
                     "boost_max_iob", "openapsmb_max_iob", "openapsma_max_iob"),  # Boost / AAPS-SMB / AMA
                    True, "Maximum insulin on board the loop may reach.", 0, 25,
                    # Safe to withhold: from_cycle refuses the cycle when max_iob is absent,
                    # so a suspect zero skips the counterfactual instead of inventing one.
                    zero_suspect=True),
        SettingSpec("max_basal", "float", "U/h", ("max_basal", "Max basal", "maxBasal"),
                    True, "Maximum temp basal rate.", 0, 25),
        SettingSpec("target_bg", "float", "mg/dL", ("target_bg", "Target", "target"),
                    True, "Target glucose (single value).", 70, 180),
        SettingSpec("sens", "float", "mg/dL/U", ("sens", "ISF", "isf", "insulin_sensitivity"),
                    True, "Insulin sensitivity factor.", 5, 500),
        SettingSpec("carb_ratio", "float", "g/U", ("carb_ratio", "CR", "carbratio", "IC"),
                    True, "Carb ratio.", 2, 150),
        SettingSpec("max_smb_minutes", "int", "min",
                    ("maxSMBBasalMinutes", "Max SMB Basal Minutes", "max_smb_minutes", "smbmaxminutes"),
                    True, "SMB size cap as minutes of basal.", 0, 120),
        SettingSpec("max_uam_minutes", "int", "min",
                    ("maxUAMSMBBasalMinutes", "Max UAM SMB Basal Minutes", "max_uam_minutes", "uamsmbmaxminutes"),
                    True, "UAM SMB size cap as minutes of basal.", 0, 120),
        SettingSpec("enable_smb", "bool", None,
                    ("enableSMB_always", "Enable SMB Always", "enableSMB", "use_smb"),
                    True, "Whether SMBs are enabled.", None, None),
        SettingSpec("enable_smb_uam", "bool", None,
                    ("enableSMB_uam", "Enable UAM", "enableUAM", "use_uam"),
                    True, "Whether UAM SMBs are enabled.", None, None),
        # --- advisory-only inputs (not replay levers) ---
        SettingSpec("smb_delivery_ratio", "float", None,
                    ("smb_delivery_ratio", "SMB delivery ratio"),
                    False, "Fraction of the calculated dose delivered as an SMB.", 0.1, 1.0),
        SettingSpec("max_cob", "int", "g", ("maxCOB", "Max COB"),
                    False, "Maximum carbs on board the loop will act on.", 0, 200),
        SettingSpec("autosens_min", "float", None, ("autosens_min", "Autosens min"),
                    False, "Lower bound on the autosens ratio.", 0.3, 1.0),
        SettingSpec("autosens_max", "float", None, ("autosens_max", "Autosens max"),
                    False, "Upper bound on the autosens ratio.", 1.0, 3.0),
        SettingSpec("dynamic_isf_enabled", "bool", None,
                    ("use_dynamic_sensitivity", "Dynamic ISF", "enableDynamicCR", "dynisf"),
                    False, "Whether dynamic ISF/CR is enabled (informs variant handling).",
                    None, None),
    ]
}

# alias (lowercased) -> friendly key, including each key as its own alias
_ALIAS_TO_KEY: dict[str, str] = {}
for _spec in SETTINGS.values():
    _ALIAS_TO_KEY[_spec.key.lower()] = _spec.key
    for _a in _spec.aliases:
        _ALIAS_TO_KEY[_a.lower()] = _spec.key


def resolve_alias(raw_name: str) -> str | None:
    """Map a source name/label to a friendly key, or None if unrecognised."""
    return _ALIAS_TO_KEY.get(str(raw_name).strip().lower())
