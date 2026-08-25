"""Variant-detection tests. Synthetic reason strings only — no real data.

Reason/device tokens here are illustrative of the markers the detector looks for; they
are heuristics pending validation against real corpora (see signals.py).
"""

import json

from ingestion.models import DeviceStatusCycle
from variant import Advisability, IsfMode, Platform, Variant, detect_variant


def cyc(reason="", device="", raw=None):
    return DeviceStatusCycle(ts_ms=0, device=device, reason=reason, raw_openaps=raw)


def many(c, n=5):
    return [c for _ in range(n)]


def test_aaps_autoisf():
    cycles = many(cyc(reason="autoISF: acce_ISF 1.2, bg_ISF applied", device="openaps://AAPS"))
    v = detect_variant(cycles)
    assert v.variant is Variant.AAPS_AUTOISF
    assert v.platform is Platform.AAPS and v.isf_mode is IsfMode.AUTOISF
    assert v.advisability is Advisability.FULL
    assert v.confidence > 0.6


def test_aaps_stock_smb():
    cycles = many(cyc(reason="COB: 0, IOB 1.2, Enact temp 0.5", device="openaps://AAPS on Pixel"))
    v = detect_variant(cycles)
    assert v.variant is Variant.AAPS_SMB
    assert v.isf_mode is IsfMode.STOCK
    assert v.advisability is Advisability.FULL


def test_trio_dynamic_with_middleware_is_diagnosis_only():
    cycles = many(cyc(reason="Dynamic ISF; Middleware: profile override", device="Trio"))
    v = detect_variant(cycles)
    assert v.platform is Platform.TRIO
    assert v.variant is Variant.TRIO_DYNAMIC_ISF
    assert v.middleware_present is True
    assert v.advisability is Advisability.DIAGNOSIS_ONLY
    assert any("middleware" in note.lower() for note in v.notes)


def test_boost_fork_detected_and_routed_out():
    cycles = many(cyc(reason="boostV7 R4=0.2; boost_bolus 0.6", device="openaps://phone"))
    v = detect_variant(cycles)
    assert v.variant is Variant.BOOST
    assert v.boost_fork is True
    assert v.advisability is Advisability.DIAGNOSIS_ONLY
    assert v.platform is Platform.AAPS  # inferred from a fork-specific feature


def test_dynamic_isf_via_openaps_key_not_reason_text():
    # reason says nothing, but the payload exposes variable_sens -> dynamic ISF
    raw = {"enacted": {"variable_sens": 42.0, "reason": "temp 0.4"}}
    cycles = many(cyc(reason="temp 0.4", device="openaps://AAPS", raw=raw))
    v = detect_variant(cycles)
    assert v.isf_mode is IsfMode.DYNAMIC_ISF
    assert v.variant is Variant.AAPS_DYNAMIC_ISF


def test_unknown_platform_is_out_of_scope():
    cycles = many(cyc(reason="IOB 1.0, temp 0.3", device="openaps://phone"))
    v = detect_variant(cycles)
    assert v.platform is Platform.UNKNOWN
    assert v.variant is Variant.UNKNOWN
    assert v.advisability is Advisability.OUT_OF_SCOPE


def test_empty_cycles_notes_possible_loop():
    v = detect_variant([], dropped_no_oref=300)
    assert v.advisability is Advisability.OUT_OF_SCOPE
    assert v.cycles_examined == 0
    assert any("loop" in note.lower() for note in v.notes)


def test_stray_marker_below_threshold_does_not_flip():
    # 1 boost cycle in 10 (10% < 20% threshold) must not classify as Boost
    cycles = [cyc(reason="boostV7", device="openaps://AAPS")] + many(
        cyc(reason="IOB 1.0 temp 0.3", device="openaps://AAPS"), n=9
    )
    v = detect_variant(cycles)
    assert v.boost_fork is False
    assert v.variant is Variant.AAPS_SMB


def test_verdict_is_json_serialisable():
    v = detect_variant(many(cyc(reason="autoISF", device="openaps://AAPS")))
    json.dumps(v.to_dict())  # must not raise
    assert v.to_dict()["variant"] == "aaps_autoisf"
