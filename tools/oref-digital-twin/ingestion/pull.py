"""Orchestration: one full pull -> a normalised, coverage-annotated result.

This is the module boundary the rest of the system consumes. It does not analyse the
data (that is `diagnostics/`, `variant/`, `replay/`); it produces the tidy timeline they
assume already exists.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from .client import NightscoutClient
from .config import NightscoutConfig
from .coverage import (
    StreamCoverage,
    cgm_coverage,
    loop_coverage,
    treatment_summary,
)
from .models import DeviceStatusCycle, GlucoseReading, ProfileSnapshot, Treatment
from .normalise import (
    normalise_devicestatus,
    normalise_entry,
    normalise_profile,
    normalise_treatment,
)


@dataclass
class PullResult:
    base_url: str
    start_ms: int
    end_ms: int
    entries: list[GlucoseReading]
    treatments: list[Treatment]
    devicestatus: list[DeviceStatusCycle]
    profiles: list[ProfileSnapshot]
    cgm: StreamCoverage
    loop: StreamCoverage
    treatment_counts: dict[str, int]
    dropped: dict[str, int] = field(default_factory=dict)  # docs with no usable timestamp

    @property
    def devicestatus_present(self) -> bool:
        return len(self.devicestatus) > 0

    def warnings(self) -> list[str]:
        w: list[str] = []
        if not self.devicestatus_present:
            w.append(
                "No oref devicestatus found — the replay oracle and most decision-level "
                "diagnostics cannot run. Confirm the loop uploads devicestatus."
            )
        if self.cgm.coverage_pct < 85.0 and self.cgm.count:
            w.append(f"CGM coverage {self.cgm.coverage_pct}% — gaps present; see cgm.largest_gaps.")
        if self.loop.count and self.loop.coverage_pct < 85.0:
            w.append(f"Loop coverage {self.loop.coverage_pct}% — the loop was down for stretches.")
        if not self.profiles:
            w.append("No profile document found — basal/ISF/CR/targets unavailable from Nightscout.")
        return w

    def summary(self) -> dict[str, Any]:
        return {
            "base_url": self.base_url,
            "range_ms": [self.start_ms, self.end_ms],
            "counts": {
                "entries": len(self.entries),
                "treatments": len(self.treatments),
                "devicestatus": len(self.devicestatus),
                "profiles": len(self.profiles),
            },
            "dropped": self.dropped,
            "cgm": self.cgm.to_dict(),
            "loop": self.loop.to_dict(),
            "treatment_counts": self.treatment_counts,
            "warnings": self.warnings(),
        }

    def to_dict(self) -> dict[str, Any]:
        return {
            **self.summary(),
            "data": {
                "entries": [r.to_dict() for r in self.entries],
                "treatments": [t.to_dict() for t in self.treatments],
                "devicestatus": [c.to_dict() for c in self.devicestatus],
                "profiles": [p.to_dict() for p in self.profiles],
            },
        }


def _normalise_all(raw: list[dict], fn) -> tuple[list, int]:
    out, dropped = [], 0
    for doc in raw:
        m = fn(doc)
        if m is None:
            dropped += 1
        else:
            out.append(m)
    return out, dropped


def pull_from_raw(
    base_url: str,
    start_ms: int,
    end_ms: int,
    raw_entries: list[dict],
    raw_treatments: list[dict],
    raw_devicestatus: list[dict],
    raw_profiles: list[dict],
) -> PullResult:
    """Normalise + assemble already-fetched Nightscout JSON into a PullResult.

    This is the seam the browser (Pyodide) uses: the JS side does the `fetch` (so the token
    and CORS live in the browser) and hands the raw arrays here. `run_pull` is the same
    thing with the fetching done by the Python client.
    """
    entries, d_e = _normalise_all(raw_entries, normalise_entry)
    treatments, d_t = _normalise_all(raw_treatments, normalise_treatment)
    devicestatus, d_d = _normalise_all(raw_devicestatus, normalise_devicestatus)
    profiles, d_p = _normalise_all(raw_profiles, normalise_profile)

    entries.sort(key=lambda r: r.ts_ms)
    treatments.sort(key=lambda t: t.ts_ms)
    devicestatus.sort(key=lambda c: c.ts_ms)

    return PullResult(
        base_url=base_url,
        start_ms=start_ms,
        end_ms=end_ms,
        entries=entries,
        treatments=treatments,
        devicestatus=devicestatus,
        profiles=profiles,
        cgm=cgm_coverage(entries),
        loop=loop_coverage(devicestatus),
        treatment_counts=treatment_summary(treatments),
        dropped={
            "entries": d_e,
            "treatments": d_t,
            "devicestatus": d_d,
            "profiles": d_p,
        },
    )


def run_pull(
    config: NightscoutConfig,
    start_ms: int,
    end_ms: int,
    *,
    client: NightscoutClient | None = None,
    window_days: int = 7,
) -> PullResult:
    """Fetch from Nightscout and assemble a PullResult (server/CLI path)."""
    client = client or NightscoutClient(config)
    return pull_from_raw(
        config.base_url, start_ms, end_ms,
        client.fetch_entries(start_ms, end_ms, window_days),
        client.fetch_treatments(start_ms, end_ms, window_days),
        client.fetch_devicestatus(start_ms, end_ms, window_days),
        client.fetch_profiles(),
    )
