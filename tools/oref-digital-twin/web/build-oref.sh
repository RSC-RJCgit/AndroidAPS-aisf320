#!/usr/bin/env bash
# Regenerate web/oref-bundle.js — real oref0 determine-basal bundled for the browser.
# The committed bundle is vendored so Pages/local serving work without a build step; run
# this to refresh it (e.g. after bumping the pinned oref0 version).
set -euo pipefail
cd "$(dirname "$0")/../replay/oracle"

npm install --no-audit --no-fund >/dev/null   # pinned oref0 (see package.json)
# --inject the process shim: oref0 writes its reasoning to process.stderr, which does not
# exist in the browser, and without this every determine-basal call throws immediately.
npx --yes esbuild browser-entry.mjs --bundle --format=iife --platform=browser \
    --inject:./process-shim.mjs \
    --outfile=../../web/oref-bundle.js
echo "wrote web/oref-bundle.js"

# Guard: prove the bundle actually evaluates a request. A textual check is not enough —
# the shim leaves `process.stderr` in the source and merely rebinds `process` to an
# IIFE-scoped var — so run determine-basal for real and require a decision back. The var
# shadows Node's own global, so this exercises the shim rather than bypassing it.
node --input-type=module -e "
  import { readFileSync } from 'node:fs';
  new Function(readFileSync('../../web/oref-bundle.js', 'utf8'))();
  const req = {
    glucose_status: { glucose: 120, delta: 2, short_avgdelta: 2, long_avgdelta: 1, date: Date.now() },
    currenttemp: { duration: 0, rate: 0, temp: 'absolute' },
    iob_data: { iob: 0.5, activity: 0.01, basaliob: 0.2, bolusiob: 0.3, time: Date.now() },
    profile: { dia: 6, current_basal: 0.8, max_basal: 3, max_daily_basal: 0.8,
               max_daily_safety_multiplier: 3, current_basal_safety_multiplier: 4,
               max_iob: 6, sens: 50, carb_ratio: 10, min_bg: 100, max_bg: 110,
               target_bg: 105, min_5m_carbimpact: 8, type: 'current' },
    autosens_data: { ratio: 1.0 }, meal_data: { carbs: 0, mealCOB: 0 },
    microBolusAllowed: true, currentTime: Date.now(),
  };
  const [r] = globalThis.orefDetermine([req]);
  if (!r || !r.ok) { console.error('ERROR: bundle failed to evaluate:', r && r.error); process.exit(1); }
  console.error('bundle smoke test OK — rate ' + r.rt.rate + ', duration ' + r.rt.duration);
"
