/*
 * Browser glue for the oref digital twin.
 *
 * Everything runs client-side: the browser fetches Nightscout directly (token + CORS stay
 * here), Pyodide runs the read-only Python pipeline, and the deterministic report renders
 * with no network. Optional narration goes to the Cloudflare Worker, and its response is
 * re-verified by the grounding gate (also in Pyodide) before it is shown.
 */

import { NARRATOR_URL } from './config.js';
import { loadSettingsFromFile } from './settings.js';

const PYODIDE_CDN = 'https://cdn.jsdelivr.net/pyodide/v0.26.2/full/';
const PACKAGES_ZIP = './odt-packages.zip';

const DAY_MS = 86_400_000;
const $ = (id) => document.getElementById(id);
const setStatus = (t) => ($('status').textContent = t);

let pyodide = null;

async function boot() {
  pyodide = await loadPyodide({ indexURL: PYODIDE_CDN });
  const buf = await (await fetch(PACKAGES_ZIP)).arrayBuffer();
  await pyodide.unpackArchive(buf, 'zip');           // unpacks package dirs into the cwd
  pyodide.runPython("import sys; sys.path.insert(0, '.'); import report.browser");
  setStatus('Ready.');
  $('run').disabled = false;
}

// --- Nightscout fetch (mirrors ingestion.client: 7-day windows) ---
async function nsGet(base, path, params, token) {
  const u = new URL(`${base.replace(/\/$/, '')}/api/v1/${path}`);
  for (const [k, v] of Object.entries(params)) u.searchParams.set(k, v);
  if (token) u.searchParams.set('token', token);
  const r = await fetch(u, { headers: { accept: 'application/json' } });
  if (r.status === 401 || r.status === 403) throw new Error('Nightscout rejected the token (401/403).');
  if (!r.ok) throw new Error(`Nightscout ${path} returned ${r.status}`);
  return r.json();
}

async function windowed(base, path, field, iso, startMs, endMs, token) {
  const out = [];
  for (let lo = startMs; lo < endMs; lo += 7 * DAY_MS) {
    const hi = Math.min(lo + 7 * DAY_MS, endMs);
    const p = {
      [`find[${field}][$gte]`]: iso ? new Date(lo).toISOString() : lo,
      [`find[${field}][$lte]`]: iso ? new Date(hi).toISOString() : hi,
      count: 50000,
    };
    const docs = await nsGet(base, path, p, token);
    if (Array.isArray(docs)) out.push(...docs);
  }
  return out;
}

async function fetchNightscout(base, token, days) {
  const endMs = Date.now();
  const startMs = endMs - days * DAY_MS;
  const [entries, treatments, devicestatus, profiles] = await Promise.all([
    windowed(base, 'entries.json', 'date', false, startMs, endMs, token),
    windowed(base, 'treatments.json', 'created_at', true, startMs, endMs, token),
    windowed(base, 'devicestatus.json', 'created_at', true, startMs, endMs, token),
    nsGet(base, 'profile.json', {}, token),
  ]);
  return { base_url: base, start_ms: startMs, end_ms: endMs, entries, treatments, devicestatus,
           profiles: Array.isArray(profiles) ? profiles : [profiles] };
}

// Escape before any interpolation into innerHTML. Settings-file keys and error strings are
// attacker-influenced (a hostile prefs export is a normal thing to be handed in a forum),
// and this page holds a Nightscout token and a master-password field in the DOM.
const esc = (s) => String(s)
  .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;').replace(/'/g, '&#39;');

// --- minimal, safe Markdown -> HTML (headings, bold, list items) ---
function mdToHtml(md) {
  return esc(md).split('\n').map((line) => {
    if (line.startsWith('### ')) return `<h3>${line.slice(4)}</h3>`;
    if (line.startsWith('## ')) return `<h2>${line.slice(3)}</h2>`;
    if (line.startsWith('# ')) return `<h1>${line.slice(2)}</h1>`;
    if (line.startsWith('- ')) return `<li>${inline(line.slice(2))}</li>`;
    if (line.trim() === '---') return '<hr>';
    if (line.trim() === '') return '';
    return `<p>${inline(line)}</p>`;
  }).join('\n');
  function inline(s) { return s.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>').replace(/_(.+?)_/g, '<em>$1</em>'); }
}

async function narrate(sourceJson) {
  if (!NARRATOR_URL) return null;
  const r = await fetch(NARRATOR_URL, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ findings: sourceJson }),
  });
  if (!r.ok) return null;
  const { narrative } = await r.json();
  return narrative || null;
}

async function run() {
  $('run').disabled = true;
  try {
    setStatus('Fetching Nightscout…');
    // clamp to the same bounds as the input's min/max — a typed 3650 would fire 522
    // windowed requests at someone's Nightscout.
    const days = Math.min(90, Math.max(1, parseInt($('days').value, 10) || 14));
    const raw = await fetchNightscout($('url').value.trim(), $('token').value.trim(), days);

    setStatus('Analysing…');
    const B = pyodide.pyimport('report.browser');
    // Run decision-level counterfactuals via real oref0 (web/oref-bundle.js) when available.
    const runner = (typeof globalThis.orefDetermine === 'function') ? B.make_js_oref_runner() : null;
    const kwargs = { oref_runner: runner };

    // Optional settings file (AAPS prefs / Trio JSON) — extracted and validated locally.
    const file = $('prefsfile').files[0];
    let settingsNote = '';
    if (file) {
      setStatus('Reading settings file…');
      const loaded = await loadSettingsFromFile(file, $('prefspw').value);
      if (loaded.needsPassword) throw new Error('That AAPS file is encrypted — enter your master password and try again.');
      const parsed = B.settings_from_raw(pyodide.toPy(loaded.raw)).toJs({ dict_converter: Object.fromEntries });
      const blocked = parsed.blocked || [];
      if (parsed.settings && Object.keys(parsed.settings).length) {
        kwargs.settings = pyodide.toPy(parsed.settings);
        settingsNote = `Settings loaded from ${esc(loaded.format)} `
          + `(${Object.keys(parsed.settings).length} values used`;
        settingsNote += blocked.length
          ? `, ${blocked.length} withheld pending confirmation: ${esc(blocked.join(', '))}).`
          : ').';
        if (parsed.settings.max_iob === undefined && parsed.unmapped_iob_keys.length) {
          settingsNote += ` Max IOB not recognised; IOB-like keys in your file: ${esc(parsed.unmapped_iob_keys.join(', '))}.`;
        }
      } else {
        settingsNote = 'No recognised settings found in that file.'
          + (blocked.length ? ` Withheld pending confirmation: ${esc(blocked.join(', '))}.` : '')
          + (parsed.unmapped_iob_keys.length ? ` IOB-like keys present: ${esc(parsed.unmapped_iob_keys.join(', '))}.` : '');
      }
      if (loaded.collisions && loaded.collisions.length) {
        settingsNote += ` <span class="warn">Duplicate keys in that file (last value used):`
          + ` ${esc(loaded.collisions.join(', '))}.</span>`;
      }
      for (const i of (parsed.issues || [])) {
        if (i.kind === 'out_of_range' || i.kind === 'needs_confirm') {
          settingsNote += ` <span class="warn">${esc(i.message)}</span>`;
        }
      }
    }

    const maxIob = parseFloat($('maxiob').value);
    if (!isNaN(maxIob)) kwargs.max_iob_override = maxIob;
    setStatus('Analysing…');
    // Always pass kwargs: with the ternary, a failed oref bundle silently dropped the
    // uploaded settings and the Max IOB override while still claiming they were loaded.
    const rawPy = pyodide.toPy(raw);
    const resultProxy = B.build_report.callKwargs(rawPy, kwargs);
    const result = resultProxy.toJs({ dict_converter: Object.fromEntries });
    resultProxy.destroy();
    rawPy.destroy();

    let html = mdToHtml(result.report_md);
    if (settingsNote) html = `<p class="muted">${settingsNote}</p>` + html;

    if ($('narrate').checked && NARRATOR_URL) {
      setStatus('Generating written summary…');
      const resultPy = pyodide.toPy(result);
      const sourceProxy = B.abstracted_findings(resultPy);
      const source = sourceProxy.toJs({ dict_converter: Object.fromEntries });
      sourceProxy.destroy();
      resultPy.destroy();
      const narrative = await narrate(source);
      if (narrative) {
        const sourcePy = pyodide.toPy(source);
        const gateProxy = B.gate_narrative(narrative, sourcePy);
        const gate = gateProxy.toJs({ dict_converter: Object.fromEntries });
        gateProxy.destroy();
        sourcePy.destroy();
        if (gate.passed) {
          html = `<h2>Summary</h2>${mdToHtml(narrative)}<hr>` + html;
        } else {
          html = `<p class="warn">The written summary failed verification (${gate.violations.length} issue(s)); showing the verified report instead.</p>` + html;
        }
      }
    }

    $('report').innerHTML = html;
    setStatus('Done.');
  } catch (e) {
    setStatus('');
    $('report').innerHTML = `<p class="warn">${esc(e.message)}</p>
      <p class="muted">If this is a CORS error, enable CORS on your Nightscout instance rather than proxying your data.</p>`;
  } finally {
    $('run').disabled = false;
  }
}

$('run').addEventListener('click', run);
boot().catch((e) => setStatus('Failed to load runtime: ' + e.message));
