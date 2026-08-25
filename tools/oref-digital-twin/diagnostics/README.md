# diagnostics

Deterministic sanity checks plus out-of-sample pattern detection. The honest, safe core —
outcome-based, needs no counterfactual, and runs for **every** variant (including those
the replay oracle cannot touch). See [../DESIGN.md](../DESIGN.md) §6.

Every output is a `Finding`: **descriptive, never prescriptive**. Diagnostics flag what
happened and what is risky; they do not emit "set X to Y" (that is the gated
recommendation layer).

## What it checks

| Module | Checks |
|---|---|
| `glycemia.py` | TIR / TBR<70 / TBR<54 / TAR / TING / GMI / CV vs the consensus targets. The absolute time-below-range limits are the project's safety floors (TBR<54 > 1% is CRITICAL). |
| `sanity.py` | Profile hard limits — DIA, inverted/implausible targets, ISF, CR, negative basal — plus a rough daily-insulin estimate (with caveats). |
| `patterns.py` | (1) overnight SMBs at high IOB and whether a low followed — within-subject, association-only; (2) predicted vs realised BG bias at 30 min; (3) nocturnal hypo episodes. |

## Output

`run_diagnostics(pull, variant)` → a `DiagnosticsReport`: the glycaemic summary plus
findings sorted most-severe first, with severity counts. Fully JSON-serialisable.

```python
from ingestion import run_pull
from variant import detect_variant
from diagnostics import run_diagnostics

pull = run_pull(config, start_ms, end_ms)
verdict = detect_variant(pull.devicestatus, dropped_no_oref=pull.dropped["devicestatus"])
report = run_diagnostics(pull, variant=verdict.to_dict())
```

## Honesty rules baked in

- Association ≠ causation: the SMB/low pattern says so in its own text.
- Within-subject where possible: "high IOB" is the user's own overnight p75, not a
  population constant.
- Insufficient data is stated, not hidden: checks emit an explicit "not enough data"
  finding rather than a spurious result.

## Tests

```bash
pytest diagnostics/tests
```
