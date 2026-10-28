# Releasing Redline

## Workflows

| Workflow | Trigger | What it does |
| --- | --- | --- |
| `CI` (`.github/workflows/ci.yml`) | push to `main`, pull requests, manual | Debug build, unit tests, lint, calibration tests; uploads the debug APK and reports as artifacts |
| `Release` (`.github/workflows/release.yml`) | push of a `v*` tag, or manual with an existing tag | Release APK + AAB, checksums, GitHub Release with auto-generated notes. Tags containing `-` are pre-releases |

Version name comes from the tag (`v0.1.2-alpha.1` -> `0.1.2-alpha.1`); version code is the workflow run number.

## Signing

The release keystore is committed **encrypted** at `signing/redline-release.jks.enc` (AES-256-CBC, PBKDF2 with 600,000 iterations). The Release workflow decrypts it with the `REDLINE_KEYSTORE_PASSWORD` secret. Required repository secrets (Settings -> Secrets and variables -> Actions):

- `REDLINE_KEYSTORE_PASSWORD`: keystore password, also the decryption passphrase
- `REDLINE_KEY_ALIAS`
- `REDLINE_KEY_PASSWORD`

Optional: `REDLINE_KEYSTORE_BASE64` (a base64 `.jks`) overrides the committed file, for rotating to a different key.

Without a keystore, release builds are signed with a debug key: they install for sideload testing but cannot go to Google Play, and the workflow prints a warning. Keep an offline backup of the keystore and its password; losing them means a new app listing (or a Play App Signing key reset).

Decrypt locally:

```bash
openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -in signing/redline-release.jks.enc -out redline.jks
```

## Cutting a release

```bash
git checkout main && git pull
git tag v0.0.1-alpha.1
git push origin v0.0.1-alpha.1
```
