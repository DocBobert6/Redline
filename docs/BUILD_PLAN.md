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
| [~] B02 | `v0.0.3-alpha.1` | Calibration ingest: CR3 decode (rawpy), MakerNotes via exiftool (Picture Style, sharpness, ALO/HTP, lens corrections, WB), dataset manifest; filter to Standard/default pairs | Manifest of usable RAW+JPEG pairs with parameters; CI runs on tiny fixtures |
| [ ] B03 | `v0.0.4-alpha.1` | Fit tone curve + 33^3 residual LUT (scene XYZ -> Standard), hold-out report, `.cube` export; Adobe DCP cross-check (internal only) | Hold-out mean dE2000 < 2.5 (skin < 2.0) |
| [ ] B04 | `v0.0.5-alpha.1` | Lens profile JSON from lensfun (distortion ptlens, vignetting pa, all stops); noise model from published data; baseline-phone ColorChecker + flat-field + noise profiling scripts and outputs | Baseline phone chart mean dE2000 < 2.0; profiles load in unit tests |

## Phase 1: Capability engine and baseline camera (`v0.1.x`)

| Build | Tag | Scope | Acceptance gate |
| --- | --- | --- | --- |
| [ ] B10 | `v0.1.0-alpha.1` | Full DeviceProfile (all keys in PLAN 4.1), equivalent focal per physical lens, JSON capability report export/share | Report matches manual readout on baseline device |
| [ ] B11 | `v0.1.1-alpha.1` | Feature-combination checks (session-config query + CameraX feature groups), first-launch self-test, demotion rules, remote quirks table | Self-test passes 100/100 on baseline; forced failures demote cleanly |
| [ ] B12 | `v0.1.2-alpha.1` | Camera2 preview in 3:2, permissions, zoom ring 24*/28/35/50/70 with crop and honest labels | 30 fps preview, cold start < 1 s to first frame |
| [ ] B13 | `v0.1.3-alpha.1` | Single-shot JPEG + RAW/DNG capture, MediaStore save, EXIF incl. look tag (no make/model spoof) | 500 consecutive captures, zero failures |
| [ ] B14 | `v0.1.4-alpha.1` | Manual ISO/shutter/focus, EV, AE/AWB lock, histogram, level; Av/M modes | Every control limited to reported ranges |

## Phase 2: Stills look pipeline (`v0.2.x`)

| Build | Tag | Scope | Acceptance gate |
| --- | --- | --- | --- |
| [ ] B20 | `v0.2.0-alpha.1` | NDK/CMake native module (C++/Halide), GPU LUT in live preview | Preview LUT <= 8 ms per frame |
| [ ] B21 | `v0.2.1-alpha.1` | RAW color pipeline: phone matrices -> XYZ -> Standard LUT, tone curve, highlight shoulder, Canon-style AWB warmth | Chart shots match fitted target within dE2000 3.0 |
| [ ] B22 | `v0.2.2-alpha.1` | RAW burst capture (8-12 frames, under-exposed), tile align + merge, background processing queue | 24/35mm save < 3 s; no shutter blocking |
| [ ] B23 | `v0.2.3-alpha.1` | Handheld multi-frame super-resolution at 50/70mm; per-stop output sizes | 50/70mm save < 5 s; resolution chart gain measured |
| [ ] B24 | `v0.2.4-alpha.1` | Vignetting (in-camera residual), distortion (off by default, RAW-feel toggle), sharpening, grain; full Picture Style set | Visual sign-off on shoot list |
| [ ] B25 | `v0.2.5-alpha.1` | Ultra HDR JPEG (libultrahdr gainmap), HEIF, optional DNG of merge | Ultra HDR files validate and render on HDR displays |

## Phase 3: Simulated depth of field (`v0.3.x`)

| Build | Tag | Scope | Acceptance gate |
| --- | --- | --- | --- |
| [ ] B30 | `v0.3.0-alpha.1` | Monocular depth (LiteRT GPU) + person segmentation; depth stored with capture | Depth in < 400 ms on baseline |
| [ ] B31 | `v0.3.1-alpha.1` | Thin-lens CoC model, 9-blade rounded kernel, linear-light bokeh rendering, aperture dial f/2.8-f/22 | Blur sizes match PLAN 19.5 table |
| [ ] B32 | `v0.3.2-alpha.1` | Guided-filter edges, confidence gating, cat's-eye toward corners, 70mm bokeh fringing, re-edit after capture | < 5% of 200-image test set needs manual fix |

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
