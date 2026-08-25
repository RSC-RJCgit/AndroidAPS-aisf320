"""Coverage and gap analysis.

The quality ceiling of every downstream diagnosis is whatever the user's Nightscout
actually contains. This module surfaces holes rather than papering over them: it reports
the observed date range, CGM coverage, loop-cycle coverage, and the largest gaps, so a
report can say "I can't see this stretch" instead of silently analysing a partial record.
"""

from __future__ import annotations

from dataclasses import dataclass, field, asdict
from typing import Any

from .models import DeviceStatusCycle, GlucoseReading

CGM_NOMINAL_INTERVAL_MS = 5 * 60_000       # 5-minute CGM
LOOP_NOMINAL_INTERVAL_MS = 5 * 60_000      # oref runs ~every 5 min
CGM_GAP_MS = 15 * 60_000                   # > 15 min between readings = a gap
LOOP_GAP_MS = 15 * 60_000                  # > 15 min between cycles = loop was down
TOP_GAPS = 10


@dataclass
class Gap:
    start_ms: int
    end_ms: int
    minutes: float

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class StreamCoverage:
    name: str
    count: int
    first_ms: int | None
    last_ms: int | None
    span_hours: float
    expected: int          # docs we'd expect at nominal cadence across the span
    coverage_pct: float    # 100 * count / expected, capped at 100
    largest_gaps: list[Gap] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        d = asdict(self)
        d["largest_gaps"] = [g.to_dict() for g in self.largest_gaps]
        return d


def _gaps(ts_sorted: list[int], threshold_ms: int) -> list[Gap]:
    gaps: list[Gap] = []
    for a, b in zip(ts_sorted, ts_sorted[1:]):
        delta = b - a
        if delta > threshold_ms:
            gaps.append(Gap(start_ms=a, end_ms=b, minutes=round(delta / 60_000, 1)))
    gaps.sort(key=lambda g: g.minutes, reverse=True)
    return gaps[:TOP_GAPS]


def _stream_coverage(name: str, ts: list[int], nominal_ms: int, gap_ms: int) -> StreamCoverage:
    ts = sorted(t for t in ts if t is not None)
    if not ts:
        return StreamCoverage(name, 0, None, None, 0.0, 0, 0.0, [])
    first, last = ts[0], ts[-1]
    span_ms = max(last - first, 0)
    expected = max(int(span_ms / nominal_ms) + 1, 1)
    coverage = min(100.0, round(100.0 * len(ts) / expected, 1))
    return StreamCoverage(
        name=name,
        count=len(ts),
        first_ms=first,
        last_ms=last,
        span_hours=round(span_ms / 3_600_000, 1),
        expected=expected,
        coverage_pct=coverage,
        largest_gaps=_gaps(ts, gap_ms),
    )


def cgm_coverage(readings: list[GlucoseReading]) -> StreamCoverage:
    ts = [r.ts_ms for r in readings if r.sgv_mgdl is not None]
    return _stream_coverage("cgm", ts, CGM_NOMINAL_INTERVAL_MS, CGM_GAP_MS)


def loop_coverage(cycles: list[DeviceStatusCycle]) -> StreamCoverage:
    ts = [c.ts_ms for c in cycles]
    return _stream_coverage("loop", ts, LOOP_NOMINAL_INTERVAL_MS, LOOP_GAP_MS)


def treatment_summary(treatments: list) -> dict[str, int]:
    counts: dict[str, int] = {}
    for t in treatments:
        key = t.event_type or "unknown"
        counts[key] = counts.get(key, 0) + 1
    return dict(sorted(counts.items(), key=lambda kv: kv[1], reverse=True))
