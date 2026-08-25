"""Algorithm-variant detection.

Public surface:
    detect_variant, VariantVerdict, Variant, Platform, IsfMode, Advisability
"""

from .detect import (
    Advisability,
    IsfMode,
    Platform,
    Variant,
    VariantVerdict,
    detect_variant,
)

__all__ = [
    "detect_variant",
    "VariantVerdict",
    "Variant",
    "Platform",
    "IsfMode",
    "Advisability",
]
