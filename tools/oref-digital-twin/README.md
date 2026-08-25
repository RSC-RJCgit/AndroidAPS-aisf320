# oref-digital-twin

Advisory decision-support for **AAPS** and **Trio** insulin-dosing settings.

A user supplies their current settings and read-only access to their Nightscout data.
The service returns a report that explains what their loop is doing, flags what is
risky, and proposes settings changes **only as trials to be tested** — never as
instructions to apply blind.

> **Read-only, advisory-only.** This project never writes to Nightscout, a pump, or a
> loop. It is not medical advice. Settings changes are hypotheses to discuss with your
> clinician and trial deliberately — not instructions.

## The idea in one line

There is no glucodynamic simulator, so the tool does not predict blood glucose. What it
*can* twin is the **controller**: oref `determine-basal` is open and deterministic, so we
replay the real controller under altered settings to price how a change flips a
**decision** — while stating plainly that the resulting glucose is unknowable.

See **[DESIGN.md](DESIGN.md)** for the full architecture, the identification constraint,
algorithm-variant handling, the replay oracle, and the safety/privacy/regulatory
posture.

## Status

Early scaffold. See DESIGN.md §12 for phasing. Nothing here doses insulin or writes
anywhere.

## Layout

| Path | Purpose |
|---|---|
| `DESIGN.md` | Architecture and rationale. Start here. |
| `ingestion/` | Nightscout pulls (`devicestatus`, `entries`, `treatments`, `profile`) → common schema. |
| `variant/` | Algorithm-variant detection (AAPS stock/DynISF/AutoISF, Trio, middleware). |
| `diagnostics/` | Deterministic sanity checks + out-of-sample pattern detection. |
| `replay/` | oref `determine-basal` replay oracle (decision-level counterfactuals). |
| `settings/` | Settings ingestion — screenshots and client-side encrypted-prefs decrypt. |
| `web/` | Thin front door: connect/upload → async job → structured report. |

## AndroidAPS UKF3426 integration

This copy lives under `tools/oref-digital-twin` in the Android repository but is not an
Android/Gradle module and is not packaged into the APK.  The custom controller is
identified from both operative source files:

- `DetermineBasalAutoISF.kt` — final dosing calculation;
- `OpenAPSAutoISFPlugin.kt` — live input/state preparation and safety gates.

`replay.autoisf_source` hashes both files and defines a fail-closed process boundary for
an executable Kotlin replay adapter.  Until that adapter is present, the stock oref0
oracle must not be used for UKF3426 counterfactual dosing claims: it is a different
controller.

The Android side can now emit an explicitly enabled, read-only replay trace, and
`replay.autoisf_trace` validates/reassembles it from an AAPS log or exported log ZIP.
See [UKF3426 trace collection and import](docs/AUTOISF_UKF3426.md).
| `docs/` | Supporting notes and references. |

## Privacy

Public repository. No names, tokens, site URLs or locations in code, docs, fixtures or
commit messages. Test data is synthetic or anonymised. Nightscout tokens are read-only,
scoped, never stored in plaintext, and revocable.

## Licence

Intended: **AGPL-3.0**, to stay consistent with the AAPS/oref ecosystem this builds on
and replays. See `LICENSE` — confirm before first substantive release.
