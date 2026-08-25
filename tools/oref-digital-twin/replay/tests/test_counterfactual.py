"""Counterfactual-engine tests with a FAKE oracle (no Node needed).

The fake runner returns a decision that depends on the profile's max_iob, so applying a
max_iob delta produces a deterministic, checkable decision change.
"""

import json

from replay.counterfactual import Decision, decision_from_rt, run_counterfactual
from replay.oracle_bridge import OrefOracle


def _fake_runner(requests):
    """SMB scales with max_iob: rt.units = max_iob * 0.1, no temp basal."""
    out = []
    for r in requests:
        max_iob = r["profile"].get("max_iob", 0.0)
        out.append({"ok": True, "rt": {"rate": 0.0, "duration": 30, "units": round(max_iob * 0.1, 3)}})
    return out


def _reqs(n=5, max_iob=6.0):
    return [{"profile": {"max_iob": max_iob, "min_bg": 100, "max_bg": 100, "target_bg": 100}}
            for _ in range(n)]


def test_delivery_30min_scalar():
    d = Decision(rate=1.2, duration=30, smb_u=0.6)
    assert d.delivery_30min_u() == round(0.6 + 1.2 * 0.5, 3)  # 1.2
    d2 = Decision(rate=2.0, duration=15, smb_u=0.0)
    assert d2.delivery_30min_u() == round(2.0 * 0.25, 3)      # 0.5


def test_counterfactual_detects_decision_change():
    oracle = OrefOracle(runner=_fake_runner)
    cf = run_counterfactual(oracle, _reqs(n=5, max_iob=6.0), {"max_iob": 3.0},
                            ts_of=[i for i in range(5)])
    # baseline SMB 0.6, altered 0.3 -> every cycle changed by -0.3 U
    assert cf.n_cycles == 5 and cf.n_evaluated == 5 and cf.n_changed == 5
    assert cf.mean_delta_u == -0.3
    assert cf.total_delta_u == -1.5
    assert cf.examples and cf.examples[0].delta_u == -0.3


def test_no_change_when_delta_is_noop_relative_to_decision():
    oracle = OrefOracle(runner=_fake_runner)
    # sens does not affect the fake decision -> no changed cycles
    cf = run_counterfactual(oracle, _reqs(n=4), {"sens": 40.0})
    assert cf.n_changed == 0
    assert cf.mean_delta_u == 0.0


def test_errored_cycles_are_skipped_not_counted():
    def runner(requests):
        # first cycle errors, rest ok
        res = _fake_runner(requests)
        res[0] = {"ok": False, "error": "boom"}
        return res

    oracle = OrefOracle(runner=runner)
    cf = run_counterfactual(oracle, _reqs(n=4, max_iob=6.0), {"max_iob": 0.0})
    assert cf.n_cycles == 4 and cf.n_evaluated == 3  # one skipped


def test_result_is_json_serialisable():
    oracle = OrefOracle(runner=_fake_runner)
    cf = run_counterfactual(oracle, _reqs(), {"max_iob": 0.0})
    json.dumps(cf.to_dict())
    assert "not the resulting blood glucose" in cf.to_dict()["caveat"]


def test_decision_from_rt_none():
    assert decision_from_rt(None) is None
