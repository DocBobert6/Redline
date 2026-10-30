"""B21 gate on real phone DNGs: render each frame with the Python reference and the native pipeline, compare.

The frame's own DNG tags supply everything the app reads from Camera2 at capture time: black/white levels, the CFA
layout, AsShotNeutral (or AsShotWhiteXY), the colour calibration, and the lens shading (the DNG GainMap opcodes, resampled onto a
Camera2-style shading grid). The exposure target is the embedded preview JPEG's median luma when there is one,
else the default target. Frames are identified by index only; outputs carry numbers, never pixels.

Usage::

    python calibration/tools/raw_parity.py --tool build/raw_pipeline_tool FRAME.dng [...] [--zoom 1.37 --long-px 2910]
"""
from __future__ import annotations

import argparse
import io
import json
import os
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from redline_cal import dng_profile as dp  # noqa: E402
from redline_cal import raw_pipeline as rp  # noqa: E402
from redline_cal.metadata import find_exiftool  # noqa: E402
from redline_cal.profile_device import _first, read_dng  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TONE = os.path.join(ROOT, "app", "src", "main", "assets", "colour", "standard_tone.json")
CUBE = os.path.join(ROOT, "app", "src", "main", "assets", "luts", "redline_standard_preview_33.cube")


def cfa_index(pattern) -> int:
    vals = tuple(int(v) for v in str(pattern).split())
    for idx, layout in rp.CFA_LAYOUTS.items():
        if layout == vals:
            return idx
    raise ValueError(f"unsupported CFA pattern {pattern}")


def shading_from_gain_maps(maps: list[dp.GainMap], cfa: int) -> np.ndarray | None:
    """Camera2-order (R, G_even, G_odd, B) grid sampled from per-plane DNG GainMaps (one per CFA position)."""
    if len(maps) != 4:
        return None
    rows, cols = max(m.points_v for m in maps), max(m.points_h for m in maps)
    v = np.linspace(0.0, 1.0, rows)[:, None] * np.ones((1, cols))
    h = np.ones((rows, 1)) * np.linspace(0.0, 1.0, cols)[None, :]
    out = np.ones((rows, cols, 4))
    layout = rp.CFA_LAYOUTS[cfa]
    for m in maps:
        p = ((m.top & 1) << 1) | (m.left & 1)
        colour = layout[p]
        ch = 0 if colour == 0 else 3 if colour == 2 else (1 if (p >> 1) == 0 else 2)
        out[..., ch] = m.gain_at(v, h)
    return out


def preview_median(path: str) -> float | None:
    try:
        import rawpy
        from PIL import Image

        with rawpy.imread(path) as raw:
            th = raw.extract_thumb()
        if th.format != rawpy.ThumbFormat.JPEG:
            return None
        img = np.asarray(Image.open(io.BytesIO(th.data)).convert("RGB"), float) / 255.0
        return rp.jpeg_median_encoded(img[::4, ::4])
    except Exception:  # noqa: BLE001 - no preview is fine, the default target is used
        return None


def load_frame(path: str, exiftool: str):
    import rawpy

    tags, op2, _ = read_dng(path, exiftool)
    cal = dp.color_from_dng_tags(tags)
    cfa = cfa_index(_first(tags, "CFAPattern2"))
    black = [float(b) for b in str(_first(tags, "BlackLevel")).split()]
    black = (black * 4)[:4] if len(black) < 4 else black[:4]
    white = float(str(_first(tags, "WhiteLevel")).split()[0])
    if _first(tags, "AsShotNeutral") is not None:
        neutral = [float(v) for v in str(_first(tags, "AsShotNeutral")).split()]
    else:  # some DNG writers record the white as AsShotWhiteXY: the camera neutral follows from the calibration
        xy = [float(v) for v in str(_first(tags, "AsShotWhiteXY")).split()]
        neutral = dp.camera_to_xyz(cal, white_xy=(xy[0], xy[1])).camera_neutral
    with rawpy.imread(path) as raw:
        mosaic = np.array(raw.raw_image, dtype=np.uint16)
    maps = [o for i, o in dp.parse_opcode_list(op2) if i == dp.OP_GAIN_MAP] if op2 else []
    frame = rp.RawFrame(mosaic, cfa, tuple(black), white, shading_from_gain_maps(maps, cfa))
    return frame, cal, neutral


def run(path: str, tool: str, exiftool: str, zoom: float, long_px: int, tone, lut) -> dict:
    frame, cal, neutral = load_frame(path, exiftool)
    target = preview_median(path)
    t0 = time.time()
    ref_plan = rp.plan_shot(frame, cal, neutral, tone, zoom=zoom, long_px=long_px, target_encoded=target)
    ref = rp.render_plan(frame, ref_plan, lut)
    ref_s = time.time() - t0
    st = rp.native_stats(tool, frame, ref_plan.crop, ref_plan.solve.neutral, ref_plan.solve.matrix)
    plan = rp.plan_shot(frame, cal, neutral, tone, zoom=zoom, long_px=long_px, target_encoded=target, stats=st)
    img, ms = rp.native_render(tool, frame, plan, CUBE, threads=os.cpu_count() or 2)
    img8, ms8 = rp.native_render(tool, frame, plan, CUBE, threads=os.cpu_count() or 2, precise=False)
    par = rp.parity(ref, img)
    par8 = rp.parity(rp.quantise8(ref), img8)
    # Neutral check on the real frame: near-grey pixels of the reference stay near-grey in the native render.
    lab_ref = rp.srgb_encoded_to_lab(ref.reshape(-1, 3))
    grey = (np.hypot(lab_ref[:, 1], lab_ref[:, 2]) < 2.0) & (lab_ref[:, 0] > 20) & (lab_ref[:, 0] < 95)
    nat_chroma = rp.neutral_chroma(img.reshape(-1, 3)[grey]) if grey.any() else np.array([np.nan])
    return {
        "size": list(frame.raw.shape[::-1]), "crop": list(plan.crop), "out": list(plan.out_size),
        "cct": round(plan.solve.cct), "warmth": round(plan.solve.warmth, 3), "exposure_ev": round(plan.exposure_ev, 3),
        "exposure_method": plan.exposure_method, "stats_median_rel_diff": abs(st["median_y"] / ref_plan.stats["median_y"] - 1),
        "parity_float": par, "parity_8bit": par8,
        "grey_pixels": int(grey.sum()), "grey_native_chroma_p99": float(np.percentile(nat_chroma, 99)),
        "native_ms_host": ms, "native_8bit_ms_host": ms8, "reference_s": round(ref_s, 1),
    }


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("dngs", nargs="+")
    ap.add_argument("--tool", required=True)
    ap.add_argument("--exiftool")
    ap.add_argument("--zoom", type=float, default=1.0)
    ap.add_argument("--long-px", type=int, default=0)
    ap.add_argument("--out", help="append JSON lines here (keep it outside the repo)")
    a = ap.parse_args(argv)
    et = find_exiftool(a.exiftool)
    tone, lut = rp.load_tone(TONE), rp.load_lut(CUBE)
    worst = 0.0
    for i, path in enumerate(a.dngs):
        res = run(path, a.tool, et, a.zoom, a.long_px, tone, lut)
        res.update({"frame": i, "zoom": a.zoom, "long_px": a.long_px})
        worst = max(worst, res["parity_float"]["max_de2000"])
        print(json.dumps(res))
        if a.out:
            with open(a.out, "a") as f:
                f.write(json.dumps(res) + "\n")
    print(f"worst max dE2000 {worst:.3f} -> {'PASS' if worst <= 1.0 else 'FAIL'}")
    return 0 if worst <= 1.0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
