"""B22: merge quality of the native burst merge on real sensor texture (numbers only; no image is written).

Each DNG becomes a simulated handheld burst: N windows of its CFA mosaic at known whole-quad offsets, each with
fresh Poisson-Gaussian noise added (``--noise-s/--noise-o``, normalised units). The DNG's own content is the ground
truth for the reference window (its own noise is shared by every window, so it is scene texture here). Reports per
file: interior tiles aligned exactly, mean alignment error in RAW pixels, the RMS error of the reference window and
of the merge against the truth (their ratio is the noise reduction), and the native timings.

    python calibration/tools/burst_quality.py --tool build/native-host/burst_merge_tool path/to/*.dng --frames 8

Private files stay outside the repository; the output names files by index only.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile

import numpy as np


def load_mosaic(path):
    import rawpy

    with rawpy.imread(path) as r:
        raw = r.raw_image_visible.astype(np.uint16).copy()
        black = [float(v) for v in r.black_level_per_channel]
        white = float(r.white_level)
    h, w = raw.shape
    return raw[: h - h % 2, : w - w % 2], black, white


def simulate(raw, black, white, n, s, o, seed, margin=16):
    """Windows of the mosaic at offsets (grey px) and noisy copies; returns frames, offsets, truth of window 0."""
    rs = np.random.default_rng(seed)
    h, w = raw.shape
    offsets = [(0, 0)] + [tuple(int(v) for v in rs.integers(-6, 7, 2)) for _ in range(n - 1)]
    hh, ww = h - 4 * margin, w - 4 * margin
    bm = float(np.mean(black))
    rng = white - bm
    frames = []
    truth = None
    for k, (dy, dx) in enumerate(offsets):
        y0, x0 = 2 * margin + 2 * dy, 2 * margin + 2 * dx
        win = raw[y0 : y0 + hh, x0 : x0 + ww].astype(np.float64)
        x = np.clip((win - bm) / rng, 0, 1)
        noisy = win + rs.standard_normal(win.shape) * np.sqrt(s * x + o) * rng
        frames.append(np.clip(np.round(noisy), 0, white).astype(np.uint16))
        if k == 0:
            truth = win
    return frames, offsets, truth


def run(tool, frames, black, white, s, o, threads, tmp):
    h, w = frames[0].shape
    paths = []
    for i, f in enumerate(frames):
        p = os.path.join(tmp, f"f{i}.raw")
        f.astype("<u2").tofile(p)
        paths.append(p)
    params = os.path.join(tmp, "params.txt")
    with open(params, "w") as fh:
        fh.write(
            f"width {w} height {h} black {' '.join(str(b) for b in black)} white {white} noise {s} {o} "
            f"threads {threads} reference 0 frames {len(paths)} " + " ".join(paths)
        )
    out, disp = os.path.join(tmp, "out.f32"), os.path.join(tmp, "disp.i32")
    res = subprocess.run([tool, params, out, disp], capture_output=True, text=True, check=True)
    st = json.loads(res.stdout)
    merged = np.fromfile(out, dtype="<f4").reshape(h, w)
    d = np.fromfile(disp, dtype="<i4").reshape(len(frames), st["tiles_y"], st["tiles_x"], 2)
    return merged, d, st


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("files", nargs="+")
    ap.add_argument("--tool", required=True)
    ap.add_argument("--frames", type=int, default=8)
    ap.add_argument("--noise-s", type=float, default=1e-3)
    ap.add_argument("--noise-o", type=float, default=1e-6)
    ap.add_argument("--threads", type=int, default=os.cpu_count() or 2)
    a = ap.parse_args(argv)
    for idx, path in enumerate(a.files):
        raw, black, white = load_mosaic(path)
        frames, offsets, truth = simulate(raw, black, white, a.frames, a.noise_s, a.noise_o, seed=idx + 1)
        with tempfile.TemporaryDirectory() as tmp:
            merged, d, st = run(a.tool, frames, black, white, a.noise_s, a.noise_o, a.threads, tmp)
        h0, w0 = frames[0].shape[0] // 2, frames[0].shape[1] // 2
        oy = np.arange(st["tiles_y"]) * 8 - 8
        ox = np.arange(st["tiles_x"]) * 8 - 8
        iy = (oy >= 16) & (oy + 16 <= h0 - 16)
        ix = (ox >= 16) & (ox + 16 <= w0 - 16)
        exact, err = [], []
        for k in range(1, len(frames)):
            want = np.array(offsets[0]) - np.array(offsets[k])
            sub = d[k][np.ix_(iy, ix)]
            exact.append(float((sub == want).all(-1).mean()))
            err.append(float(np.abs(sub - want).max(-1).mean() * 2))
        m = 32
        e1 = float(np.sqrt(np.mean((frames[0].astype(np.float64) - truth)[m:-m, m:-m] ** 2)))
        e2 = float(np.sqrt(np.mean((merged - truth)[m:-m, m:-m] ** 2)))
        print(
            json.dumps(
                {
                    "file": idx,
                    "size": [int(frames[0].shape[1]), int(frames[0].shape[0])],
                    "frames": len(frames),
                    "aligned_exact_min": round(min(exact), 4),
                    "aligned_exact_mean": round(float(np.mean(exact)), 4),
                    "align_error_px_mean": round(float(np.mean(err)), 4),
                    "rms_single": round(e1, 3),
                    "rms_merged": round(e2, 3),
                    "noise_reduction": round(e1 / e2, 3),
                    "ideal": round(float(np.sqrt(len(frames))), 3),
                    "rejected_mean": round(float(np.mean([f["rejected_fraction"] for f in st["frames"]])), 4),
                    "timings_ms": {k: st[k] for k in ("pyramid_ms", "reference_ms", "align_ms", "merge_ms", "finish_ms")},
                }
            )
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
