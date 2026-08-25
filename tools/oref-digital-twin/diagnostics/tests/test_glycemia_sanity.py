from ingestion.models import GlucoseReading, ProfileBlock, ProfileSnapshot, Treatment
from diagnostics.glycemia import glycemia_findings, summarise
from diagnostics.sanity import profile_findings


def _readings(values, start=1_700_000_000_000, step=300_000):
    return [GlucoseReading(ts_ms=start + i * step, sgv_mgdl=v) for i, v in enumerate(values)]


def _keys(findings):
    return {f.key for f in findings}


def test_summary_basic_stats():
    s = summarise(_readings([100, 100, 100, 100]), days=1)
    assert s.n_readings == 4
    assert s.mean_mgdl == 100.0
    assert s.tir_70_180 == 100.0
    assert s.tbr_lt70 == 0.0 and s.tbr_lt54 == 0.0
    assert s.cv_pct == 0.0
    assert s.gmi_pct == round(3.31 + 0.02392 * 100, 1)


def test_severe_hypo_triggers_critical_floor():
    # 3 of 100 readings below 54 -> 3% > 1% limit
    vals = [40, 40, 40] + [120] * 97
    s = summarise(_readings(vals), days=1)
    findings = glycemia_findings(s)
    crit = [f for f in findings if f.key == "tbr_lt54_over_limit"]
    assert crit and crit[0].severity.value == "critical"


def test_tbr70_and_low_tir_flagged():
    vals = [60] * 10 + [120] * 50 + [300] * 40  # 10% <70, TIR 50%
    s = summarise(_readings(vals), days=1)
    keys = _keys(glycemia_findings(s))
    assert "tbr_lt70_over_limit" in keys
    assert "tir_below_target" in keys


def test_clean_record_gets_positive_note():
    vals = [110] * 100
    s = summarise(_readings(vals), days=1)
    keys = _keys(glycemia_findings(s))
    assert "meets_consensus" in keys


def test_empty_readings_warns():
    s = summarise([], days=1)
    assert s.n_readings == 0
    assert "no_cgm" in _keys(glycemia_findings(s))


def _profile(dia=6.0, tz="Europe/London", basal_val=0.85,
             target_low=100.0, target_high=110.0, isf=50.0, cr=10.0):
    b = lambda v: [ProfileBlock(0, v)]
    return ProfileSnapshot(
        valid_from_ms=1, units="mg/dl", dia_h=dia, timezone=tz,
        basal=b(basal_val), isf_mgdl=b(isf), carb_ratio=b(cr),
        target_low_mgdl=b(target_low), target_high_mgdl=b(target_high),
    )


def test_profile_ok_reports_daily_insulin_only():
    tx = [Treatment(ts_ms=1, event_type="Bolus", insulin_u=5.0)]
    findings = profile_findings([_profile()], tx, days=1)
    keys = _keys(findings)
    assert "daily_insulin" in keys
    assert "dia_short" not in keys and "target_inverted" not in keys


def test_short_dia_flagged():
    assert "dia_short" in _keys(profile_findings([_profile(dia=3.0)], [], days=1))


def test_inverted_target_is_critical():
    findings = profile_findings([_profile(target_low=120.0, target_high=100.0)], [], days=1)
    inv = [f for f in findings if f.key == "target_inverted"]
    assert inv and inv[0].severity.value == "critical"


def test_implausible_isf_flagged():
    assert "isf_out_of_range" in _keys(profile_findings([_profile(isf=1.0)], [], days=1))


def test_no_profile_warns():
    assert "no_profile" in _keys(profile_findings([], [], days=1))


def test_daily_basal_integrates_blocks():
    # two 12h basal blocks of 1.0 and 2.0 U/h -> 12 + 24 = 36 U/day
    p = ProfileSnapshot(
        valid_from_ms=1, units="mg/dl", dia_h=6.0, timezone="UTC",
        basal=[ProfileBlock(0, 1.0), ProfileBlock(43200, 2.0)],
        isf_mgdl=[ProfileBlock(0, 50.0)], carb_ratio=[ProfileBlock(0, 10.0)],
        target_low_mgdl=[ProfileBlock(0, 100.0)], target_high_mgdl=[ProfileBlock(0, 110.0)],
    )
    findings = profile_findings([p], [], days=1)
    di = next(f for f in findings if f.key == "daily_insulin")
    assert di.evidence["daily_basal_u"] == 36.0
