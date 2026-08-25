/*
 * Client-side settings loader. Turns an uploaded file into a flat {name: value} object
 * for the Python validator. Two formats, both handled in the browser (nothing leaves it):
 *   - AAPS encrypted prefs (.json, security.algorithm "v1") -> WebCrypto decrypt with the
 *     master password (aaps_prefs.mjs). "none" = unencrypted AAPS export.
 *   - Trio / oref preferences (plain JSON, e.g. {"max_iob":6,...}) -> parse + flatten.
 */
import { decryptAapsPrefs } from './aaps_prefs.mjs';

// Nested sections are flattened onto one namespace, so the same leaf key can appear twice
// with different values. Last-wins silently would let the wrong insulin-relevant number
// through, so collisions are collected and reported rather than swallowed.
function flatten(obj, out = {}, collisions = new Set()) {
  for (const [k, v] of Object.entries(obj || {})) {
    if (v !== null && typeof v === 'object' && !Array.isArray(v)) {
      flatten(v, out, collisions);
    } else {
      if (Object.prototype.hasOwnProperty.call(out, k) && out[k] !== v) collisions.add(k);
      out[k] = v;
    }
  }
  return out;
}

function flattenWithCollisions(obj) {
  const collisions = new Set();
  const raw = flatten(obj, {}, collisions);
  return { raw, collisions: [...collisions] };
}

function isAapsEncrypted(obj) {
  return obj && obj.security && obj.security.algorithm === 'v1';
}
function isAapsUnencrypted(obj) {
  return obj && obj.security && obj.security.algorithm === 'none' && obj.content;
}

/**
 * @returns {Promise<{raw?: object, format: string, needsPassword?: boolean}>}
 */
export async function loadSettingsFromFile(file, password) {
  const text = await file.text();
  let obj;
  try {
    obj = JSON.parse(text);
  } catch {
    throw new Error('That file is not valid JSON. Upload an AAPS prefs export or a Trio settings JSON.');
  }

  if (isAapsEncrypted(obj)) {
    if (!password) return { format: 'aaps-encrypted', needsPassword: true };
    const content = await decryptAapsPrefs(text, password);   // throws on wrong password
    return { ...flattenWithCollisions(content), format: 'aaps-encrypted' };
  }
  if (isAapsUnencrypted(obj)) {
    const content = await decryptAapsPrefs(text, '');         // returns content.content directly
    return { ...flattenWithCollisions(content), format: 'aaps' };
  }
  // Trio / oref preferences, or any plain settings JSON.
  return { ...flattenWithCollisions(obj), format: 'trio/plain' };
}
