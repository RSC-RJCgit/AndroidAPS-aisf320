from datetime import datetime, timezone

from ingestion.models import DeviceStatusCycle, GlucoseReading, Treatment
from diagnostics.patterns import (
    nocturnal_hypos,
    predicted_vs_realised,
    smb_high_iob_overnight,
)

TZ = "UTC"


def ms(h, mi=0, day=15):
    return int(datetime(2023, 11, day, h, mi, tzinfo=timezone.utc).timestamp() * 1000)


def test_smb_high_iob_overnight_with_low_follow():
    # 30 overnight cycles: first 20 at IOB 1.0, last 10 at IOB 4.0 -> p75 = 4.0
    cycles = []
    for i in range(30):
        t = ms(2) + i * 5 * 60_000       # 02:00 + 5-min steps
        iob = 1.0 if i < 20 else 4.0
        cycles.append(DeviceStatusCycle(ts_ms=t, iob=iob))

    smb_ts = ms(2) + 25 * 5 * 60_000     # coincides with a high-IOB (4.0) cycle
    tx = [Treatment(ts_ms=smb_ts, event_type="SMB", insulin_u=0.6, is_smb=True)]

    # BG falls to 55 within 2h of the SMB
    entries = [GlucoseReading(ts_ms=smb_ts + j * 5 * 60_000, sgv_mgdl=150 - j * 8) for j in range(13)]

    findings = smb_high_iob_overnight(tx, cycles, entries, TZ)
    f = next(f for f in findings if f.key == "smb_high_iob_overnight")
    assert f.evidence["high_iob_smbs"] == 1
    assert f.evidence["followed_by_low"] == 1
    assert f.severity.value == "warning"


def test_smb_high_iob_insufficient_data():
    cycles = [DeviceStatusCycle(ts_ms=ms(2) + i * 5 * 60_000, iob=1.0) for i in range(5)]
    findings = smb_high_iob_overnight([], cycles, [], TZ)
    assert findings[0].key == "smb_high_iob_insufficient"


def test_daytime_smb_not_counted_overnight():
    cycles = [DeviceStatusCycle(ts_ms=ms(2) + i * 5 * 60_000, iob=2.0) for i in range(30)]
    # SMB at 14:00 (daytime) must not be an overnight SMB
    tx = [Treatment(ts_ms=ms(14), event_type="SMB", insulin_u=0.6, is_smb=True)]
    findings = smb_high_iob_overnight(tx, cycles, [], TZ)
    # no overnight SMBs -> the "none" branch
    assert findings[0].key == "smb_high_iob_none"
    assert findings[0].evidence.get("overnight_smbs", 0) == 0 or "overnight_smbs" not in findings[0].evidence


def test_predicted_vs_realised_bias():
    cycles, entries = [], []
    for i in range(40):
        t = ms(0) + i * 15 * 60_000
        actual = 120.0
        # pred_iob[6] (30 min) set 20 above the realised value -> bias +20
        pred = [130.0] * 6 + [actual + 20.0] + [140.0]
        cycles.append(DeviceStatusCycle(ts_ms=t, pred_iob=pred))
        entries.append(GlucoseReading(ts_ms=t + 30 * 60_000, sgv_mgdl=actual))
    findings = predicted_vs_realised(cycles, entries)
    f = next(f for f in findings if f.key == "pred_vs_realised")
    assert f.evidence["bias_mgdl"] == 20.0
    assert f.severity.value == "warning"


def test_predicted_vs_realised_insufficient():
    findings = predicted_vs_realised([], [])
    assert findings[0].key == "pred_vs_realised_insufficient"


def test_nocturnal_hypo_episodes_split_by_gap():
    entries = [
        GlucoseReading(ts_ms=ms(2, 0), sgv_mgdl=60),
        GlucoseReading(ts_ms=ms(2, 5), sgv_mgdl=58),
        GlucoseReading(ts_ms=ms(2, 10), sgv_mgdl=65),   # same episode
        GlucoseReading(ts_ms=ms(3, 0), sgv_mgdl=55),    # +50 min gap -> new episode
        GlucoseReading(ts_ms=ms(12, 0), sgv_mgdl=60),   # daytime, ignored
    ]
    findings = nocturnal_hypos(entries, TZ)
    assert findings and findings[0].key == "nocturnal_hypos"
    assert findings[0].evidence["episodes"] == 2


def test_no_nocturnal_hypos_returns_nothing():
    entries = [GlucoseReading(ts_ms=ms(2), sgv_mgdl=120)]
    assert nocturnal_hypos(entries, TZ) == []
