/*
 * Client-side decryptor for AAPS encrypted preferences.
 *
 * Runs in the BROWSER (or Node) via WebCrypto so the master password never leaves the
 * user's device (DESIGN §5.2). The server only ever receives the minimised, validated
 * settings subset — never the file, never the password.
 *
 * Format (from AAPS CryptoUtil / EncryptedPrefsFormat):
 *   container JSON: { format, security: { algorithm, salt(hex) }, content }
 *   when algorithm === "v1": content = base64( [ivLen:1][iv:12][ciphertext+GCM tag] )
 *   key = PBKDF2(masterPassword, salt, 50000 iters, HMAC-SHA1) -> AES-256
 *   cipher = AES-256-GCM, 128-bit tag
 *   when algorithm === "none": content is the plaintext settings object (unencrypted export)
 */

const PBKDF2_ITERATIONS = 50000;
const AES_KEY_BITS = 256;
const GCM_TAG_BITS = 128;

const _crypto = globalThis.crypto;

function hexToBytes(hex) {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.substr(i * 2, 2), 16);
  return out;
}
function bytesToHex(bytes) {
  return Array.from(bytes).map((b) => b.toString(16).padStart(2, '0')).join('');
}
function b64ToBytes(b64) {
  if (typeof atob === 'function') {
    const bin = atob(b64);
    const a = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) a[i] = bin.charCodeAt(i);
    return a;
  }
  return new Uint8Array(Buffer.from(b64, 'base64'));
}
function bytesToB64(bytes) {
  if (typeof btoa === 'function') {
    let bin = '';
    bytes.forEach((b) => (bin += String.fromCharCode(b)));
    return btoa(bin);
  }
  return Buffer.from(bytes).toString('base64');
}

async function deriveKey(masterPassword, salt) {
  const baseKey = await _crypto.subtle.importKey(
    'raw', new TextEncoder().encode(masterPassword), 'PBKDF2', false, ['deriveKey']
  );
  return _crypto.subtle.deriveKey(
    { name: 'PBKDF2', salt, iterations: PBKDF2_ITERATIONS, hash: 'SHA-1' },
    baseKey,
    { name: 'AES-GCM', length: AES_KEY_BITS },
    false,
    ['encrypt', 'decrypt']
  );
}

/** Decrypt an AAPS prefs export. Returns the settings object. Throws on bad password. */
export async function decryptAapsPrefs(fileText, masterPassword) {
  const container = JSON.parse(fileText);
  const security = container.security || {};
  const algorithm = security.algorithm;
  const content = container.content;

  if (algorithm === 'none') {
    return typeof content === 'string' ? JSON.parse(content) : content; // unencrypted export
  }
  if (algorithm !== 'v1') {
    throw new Error(`unsupported prefs algorithm: ${algorithm}`);
  }

  const salt = hexToBytes(security.salt);
  const blob = b64ToBytes(content);
  const ivLen = blob[0];
  const iv = blob.slice(1, 1 + ivLen);
  const ct = blob.slice(1 + ivLen);
  const key = await deriveKey(masterPassword, salt);
  let plain;
  try {
    plain = await _crypto.subtle.decrypt({ name: 'AES-GCM', iv, tagLength: GCM_TAG_BITS }, key, ct);
  } catch (e) {
    throw new Error('decryption failed — wrong master password or corrupt file');
  }
  return JSON.parse(new TextDecoder().decode(plain));
}

/** Keep only allow-listed keys, so only the minimised subset is ever sent onward. */
export function pickSettings(content, allowlist) {
  const want = new Set(allowlist.map((k) => k.toLowerCase()));
  const out = {};
  for (const [k, v] of Object.entries(content || {})) {
    if (want.has(k.toLowerCase())) out[k] = v;
  }
  return out;
}

/**
 * Re-encrypt in the AAPS format. NOT used in production (we only ever decrypt) — it exists
 * to document the byte layout and to drive the round-trip self-test.
 */
export async function encryptAapsPrefs(contentObj, masterPassword) {
  const salt = _crypto.getRandomValues(new Uint8Array(32));
  const iv = _crypto.getRandomValues(new Uint8Array(12));
  const key = await deriveKey(masterPassword, salt);
  const ctBuf = await _crypto.subtle.encrypt(
    { name: 'AES-GCM', iv, tagLength: GCM_TAG_BITS },
    key,
    new TextEncoder().encode(JSON.stringify(contentObj))
  );
  const ct = new Uint8Array(ctBuf);
  const blob = new Uint8Array(1 + iv.length + ct.length);
  blob[0] = iv.length;
  blob.set(iv, 1);
  blob.set(ct, 1 + iv.length);
  return JSON.stringify({
    format: 'aaps_encrypted',
    security: { algorithm: 'v1', salt: bytesToHex(salt) },
    content: bytesToB64(blob),
  });
}
