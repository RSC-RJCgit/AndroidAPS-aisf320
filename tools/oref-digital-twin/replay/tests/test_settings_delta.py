import pytest

from replay.settings_delta import apply_delta, known_keys


def _req():
    return {"profile": {"max_iob": 6.0, "sens": 50.0, "min_bg": 100, "max_bg": 100,
                        "target_bg": 100, "enableSMB_always": False}}


def test_apply_delta_sets_fields_and_deep_copies():
    base = _req()
    out = apply_delta(base, {"max_iob": 3.0})
    assert out["profile"]["max_iob"] == 3.0
    assert base["profile"]["max_iob"] == 6.0  # original untouched


def test_target_bg_sets_all_three_fields():
    out = apply_delta(_req(), {"target_bg": 120})
    p = out["profile"]
    assert p["min_bg"] == p["max_bg"] == p["target_bg"] == 120.0


def test_enable_smb_toggles_all_flags():
    out = apply_delta(_req(), {"enable_smb": True})
    p = out["profile"]
    assert all(p[k] for k in ("enableSMB_always", "enableSMB_with_COB",
                              "enableSMB_after_carbs", "enableSMB_uam"))


def test_max_iob_clamped_non_negative():
    out = apply_delta(_req(), {"max_iob": -5})
    assert out["profile"]["max_iob"] == 0.0


def test_unknown_key_raises():
    with pytest.raises(ValueError):
        apply_delta(_req(), {"not_a_setting": 1})


def test_known_keys_lists_supported_levers():
    assert "max_iob" in known_keys() and "enable_smb" in known_keys()
