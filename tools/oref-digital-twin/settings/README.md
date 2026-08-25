# settings

Settings ingestion — the SMB/oref preferences that are **not** in the Nightscout profile
(max_iob, SMB flags, SMB minutes, dynamic-ISF toggles…). Two input paths, one validated
output. See [../DESIGN.md](../DESIGN.md) §5.2.

## Pieces

| File | Role |
|---|---|
| `schema.py` | The bounded, typed key registry: each setting's type, range, source aliases, and whether it is a replay lever. |
| `validate.py` | Map source names → friendly keys, coerce types, range-check, and flag anything needing user confirmation. Never silently accepts an out-of-range or low-confidence value. |
| `extract.py` | Screenshot → validated settings, with the **vision model injected** (provider-agnostic, testable offline). |
| `decrypt/aaps_prefs.mjs` | Client-side AAPS encrypted-prefs decryptor (browser/Node WebCrypto). |

## Two input paths

1. **Screenshots** (lowest friction). The vision step only proposes `name → value/confidence`;
   `validate` decides what is trusted. Because the target is a fixed set of typed slots, an
   out-of-range or low-confidence read is flagged for confirmation rather than accepted.
2. **Encrypted-prefs decrypt** (more robust). Runs entirely in the browser: the user picks
   the file and types the master password locally; `decrypt/aaps_prefs.mjs` decrypts with
   WebCrypto and `pickSettings` keeps only the allow-listed subset. The master password and
   full config **never leave the device** — the server receives only the minimised settings.

Profile-side settings (basal/ISF/CR/targets/DIA) are not here — they come from `ingestion/`
via the Nightscout profile.

## Output

`ValidatedSettings`: `values` (coerced), `needs_confirm` (keys to confirm), `issues`
(typed problems). `.replay_settings()` returns just the levers the replay oracle can vary,
ready to feed `replay.from_cycle`.

## Tests

```bash
pytest settings/tests            # schema/validate/extract + the Node decrypt round-trip
node --test settings/decrypt     # the decryptor's WebCrypto round-trip on its own
```
