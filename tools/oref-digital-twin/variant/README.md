# variant

Algorithm-variant detection: AAPS stock SMB / Dynamic ISF / AutoISF, Trio dynamic
ISF-CR, and Trio middleware. Runs first and gates all advice; downgrades to
diagnosis-only for unmodelled variants or middleware. See [../DESIGN.md](../DESIGN.md) §4.

## What it produces

A `VariantVerdict` with the classified `variant`, `platform`, `isf_mode`, a
**confidence**, and — crucially — an **advisability**:

| Advisability | Meaning |
|---|---|
| `FULL` | A modelled AAPS/Trio controller — the replay oracle may run. |
| `DIAGNOSIS_ONLY` | Middleware or a fork (e.g. Boost) rewrites behaviour — describe, don't replay. |
| `OUT_OF_SCOPE` | Loop / unknown controller — cannot classify or replay. |

We never advise on a controller we are not actually replaying.

## How it works

`signals.py` holds an editable table of heuristic reason-string / device markers and
extracts the matching set per cycle. `detect.py` aggregates those markers **across the
whole pull** (a single stray reason string cannot swing the verdict), infers platform
from device strings or, failing that, from platform-specific features (autoISF ⇒ AAPS,
middleware ⇒ Trio), and maps platform + ISF mode to a variant.

## Honest caveat

The markers are heuristics, not a published spec — no system emits a stable "which
algorithm am I" field. They must be validated against real devicestatus corpora before
the confidence numbers are relied upon; the evidence counts are returned in the verdict
so the classification is auditable, not a black box.

## Tests

```bash
pytest variant/tests
```
