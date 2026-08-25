"""Decision-level counterfactual (DESIGN §2).

Given a set of baseline determine-basal requests and a settings delta, run the real oref0
oracle on both baseline and altered requests and diff the **enacted decision** per cycle.

This prices how a setting change flips what the controller would deliver. It is emphatically
NOT a blood-glucose counterfactual — the BG that would have followed the altered dose is
unknowable without a glucodynamic model (the identification constraint). Every result says
so.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Any

from .oracle_bridge import OrefOracle
from .settings_delta import apply_delta

# a decision is "changed" if 30-min projected delivery differs by more than this (U)
DELIVERY_EPS_U = 0.05


@dataclass
class Decision:
    rate: float | None          # temp basal rate, U/h (absolute)
    duration: float | None      # minutes
    smb_u: float | None         # SMB bolus, U

    def delivery_30min_u(self) -> float:
        """Gross insulin the decision would deliver over the next 30 minutes.

        SMB is immediate; the temp basal contributes rate * (min(duration,30)/60). This is a
        comparable scalar for diffing decisions, not a net-vs-scheduled figure.
        """
        smb = self.smb_u or 0.0
        rate = self.rate or 0.0
        dur = min(self.duration or 0.0, 30.0)
        return round(smb + rate * (dur / 60.0), 3)


def decision_from_rt(rt: dict | None) -> Decision | None:
    if rt is None:
        return None
    return Decision(rate=rt.get("rate"), duration=rt.get("duration"), smb_u=rt.get("units"))


@dataclass
class CycleDiff:
    index: int
    ts_ms: int | None
    baseline_u: float
    altered_u: float
    delta_u: float
    changed: bool

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class CounterfactualResult:
    label: str
    delta: dict[str, Any]
    n_cycles: int
    n_evaluated: int          # cycles where both baseline and altered produced a decision
    n_changed: int
    mean_delta_u: float | None
    total_delta_u: float | None
    examples: list[CycleDiff] = field(default_factory=list)
    caveat: str = (
        "Decision-level only: this prices the change in what the controller would deliver, "
        "not the resulting blood glucose (no glucodynamic counterfactual exists)."
    )

    def to_dict(self) -> dict[str, Any]:
        d = asdict(self)
        d["examples"] = [e.to_dict() for e in self.examples]
        return d


def run_counterfactual(
    oracle: OrefOracle,
    baseline_requests: list[dict],
    delta: dict[str, Any],
    *,
    label: str | None = None,
    ts_of: list[int] | None = None,
    max_examples: int = 10,
) -> CounterfactualResult:
    altered_requests = [apply_delta(r, delta) for r in baseline_requests]
    base = oracle.enacted(baseline_requests)
    alt = oracle.enacted(altered_requests)

    diffs: list[CycleDiff] = []
    for i, (b_rt, a_rt) in enumerate(zip(base, alt)):
        b, a = decision_from_rt(b_rt), decision_from_rt(a_rt)
        if b is None or a is None:
            continue
        bu, au = b.delivery_30min_u(), a.delivery_30min_u()
        du = round(au - bu, 3)
        diffs.append(CycleDiff(
            index=i,
            ts_ms=(ts_of[i] if ts_of and i < len(ts_of) else None),
            baseline_u=bu, altered_u=au, delta_u=du,
            changed=abs(du) > DELIVERY_EPS_U,
        ))

    changed = [d for d in diffs if d.changed]
    total = round(sum(d.delta_u for d in diffs), 3) if diffs else None
    mean = round(total / len(diffs), 3) if diffs else None
    # surface the biggest movers as examples
    examples = sorted(changed, key=lambda d: abs(d.delta_u), reverse=True)[:max_examples]

    return CounterfactualResult(
        label=label or ", ".join(f"{k}={v}" for k, v in delta.items()),
        delta=delta,
        n_cycles=len(baseline_requests),
        n_evaluated=len(diffs),
        n_changed=len(changed),
        mean_delta_u=mean,
        total_delta_u=total,
        examples=examples,
    )
