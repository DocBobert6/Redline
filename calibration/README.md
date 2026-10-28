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
