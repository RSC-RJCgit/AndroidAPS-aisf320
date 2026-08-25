import { test } from 'node:test';
import assert from 'node:assert/strict';
import { decryptAapsPrefs, encryptAapsPrefs, pickSettings } from './aaps_prefs.mjs';

const SAMPLE = { max_iob: '6.0', enableSMB_always: 'true', isf: '50', not_a_setting: 'x' };

test('round-trips through the AAPS v1 envelope (PBKDF2-SHA1 + AES-GCM)', async () => {
  const file = await encryptAapsPrefs(SAMPLE, 'correct horse');
  const parsed = JSON.parse(file);
  assert.equal(parsed.security.algorithm, 'v1');
  assert.equal(parsed.format, 'aaps_encrypted');

  const out = await decryptAapsPrefs(file, 'correct horse');
  assert.deepEqual(out, SAMPLE);
});

test('wrong master password throws, not returns garbage', async () => {
  const file = await encryptAapsPrefs(SAMPLE, 'right');
  await assert.rejects(() => decryptAapsPrefs(file, 'wrong'), /wrong master password/);
});

test('unencrypted (algorithm none) export is read directly', async () => {
  const file = JSON.stringify({ format: 'aaps', security: { algorithm: 'none' }, content: SAMPLE });
  const out = await decryptAapsPrefs(file, 'ignored');
  assert.deepEqual(out, SAMPLE);
});

test('pickSettings keeps only the allow-listed subset', () => {
  const picked = pickSettings(SAMPLE, ['max_iob', 'enableSMB_always', 'isf']);
  assert.deepEqual(picked, { max_iob: '6.0', enableSMB_always: 'true', isf: '50' });
  assert.equal(picked.not_a_setting, undefined);
});
