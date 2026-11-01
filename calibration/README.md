# Redline calibration toolkit

Desktop Python tools that turn public reference data into the look assets the app ships: tone curve + 3D LUT, lens profiles, noise model, and per-phone color matrices. See [../docs/PLAN.md](../docs/PLAN.md) section 9 for the method and [../docs/BUILD_PLAN.md](../docs/BUILD_PLAN.md) builds B02-B04.

## Setup

```bash
pip install -r calibration/requirements.txt   # rawpy needs LibRaw >= 0.22 for EOS R5 Mark II CR3 (rawpy >= 0.25 bundles it)
# exiftool >= 13 on PATH: https://exiftool.org
```

## Data (never committed)

Put source files under `calibration/data/` (git-ignored). Each source keeps its own license; download it yourself:

| Source | Use |
| --- | --- |
| Public EOS R5 Mark II RAW + in-camera JPEG galleries (keep each RAW next to its JPEG with the same file stem) | Fitting the Standard color/tone model |
| raw.pixls.us EOS R5 Mark II samples (CC0) | Decoder and metadata checks |
| lensfun database (RF 24-70mm F2.8 L IS USM) | Distortion and vignetting (B04) |
| Photons to Photos | Sensor noise model (B04) |

## B02: dataset manifest

```bash
cd calibration
python -m redline_cal.ingest data/r5ii --out out/manifest.jsonl          # ALO Off or Standard accepted
python -m redline_cal.ingest data/r5ii --out out/manifest.jsonl --strict-alo
```

The tool scans recursively, pairs each RAW with its JPEG (same folder and stem, falling back to capture time + sub-second + body serial for renamed downloads), reads EXIF + Canon MakerNotes with exiftool, applies the Standard policy, and writes one JSON line per pair. It prints a summary: pairs, usable pairs, usable pairs per zoom stop for the RF 24-70mm f/2.8, hold-out count, and rejection reasons.

**Standard policy** (`redline_cal/policy.py`): body EOS R5 Mark II; Picture Style Standard with contrast, saturation, and color tone at Normal; Highlight Tone Priority Off; Digital Lens Optimizer Off; Auto Lighting Optimizer Off or Standard (recorded, so the fitter can weight or exclude it). A JPEG whose style/ALO/HTP differ from its RAW is rejected as re-rendered. Sharpening and in-camera lens-correction settings are recorded, not filtered.

**Manifest record** (version 1):

| Field | Meaning |
| --- | --- |
| `id` | First 16 hex chars of the RAW's SHA-256 |
| `raw`, `jpeg` | Paths as scanned |
| `raw_sha256`, `jpeg_sha256` | File hashes, so a manifest can be checked against a re-download |
| `usable`, `reasons` | Policy verdict and rejection reasons |
| `split` | `train` or `holdout` (20%), derived from the RAW hash so it never changes between runs |
| `target_lens`, `zoom_stop_mm` | RF 24-70mm f/2.8 (non-Z) and the nearest 24/28/35/50/70 stop |
| `shot` | Normalized capture settings (style, sharpening, ALO, HTP, lens corrections, WB, ISO, aperture, shutter, focal length, capture time). Body serial numbers are never written |

`redline_cal.raw.decode_linear_xyz()` demosaics a RAW to linear CIE XYZ for the B03 fitter.

## B03: Standard look fit

```bash
cd calibration
python -m redline_cal.fit_look out/manifest.jsonl --out out/look --gate
```

Model (exactly what the app's shader runs): white-balanced scene XYZ (D65) -> linear sRGB -> per-channel tone curve on a log2 grid (257 points, EV -14 to +3) -> 33^3 residual LUT -> sRGB. The tone curve is the robust median of JPEG luminance per exposure bin, made monotone. The residual LUT is a smoothness-regularized least-squares fit pulled towards identity, so colors the dataset never shows fall back to the tone curve instead of extrapolating.

Pairs are sampled as flat, unclipped blocks from the central 80% of the frame (tolerant of the JPEG's crop margins and in-camera vignetting correction). Samples are cached per pair under `out/look/cache/`. The fit uses the manifest's `train` split; `report.json` gives hold-out mean / median / p95 dE2000, skin dE2000 (broad CIELAB skin region), and per-image hold-out error. `--gate` exits non-zero unless the hold-out passes gate v2: colour dE2000 (prediction's L* replaced by the target's) < 2.5, skin colour < 2.5 (provisional; the skin region is a CIELAB colour box, a known limitation since no chart is used), median dE2000 < 2.0 and median |dL*| < 2.0. Colour and lightness are scored separately because camera JPEGs carry per-scene tone adjustments a single global look cannot (and should not) reproduce; the report still lists the plain mean / p95 dE2000. Options: `--target-lens-only`, `--no-alo`, `--lut-size`, `--smooth`, `--allow-dlo` (ingest), `--exclude ID...`, `--align-exposure` (per-image exposure nuisance), `--devignette` (per-image radial falloff: the camera JPEG has peripheral-illumination correction, the RAW does not), `--robust N` (Huber IRLS passes for the residual LUT).

Outputs: `standard_look.json` (tone curve + model) and `standard_residual.cube`. CI checks the fitter on a synthetic camera model (hold-out mean dE2000 about 0.34); the real gate runs on downloaded pairs.

The Adobe DCP cross-check (PLAN 9.2) stays a local, internal step and is not part of this repo.

## B23c: metering and tone re-check from the R5 II pairs

```bash
cd calibration
exiftool -j -n -CanonExposureMode -ExposureCompensation -@ raws.txt > out/exif.json   # one RAW path per line
python -m redline_cal.metering out/manifest.jsonl --exif out/exif.json \
  --tone ../app/src/main/assets/colour/standard_tone.json --lut ../app/src/main/assets/luts/redline_standard_preview_33.cube \
  --cache out/metering_cache --out out/metering --asset ../app/src/main/assets/colour/metering.json
```

Before B23c the app placed a still's mid-tone where the phone's own camera JPEG put its median (`camera_jpeg_median`), inheriting the phone's metering. `redline_cal.metering` fits the target from the R5 II pairs instead: per pair, the camera JPEG's median luma inverted through the shipped tone curve gives the mid-tone target (EV on the tone curve's input axis; exposure compensation of Av/Tv/P shots taken out), and the RAW frame gives scale-free scene features. Candidates (constant; linear in `log2(p98/median)`; plus `log2(median/p5)`) are chosen by leave-one-out error on the train split and scored once on the hold-out. The app reads the result from `colour/metering.json` (`raw_pipeline.Metering`, Kotlin `ColourMath.Metering`); its stats pass returns the frame's p98 luminance for it. The spread is clamped to the training range (no extrapolation).

The same run re-checks the shipped tone curve on the hold-out on matched scenes (each pair at its own exposure, aligned on the central mid-tones as in B03 `--align-exposure`): median |dL\*| for the black point (JPEG L\* < 15), mid-tones (L\* 30-70) and the highlight shoulder (gained input above the -1.5 EV knee), and chroma of near-white blocks (specular desaturation). `--extract-only --shard i/n` extracts pairs in parallel; extraction caches per pair.

**B23c.1 headroom lift.** The fit sees only `log2(p98/median)`. When the median sits in a dominant bright region (overcast sky filling half the frame over dark asphalt, a sunlit wall over shade) that spread is tiny and the fit places the bright region like a flat mid-tone, about 1 EV under the R5 II. On the train pairs, the R5 II lifts exactly those frames: the ones whose darkest quarter sits far below the median (`log2(median/p25)` large). The same run fits `raw_pipeline.Headroom` on the train split (`fit_headroom`: grid over the p98 floor on the tone curve's input axis and the split gate, the cap is the fit's largest under-placement on train), scores it once on the hold-out (`headroom` in the report) and writes it to the asset's `headroom` block. Frames that are clipping (> 2 %) or whose p98 already reaches the floor are unchanged. The stats pass also returns p25 for it.

## Tests

```bash
python -m pytest calibration -q
REDLINE_CAL_SAMPLE_CR3=/path/to/RAW.CR3 python -m pytest calibration -q   # adds real-file exiftool + decode checks
```

## B04: phone profiles from factory calibration (no chart)

Every RAW-capable phone stores per-unit factory calibration, and writes the same values into its DNGs. B04 reads them; nothing has to be bought.

1. In Pixel Camera (or any camera app that saves DNG), turn on RAW + JPEG and shoot at 1x: a plain white wall in even light (the flat field), plus a few everyday scenes under daylight and a warm lamp.
2. In Redline, open the probe screen and tap **Share calibration** on the camera you shot with; save the JSON next to the DNGs.
3. Run:

```bash
cd calibration
python -m redline_cal.profile_device data/phone/*.dng \
    --camera2 data/phone/redline_camera2_0.json --flat data/phone/wall.dng \
    --profile-id baseline --out out/device_baseline.json --gate
```

| Check (B04 gate) | Limit |
| --- | --- |
| Camera2 export matrices vs the phone's DNG tags | identical (1e-3, rational rounding) when Camera2 exposes them; skipped when every colour key is null |
| Calibration constant across all DNGs | identical |
| Camera neutral -> D50 white (forward-matrix path) | < 1 dE |
| Forward- vs colour-matrix path agreement | warning above 3 dE |
| Flat field: residual corner falloff after the phone's gain maps | within 0.5 EV |

Modules: `dng_profile.py` (DNG 1.6 camera->XYZ with two-illuminant interpolation, noise profile + Poisson-Gaussian model, GainMap/WarpRectilinear opcode parsing, device profile JSON), `flatfield.py`, `lensfun_import.py` (ptlens distortion + pa vignetting, interpolated by focal length and aperture stop), `profile_device.py` (the gate).

Notes: phones that merge bursts (HDR+) write a NoiseProfile for the merged frame, so the fitted noise model describes the delivered DNG, not a single readout. A third DNG 1.6 illuminant (custom IlluminantData3) is ignored for now; two-illuminant interpolation is used. Device profile JSON describes one specific phone and stays out of git, as do the source DNGs.

Lens profile:

```bash
python -m redline_cal.lensfun_import data/lensfun/mil-canon.xml \
    --model "Canon RF 24-70mm F2.8L IS USM" --out out/lens_rf24-70.json
```

### When Camera2 hides the calibration

Some phones (the baseline device among them) leave every `SENSOR_COLOR_TRANSFORM*`, `SENSOR_FORWARD_MATRIX*`, `SENSOR_CALIBRATION_TRANSFORM*`, `SENSOR_REFERENCE_ILLUMINANT*` and `LENS_DISTORTION` key null for third-party apps. The stock camera still writes them into its DNGs. On those phones:

- the device profile comes from the stock camera's DNG tags (`profile_device.py` without `--camera2`);
- Redline's own RAW capture (B13) has to write the matrices from that bundled profile, because `DngCreator` has nothing to copy;
- an unprofiled phone with no Camera2 calibration falls back to the generic sRGB path, and its profile is labelled as uncalibrated.

## B21: RAW colour pipeline (reference and parity)

`redline_cal/raw_pipeline.py` is the reference implementation of the app's RAW colour pipeline; its docstring is the
contract (normalise with levels + lens shading map, Malvar-He-Cutler demosaic, 3:2 crop at the zoom stop, white
balance on AsShotNeutral then clip, DNG forward-matrix path into a warmth-adjusted D65, linear sRGB, area downscale,
fitted Standard tone curve with a highlight shoulder, 33^3 residual LUT). The app runs the same steps: Kotlin
`colour/ColourMath.kt` plans each shot, C++ `app/src/main/cpp/raw_pipeline.cpp` does the pixels.

```bash
# Kotlin fixture + on-device self-check frame (re-run after changing the reference or the bundled assets)
python calibration/tools/make_colour_fixtures.py          # --check to verify the committed files

# Native parity on synthetic frames (CI builds the host tool and sets REDLINE_RAW_TOOL)
cmake -S app/src/test/cpp -B build/native-host && cmake --build build/native-host --target raw_pipeline_tool
REDLINE_RAW_TOOL=build/native-host/raw_pipeline_tool python -m pytest calibration/tests/test_raw_pipeline.py

# Native parity on your own phone DNGs (private files stay outside the repo; output is numbers only)
python calibration/tools/raw_parity.py --tool build/native-host/raw_pipeline_tool path/to/*.dng --zoom 1.37 --long-px 2910
```

Gate: the native render matches the reference within dE2000 1.0 (max over every pixel, compared before the final
8-bit rounding) and daylight greys stay neutral (CIELAB chroma <= 2.0). Bundled assets the pipeline reads:
`app/src/main/assets/colour/standard_tone.json` (fitted Standard tone curve), `colour/baseline_profile.json` (the
baseline device profile's colour numbers, matched by model hash), and `luts/redline_standard_preview_33.cube`.

## B22: RAW burst align + merge (reference and parity)

`redline_cal/burst_merge.py` is the reference implementation of the app's burst merge; its docstring is the contract
(grey quads, x2/x4/x4 pyramid, reference = sharpest of the first 3 frames, coarse-to-fine tile alignment with
whole-quad displacements, noise-model tile weights + per-quad outlier rejection, raised-cosine blended merge of the
CFA samples). The app runs it in C++ (`app/src/main/cpp/burst_merge.cpp`) on the processing queue and feeds the merged
mosaic to the B21 pipeline.

```bash
# Native parity on synthetic bursts (CI builds the host tool and sets REDLINE_BURST_TOOL)
cmake -S app/src/test/cpp -B build/native-host && cmake --build build/native-host --target burst_merge_tool
REDLINE_BURST_TOOL=build/native-host/burst_merge_tool python -m pytest calibration/tests/test_burst_merge.py

# Merge quality on real sensor texture: simulated bursts from your own DNGs (numbers only, files named by index)
python calibration/tools/burst_quality.py --tool build/native-host/burst_merge_tool path/to/*.dng --frames 8
```

Parity: identical reference frame and tile displacements, merged RAW within 0.05 RAW units.
