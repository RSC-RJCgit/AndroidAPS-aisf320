# settings/decrypt

Client-side decryptor for AAPS **encrypted preferences**. Runs in the browser (or Node)
via WebCrypto so the **master password never leaves the device** (DESIGN §5.2). The server
only ever receives the minimised, validated settings subset — never the file, never the
password.

## Why client-side

The export is keyed on the user's AAPS master password — the key to their whole config, not
just settings. It cannot be decrypted server-side and users must never be asked to send it.
Every primitive is in WebCrypto, so decryption happens locally and only the allow-listed
keys are sent onward.

## Format (from AAPS `CryptoUtil` / `EncryptedPrefsFormat`)

```
container JSON: { format, security: { algorithm, salt(hex) }, content }
algorithm "v1": content = base64( [ivLen:1][iv:12][ciphertext + GCM tag] )
key            = PBKDF2(masterPassword, salt, 50000 iterations, HMAC-SHA1) -> AES-256
cipher         = AES-256-GCM, 128-bit tag
algorithm "none": content is the plaintext settings object (unencrypted export)
```

## API

```js
import { decryptAapsPrefs, pickSettings } from './aaps_prefs.mjs';
const content = await decryptAapsPrefs(fileText, masterPassword);   // throws on wrong password
const minimal = pickSettings(content, ALLOWLIST);                    // only send these onward
```

`encryptAapsPrefs` is included to document the byte layout and drive the round-trip
self-test; it is not used in production (the flow only ever decrypts).

## Tests

```bash
node --test settings/decrypt/roundtrip.test.mjs
```

The round-trip validates the crypto pipeline is self-consistent with the documented AAPS
format. Validating against a *real* AAPS export is left to on-device testing — such files
carry PII and must not enter this repo.
