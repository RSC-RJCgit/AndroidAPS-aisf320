from ingestion.models import MGDL_PER_MMOL, to_mgdl
from ingestion.normalise import (
    normalise_devicestatus,
    normalise_entry,
    normalise_profile,
    normalise_treatment,
    parse_iso_ms,
)
from ingestion.tests import fixtures as fx


def test_parse_iso_ms_handles_z_and_none():
    assert parse_iso_ms("2023-11-14T22:14:00.000Z") == 1_700_000_040_000
    assert parse_iso_ms(None) is None
    assert parse_iso_ms("not a date") is None


def test_to_mgdl_converts_only_mmol_small_values():
    assert to_mgdl(128, "mg/dl") == 128
    assert to_mgdl(5.0, "mmol") == round(5.0 * MGDL_PER_MMOL, 3)
    # a large value labelled mmol is treated as already-mgdl (mislabel guard)
    assert to_mgdl(128, "mmol") == 128


def test_normalise_entry_sgv_and_mbg():
    r = normalise_entry(fx.ENTRY)
    assert r.sgv_mgdl == 128 and r.ts_ms == 1_700_000_000_000 and r.direction == "Flat"
    r2 = normalise_entry(fx.ENTRY_MBG)
    assert r2.sgv_mgdl == 140 and r2.raw_type == "mbg"


def test_normalise_entry_requires_timestamp():
    assert normalise_entry({"sgv": 100}) is None


def test_smb_detection_from_entered_by():
    t = normalise_treatment(fx.TREATMENT_SMB)
    assert t.is_smb is True and t.insulin_u == 0.6
    t2 = normalise_treatment(fx.TREATMENT_CARB)
    assert t2.is_smb is False and t2.carbs_g == 45


def test_temp_target_targets_captured():
    t = normalise_treatment(fx.TREATMENT_TT)
    assert t.duration_min == 225
    assert t.target_high_mgdl == 160 and t.target_low_mgdl == 160


def test_devicestatus_inputs_from_suggested_decision_from_enacted():
    c = normalise_devicestatus(fx.DEVICESTATUS)
    assert c is not None
    assert c.iob == 1.85                        # openaps.iob[0].iob
    assert c.bg_mgdl == 128
    assert c.eventual_bg_mgdl == 110            # INPUT source is suggested, not enacted's 108
    assert c.insulin_req == 0.2                 # suggested
    assert c.enacted_smb_u == 0.6              # decision field still from enacted
    assert c.enacted_duration_min == 30
    assert "SMB 0.6U" in c.reason              # enacted reason
    assert c.pred_iob == [128, 124, 120]        # suggested predBGs


def test_devicestatus_pump_only_record_dropped():
    # openaps present but no suggested and no enacted -> not a loop cycle
    doc = {"created_at": "2023-11-14T22:19:00.000Z", "openaps": {"iob": [{"iob": 1.0}]},
           "pump": {"battery": {"percent": 80}}}
    assert normalise_devicestatus(doc) is None


def test_devicestatus_without_openaps_is_dropped():
    assert normalise_devicestatus(fx.DEVICESTATUS_NO_OREF) is None


def test_numeric_units_not_treated_as_unit_string():
    # oref `units` is the SMB bolus amount; it must not leak into device_reported_units
    c = normalise_devicestatus(fx.DEVICESTATUS)
    assert c.device_reported_units is None
    assert c.enacted_smb_u == 0.6


def test_profile_mmol_converts_glucose_blocks():
    p = normalise_profile(fx.PROFILE_MMOL)
    assert p.units == "mmol" and p.dia_h == 6
    assert p.basal[0].value == 0.85                       # basal not converted
    assert p.carb_ratio[0].value == 10                    # CR not converted
    # to_mgdl rounds to 3 dp, so allow a small tolerance
    assert abs(p.isf_mgdl[0].value - 3.1 * MGDL_PER_MMOL) < 1e-2
    assert abs(p.target_low_mgdl[0].value - 5.0 * MGDL_PER_MMOL) < 1e-2
    assert abs(p.target_high_mgdl[0].value - 7.5 * MGDL_PER_MMOL) < 1e-2
