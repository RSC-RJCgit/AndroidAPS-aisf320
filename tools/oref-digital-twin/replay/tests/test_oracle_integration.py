"""Integration test against the REAL oref0 via Node.

Skips automatically when Node or the installed oref0 package is unavailable (e.g. CI
without `npm install` in replay/oracle), so the suite stays green everywhere while still
exercising the genuine controller where it exists.
"""

import shutil

import pytest

from ingestion.models import DeviceStatusCycle, GlucoseReading, ProfileBlock, ProfileSnapshot
from replay import OrefOracle, from_cycle, run_counterfactual
from replay.oracle_bridge import ORACLE_DIR

_node = shutil.which("node")
_oref0 = (ORACLE_DIR / "node_modules" / "oref0").exists()
pytestmark = pytest.mark.skipif(
    not (_node and _oref0),
    reason="node and/or oref0 not installed in replay/oracle (run npm install there)",
)


def _snapshot():
    b = lambda v: [ProfileBlock(0, v)]
    return ProfileSnapshot(valid_from_ms=1, units="mg/dl", dia_h=6.0, timezone="UTC",
                           basal=b(1.0), isf_mgdl=b(50.0), carb_ratio=b(10.0),
                           target_low_mgdl=b(100.0), target_high_mgdl=b(100.0))


def _high_rising_cycle(base=1_700_000_000_000):
    cyc = DeviceStatusCycle(ts_ms=base, bg_mgdl=180, iob=0.3, sensitivity_ratio=1.0)
    entries = [GlucoseReading(ts_ms=base - m * 60_000, sgv_mgdl=v)
               for m, v in [(0, 180), (5, 174), (15, 165), (45, 150)]]
    return cyc, entries


def test_real_oref_produces_a_decision():
    cyc, entries = _high_rising_cycle()
    req, _ = from_cycle(cyc, _snapshot(), entries, settings={"max_iob": 6.0, "enable_smb": True})
    assert req is not None
    rt = OrefOracle().enacted([req])[0]
    assert rt is not None
    assert "eventualBG" in rt and rt.get("error") is None


def test_max_iob_zero_reduces_delivery_vs_baseline():
    cyc, entries = _high_rising_cycle()
    settings = {"max_iob": 6.0, "enable_smb": True, "max_smb_minutes": 60}
    req, _ = from_cycle(cyc, _snapshot(), entries, settings=settings)
    assert req is not None

    cf = run_counterfactual(OrefOracle(), [req], {"max_iob": 0.0}, ts_of=[cyc.ts_ms])
    # capping IOB at 0 cannot deliver MORE insulin into a high than the baseline
    assert cf.n_evaluated == 1
    assert cf.total_delta_u is not None and cf.total_delta_u <= 0.0
