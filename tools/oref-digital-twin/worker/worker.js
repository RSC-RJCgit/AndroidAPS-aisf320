/*
 * oref-twin narrator — Cloudflare Worker.
 *
 * Turns the abstracted findings into readable prose using a small LLM (Cloudflare
 * Workers AI, free daily allocation). It is a NARRATOR, not an analyst: it is told never
 * to invent numbers and never to give dosing instructions.
 *
 * Trust model: this Worker is UNTRUSTED from the client's safety point of view. The
 * browser runs the deterministic grounding gate on whatever this returns and only shows
 * it if it passes; otherwise it falls back to the deterministic template. So the Worker
 * cannot, by construction, put an ungrounded number or a prescription in front of a user.
 *
 * Privacy: the client sends ONLY abstracted findings (stats + finding-keys). Reject any
 * payload that carries raw data or connection info.
 */

const ALLOWED_KEYS = new Set(['counts', 'glycemia', 'findings', 'variant', 'counterfactuals']);
const MAX_BODY_BYTES = 64 * 1024;
const MODEL = '@cf/meta/llama-3.1-8b-instruct';

const SYSTEM_PROMPT = [
  'You rewrite structured diabetes-loop findings into a clear, calm report for the person.',
  'Hard rules:',
  '- Use ONLY numbers that appear in the provided findings. Never introduce a new number.',
  '- Never give dosing instructions or tell the user to change a setting. This is advisory only.',
  '- Preserve every caveat: association is not causation; a counterfactual is decision-level, not a blood-glucose prediction.',
  '- Lead with anything critical. Be plain and British-spelled. Do not add a diagnosis.',
].join('\n');

// Fallback limiter for when the Ratelimit binding is absent. Per-isolate and therefore
// weak — isolates are ephemeral and per-colo — but it blunts a single-source flood, which
// is better than the previous behaviour of skipping the limit entirely and silently.
const FALLBACK_LIMIT = 20;
const FALLBACK_WINDOW_MS = 60_000;
const FALLBACK_MAX_KEYS = 5000;
const _hits = new Map();

function fallbackAllow(key, now) {
  if (_hits.size > FALLBACK_MAX_KEYS) _hits.clear();   // bound memory; coarse by design
  const rec = _hits.get(key);
  if (!rec || now - rec.start >= FALLBACK_WINDOW_MS) {
    _hits.set(key, { start: now, n: 1 });
    return true;
  }
  rec.n += 1;
  return rec.n <= FALLBACK_LIMIT;
}

function cors(origin) {
  return {
    'Access-Control-Allow-Origin': origin || '*',
    'Access-Control-Allow-Methods': 'POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type',
    'Vary': 'Origin',
  };
}

function json(body, status, origin) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...cors(origin) },
  });
}

function sanitiseFindings(payload) {
  // keep only allow-listed top-level keys; reject anything that smells like raw data
  const out = {};
  for (const [k, v] of Object.entries(payload || {})) {
    if (ALLOWED_KEYS.has(k)) out[k] = v;
  }
  return out;
}

export default {
  async fetch(request, env) {
    const origin = request.headers.get('Origin') || '';
    const allowOrigin = env.ALLOWED_ORIGIN || origin;

    if (request.method === 'OPTIONS') return new Response(null, { headers: cors(allowOrigin) });
    if (request.method !== 'POST') return json({ error: 'POST only' }, 405, allowOrigin);

    // Origin check — be precise about what this is worth.
    //
    // It buys one real thing: a browser sets Origin itself and page script cannot forge it,
    // so another *site* cannot drive this endpoint with its visitors' browsers. Setting
    // Access-Control-Allow-Origin alone would not stop that — it only stops the other site
    // reading the reply, after the request has been made, billed and run.
    //
    // It buys nothing against a direct caller: curl can omit Origin or send the expected
    // value, so this is friction, not authorization. A public, keyless static page cannot
    // hold a secret, so there is no capability to demand instead — the rate limiter below
    // is the actual control on the AI allocation. A missing Origin is rejected too, since
    // the only legitimate client is a browser on the Pages site and it always sends one.
    if (env.ALLOWED_ORIGIN && origin !== env.ALLOWED_ORIGIN) {
      return json({ error: 'origin not allowed' }, 403, allowOrigin);
    }

    // Rate limit. env.RL is the Cloudflare Ratelimit binding when it is actually bound;
    // if it is not, fall back to the in-isolate limiter rather than skipping silently —
    // this is a public, keyless endpoint spending a Workers AI allocation.
    const ip = request.headers.get('CF-Connecting-IP') || 'anon';
    if (env.RL) {
      const { success } = await env.RL.limit({ key: ip });
      if (!success) return json({ error: 'rate limited' }, 429, allowOrigin);
    } else if (!fallbackAllow(ip, Date.now())) {
      return json({ error: 'rate limited' }, 429, allowOrigin);
    }

    const raw = await request.text();
    if (raw.length > MAX_BODY_BYTES) return json({ error: 'payload too large' }, 413, allowOrigin);

    let body;
    try {
      body = JSON.parse(raw);
    } catch {
      return json({ error: 'invalid JSON' }, 400, allowOrigin);
    }

    const findings = sanitiseFindings(body.findings || body);
    if (!findings.findings) return json({ error: 'no findings provided' }, 400, allowOrigin);

    if (!env.AI) return json({ error: 'no AI binding configured' }, 501, allowOrigin);

    let narrative = '';
    try {
      const res = await env.AI.run(MODEL, {
        messages: [
          { role: 'system', content: SYSTEM_PROMPT },
          { role: 'user', content: 'Findings JSON:\n' + JSON.stringify(findings) },
        ],
        max_tokens: 700,
        temperature: 0.2,
      });
      narrative = (res && (res.response || res.result || '')).trim();
    } catch (e) {
      return json({ error: 'model error: ' + String(e && e.message ? e.message : e) }, 502, allowOrigin);
    }

    // The client re-verifies this with the grounding gate before showing it.
    return json({ narrative }, 200, allowOrigin);
  },
};
