from settings.schema import SETTINGS, resolve_alias
from settings.validate import validate


def test_alias_resolution():
    assert resolve_alias("Max IOB") == "max_iob"
    assert resolve_alias("maxSMBBasalMinutes") == "max_smb_minutes"
    assert resolve_alias("ISF") == "sens"
    assert resolve_alias("not a thing") is None


def test_validate_clean_mixed_types():
    v = validate({"Max IOB": "6.0", "isf": "50", "enableSMB_always": "true"})
    assert v.values["max_iob"] == 6.0
    assert v.values["sens"] == 50.0
    assert v.values["enable_smb"] is True
    assert v.is_clean()
    assert v.needs_confirm == []


def test_out_of_range_flags_confirm_but_keeps_value():
    v = validate({"max_iob": "99"})   # above the 25 U ceiling
    assert v.values["max_iob"] == 99.0
    assert "max_iob" in v.needs_confirm
    assert any(i.kind == "out_of_range" for i in v.issues)


def test_wrong_type_not_stored():
    v = validate({"max_iob": "abc"})
    assert "max_iob" not in v.values
    assert any(i.kind == "wrong_type" for i in v.issues)


def test_unknown_key_reported():
    v = validate({"totally_unknown": 1})
    assert any(i.kind == "unknown_key" for i in v.issues)
    assert v.values == {}


def test_low_confidence_needs_confirm():
    v = validate({"Max IOB": "6"}, confidences={"Max IOB": 0.5})
    assert "max_iob" in v.needs_confirm
    assert any(i.kind == "needs_confirm" for i in v.issues)


def test_replay_settings_filters_to_levers():
    v = validate({"max_iob": "6", "maxCOB": "120", "ISF": "45"})
    replay = v.replay_settings()
    assert "max_iob" in replay and "sens" in replay
    assert "max_cob" not in replay          # advisory-only, not a replay lever
    assert SETTINGS["max_cob"].replay_lever is False


def test_int_coercion_rounds():
    v = validate({"maxSMBBasalMinutes": "45.0"})
    assert v.values["max_smb_minutes"] == 45 and isinstance(v.values["max_smb_minutes"], int)


def test_out_of_range_is_withheld_from_replay():
    """An implausible insulin value may be reported but must never drive a counterfactual."""
    v = validate({"max_iob": "500"})
    assert v.values["max_iob"] == 500.0          # still visible to the caller
    assert "max_iob" in v.blocked
    assert "max_iob" not in v.replay_settings()  # withheld


def test_negative_value_is_withheld_from_replay():
    v = validate({"max_iob": "-3"})
    assert "max_iob" in v.blocked
    assert v.replay_settings() == {}


def test_zero_lever_is_withheld_pending_confirmation():
    """0 is inside max_iob's [0, 25] range but disables the lever — almost always a bad read."""
    v = validate({"max_iob": "0"})
    assert "max_iob" in v.needs_confirm and "max_iob" in v.blocked
    assert v.replay_settings() == {}


def test_low_confidence_is_withheld_from_replay():
    v = validate({"Max IOB": "6"}, confidences={"Max IOB": 0.5})
    assert "max_iob" in v.blocked and v.replay_settings() == {}


def test_good_value_is_not_blocked():
    v = validate({"max_iob": "6"})
    assert v.blocked == [] and v.replay_settings() == {"max_iob": 6.0}


def test_zero_is_kept_for_caps_where_zero_means_off():
    """max_smb_minutes/max_uam_minutes/max_basal = 0 is a deliberate 'off', not a bad read.

    Withholding them would be worse than accepting: build_profile substitutes 30 / 30 /
    4x basal, so the replay would simulate insulin the real configuration forbids.
    """
    v = validate({"maxSMBBasalMinutes": "0", "maxUAMSMBBasalMinutes": "0", "max_basal": "0"})
    assert v.blocked == []
    assert v.replay_settings() == {"max_smb_minutes": 0, "max_uam_minutes": 0, "max_basal": 0.0}


def test_zero_still_suspect_where_withholding_is_safe():
    """max_iob is flagged zero_suspect: from_cycle refuses the cycle rather than defaulting."""
    v = validate({"max_iob": "0"})
    assert "max_iob" in v.blocked and v.replay_settings() == {}
