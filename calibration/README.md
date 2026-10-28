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

## Tests

```bash
python -m pytest calibration -q
REDLINE_CAL_SAMPLE_CR3=/path/to/RAW.CR3 python -m pytest calibration -q   # adds real-file exiftool + decode checks
```
