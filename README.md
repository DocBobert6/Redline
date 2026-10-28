# Redline

**An Android camera app that makes phone photos and video look like they were shot on a full-frame mirrorless body with a professional 24-70mm f/2.8 zoom.**

Redline's calibration target is the rendering of the Canon EOS R5 Mark II with the RF 24-70mm f/2.8L IS USM: its color and tone, 3:2 framing, 24/35/50/70mm fields of view, residual vignetting, distortion signature, highlight roll-off, grain, and simulated f/2.8 full-frame depth of field.

> Status: **pre-alpha.** The repository currently contains the plan, the build roadmap, the CI/release pipeline, a capability-probe app skeleton, and the start of the calibration toolkit. There is no usable camera yet.

## What Redline does

1. **Never applies a configuration a phone can't run.** At first launch Redline reads every camera through Camera2 (hardware level, RAW, manual control, stream sizes, frame rates, 10-bit, ultra-high-res sensors, physical lenses), verifies feature *combinations*, and runs a short self-test before offering anything.
2. **Ships defaults that work on a mid-range baseline.** Every setting that is on by default is built and tested against a baseline device profile: Camera2 FULL, RAW, 12 MP maximum output, 8-bit video, no telephoto.
3. **Unlocks flagship capabilities when a phone has them.** 10-bit HLG and log video, HDR10/Dolby Vision, the APV codec, 48-200 MP sources, native telephoto lenses for the 70mm stop, 4K120 and 8K are detected and offered only on hardware that proves it can run them.
4. **Renders the look from data, not guesswork.** The color/tone model is fitted from public RAW + in-camera JPEG pairs, lens behaviour comes from measured lensfun data, and sensor noise from published measurements. Each test phone is profiled from its own factory colour calibration (Camera2 / DNG metadata), so no colour chart is needed.

## Docs

| Document | What it covers |
| --- | --- |
| [docs/PLAN.md](docs/PLAN.md) | Product and engineering plan: feasibility, default contract, capability tiers, stills/video pipelines, lens emulation, calibration method, numeric look spec |
| [docs/BUILD_PLAN.md](docs/BUILD_PLAN.md) | Build-by-build roadmap, each build's scope, acceptance gate, and release tag |
| [docs/RELEASING.md](docs/RELEASING.md) | How CI, tags, signing, and GitHub Releases work |

## Repository layout

```
app/            Android app (Kotlin, Jetpack Compose, Camera2/CameraX)
calibration/    Desktop calibration toolkit (Python): LUT fitting, lens and noise models
docs/           Plan, build roadmap, release process
.github/        CI and release workflows
```

## Building

Requirements: JDK 21 and the Android SDK (platform 37). The Gradle wrapper downloads Gradle 9.8.2.

```bash
./gradlew :app:assembleDebug            # debug APK in app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest        # unit tests
./gradlew :app:ktlintCheck              # code style (ktlintFormat to fix)
./gradlew :app:connectedDebugAndroidTest  # instrumented tests on a device/emulator
pip install -r calibration/requirements-ci.txt && python -m pytest calibration
```

Every push to `main` and every pull request runs CI and uploads a debug APK as a workflow artifact. Pushing a tag such as `v0.1.0-alpha.1` publishes a GitHub Release with the APK, AAB, and checksums.

## Capability checks and self-test (B11)

On top of the static device profile (B10), Redline checks that its defaults actually run before it uses them.

- **Feature combinations.** On Android 15+ (API 35) with `CameraManager.isCameraDeviceSetupSupported`, each default stream combination for the default back camera is asked of the camera HAL through `CameraDevice.CameraDeviceSetup.isSessionConfigurationSupported`, without opening the camera: preview + JPEG (max), preview + RAW_SENSOR (max), preview + YUV + RAW, video (3840x2160, else 1920x1080) at 24 and 30 fps + preview, preview stabilization at 30 fps, and preview stabilization + 60 fps. These answers are labelled **verified (session query)**. On older Android, when the HAL does not support the query, or when a query throws, the answer comes from the guaranteed stream combination tables for the hardware level and is labelled **assumed (mandatory table)**; combinations the tables do not cover (stabilization, 60 fps) stay **unknown**. Nothing is reported as verified that the HAL did not confirm. CameraX feature groups are a TODO for when a CameraX session exists; Camera2 is the source of truth.
- **First-launch self-test.** On first launch, and again whenever the OS build fingerprint changes, Redline opens the default back camera off the main thread and runs short sessions (about 1 s each, every wait with a timeout): preview frames and measured fps, a still JPEG, a RAW_SENSOR capture whose result must carry `SENSOR_SENSITIVITY` and `SENSOR_EXPOSURE_TIME` (skipped without RAW), and a fixed [24,24] repeating request whose measured fps must be within 1.5 of 24 (skipped without that range). Each check is pass, fail or skipped with a reason. The result is cached in `self_test.json`. The app asks for the CAMERA permission at launch; if it is denied, or the camera is busy, the checks are marked skipped (not failed) and run again next launch. **Run self-test again** re-runs it; debug builds also have a 100-run button for the B11 gate.
- **Demotion rules.** `FeaturePolicy` is a pure function from the device profile, combination results, self-test results and quirks to the effective defaults: RAW unusable means the JPEG-only path (and a Pro camera then runs as Basic, PLAN 4.3); a 24p failure means 30p; no preview stabilization means a plain preview; stabilized 60 fps is offered only when verified. Every step down is recorded with a human-readable reason and source (profile, quirk, combination, self-test). Skipped checks never demote.
- **Quirks table.** `app/src/main/assets/quirks.json` (`version`, `entries` of `{match: {manufacturer?, model?, fingerprint_prefix?, sdk_min?, sdk_max?}, disable: [feature keys], note}`) ships empty. At launch the app also tries, in the background with a 3 s timeout, to fetch the same file from this repository's `main` branch; a valid copy that is at least as new is cached and used, otherwise the bundled copy is. Feature keys: `raw`, `video_24p`, `preview_stabilization`, `stabilized_60fps`, `session_query` (ignore session queries on a HAL known to answer them wrongly). A matching entry overrides detection. An entry with an empty `match` is ignored. No Firebase or remote config yet (B51).
- **Report and logs.** The capability screen shows the effective tier, self-test results, combination results with their verified/assumed labels, effective defaults and demotions. The shared capability report JSON adds `self_test`, `combinations`, `effective_defaults`, `demotions` and `quirks`. Self-test failures are logged locally (logcat and `self_test_failures.log` in app storage); nothing is sent off the phone.
- **Forcing failures (debug builds only).** Launch with the string extra `redline.force_fail` set to a comma-separated list of self-test keys (`preview_frames`, `still_jpeg`, `raw_capture`, `fps_24`) or `combo:<key>` (for example `combo:preview_raw`) to see the demotions, e.g. `adb shell am start -n com.redline.camera/.MainActivity --es redline.force_fail raw_capture,fps_24`. The boolean extra `redline.no_permission_prompt` skips the permission prompt (used by the emulator smoke test).

The B11 gate is checked on the baseline device profile: the self-test passes 100 runs out of 100, and forced failures demote cleanly (unit tests in `FeaturePolicyTest` plus the emulator smoke test).

## Honest limits

A phone sensor is a fraction of the size of a full-frame sensor, so depth of field and bokeh are simulated from depth maps, and the longer focal lengths are digital crops unless the phone has a matching telephoto lens. The goal is that a photographer glancing at an image reads it as a full-frame L-zoom shot, not that it is indistinguishable at 100%.

## Legal

Redline is an independent project and is not affiliated with, endorsed by, or sponsored by Canon Inc. Canon, EOS, and RF are trademarks of Canon Inc., used here only to describe the calibration target. Redline ships no Canon or Adobe profile data; all looks are fitted independently. Output files are never labelled with another manufacturer's camera make or model.

Copyright (c) 2026 the Redline authors. All rights reserved until a license is chosen.
