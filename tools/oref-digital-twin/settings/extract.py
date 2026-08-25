"""Screenshot -> validated settings, with the vision model injected.

The vision model is a dependency, not a hardcoded call — `extract_settings` takes a
`vision` callable so the pipeline is testable offline and provider-agnostic (DESIGN §5.2,
and the project rule that an LLM narrates/extracts but the schema validates). The vision
step only proposes raw name/value/confidence triples; `validate` decides what is trusted.

The vision callable contract, per image (bytes):
    vision(image_bytes) -> { source_name: value }
                         | { source_name: {"value": value, "confidence": 0..1} }
"""

from __future__ import annotations

from typing import Any, Callable

from .validate import ValidatedSettings, validate

VisionFn = Callable[[bytes], dict[str, Any]]


def _split(entry: Any) -> tuple[Any, float | None]:
    if isinstance(entry, dict) and "value" in entry:
        return entry.get("value"), entry.get("confidence")
    return entry, None


def extract_settings(images: list[bytes], vision: VisionFn) -> ValidatedSettings:
    """Run the injected vision model over screenshots and validate the result.

    Later images win on a repeated name (the user can re-screenshot to correct a read).
    """
    raw: dict[str, Any] = {}
    confidences: dict[str, float] = {}
    for image in images:
        proposed = vision(image) or {}
        for name, entry in proposed.items():
            value, conf = _split(entry)
            raw[name] = value
            if conf is not None:
                confidences[name] = conf
    return validate(raw, confidences)
