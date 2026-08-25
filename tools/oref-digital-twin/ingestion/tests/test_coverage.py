from ingestion.coverage import cgm_coverage, loop_coverage, treatment_summary
from ingestion.models import DeviceStatusCycle, GlucoseReading, Treatment

FIVE_MIN = 5 * 60_000


def _cgm_series(n: int, start: int = 1_700_000_000_000, step: int = FIVE_MIN):
    return [GlucoseReading(ts_ms=start + i * step, sgv_mgdl=100 + i) for i in range(n)]


def test_full_coverage_is_100():
    cov = cgm_coverage(_cgm_series(12))  # one hour, 5-min cadence
    assert cov.count == 12
    assert cov.coverage_pct == 100.0
    assert cov.largest_gaps == []


def test_gap_detected_and_coverage_drops():
    series = _cgm_series(6)  # 30 min
    # jump forward 1 hour -> a 60-min gap, then 6 more readings
    later_start = series[-1].ts_ms + 60 * 60_000
    series += [GlucoseReading(ts_ms=later_start + i * FIVE_MIN, sgv_mgdl=120 + i) for i in range(6)]
    cov = cgm_coverage(series)
    assert cov.count == 12
    assert cov.coverage_pct < 100.0
    assert cov.largest_gaps and cov.largest_gaps[0].minutes == 60.0


def test_empty_stream_is_safe():
    cov = cgm_coverage([])
    assert cov.count == 0 and cov.coverage_pct == 0.0 and cov.first_ms is None


def test_loop_coverage_counts_cycles():
    cycles = [DeviceStatusCycle(ts_ms=1_700_000_000_000 + i * FIVE_MIN) for i in range(10)]
    cov = loop_coverage(cycles)
    assert cov.name == "loop" and cov.count == 10 and cov.coverage_pct == 100.0


def test_treatment_summary_counts_by_type():
    tx = [
        Treatment(ts_ms=1, event_type="SMB"),
        Treatment(ts_ms=2, event_type="SMB"),
        Treatment(ts_ms=3, event_type="Meal Bolus"),
        Treatment(ts_ms=4, event_type=None),
    ]
    counts = treatment_summary(tx)
    assert counts["SMB"] == 2 and counts["Meal Bolus"] == 1 and counts["unknown"] == 1
