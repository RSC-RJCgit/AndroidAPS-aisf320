import json
from datetime import datetime, timezone

from ingestion.coverage import cgm_coverage, loop_coverage, treatment_summary
from ingestion.models import (
    DeviceStatusCycle,
    GlucoseReading,
    ProfileBlock,
    ProfileSnapshot,
    Treatment,
)
from ingestion.pull import PullResult
from diagnostics import run_diagnostics


def ms(h, mi=0, day=15):
    return int(datetime(2023, 11, day, h, mi, tzinfo=timezone.utc).timestamp() * 1000)


def _profile():
    b = lambda v: [ProfileBlock(0, v)]
    return ProfileSnapshot(
        valid_from_ms=1, units="mg/dl", dia_h=6.0, timezone="UTC",
        basal=b(1.0), isf_mgdl=b(50.0), carb_ratio=b(10.0),
        target_low_mgdl=b(100.0), target_high_mgdl=b(110.0),
    )


def _build_pull():
    start, end = ms(0, 0, 15), ms(0, 0, 16)  # one day
    entries = [GlucoseReading(ts_ms=start + i * 5 * 60_000, sgv_mgdl=120) for i in range(200)]
    cycles = [DeviceStatusCycle(ts_ms=start + i * 5 * 60_000, iob=1.0) for i in range(200)]
    tx = [Treatment(ts_ms=start + 3_600_000, event_type="Bolus", insulin_u=4.0)]
    return PullResult(
        base_url="https://example.test",
        start_ms=start, end_ms=end,
        entries=entries, treatments=tx, devicestatus=cycles, profiles=[_profile()],
        cgm=cgm_coverage(entries), loop=loop_coverage(cycles),
        treatment_counts=treatment_summary(tx), dropped={},
    )


def test_run_diagnostics_produces_serialisable_report():
    pull = _build_pull()
    report = run_diagnostics(pull, variant={"variant": "aaps_smb", "advisability": "full"})

    d = report.to_dict()
    json.dumps(d)  # must not raise

    assert d["glycemia"]["mean_mgdl"] == 120.0
    assert d["variant"]["variant"] == "aaps_smb"
    # findings sorted most-severe first
    ranks = [{"critical": 2, "warning": 1, "info": 0}[f["severity"]] for f in d["findings"]]
    assert ranks == sorted(ranks, reverse=True)
    # a clean 120-flat record should meet consensus
    assert any(f["key"] == "meets_consensus" for f in d["findings"])


def test_counts_match_findings():
    report = run_diagnostics(_build_pull())
    counts = report.counts()
    assert sum(counts.values()) == len(report.findings)
