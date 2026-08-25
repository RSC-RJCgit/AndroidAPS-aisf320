"""Normalised ingestion schema.

Raw Nightscout documents are converted into these dataclasses so that every
downstream module (variant detection, diagnostics, the replay oracle) reads one
consistent shape regardless of uploader quirks.

Canonical conventions:
  * time is epoch milliseconds, UTC (`ts_ms`); the local offset is kept separately
    where Nightscout provides it (`utc_offset_min`).
  * glucose is milligrams per decilitre (`*_mgdl`); mmol/L sources are converted on
    the way in and the original unit is recorded.
  * a field that the source did not provide is ``None`` — never a silent zero.
"""

from __future__ import annotations

from dataclasses import dataclass, field, asdict
from typing import Any

# AAPS/oref use 18.0182; keep it exact rather than the rounded 18.
MGDL_PER_MMOL = 18.0182


def mmol_to_mgdl(mmol: float) -> float:
    return mmol * MGDL_PER_MMOL


def to_mgdl(value: float, units: str | None) -> float:
    """Coerce a glucose value to mg/dL given its source units.

    Nightscout ``entries.sgv`` is always mg/dL; profile/devicestatus values may be
    mmol/L. A value that already looks like mg/dL (>= 40) is passed through even if the
    unit string says mmol, because mislabelled-but-large values are common in the wild.
    """
    if units and units.lower().startswith("mmol") and value < 40:
        return round(mmol_to_mgdl(value), 3)
    return float(value)


@dataclass
class GlucoseReading:
    ts_ms: int
    sgv_mgdl: float | None
    direction: str | None = None
    device: str | None = None
    raw_type: str | None = None  # sgv | mbg | cal ...

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class Treatment:
    ts_ms: int
    event_type: str | None
    insulin_u: float | None = None
    carbs_g: float | None = None
    duration_min: float | None = None
    rate: float | None = None            # temp basal rate (U/h)
    absolute: float | None = None        # absolute temp basal (U/h)
    target_low_mgdl: float | None = None
    target_high_mgdl: float | None = None
    is_smb: bool = False
    entered_by: str | None = None
    notes: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class DeviceStatusCycle:
    """One loop cycle as reported in ``devicestatus.openaps``.

    This is the controller narrating itself: what it saw, what it suggested, and what it
    enacted. It is the substrate for both diagnostics and the replay oracle.
    """

    ts_ms: int
    device: str | None = None
    bg_mgdl: float | None = None
    iob: float | None = None
    cob: float | None = None
    eventual_bg_mgdl: float | None = None
    insulin_req: float | None = None
    sensitivity_ratio: float | None = None
    reason: str | None = None
    # enacted decision
    enacted_smb_u: float | None = None
    enacted_rate: float | None = None
    enacted_duration_min: float | None = None
    # prediction arrays (mg/dL), when present
    pred_iob: list[float] | None = None
    pred_zt: list[float] | None = None
    pred_cob: list[float] | None = None
    pred_uam: list[float] | None = None
    # coarse provenance for variant detection
    device_reported_units: str | None = None
    raw_openaps: dict[str, Any] | None = field(default=None, repr=False)

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class ProfileBlock:
    seconds_from_midnight: int
    value: float

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class ProfileSnapshot:
    valid_from_ms: int | None
    units: str | None
    dia_h: float | None
    timezone: str | None
    basal: list[ProfileBlock] = field(default_factory=list)
    isf_mgdl: list[ProfileBlock] = field(default_factory=list)
    carb_ratio: list[ProfileBlock] = field(default_factory=list)
    target_low_mgdl: list[ProfileBlock] = field(default_factory=list)
    target_high_mgdl: list[ProfileBlock] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "valid_from_ms": self.valid_from_ms,
            "units": self.units,
            "dia_h": self.dia_h,
            "timezone": self.timezone,
            "basal": [b.to_dict() for b in self.basal],
            "isf_mgdl": [b.to_dict() for b in self.isf_mgdl],
            "carb_ratio": [b.to_dict() for b in self.carb_ratio],
            "target_low_mgdl": [b.to_dict() for b in self.target_low_mgdl],
            "target_high_mgdl": [b.to_dict() for b in self.target_high_mgdl],
        }
