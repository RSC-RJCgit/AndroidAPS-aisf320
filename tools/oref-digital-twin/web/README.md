# web — the client-side app (GitHub Pages + Pyodide)

Runs the whole read-only pipeline in the browser via Pyodide (CPython in WASM). The
Nightscout token and data stay on the device; the deterministic report renders with no
network. Optional narration goes to the Cloudflare Worker and is re-verified by the
grounding gate before it is shown. See [../DESIGN.md](../DESIGN.md) §7, §10.

## Flow

1. `boot()` loads Pyodide and unpacks `odt-packages.zip` (the pure-Python packages) into it.
2. On **Analyse**, the browser fetches Nightscout directly (`entries`/`treatments`/
   `devicestatus`/`profile`, 7-day windows), hands the raw JSON to `report.browser.build_report`,
   and renders the deterministic Markdown report.
3. If narration is enabled and `NARRATOR_URL` is set, it sends **only** `abstracted_findings`
   (stats + finding-keys, no data/token) to the Worker, runs the returned text through
   `report.browser.gate_narrative`, and shows it **only if it passes** — otherwise the
   verified deterministic report stands.

## Build & run locally

```bash
bash web/build.sh                     # produces web/odt-packages.zip
python3 -m http.server -d web 8000    # then open http://localhost:8000
```

## Deploy

`.github/workflows/pages.yml` builds the package bundle and deploys `web/` to GitHub Pages
on push to `main`. Set `NARRATOR_URL` in `app.js` to your deployed Worker to enable
narration (leave empty for report-only).

## Notes / caveats

- `odt-packages.zip` is a build artifact (gitignored) — the workflow builds it; `build.sh`
  builds it locally.
- `replay/` (oref0 counterfactuals) is not in the browser bundle yet — running oref0 in the
  browser is a documented follow-up; the diagnostic report does not need it.
- CORS: browser `fetch` to Nightscout usually works (cgm-remote-monitor enables CORS). If a
  site blocks it, enable CORS on that Nightscout — do not proxy health data through a server.
