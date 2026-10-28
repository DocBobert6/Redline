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

## Honest limits

A phone sensor is a fraction of the size of a full-frame sensor, so depth of field and bokeh are simulated from depth maps, and the longer focal lengths are digital crops unless the phone has a matching telephoto lens. The goal is that a photographer glancing at an image reads it as a full-frame L-zoom shot, not that it is indistinguishable at 100%.

## Legal

Redline is an independent project and is not affiliated with, endorsed by, or sponsored by Canon Inc. Canon, EOS, and RF are trademarks of Canon Inc., used here only to describe the calibration target. Redline ships no Canon or Adobe profile data; all looks are fitted independently. Output files are never labelled with another manufacturer's camera make or model.

Copyright (c) 2026 the Redline authors. All rights reserved until a license is chosen.
