# worker — narration (Cloudflare Worker)

Turns the abstracted findings into readable prose with a small LLM (Cloudflare **Workers
AI**, free daily allocation). See [../DESIGN.md](../DESIGN.md) §7.

## Trust model

The Worker is **untrusted** from the client's safety point of view. The browser runs the
deterministic grounding gate (`report/grounding.py`, in Pyodide) on whatever this returns
and only shows it if it passes; otherwise it falls back to the deterministic template. So
the narrator cannot put an ungrounded number or a dosing instruction in front of a user.

## Privacy

The client sends **only abstracted findings** (`report.abstracted_findings` — stats and
finding-keys, no raw CGM/treatments, no token, no site URL). The Worker additionally
strips anything outside an allow-list of top-level keys. Nothing personal transits it.

## Deploy

```bash
cd worker
npm i -g wrangler
wrangler deploy            # needs a Cloudflare account with Workers AI enabled
```

Set `ALLOWED_ORIGIN` in `wrangler.toml` to your Pages origin in production, and consider
enabling the optional per-IP rate limit (a public keyless endpoint can be abused, which
would burn your Workers AI allocation).

## Contract

`POST /` with `{ "findings": <abstracted findings> }` → `{ "narrative": "..." }`.
Errors return `{ "error": "..." }` with the appropriate status.
