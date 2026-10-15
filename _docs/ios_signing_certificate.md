# iOS signing certificate for the TestFlight workflow (no Mac needed)

`.github/workflows/ios-testflight.yml` signs with **one** Apple Distribution certificate, stored as two
secrets in the `testflight` environment:

- `IOS_DISTRIBUTION_P12`: the `.p12` file, base64
- `IOS_DISTRIBUTION_P12_PASSWORD`: the password of that `.p12`

Before 2026-10-15 the workflow let Xcode create a new certificate on every run (each runner is a blank Mac).
After enough runs the Apple account reached its certificate limit, and every archive failed with
"Your account has reached the maximum number of certificates". With one stored certificate, Xcode only
fetches provisioning profiles, which have no limit.

## 1. Clean up the old certificates

developer.apple.com → Certificates, Identifiers & Profiles → **Certificates**. Revoke the Apple Development
and Apple Distribution certificates that the CI runs created. You can keep the ones you use yourself.

## 2. Make the key and the request (Windows, Git Bash)

Work in a folder that is not inside the repository. The `.key` file is the private key: never commit it.

```bash
openssl genrsa -out aaps_dist.key 2048
openssl req -new -key aaps_dist.key -out aaps_dist.csr -subj "/emailAddress=you@example.com/CN=AAPS Distribution/C=AU"
```

## 3. Get the certificate from Apple

Certificates → **+** → **Apple Distribution** → upload `aaps_dist.csr` → download the file
(`distribution.cer`).

## 4. Make the .p12

```bash
openssl x509 -inform DER -in distribution.cer -out aaps_dist.pem
openssl pkcs12 -export -legacy -inkey aaps_dist.key -in aaps_dist.pem -out aaps_dist.p12 -passout pass:CHOOSE_A_PASSWORD
```

`-legacy` matters: the macOS keychain on the runner cannot read the newer OpenSSL 3 default format.

## 5. Store it as secrets

```bash
base64 -w0 aaps_dist.p12 > aaps_dist.p12.b64
```

GitHub → repository → Settings → Environments → `testflight` → add:

- `IOS_DISTRIBUTION_P12` = the contents of `aaps_dist.p12.b64`
- `IOS_DISTRIBUTION_P12_PASSWORD` = the password from step 4

Keep `aaps_dist.key` and `aaps_dist.p12` somewhere safe and private. The certificate is valid for one year;
repeat steps 2 to 5 when it expires.
