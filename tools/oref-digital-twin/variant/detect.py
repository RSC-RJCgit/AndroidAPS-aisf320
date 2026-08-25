"""Variant detection: classify the running algorithm and decide advisability.

Runs first and gates everything downstream (DESIGN.md §4). The output is not just a label
but an **advisability**: whether the replay oracle may run (FULL), whether we must stay
diagnosis-only (middleware / a fork we do not replay), or whether the loop is out of scope
entirely (Loop / unknown). We never advise on a controller we are not actually replaying.

Classification aggregates per-cycle markers (signals.py) across the whole pull, so a
single stray reason string cannot swing the verdict.
"""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field
from enum import Enum
from typing import Any

from ingestion.models import DeviceStatusCycle

from .signals import extract_signals

# A marker must appear in at least this fraction of cycles to count as a positive signal.
POSITIVE_THRESHOLD = 0.20
# Middleware may only print when it actually alters something, so it needs a lower bar.
MIDDLEWARE_THRESHOLD = 0.10


class Platform(Enum):
    AAPS = "aaps"
    TRIO = "trio"
    UNKNOWN = "unknown"


class IsfMode(Enum):
    STOCK = "stock"
    DYNAMIC_ISF = "dynamic_isf"
    AUTOISF = "autoisf"
    UNKNOWN = "unknown"


class Variant(Enum):
    AAPS_SMB = "aaps_smb"
    AAPS_DYNAMIC_ISF = "aaps_dynamic_isf"
    AAPS_AUTOISF = "aaps_autoisf"
    TRIO = "trio"
    TRIO_DYNAMIC_ISF = "trio_dynamic_isf"
    BOOST = "boost"
    UNKNOWN = "unknown"


class Advisability(Enum):
    FULL = "full"                     # replay oracle may run
    DIAGNOSIS_ONLY = "diagnosis_only"  # middleware / fork: describe, don't replay
    OUT_OF_SCOPE = "out_of_scope"      # Loop / unknown controller


@dataclass
class VariantVerdict:
    variant: Variant
    platform: Platform
    isf_mode: IsfMode
    advisability: Advisability
    confidence: float                 # 0..1
    middleware_present: bool
    boost_fork: bool
    cycles_examined: int
    evidence: dict[str, int] = field(default_factory=dict)   # marker -> cycles matched
    notes: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "variant": self.variant.value,
            "platform": self.platform.value,
            "isf_mode": self.isf_mode.value,
            "advisability": self.advisability.value,
            "confidence": self.confidence,
            "middleware_present": self.middleware_present,
            "boost_fork": self.boost_fork,
            "cycles_examined": self.cycles_examined,
            "evidence": self.evidence,
            "notes": self.notes,
        }


def detect_variant(
    cycles: list[DeviceStatusCycle],
    *,
    dropped_no_oref: int = 0,
) -> VariantVerdict:
    """Classify the controller from normalised devicestatus cycles.

    :param dropped_no_oref: count of devicestatus docs ingestion dropped for having no
        openaps payload. A high count alongside no oref cycles hints at a Loop uploader
        (out of scope) rather than an empty site.
    """
    n = len(cycles)
    if n == 0:
        notes = ["No oref devicestatus cycles — cannot classify the controller."]
        if dropped_no_oref > 0:
            notes.append(
                f"{dropped_no_oref} devicestatus docs had no openaps payload — possibly a "
                "Loop uploader (out of scope for the replay oracle)."
            )
        return VariantVerdict(
            variant=Variant.UNKNOWN, platform=Platform.UNKNOWN, isf_mode=IsfMode.UNKNOWN,
            advisability=Advisability.OUT_OF_SCOPE, confidence=0.0, middleware_present=False,
            boost_fork=False, cycles_examined=0, evidence={}, notes=notes,
        )

    evidence: Counter = Counter()
    for c in cycles:
        evidence.update(extract_signals(c))

    def frac(marker: str) -> float:
        return evidence.get(marker, 0) / n

    autoisf = frac("autoisf") >= POSITIVE_THRESHOLD
    dynamic = frac("dynamic_isf") >= POSITIVE_THRESHOLD
    boost = frac("boost") >= POSITIVE_THRESHOLD
    middleware = frac("middleware") >= MIDDLEWARE_THRESHOLD

    notes: list[str] = []

    # --- platform: device markers first, else infer from platform-specific features ---
    device_inferred = False
    if frac("trio_device") >= POSITIVE_THRESHOLD:
        platform = Platform.TRIO
        platform_conf = frac("trio_device")
    elif frac("aaps_device") >= POSITIVE_THRESHOLD:
        platform = Platform.AAPS
        platform_conf = frac("aaps_device")
    elif autoisf or boost:
        platform, platform_conf, device_inferred = Platform.AAPS, 0.5, True  # AAPS-only features
    elif middleware:
        platform, platform_conf, device_inferred = Platform.TRIO, 0.5, True  # Trio/iAPS feature
    else:
        platform, platform_conf = Platform.UNKNOWN, 0.0

    if device_inferred:
        notes.append("Platform inferred from algorithm features, not the device string.")

    # --- ISF mode ---
    if autoisf:
        isf_mode, isf_conf = IsfMode.AUTOISF, min(1.0, 0.6 + frac("autoisf"))
    elif dynamic:
        isf_mode, isf_conf = IsfMode.DYNAMIC_ISF, min(1.0, 0.6 + frac("dynamic_isf"))
    elif platform is not Platform.UNKNOWN:
        isf_mode, isf_conf = IsfMode.STOCK, 0.5  # inferred from absence of dynamic markers
        notes.append("Stock ISF inferred from the absence of dynamic-ISF/autoISF markers.")
    else:
        isf_mode, isf_conf = IsfMode.UNKNOWN, 0.1

    # --- variant ---
    if boost:
        variant = Variant.BOOST
        notes.append("Boost fork detected — the generic replay oracle does not model it; "
                     "use Boost-specific tooling.")
    elif platform is Platform.AAPS:
        variant = (Variant.AAPS_AUTOISF if autoisf
                   else Variant.AAPS_DYNAMIC_ISF if dynamic
                   else Variant.AAPS_SMB)
    elif platform is Platform.TRIO:
        if autoisf:
            notes.append("autoISF markers on a Trio device are unexpected; treating as dynamic ISF.")
        variant = Variant.TRIO_DYNAMIC_ISF if (dynamic or autoisf) else Variant.TRIO
    else:
        variant = Variant.UNKNOWN

    if middleware:
        notes.append("Trio middleware detected — it rewrites the profile before "
                     "determine-basal; stock-oref replay cannot be assumed.")

    # --- advisability ---
    if platform is Platform.UNKNOWN or variant is Variant.UNKNOWN:
        advisability = Advisability.OUT_OF_SCOPE
    elif boost or middleware:
        advisability = Advisability.DIAGNOSIS_ONLY
    else:
        advisability = Advisability.FULL

    confidence = round(min(max(platform_conf, 0.0), isf_conf), 2)

    return VariantVerdict(
        variant=variant,
        platform=platform,
        isf_mode=isf_mode,
        advisability=advisability,
        confidence=confidence,
        middleware_present=middleware,
        boost_fork=boost,
        cycles_examined=n,
        evidence=dict(evidence),
        notes=notes,
    )
