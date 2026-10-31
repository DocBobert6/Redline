"""B23: super-resolution resolution gain on real sensor texture (numbers only; no image or data is written).

Each DNG becomes a set of simulated handheld bursts. A window of the DNG is demosaicked (B21 MHC) and treated as the
continuous scene at twice the simulated sensor's resolution (one simulated RAW px = 2 x 2 DNG px), so the texture
carries real detail beyond the simulated sensor's Nyquist. An MTF probe (a grey grating at a known frequency, with
the DNG texture's fine detail kept on it so its tiles still see the scene's motion, as ``superres.hires_scene``) covers
the window's central quarter. Frames are rendered with real-valued handheld shifts (uniform in +-3 RAW px, 1 RAW px
box aperture), mosaicked RGGB, with fresh Poisson-Gaussian noise, then merged with the reference implementation using
ESTIMATED alignment (no oracle flow).

Per file and stop it reports: the MTF10 limit of luma on the probe for the super-res merge and for one frame
demosaicked and bicubic-upscaled (their ratio is the linear resolution gain), the RMS error against the true scene on
the texture outside the probe for both, the burst coverage and the kernel used.

    python calibration/tools/superres_quality.py path/to/*.dng --stops 50 70 --frames 8

The 50 and 70 mm output scales (3000 px long side over the stop's crop) default to 1.44 and 2.02 (the baseline device
profile's crops). Private files stay outside the repository; the output names files by index only.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import sys

import numpy as np
from scipy import ndimage

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from redline_cal import raw_pipeline as rp  # noqa: E402
from redline_cal import superres as sr  # noqa: E402

BLACK = [64.0] * 4
WHITE = 1023.0
SCALES = {50: 1.44, 70: 2.02}
FREQS = (0.3, 0.4, 0.5, 0.55, 0.6, 0.65, 0.7, 0.75, 0.8, 0.85, 0.9, 1.0)
THETA = 0.12
PROBE_AMP = 0.25
PAD = sr.MARGIN + 16
DNG_PER_RAW = 2  # DNG px per simulated RAW px


def load_scene_rgb(path):
    """The DNG demosaicked to normalised camera RGB (B21 stages 1-2), white-balanced roughly to grey, scaled to ~0.8."""
    import rawpy

    with rawpy.imread(path) as r:
        raw = r.raw_image_visible.astype(np.uint16).copy()
        black = tuple(float(v) for v in r.black_level_per_channel)
        white = float(r.white_level)
        pattern = r.raw_pattern
    h, w = raw.shape
    raw = raw[: h - h % 2, : w - w % 2]
    order = "".join("RGBG"[pattern[y, x]] for y in range(2) for x in range(2))
    cfa = {"RGGB": 0, "GRBG": 1, "GBRG": 2, "BGGR": 3}.get(order, 0)
    rgb = rp.demosaic_mhc(rp.normalise(rp.RawFrame(raw, cfa, black, white)), cfa)
    rgb = rgb / np.maximum(np.median(rgb.reshape(-1, 3), axis=0), 1e-6)
    rgb *= 0.8 / max(float(np.percentile(rgb, 99.0)), 1e-6)
    return np.clip(rgb, 0.0, 1.0)


def pick_windows(rgb, size, count):
    """The ``count`` most textured non-overlapping windows (mean gradient energy of grey, coarse grid), not clipped."""
    g = rgb.mean(-1)
    e = ndimage.gaussian_gradient_magnitude(g, 1.0)
    h, w = g.shape
    step = size // 2
    cands = []
    for y in range(0, h - size, step):
        for x in range(0, w - size, step):
            win = g[y : y + size, x : x + size]
            if (win > 0.98).mean() > 0.01 or win.mean() < 0.05:
                continue
            cands.append((float(e[y : y + size, x : x + size].mean()), y, x))
    cands.sort(reverse=True)
    out = []
    for _, y, x in cands:
        if all(abs(y - oy) >= size or abs(x - ox) >= size for oy, ox in out):
            out.append((y, x))
        if len(out) == count:
            break
    return out


def scene_from_window(win, grating):
    """The continuous scene at SUPERSAMPLE x the simulated RAW resolution, with the MTF probe in the central quarter."""
    zoom = sr.SUPERSAMPLE / DNG_PER_RAW
    big = np.clip(np.stack([ndimage.zoom(win[..., c], zoom, order=3) for c in range(3)], -1), 0.0, 1.0)
    f, th, amp = grating
    hh, ww = big.shape[:2]
    yy, xx = np.mgrid[0:hh, 0:ww] / sr.SUPERSAMPLE
    g = 0.45 + amp * np.cos(2.0 * math.pi * (f * math.cos(th) * xx + f * math.sin(th) * yy))
    lum = big.mean(-1)
    g = g + 0.5 * (lum - ndimage.uniform_filter(lum, 64))
    m = np.zeros((hh, ww), bool)
    m[hh // 4 : 3 * hh // 4, ww // 4 : 3 * ww // 4] = True
    big[m] = g[m][:, None]
    return big


def amplitude(img, yy, xx, f, theta):
    return sr.fit_amplitude(img @ sr.LUMA_W, yy, xx, f, theta)


def run_case(win, shifts, scale, crop_px, noise_s, noise_o, seed):
    cw = ch = crop_px
    h = w = crop_px + 2 * PAD
    hs = h + 16
    crop = (PAD, PAD, cw, ch)
    out = (int(round(cw * scale)), int(round(ch * scale)))
    ry = PAD + (np.arange(out[1]) + 0.5) * ch / out[1] - 0.5
    rx = PAD + (np.arange(out[0]) + 0.5) * cw / out[0] - 0.5
    s0 = shifts[0]
    lo, hi = hs / 4 + 6, 3 * hs / 4 - 6
    iy = np.where((ry + s0[0] > lo) & (ry + s0[0] < hi))[0]
    ix = np.where((rx + s0[1] > lo) & (rx + s0[1] < hi))[0]
    probe = np.ix_(iy, ix)
    yy, xx = np.meshgrid(ry[iy] + s0[0], rx[ix] + s0[1], indexing="ij")
    m_sr, m_one = [], []
    cov = sig = None
    rms = None
    for k, f in enumerate(FREQS):
        big = scene_from_window(win, (f, THETA, PROBE_AMP))
        frames = sr.textured_burst(big, shifts, h, w, noise_s=noise_s, noise_o=noise_o, seed=seed + k)
        r = sr.merge(frames, 0, BLACK, WHITE, noise_s, noise_o, crop, out, reference=0)
        one = sr.single_frame_upscale(frames[0], 0, BLACK, WHITE, crop, out)
        m_sr.append(amplitude(r.rgb[probe], yy, xx, f, THETA) / PROBE_AMP)
        m_one.append(amplitude(one[probe], yy, xx, f, THETA) / PROBE_AMP)
        if k == 0:
            cov, sig = r.coverage, r.sigma
            truth = sr.scene_truth(big, ry + s0[0], rx + s0[1])
            tex = np.ones(out[::-1], bool)
            tex[probe] = False
            b = 12
            tex[:b] = tex[-b:] = False
            tex[:, :b] = tex[:, -b:] = False
            rms = (float(np.sqrt(np.mean((r.rgb - truth)[tex] ** 2))), float(np.sqrt(np.mean((one - truth)[tex] ** 2))))
    fl = list(FREQS)
    lim_sr, lim_one = sr.mtf_limit(fl, m_sr), sr.mtf_limit(fl, m_one)
    return {
        "mtf10_sr": round(lim_sr, 3),
        "mtf10_single": round(lim_one, 3),
        "gain": round(lim_sr / lim_one, 3),
        "texture_rms_sr": round(rms[0], 5),
        "texture_rms_single": round(rms[1], 5),
        "texture_error_ratio": round(rms[1] / rms[0], 3),
        "coverage": round(float(cov), 3),
        "sigma": [round(float(s), 3) for s in sig],
        "mtf_sr": [round(v, 3) for v in m_sr],
        "mtf_single": [round(v, 3) for v in m_one],
    }


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("files", nargs="+")
    ap.add_argument("--stops", type=int, nargs="+", default=[50, 70])
    ap.add_argument("--frames", type=int, default=8)
    ap.add_argument("--windows", type=int, default=1, help="textured windows per file")
    ap.add_argument("--crop", type=int, default=160, help="simulated crop side in RAW px")
    ap.add_argument("--noise-s", type=float, default=2e-4)
    ap.add_argument("--noise-o", type=float, default=2e-6)
    ap.add_argument("--seed", type=int, default=5)
    a = ap.parse_args(argv)
    gains = {s: [] for s in a.stops}
    for idx, path in enumerate(a.files):
        rgb = load_scene_rgb(path)
        size = (a.crop + 2 * PAD + 16) * DNG_PER_RAW
        for wi, (y, x) in enumerate(pick_windows(rgb, size, a.windows)):
            win = rgb[y : y + size, x : x + size]
            for stop in a.stops:
                shifts = sr.random_shifts(a.frames, seed=a.seed + 101 * idx + wi)
                res = run_case(win, shifts, SCALES[stop], a.crop, a.noise_s, a.noise_o, seed=a.seed + idx)
                gains[stop].append(res["gain"])
                print(json.dumps({"file": idx, "window": wi, "stop_mm": stop, "scale": SCALES[stop], "frames": a.frames, **res}),
                      flush=True)
    for stop, g in gains.items():
        if g:
            print(json.dumps({"summary_stop_mm": stop, "cases": len(g), "gain_mean": round(float(np.mean(g)), 3),
                              "gain_min": round(float(min(g)), 3), "gain_max": round(float(max(g)), 3)}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
