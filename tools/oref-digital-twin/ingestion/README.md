# ingestion

Nightscout pulls — `devicestatus`, `entries`, `treatments`, `profile` — normalised to a
common schema. Chunk to ~7-day windows with backoff (long windows 502). Read-only tokens
only. See [../DESIGN.md](../DESIGN.md) §5.1 and §12 phase 1.

## What it produces

A `PullResult`: normalised, time-sorted `entries` / `treatments` / `devicestatus` /
`profiles`, plus **coverage** — observed date range, CGM and loop-cycle coverage %, the
largest gaps, and warnings (no devicestatus, low coverage, missing profile). It surfaces
holes rather than papering over them.

## Modules

| File | Role |
|---|---|
| `config.py` | Connection + secret hygiene (token read from env, redacted from `repr`). |
| `client.py` | REST client: 7-day windowing, exponential backoff on 5xx/network, dedup. Transport is injectable for offline tests. |
| `normalise.py` | Raw NS JSON → dataclasses. Units → mg/dL, ISO → epoch-ms, SMB detection, enacted-over-suggested merge, prediction arrays. |
| `models.py` | The normalised schema. |
| `coverage.py` | Gap/coverage analysis. |
| `pull.py` | Orchestrates a full pull → `PullResult`. |
| `cli.py` | `python -m ingestion.cli --days 30 --out data/pull.json`. |

## Usage

```bash
export NS_URL="https://your-nightscout.example"
export NS_TOKEN="read-only-token"          # or NS_API_SECRET_SHA1
python -m ingestion.cli --days 30 --out data/pull.json
```

The token is read from the environment (never an argument), never written to output, and
`data/` is gitignored.

## Tests

Offline — no network, synthetic fixtures only.

```bash
pip install -e ".[dev]"
pytest ingestion/tests
```
