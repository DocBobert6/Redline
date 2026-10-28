# Releasing Redline

## Workflows

| Workflow | Trigger | What it does |
| --- | --- | --- |
| `CI` (`.github/workflows/ci.yml`) | push to `main`, pull requests, manual | Debug build, unit tests, lint, calibration tests; uploads the debug APK and reports as artifacts |
| `Release` (`.github/workflows/release.yml`) | push of a `v*` tag, or manual with an existing tag | Release APK + AAB, checksums, GitHub Release with auto-generated notes. Tags containing `-` are pre-releases |

Version name comes from the tag (`v0.1.2-alpha.1` -> `0.1.2-alpha.1`); version code is the workflow run number.

## Signing

Add these repository secrets (Settings -> Secrets and variables -> Actions) to sign releases with the Redline key:

- `REDLINE_KEYSTORE_BASE64`: the `.jks` keystore, base64-encoded (`base64 -w0 redline.jks`)
- `REDLINE_KEYSTORE_PASSWORD`
- `REDLINE_KEY_ALIAS`
- `REDLINE_KEY_PASSWORD`

Without them, release builds are signed with a debug key: they install for sideload testing but cannot go to Google Play, and the workflow prints a warning. Keep the keystore backed up outside GitHub; losing it means a new app listing.

## Cutting a release

```bash
git checkout main && git pull
git tag v0.0.1-alpha.1
git push origin v0.0.1-alpha.1
```
