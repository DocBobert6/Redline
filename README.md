# Redline

**An Android camera app that makes phone photos and video look like they were shot on a full-frame mirrorless body with a professional 24-70mm f/2.8 zoom.**

Redline's calibration target is the rendering of the Canon EOS R5 Mark II with the RF 24-70mm f/2.8L IS USM: its color and tone, 3:2 framing, 24/35/50/70mm fields of view, residual vignetting, distortion signature, highlight roll-off, grain, and simulated f/2.8 full-frame depth of field.

> Status: **pre-alpha.** The repository currently contains the plan, the build roadmap, the CI/release pipeline, a capability engine with self-test, a 3:2 Camera2 viewfinder with zoom stops, and the calibration toolkit. There is no photo or video capture yet.

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

## Viewfinder and zoom stops (B12)

The app now opens on a 3:2 viewfinder; the capability screen (B10/B11, with its **Share capability report** button) is behind the **Info** button, and Back returns to the viewfinder.

- **Preview.** Camera2 (not CameraX) on a `TextureView`, using the default camera chosen by `FeaturePolicy`. The stream is a 4:3 PRIVATE size taken from the cached profile (1920x1440 when offered, else the largest 4:3 size within 1920x1440), centre-cropped on screen to a letterboxed 3:2 frame (2:3 when the phone is upright), so the crop keeps the sensor's long side as the 4000x2667 still will. The picture is kept upright from the sensor orientation and the display rotation, including 180 degree turns. The camera opens on resume and closes on pause, on its own background thread.
- **Frame rate.** The viewfinder sets `CONTROL_AE_TARGET_FPS_RANGE` from the profile: [30,30], else [24,30], else the range ending at 30 with the highest floor. Left to itself, AE on the baseline device profile picked [15,30] in a dim room and dropped the preview to 15 fps with 66 ms exposures. Preview stabilization is requested only when `FeaturePolicy` keeps it on (and is re-applied if a combination check later demotes it).
- **Zoom ring.** Click stops at 24\*/28/35/50/70, opening at 35 (PLAN 5); tap a stop or drag along the ring, with a haptic tick per stop. Each stop's crop is `target / eq` of the lens used, where `eq` is that lens's equivalent focal length across the width of a 3:2 frame (PLAN 3.3, 19.6). Crops are applied with `CONTROL_ZOOM_RATIO` on API 30+ (clamped to `CONTROL_ZOOM_RATIO_RANGE`) and `SCALER_CROP_REGION` on API 29, and never go below 1.0x, so the logical camera does not switch to the ultrawide on its own.
- **Honest labels.** On the baseline device profile the main lens is 25.5 mm equivalent across a 3:2 frame, so the widest stop reads **24\*** with the note "24\* ≈ 25.5 mm". PLAN 3.3 keeps the ultrawide as an opt-in "true 24" only (about 3.8 MP from a fixed-focus sensor is worse than the main lens), so it is not used by default. A stop that hits the zoom-range limit is asterisked with its true focal length the same way. Each stop shows its crop and the pixels left, e.g. 50 mm: 1.96x crop, ~2040 px wide from 4000 (2.8 MP), so the quality trade is visible.
- **Native telephoto (data only).** `ZoomStops` picks, for each stop, the longest eligible lens that is not longer than the stop, so a phone with a 67 mm tele would take the 70 stop from it (unit-tested). B12 still frames every stop through the logical camera's zoom ratio; routing streams to a specific physical lens is the B50 adaptive default.
- **Startup.** The device profile is read back from its cache (`device_profile.json`, rebuilt when the OS build changes) off the main thread, and the camera opens as soon as the profile and the preview surface are ready. The combination checks, the self-test and the capability-screen probes wait until the first preview frame (or 3 s without one). When the self-test is due (first launch or a new OS build), the viewfinder closes its camera, waits for the close, lets the self-test run, then reopens; the two never hold the camera at once.
- **Measuring the gate.** A small overlay shows the time from activity `onCreate` to the first frame drawn on the preview surface and the rolling sensor frame rate (from `SENSOR_TIMESTAMP` over 1 s). The capability screen and the shared report add a `viewfinder` section: `first_frame_ms`, `first_capture_ms`, `process_to_first_frame_ms`, `preview_fps`, `preview_fps_min` (after a 2 s warm-up), `preview_size`, `ae_fps_range`, `preview_stabilization`, `zoom_api`, the selected stop and ratio, `profile_cache` (`hit` or `miss`), `launch`, `permission_prompted`, a `gate` block (`preview_fps_min_ge_30`, `first_frame_lt_1000_ms`) and every zoom stop with its label, true focal length, crop and output size.

The B12 gate is to be checked on the baseline device profile from the shared report: preview holds 30 fps, and a cold start with the profile cached (`profile_cache: hit`, no permission prompt) reaches the first frame in under 1 s. Unit tests cover the stop math (`ZoomStopsTest`), the fps-range, stream-size and transform choices (`PreviewMathTest`) and the cached-profile round trip (`DeviceProfileParserTest`); the emulator smoke test checks that the app launches into the viewfinder, the zoom stops exist, and the capability screen is reachable with its sections.

## Honest limits

A phone sensor is a fraction of the size of a full-frame sensor, so depth of field and bokeh are simulated from depth maps, and the longer focal lengths are digital crops unless the phone has a matching telephoto lens. The goal is that a photographer glancing at an image reads it as a full-frame L-zoom shot, not that it is indistinguishable at 100%.

## Legal

Redline is an independent project and is not affiliated with, endorsed by, or sponsored by Canon Inc. Canon, EOS, and RF are trademarks of Canon Inc., used here only to describe the calibration target. Redline ships no Canon or Adobe profile data; all looks are fitted independently. Output files are never labelled with another manufacturer's camera make or model.

Copyright (c) 2026 the Redline authors. All rights reserved until a license is chosen.
