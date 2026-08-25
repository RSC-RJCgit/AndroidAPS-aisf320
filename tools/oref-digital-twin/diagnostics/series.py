"""Small time-series helpers shared by the diagnostic checks.

Kept deliberately dependency-free (stdlib only) and pure.
"""

from __future__ import annotations

import bisect
from datetime import datetime, timezone
from typing import Iterable

MGDL_PER_MMOL = 18.0182


def to_mmol(mgdl: float) -> float:
    return round(mgdl / MGDL_PER_MMOL, 1)


class TimeIndex:
    """Nearest-value lookup over (timestamp_ms, value) points.

    `nearest(ts, tol_ms)` returns the value whose timestamp is closest to `ts`, provided
    it is within `tol_ms`; otherwise ``None``.
    """

    def __init__(self, points: Iterable[tuple[int, float]]):
        pts = sorted((t, v) for t, v in points if t is not None and v is not None)
        self._ts = [t for t, _ in pts]
        self._vals = [v for _, v in pts]

    def __len__(self) -> int:
        return len(self._ts)

    def nearest(self, ts: int, tol_ms: int) -> float | None:
        if not self._ts:
            return None
        i = bisect.bisect_left(self._ts, ts)
        best: float | None = None
        best_d = tol_ms + 1
        for j in (i - 1, i):
            if 0 <= j < len(self._ts):
                d = abs(self._ts[j] - ts)
                if d <= tol_ms and d < best_d:
                    best_d, best = d, self._vals[j]
        return best

    def min_between(self, start_ms: int, end_ms: int) -> float | None:
        lo = bisect.bisect_left(self._ts, start_ms)
        hi = bisect.bisect_right(self._ts, end_ms)
        window = self._vals[lo:hi]
        return min(window) if window else None


def percentile(sorted_values: list[float], p: float) -> float | None:
    """Linear-interpolation percentile of an already-sorted list. p in [0, 100]."""
    n = len(sorted_values)
    if n == 0:
        return None
    if n == 1:
        return sorted_values[0]
    rank = (p / 100.0) * (n - 1)
    lo = int(rank)
    hi = min(lo + 1, n - 1)
    frac = rank - lo
    return sorted_values[lo] + (sorted_values[hi] - sorted_values[lo]) * frac


def local_hour_and_date(ts_ms: int, tz: str | None) -> tuple[int, str]:
    """Local hour (0-23) and ISO date for a timestamp, using the profile timezone.

    Falls back to UTC when the timezone is missing or unrecognised. Uses zoneinfo so DST
    is handled correctly.
    """
    tzinfo = timezone.utc
    if tz:
        try:
            from zoneinfo import ZoneInfo

            tzinfo = ZoneInfo(tz)
        except Exception:
            tzinfo = timezone.utc
    dt = datetime.fromtimestamp(ts_ms / 1000, tz=tzinfo)
    return dt.hour, dt.date().isoformat()
