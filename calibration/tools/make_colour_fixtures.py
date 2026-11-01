"""Generate the B21 fixtures that tie the app to the Python reference RAW pipeline.

* ``app/src/test/resources/colour/colour_math_cases.json``: per-shot maths cases (Planckian locus, warmth,
  camera->sRGB matrices, tone curve with shoulder, exposure, JPEG median, crop, CIEDE2000). The Kotlin unit test
  ``ColourMathTest`` must reproduce every value.
* ``app/src/main/assets/colour/self_check.json``: a small synthetic RAW frame plus its reference render (8-bit),
  which the app renders through its own Kotlin + native pipeline at start-up (the on-device parity and neutral
  self-check, and the emulator smoke test).

Inputs are the bundled assets only (baseline colour profile, Standard tone curve, residual LUT), so CI can
re-run this and ``tests/test_raw_pipeline.py`` checks the committed files are current.

Usage::

    python calibration/tools/make_colour_fixtures.py            # write both files
    python calibration/tools/make_colour_fixtures.py --check    # exit 1 if the committed files differ
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from redline_cal import dng_profile as dp  # noqa: E402
from redline_cal import raw_pipeline as rp  # noqa: E402
from redline_cal.color import delta_e2000, srgb_encoded_to_lab  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
PROFILE = os.path.join(ASSETS, "colour", "baseline_profile.json")
TONE = os.path.join(ASSETS, "colour", "standard_tone.json")
METERING = os.path.join(ASSETS, "colour", "metering.json")
CUBE = os.path.join(ASSETS, "luts", "redline_standard_preview_33.cube")
CASES_OUT = os.path.join(ROOT, "app", "src", "test", "resources", "colour", "colour_math_cases.json")
SELF_CHECK_OUT = os.path.join(ASSETS, "colour", "self_check.json")

# A one-illuminant, colour-matrix-only profile (no forward matrices) exercises the other DNG path.
NO_FORWARD = {
    "illuminant1": 21,
    "illuminant2": None,
    "color_matrix1": [[0.71, -0.13, -0.07], [-0.42, 1.21, 0.24], [-0.06, 0.15, 0.62]],
    "color_matrix2": None,
    "forward_matrix1": None,
    "forward_matrix2": None,
    "calibration1": None,
    "calibration2": None,
    "analog_balance": [1.0, 1.0, 1.0],
}


def r(v, nd=10):
    """Round floats (recursively) so the JSON is stable across platforms."""
    if isinstance(v, (list, tuple)):
        return [r(x, nd) for x in v]
    if isinstance(v, np.ndarray):
        return r(v.tolist(), nd)
    if isinstance(v, (float, np.floating)):
        return float(round(float(v), nd))
    return v


def load_profile() -> dp.ColorCalibration:
    with open(PROFILE) as f:
        return dp.ColorCalibration(**json.load(f)["color"])


def neutral_for(cal: dp.ColorCalibration, xy) -> list[float]:
    return dp.camera_to_xyz(cal, white_xy=xy).camera_neutral


def colour_math_cases() -> dict:
    cal = load_profile()
    nofwd = dp.ColorCalibration(**NO_FORWARD)
    tone = rp.load_tone(TONE)
    out: dict = {"profiles": {"baseline": json.load(open(PROFILE))["color"], "no_forward": NO_FORWARD}}
    out["planck"] = [[c, *rp.planck_xy(c)] for c in (1800.0, 2222.0, 2856.0, 3500.0, 4000.0, 5000.0, 6504.0, 9000.0)]
    out["warmth"] = [[c, rp.warmth_retention(c)] for c in (2500.0, 3000.0, 3500.0, 4000.0, 4999.0, 5000.0, 6500.0)]
    out["cct"] = [[x, y, dp.xy_to_cct((x, y))] for x, y in ((0.3127, 0.3290), (0.4476, 0.4074), (0.3457, 0.3585))]
    solves = []
    whites = [(0.3127, 0.3290), rp.planck_xy(2856.0), rp.planck_xy(3800.0), rp.planck_xy(5200.0), (0.30, 0.33)]
    for name, c in (("baseline", cal), ("no_forward", nofwd)):
        for xy in whites:
            n = np.asarray(neutral_for(c, xy)) * 0.83  # not max-normalised on purpose
            for warmth in (True, False):
                s = rp.solve_colour(c, n.tolist(), warmth)
                solves.append({
                    "profile": name, "as_shot_neutral": n.tolist(), "warmth_enabled": warmth,
                    "matrix": s.matrix.reshape(-1).tolist(), "neutral": s.neutral.tolist(),
                    "white_xy": list(s.white_xy), "cct": s.cct, "retention": s.warmth, "used_forward": s.used_forward,
                })
    out["solve"] = solves
    out["tone"] = tone.tolist()
    curves = []
    xs = [0.0, 1e-6, 2.0**-14.5, 2.0**-10, 0.01, 0.05, 0.1, 0.2, 0.3, 0.5, 0.7, 1.0, 1.5, 3.0]
    for gain in (0.05, 0.3, 0.7, 1.0, 2.5, 9.0):
        c = rp.ToneCurve(tone, gain)
        curves.append({
            "gain": gain, "y_knee": c.y_knee, "slope": c.slope, "clip_ev": c.clip_ev, "k": c.k,
            "x": xs, "y": c(np.asarray(xs) * gain).tolist(), "table16": c.table(16).tolist(),
        })
    out["curves"] = curves
    out["shoulder_k"] = [[m, rp.solve_shoulder_k(m)] for m in (0.2, 0.9, 1.0, 1.7, 4.0, 60.0)]
    exp = []
    for med, target in ((0.09, 0.46), (0.09, None), (0.002, 0.5), (0.6, 0.3), (0.0, 0.46), (0.2, 0.95), (1e-5, 0.46)):
        ev, method = rp.solve_exposure(tone, med, target)
        exp.append({"median_y": med, "target": target, "ev": ev, "method": method})
    out["exposure"] = exp
    # B23c: the R5 II metering fit (asset colour/metering.json), as the app reads it.
    met = rp.load_metering(METERING)
    out["metering_model"] = json.load(open(METERING))
    mexp = []
    for med, p98 in ((0.02, 0.04), (0.02, 0.64), (0.01, 0.32), (0.02, None), (0.0, 0.1), (0.001, 1.0), (0.3, 0.31),
                     (0.05, 0.05), (1e-5, 0.9)):
        ev, method = rp.solve_exposure_metered(tone, med, p98, met)
        mexp.append({"median_y": med, "p98_y": p98, "ev": ev, "method": method})
    out["metering"] = mexp
    rng = np.random.default_rng(7)
    jm = []
    for n in (1, 2, 7, 64):
        px = rng.integers(0, 256, size=(n, 3))
        jm.append({"pixels": px.reshape(-1).tolist(), "median": rp.jpeg_median_encoded(px / 255.0)})
    out["jpeg_median"] = jm
    crops = []
    for w, h, z in ((4000, 3000, 1.0), (4000, 3000, 1.37), (4000, 3000, 2.74), (3000, 4000, 1.0), (4000, 2256, 1.0),
                    (4000, 2000, 1.0), (240, 160, 1.4), (4032, 3024, 0.9)):
        crops.append({"width": w, "height": h, "zoom": z, "crop": list(rp.crop_32(w, h, z))})
    out["crop"] = crops
    out["output_size"] = [[w, h, lp, *rp.output_size(w, h, lp)] for w, h, lp in
                          ((4000, 2666, 4000), (4000, 2666, 2910), (2920, 1946, 2040), (240, 160, 97), (1000, 667, 0))]
    rgb = rng.random((12, 3))
    rgb2 = np.clip(rgb + rng.normal(0, 0.03, rgb.shape), 0, 1)
    rgb[0] = rgb2[0] = [0.5, 0.5, 0.5]
    lab1, lab2 = srgb_encoded_to_lab(rgb), srgb_encoded_to_lab(rgb2)
    out["lab"] = [{"rgb": a.tolist(), "lab": b.tolist()} for a, b in zip(rgb, lab1)]
    out["de2000"] = [{"lab1": a.tolist(), "lab2": b.tolist(), "de": float(d)} for a, b, d in zip(lab1, lab2, delta_e2000(lab1, lab2))]
    # Sharma et al. (2005) reference pairs.
    sharma = [([50.0, 2.6772, -79.7751], [50.0, 0.0, -82.7485], 2.0425), ([50.0, -1.3802, -84.2814], [50.0, 0.0, -82.7485], 1.0),
              ([50.0, 2.5, 0.0], [73.0, 25.0, -18.0], 27.1492), ([2.0776, 0.0795, -1.135], [0.9033, -0.0636, -0.5514], 0.9082)]
    out["de2000"] += [{"lab1": a, "lab2": b, "de": d} for a, b, d in sharma]
    return r(out)


def self_check() -> dict:
    cal = load_profile()
    tone = rp.load_tone(TONE)
    lut = rp.load_lut(CUBE)
    neutral = (np.asarray(neutral_for(cal, (0.3127, 0.3290))) * 0.9).tolist()  # daylight: no warmth retained
    shading = rp.synthetic_shading(6, 8, 1.6)
    frame, boxes = rp.synthetic_frame(cal, neutral, width=120, height=80, cfa=1, shading=shading, seed=2121)
    plan = rp.plan_shot(frame, cal, neutral, tone, zoom=1.0, long_px=90, target_encoded=0.46)
    ref = rp.quantise8(rp.render_plan(frame, plan, lut))
    l, t, w, h = plan.crop
    ow, oh = plan.out_size
    sx, sy = ow / w, oh / h
    grey_boxes = []
    for x0, y0, x1, y1 in boxes[: rp.N_GREYS]:
        grey_boxes.append([int(np.ceil((x0 - l) * sx)), int(np.ceil((y0 - t) * sy)), int(np.floor((x1 - l) * sx)), int(np.floor((y1 - t) * sy))])
    means = rp.patch_means(ref, boxes[: rp.N_GREYS], plan.crop, plan.out_size)
    return r({
        "description": "B21 self-check: synthetic RAW frame and its reference (Python) render, 8-bit sRGB. "
                       "Generated by calibration/tools/make_colour_fixtures.py.",
        "width": 120, "height": 80, "cfa": frame.cfa, "black": list(frame.black), "white": frame.white,
        "raw_u16le_b64": base64.b64encode(np.ascontiguousarray(frame.raw, dtype="<u2").tobytes()).decode("ascii"),
        "shading": {"rows": shading.shape[0], "cols": shading.shape[1], "values": shading.reshape(-1).tolist()},
        "profile": json.load(open(PROFILE))["color"],
        "as_shot_neutral": neutral, "zoom": 1.0, "long_px": 90, "target_encoded": 0.46,
        "expected": {
            "median_y": plan.stats["median_y"], "exposure_ev": plan.exposure_ev, "matrix": plan.solve.matrix.reshape(-1).tolist(),
            "neutral": plan.solve.neutral.tolist(), "crop": list(plan.crop), "out": [ow, oh],
            "rgb8_b64": base64.b64encode(np.round(ref * 255).astype(np.uint8).tobytes()).decode("ascii"),
            "grey_chroma": rp.neutral_chroma(means).tolist(),
        },
        "grey_boxes_out": grey_boxes,
    }, 9)


def dump(obj: dict) -> str:
    return json.dumps(obj, separators=(",", ":")) + "\n"


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--check", action="store_true", help="compare with the committed files instead of writing")
    a = p.parse_args(argv)
    files = {CASES_OUT: dump(colour_math_cases()), SELF_CHECK_OUT: dump(self_check())}
    bad = 0
    for path, text in files.items():
        if a.check:
            same = os.path.exists(path) and open(path).read() == text
            print(("ok      " if same else "STALE   ") + os.path.relpath(path, ROOT))
            bad += 0 if same else 1
        else:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", newline="\n") as f:
                f.write(text)
            print("wrote " + os.path.relpath(path, ROOT))
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
