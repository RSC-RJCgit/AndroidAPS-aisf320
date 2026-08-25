# replay

The oref `determine-basal` replay oracle. Runs the **real** oref0 controller (pinned in
`oracle/package.json`) over reconstructed inputs to price how a settings change flips a
decision. Decision-level counterfactual only — never a BG counterfactual. We do not
reimplement determine-basal. See [../DESIGN.md](../DESIGN.md) §2.

## Pieces

| File | Role |
|---|---|
| `oracle/determine.js` | Node wrapper calling real `oref0/lib/determine-basal`. Reads `{requests:[...]}` on stdin, returns `{results:[...]}`. |
| `oracle_bridge.py` | Spawns the Node oracle (runner injectable for offline tests). |
| `settings_delta.py` | Applies a friendly settings change to a request's oref profile (max_iob, targets, ISF, SMB flags, SMB minutes…). Unknown keys raise. |
| `counterfactual.py` | Runs baseline vs altered through the oracle and diffs the enacted decision per cycle. |
| `inputs.py` | Reconstructs a determine-basal request from a devicestatus cycle + Nightscout profile + settings, with explicit fidelity flags. |

## Setup

```bash
cd replay/oracle && npm install     # installs pinned oref0 (node_modules is gitignored)
```

## Usage

```python
from replay import OrefOracle, from_cycle, run_counterfactual

req, warnings = from_cycle(cycle, profile_snapshot, entries, settings={"max_iob": 6.0, "enable_smb": True})
cf = run_counterfactual(OrefOracle(), [req], {"max_iob": 3.0})
print(cf.n_changed, cf.mean_delta_u, cf.caveat)
```

## Fidelity (read this)

devicestatus logs the loop's *decision*, not every input. Two inputs are approximated
from devicestatus alone — the running `currenttemp` (assumed none) and insulin `activity`
(assumed 0, degrading bgi/eventualBG) — and each request carries warnings saying so. The
**counterfactual diff is robust to this**: the same approximation applies to baseline and
altered runs, so it cancels in the delta. High-fidelity absolute replay (not just diffs)
should recompute IOB and glucose_status from raw entries/treatments via oref0's own libs —
a documented follow-up.

## Tests

`pytest replay/tests` — unit tests use a fake oracle (no Node needed); the integration
tests run real oref0 and skip automatically if it is not installed.
