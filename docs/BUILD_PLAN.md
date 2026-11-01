# Redline build plan

Build-by-build roadmap from empty repo to 1.0. Each build is one or more pull requests into `main`, ends with a passing CI run, and is published by pushing its tag (the Release workflow builds and attaches the APK/AAB). Phases and gates follow [PLAN.md](PLAN.md), section 13.

**Rules for every build**

- CI green on `main` (assemble, unit tests, lint, calibration tests).
- The build's acceptance gate passes on the **baseline device profile** (Camera2 FULL, RAW, 12 MP max, 8-bit video, no telephoto). Nothing turned on by default may fail there.
- Flagship-only features ship off by default and behind capability detection plus a live session check.
- Tag format: `vMAJOR.MINOR.PATCH-alpha.N` (pre-release) until beta; `-beta.N`, `-rc.N`, then `v1.0.0`.

Status key: `[ ]` to do, `[~]` in progress, `[x]` done.

---

## Phase 0: Foundation and calibration (`v0.0.x`)

| Build | Tag | Scope | Acceptance gate |
| --- | --- | --- | --- |
| [x] B00 | `v0.0.1-alpha.1` | Repo, README, plan, CI + Release workflows, Compose app skeleton with capability probe screen, tier rules + unit tests, calibration toolkit seed (lens math, LUT I/O) | CI green; release APK installs and lists every camera with its tier |
| [x] B01 | `v0.0.2-alpha.1` | Gradle wrapper (checksum-pinned, validated in CI); ktlint; emulator instrumented smoke test in CI; release keystore in repo secrets (detekt deferred until a release supports Kotlin 2.4) | CI green incl. emulator job; signed release APK once secrets are set |
| [x] B02 | `v0.0.3-alpha.1` | Calibration ingest: CR3 decode (rawpy), MakerNotes via exiftool (Picture Style, sharpness, ALO/HTP, lens corrections, WB), dataset manifest; filter to Standard/default pairs | Manifest of usable RAW+JPEG pairs with parameters; CI runs on tiny fixtures |
| [x] B03 | `v0.0.4-alpha.1` | Fit tone curve + 33^3 residual LUT (scene XYZ -> Standard), hold-out report, `.cube` export; Adobe DCP cross-check (internal only) | Hold-out (gate v2, colour and lightness scored separately): colour dE2000 < 2.5, skin colour < 2.5 (provisional), median dE2000 < 2.0, median abs dL* < 2.0. Result on 88 real R5 II pairs: colour 1.51, skin colour 2.49, median 1.61, median abs dL* 1.30 |
| [x] B04 | `v0.0.5-alpha.1` | Lens profile JSON from lensfun (distortion ptlens, vignetting pa, all stops); noise model from published data; baseline-phone factory-calibration dump (forward/colour/calibration matrices, reference illuminants, noise profile, shading map) + DNG cross-check script; free white-wall flat-field check | Dumped matrices identical to the phone's own DNG tags where Camera2 exposes them; where it doesn't (the baseline device returns null for every colour, forward, calibration and lens-distortion key on its logical and physical cameras), the export reports null honestly and the stock camera's DNG tags become the profile source; neutral patch maps to D65/D50 white within 1 dE; profiles load in unit tests (no chart required) |

## Phase 1: Capability engine and baseline camera (`v0.1.x`)

| Build | Tag | Scope | Acceptance gate |
| --- | --- | --- | --- |
| [x] B10 | `v0.1.0-alpha.1` | Full DeviceProfile (all keys in PLAN 4.1), equivalent focal per physical lens, JSON capability report export/share | Report matches manual readout on baseline device |
| [x] B11 | `v0.1.1-alpha.1` | Feature-combination checks (session-config query + CameraX feature groups), first-launch self-test, demotion rules, remote quirks table | Self-test passes 100/100 on baseline; forced failures demote cleanly |
| [x] B12 | `v0.1.2-alpha.4` | Camera2 preview in 3:2, permissions, zoom ring 24*/28/35/50/70 with crop and honest labels | 30 fps preview, cold start < 1 s to first frame (passed on the baseline device profile with preview stabilization off: 0 dropped frames, 465 ms; stabilization stays opt-in) |
| [x] B13 | `v0.1.3-alpha.1` | Single-shot JPEG + RAW/DNG capture, MediaStore save, EXIF incl. look tag (no make/model spoof) | 500 consecutive captures, zero failures |
| [x] B14 | `v0.1.4-alpha.1` | Manual ISO/shutter/focus, EV, AE/AWB lock, histogram, level; Av/M modes | Every control limited to reported ranges (passed: on-device check on the baseline device profile, 2026-10-29) |

## Phase 2: Stills look pipeline (`v0.2.x`)

| Build | Tag | Scope | Acceptance gate |
| --- | --- | --- | --- |
| [x] B20 | `v0.2.0-alpha.1` | NDK/CMake native module (C++; Halide deferred), GPU LUT in live preview | Preview LUT <= 8 ms per frame. Passed on the baseline device profile: p95 5.85 ms, max 6.21 ms (CPU glFinish estimate, 21 samples; the GPU timer reported every query disjoint), 0 dropped frames, fps min 29.85 |
| [x] B21 | `v0.2.1-alpha.1` | RAW color pipeline: phone matrices -> XYZ -> Standard LUT, tone curve, highlight shoulder, Canon-style AWB warmth | Phone DNG -> XYZ -> Standard matches the reference (Python) pipeline within dE2000 1.0 on the same frames; neutrals stay neutral. Passed on the baseline device profile: on-device self-check mean dE2000 0.0009, max 0.87, grey chroma 1.31; 11/11 shots rendered, 0 fallbacks; shutter-to-saved avg 861 ms |
| [x] B22 | `v0.2.2-alpha.3` | RAW burst capture (4-12 frames by light, under-exposed), tile align + merge streamed during capture (B22a: frames released as they merge, NEON, big-core worker pool; B22b: merge starts on the first frame, shutter free once the RAW frames are in, press feedback and queued presses), background processing queue; the shutter is never refused (busy shots take the single-frame or camera-JPEG path) | 24/35mm save < 3 s; no shutter blocking |
| [x] B23 | `v0.2.3-alpha.1` | Handheld multi-frame super-resolution at 50/70mm; per-stop output sizes | 50/70mm save < 5 s; resolution chart gain measured. Passed on the baseline device profile: 50/70mm merged at 3000x2000, coverage 0.73-0.85, max save 2175 ms; a braced shot fell back ("too still") as designed; 24/35mm unchanged, max save 1491 ms; DNG mode 2/2 saved; no ghosting or moire on the texture test shots |
| [x] B23b | `v0.2.3-alpha.3` | Rapid-fire path: busy shots go through our RAW pipeline (single RAW frame) instead of the phone's own JPEG; parallel save lane; background work throttled so the preview holds 30 fps (shipped as B23b + B23b.1: honest under-load preview meter, governor latch, compact LUT formats) | No camera-JPEG fallbacks in a 15-tap burst; preview fps min >= 29.5 and LUT p95 <= 8 ms while the queue drains. Passed on the baseline device profile (2026-11-01, v0.2.3-alpha.3): 0 camera-JPEG fallbacks in a 15+ tap run, LUT p95 6.6 ms (RGB10A2 at 1620x1216), max save wait down from 6.6 s |
| [ ] B23c | `v0.2.3-alpha.3` | Metering and tone re-fit from the R5 II dataset: exposure target fitted to R5 II Standard output (not the phone JPEG's median), re-check shoulder, toe, black point and specular desaturation against the CR3 hold-out | Mid-tone, black-point and highlight-shoulder error vs hold-out within the B03 gate on matched scenes |
| [ ] B24 | `v0.2.4-alpha.1` | Vignetting (in-camera residual), distortion (off by default, RAW-feel toggle), sharpening, grain; full Picture Style set | Visual sign-off on shoot list; fine-detail frequency response (micro-contrast) measured against R5 II files at matched scale |
| [ ] B25 | `v0.2.5-alpha.1` | Ultra HDR JPEG (libultrahdr gainmap), HEIF, optional DNG of merge; Save-formats setting: JPEG only, DNG only, or both (DNG always full-sensor, uncropped; the B13 DefaultCrop tag was removed in B14 because Google Photos showed a blank thumbnail and had no way to uncrop) | Ultra HDR files validate and render on HDR displays |

## Phase 3: Simulated depth of field (`v0.3.x`)

| Build | Tag | Scope | Acceptance gate |
| --- | --- | --- | --- |
| [ ] B30 | `v0.3.0-alpha.1` | Monocular depth (LiteRT GPU) + person segmentation; depth stored with capture | Depth in < 400 ms on baseline |
| [ ] B31 | `v0.3.1-alpha.1` | Thin-lens CoC model, 9-blade rounded kernel, linear-light bokeh rendering, aperture dial f/2.8-f/22 | Blur sizes match PLAN 19.5 table |
| [ ] B32 | `v0.3.2-alpha.1` | Guided-filter edges, confidence gating, cat's-eye toward corners, 70mm bokeh fringing, re-edit after capture; later, corner softness / field curvature only if public lens data supports it (Canon sagittal/meridional MTF, published lab tests) | < 5% of 200-image test set needs manual fix |

## Phase 4: Video (`v0.4.x`)

| Build | Tag | Scope | Acceptance gate |
| --- | --- | --- | --- |
| [ ] B40 | `v0.4.0-alpha.1` | Video session, flat ISP tonemap curve, GPU chain (float textures), MediaCodec HEVC 4K30 | Chain <= 12 ms per 4K frame |
| [ ] B41 | `v0.4.1-alpha.1` | 709-style (default) and Standard looks, true 24p, OIS + EIS, thermal step-down | 20-minute 4K30 recording without severe throttling |
| [ ] B42 | `v0.4.2-alpha.1` | Video pro tools: zebras, focus peaking, waveform, false color; audio levels | Overlays add < 2 ms per frame |
| [ ] B43 | `v0.4.3-alpha.1` | Flagship unlocks: HLG10 + HEVC Main10, log curve + downloadable `.cube`, HDR10/HDR10+/Dolby Vision, APV, 4K120/8K | Each unlock offered only after combination check; off on baseline |
| [ ] B44 | `v0.4.4-alpha.1` | Opt-in portrait video (1080p30 on baseline if benchmark passes, 4K30 on top GPUs) | No matte flicker on test clips |

## Phase 5: Flagship tier, device matrix, blind tests (`v0.5.x`)

| Build | Tag | Scope | Acceptance gate |
| --- | --- | --- | --- |
| [ ] B50 | `v0.5.0-alpha.1` | Adaptive defaults: native telephoto for 50-70mm stops, ultra-high-res sources for crops, true 24mm, large output | Same default settings, better source, no regressions on baseline |
| [ ] B51 | `v0.5.1-alpha.1` | Device-matrix workflow (Firebase Test Lab), quirks fixes for flagship and budget phones | Self-test passes on every matrix device |
| [ ] B52 | `v0.5.2-alpha.1` | Blind A/B kit, look v2 LUT delivered via remote config | A/B results recorded; look updated |

## Phase 6: Beta and launch (`v0.9.x` to `v1.0.0`)

| Build | Tag | Scope | Acceptance gate |
| --- | --- | --- | --- |
| [ ] B60 | `v0.9.0-beta.1` | Onboarding, capability screen, privacy policy, crash reporting, Play Console upload workflow (internal/closed testing) | Closed beta live |
| [ ] B61 | `v0.9.x-rc.N` | Beta fixes, performance and battery pass, store listing | Crash-free sessions > 99.5% |
| [ ] B62 | `v1.0.0` | Production release | Release checklist complete |

---

## How a build flows through GitHub

1. Branch `build/B12-preview` from `main`; open a draft PR.
2. CI runs on every push to the PR (debug APK uploaded as an artifact for on-device testing).
3. Merge when CI is green and the gate passes on the baseline device.
4. Tick the build here, then tag: `git tag v0.1.2-alpha.1 && git push origin v0.1.2-alpha.1`.
5. The Release workflow publishes the GitHub Release (pre-release for `-alpha/-beta/-rc`) with APK, AAB, and SHA256SUMS.
