from ingestion.models import DeviceStatusCycle, GlucoseReading, ProfileBlock, ProfileSnapshot
from replay.inputs import build_glucose_status, build_profile, from_cycle


def _entries(base_ms, series):
    # series: list of (minutes_before_base, sgv)
    return [GlucoseReading(ts_ms=base_ms - m * 60_000, sgv_mgdl=v) for m, v in series]


def test_build_glucose_status_delta():
    base = 1_700_000_000_000
    entries = _entries(base, [(0, 150), (5, 145), (15, 135), (45, 120)])
    gs = build_glucose_status(entries, base)
    assert gs["glucose"] == 150
    assert gs["delta"] == 5.0                     # 150 - 145
    assert gs["short_avgdelta"] == round((150 - 135) / 3.0, 1)
    assert gs["long_avgdelta"] == round((150 - 120) / 9.0, 1)
    assert "short_avgdelta" in gs and "long_avgdelta" in gs  # oref field names


def test_build_glucose_status_empty():
    assert build_glucose_status([], 1) is None


def _snapshot(tz="UTC"):
    b = lambda v: [ProfileBlock(0, v)]
    return ProfileSnapshot(valid_from_ms=1, units="mg/dl", dia_h=6.0, timezone=tz,
                           basal=b(1.0), isf_mgdl=b(50.0), carb_ratio=b(10.0),
                           target_low_mgdl=b(100.0), target_high_mgdl=b(110.0))


def test_build_profile_uses_settings_and_flags_missing():
    prof, warn = build_profile(_snapshot(), {"max_iob": 6.0, "enable_smb": True}, at_ms=1_700_000_000_000)
    assert prof["current_basal"] == 1.0 and prof["sens"] == 50.0
    assert prof["target_bg"] == 105.0 and prof["max_iob"] == 6.0
    assert prof["enableSMB_always"] is True
    # nothing missing from the *profile* — the SMB caps are separately reported as
    # substituted, which is expected here since the settings dict does not carry them.
    assert not [w for w in warn if "profile is missing" in w or "settings missing" in w]


def test_build_profile_warns_when_max_iob_missing():
    _, warn = build_profile(_snapshot(), {}, at_ms=1_700_000_000_000)
    assert any("max_iob" in w for w in warn)


def test_build_profile_picks_time_of_day_block():
    b0, b12 = ProfileBlock(0, 0.8), ProfileBlock(43200, 1.6)  # 00:00 and 12:00 UTC
    snap = ProfileSnapshot(valid_from_ms=1, units="mg/dl", dia_h=6.0, timezone="UTC",
                           basal=[b0, b12], isf_mgdl=[ProfileBlock(0, 50.0)],
                           carb_ratio=[ProfileBlock(0, 10.0)],
                           target_low_mgdl=[ProfileBlock(0, 100.0)],
                           target_high_mgdl=[ProfileBlock(0, 110.0)])
    # a 14:00 UTC timestamp should select the 12:00 basal block (1.6)
    from datetime import datetime, timezone
    at = int(datetime(2023, 11, 15, 14, 0, tzinfo=timezone.utc).timestamp() * 1000)
    prof, _ = build_profile(snap, {"max_iob": 6.0}, at)
    assert prof["current_basal"] == 1.6


def test_from_cycle_returns_none_without_max_iob():
    cyc = DeviceStatusCycle(ts_ms=1_700_000_000_000, bg_mgdl=150, iob=1.0)
    entries = _entries(1_700_000_000_000, [(0, 150), (5, 148)])
    req, warn = from_cycle(cyc, _snapshot(), entries, settings={})
    assert req is None
    assert any("max_iob" in w for w in warn)


def test_from_cycle_builds_request_with_fidelity_warnings():
    cyc = DeviceStatusCycle(ts_ms=1_700_000_000_000, bg_mgdl=150, iob=1.0, sensitivity_ratio=0.9)
    entries = _entries(1_700_000_000_000, [(0, 150), (5, 148), (15, 140)])
    req, warn = from_cycle(cyc, _snapshot(), entries, settings={"max_iob": 6.0})
    assert req is not None
    assert req["glucose_status"]["glucose"] == 150
    assert req["autosens_data"]["ratio"] == 0.9
    assert req["profile"]["max_iob"] == 6.0
    # inherent fidelity limits are always disclosed
    assert any("activity unknown" in w for w in warn)
    assert any("currenttemp" in w for w in warn)


def test_negative_max_iob_is_clamped_like_apply_delta():
    """build_profile must clamp exactly as settings_delta does.

    Otherwise an unclamped negative baseline is diffed against a clamped altered run and
    the counterfactual reports a delivery delta that means nothing.
    """
    from replay.settings_delta import apply_delta

    prof, warn = build_profile(_snapshot(), {"max_iob": -3.0}, at_ms=1_700_000_000_000)
    assert prof["max_iob"] == 0.0
    assert any("negative" in w for w in warn)

    req = {"profile": dict(prof)}
    assert apply_delta(req, {"max_iob": -3.0})["profile"]["max_iob"] == prof["max_iob"]


def test_zero_smb_cap_survives_into_the_oref_profile():
    """A deliberate 'SMB off' must reach oref as 0, not be replaced by the 30-min default."""
    prof, _ = build_profile(_snapshot(),
                            {"max_iob": 6.0, "enable_smb": True, "max_smb_minutes": 0,
                             "max_uam_minutes": 0},
                            at_ms=1_700_000_000_000)
    assert prof["maxSMBBasalMinutes"] == 0
    assert prof["maxUAMSMBBasalMinutes"] == 0


def test_substituted_smb_caps_are_warned_about():
    """Permissive defaults are allowed, but never silently."""
    _, warn = build_profile(_snapshot(), {"max_iob": 6.0, "enable_smb": True},
                            at_ms=1_700_000_000_000)
    assert any("max_smb_minutes unknown" in w for w in warn)
    assert any("more SMB than your settings allow" in w for w in warn)
