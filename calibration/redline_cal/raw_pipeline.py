"""B21 RAW colour pipeline: the reference (Python) implementation.

This module is the contract. The app runs the same pipeline (Kotlin ``colour/ColourMath.kt`` for the per-shot
maths, C++ ``app/src/main/cpp/raw_pipeline.cpp`` for the per-pixel work) and CI checks both against it:
``tests/test_raw_pipeline.py`` (C++ pixels, dE2000) and the Kotlin unit test that reads
``app/src/test/resources/colour/colour_math_cases.json`` (matrices, curve, exposure).

Per pixel, in order:

1. normalise   v = (raw - black[p]) / (white - black[p]) * shade[p](x, y), p = CFA position (y & 1) * 2 + (x & 1).
               ``shade`` is the camera's lens shading map (Camera2 layout R, G_even, G_odd, B on a rows x cols grid
               spanning the full frame, bilinear); none = 1.
2. demosaic    Malvar-He-Cutler 5x5 on the full frame, mirror (reflect-101) borders, which keep the CFA phase.
3. crop        the zoom stop's 3:2 window (left/top even).
4. balance     c = clip(v / neutral, 0, 1). ``neutral`` is the camera's AsShotNeutral, max-normalised. Clipping
               after the balance turns blown highlights white instead of magenta.
5. colour      lin = M c, M = XYZ->linear sRGB . Bradford(D50 -> W_out) . ForwardPath(scene white) . diag(neutral).
               ForwardPath is the DNG 1.6 camera->XYZ(D50) mapping with two-illuminant interpolation
               (``dng_profile.camera_to_xyz``). W_out is D65 moved towards the Planckian white at the scene CCT by
               the Canon-style warmth retention r(CCT): 0 at 5000 K and above, rising linearly to 0.30 at 3000 K and
               below, so a tungsten scene keeps about 30 % of its cast and daylight neutrals stay neutral.
6. resample    area (box) downscale in linear light to the output size.
7. curve       y = C(g * lin) per channel: the fitted Standard tone curve (B03) up to the knee at -1.5 EV, then a
               highlight shoulder that rolls off to exactly 1.0 at the sensor clip (g * 1.0), matching the fitted
               slope at the knee. ``g`` is the exposure gain: the camera JPEG's median luma, inverted through the
               fitted tone curve, over the RAW frame's median luminance.
8. look        33^3 residual LUT (trilinear) -> sRGB-encoded output.
"""
from __future__ import annotations

import json
import math
from dataclasses import dataclass, field

import numpy as np
from scipy import ndimage

from . import dng_profile as dp
from .color import D65, XYZ_TO_SRGB, delta_e2000, srgb_decode, srgb_encode, srgb_encoded_to_lab
from .look import EV_MAX, EV_MIN
from .lut import apply_lut, read_cube

LUMA = np.array([0.2126729, 0.7151522, 0.0721750])  # linear sRGB -> Y

# Canon "ambience priority" warmth (PLAN 19.3): fraction of the scene's warm cast retained.
WARMTH_MAX = 0.30
WARMTH_FULL_K = 3000.0
WARMTH_OFF_K = 5000.0

KNEE_EV = -1.5          # highlight shoulder starts here (scene EV, 0 = sensor clip at unit gain)
KNEE_SLOPE_DELTA = 0.25  # EV half-width of the central difference that measures the fitted slope at the knee
MIN_SHOULDER_EV = 0.5    # the sensor clip is kept at least this far above the knee
EXPOSURE_MIN_EV = -2.0
EXPOSURE_MAX_EV = 5.0
DEFAULT_TARGET = 0.46    # encoded median used when the camera JPEG is unavailable
CURVE_POINTS = 1024      # table handed to the native code (EV_MIN .. clip EV)
STATS_STEP = 8           # stats pass samples every 8th CFA cell in each direction

# Camera2 SENSOR_INFO_COLOR_FILTER_ARRANGEMENT: colour (0 R, 1 G, 2 B) at CFA positions 0..3 (y & 1) * 2 + (x & 1).
CFA_LAYOUTS = {0: (0, 1, 1, 2), 1: (1, 0, 2, 1), 2: (1, 2, 0, 1), 3: (2, 1, 1, 0)}
CFA_NAMES = {"RGGB": 0, "GRBG": 1, "GBRG": 2, "BGGR": 3}


# ----------------------------------------------------------------------------------------------- per-shot maths


def planck_xy(cct: float) -> tuple[float, float]:
    """Planckian locus chromaticity (Kim et al. 2002 cubic spline), 1667-25000 K (clamped)."""
    t = min(max(cct, 1667.0), 25000.0)
    if t <= 4000.0:
        x = -0.2661239e9 / t**3 - 0.2343589e6 / t**2 + 0.8776956e3 / t + 0.179910
    else:
        x = -3.0258469e9 / t**3 + 2.1070379e6 / t**2 + 0.2226347e3 / t + 0.240390
    if t <= 2222.0:
        y = -1.1063814 * x**3 - 1.34811020 * x**2 + 2.18555832 * x - 0.20219683
    elif t <= 4000.0:
        y = -0.9549476 * x**3 - 1.37418593 * x**2 + 2.09137015 * x - 0.16748867
    else:
        y = 3.0817580 * x**3 - 5.87338670 * x**2 + 3.75112997 * x - 0.37001483
    return float(x), float(y)


def warmth_retention(cct: float, enabled: bool = True) -> float:
    if not enabled:
        return 0.0
    f = (WARMTH_OFF_K - cct) / (WARMTH_OFF_K - WARMTH_FULL_K)
    return WARMTH_MAX * min(max(f, 0.0), 1.0)


def output_white(cct: float, warmth: bool = True) -> tuple[np.ndarray, float]:
    """XYZ (Y = 1) the rendered white is adapted to, and the retention used."""
    r = warmth_retention(cct, warmth)
    w = D65.copy()
    if r > 0.0:
        p = dp.xy_to_xyz(planck_xy(cct))
        w = (1.0 - r) * D65 + r * p
        w = w / w[1]
    return w, r


@dataclass
class ColourSolve:
    matrix: np.ndarray       # white-balanced camera RGB -> linear sRGB (scene-referred)
    neutral: np.ndarray      # max-normalised camera neutral
    white_xy: tuple[float, float]
    cct: float
    warmth: float
    used_forward: bool


def solve_colour(cal: dp.ColorCalibration, neutral, warmth: bool = True) -> ColourSolve:
    n = np.asarray(neutral, float)
    if n.shape != (3,) or not np.all(np.isfinite(n)) or n.min() <= 0:
        raise dp.ProfileError("AsShotNeutral must be three positive values")
    m = dp.camera_to_xyz(cal, neutral=(n / n.max()).tolist())
    cam_neutral = np.asarray(m.camera_neutral)
    fwd = m.forward_path is not None
    path = np.asarray(m.forward_path if fwd else m.color_path)
    w_out, r = output_white(m.cct, warmth)
    mat = XYZ_TO_SRGB @ dp.bradford(dp.D50_XYZ, w_out) @ path @ np.diag(cam_neutral)
    return ColourSolve(mat, cam_neutral, m.white_xy, float(m.cct), r, fwd)


@dataclass
class ToneCurve:
    """The fitted Standard tone (log2 grid) plus the highlight shoulder for one exposure gain."""

    tone: np.ndarray
    gain: float
    knee_ev: float = KNEE_EV
    y_knee: float = field(init=False)
    slope: float = field(init=False)
    clip_ev: float = field(init=False)
    k: float = field(init=False)

    def __post_init__(self):
        self.tone = np.asarray(self.tone, float)
        self.y_knee = self.fitted(self.knee_ev)
        d = KNEE_SLOPE_DELTA
        self.slope = (self.fitted(self.knee_ev + d) - self.fitted(self.knee_ev - d)) / (2 * d)
        self.clip_ev = max(math.log2(self.gain), self.knee_ev + MIN_SHOULDER_EV)
        span = self.clip_ev - self.knee_ev
        m = self.slope * span / max(1.0 - self.y_knee, 1e-6)
        self.k = solve_shoulder_k(m)

    @property
    def grid(self) -> np.ndarray:
        return np.linspace(EV_MIN, EV_MAX, len(self.tone))

    def fitted(self, ev):
        return np.interp(ev, self.grid, self.tone)

    def __call__(self, x: np.ndarray) -> np.ndarray:
        """Curve on gained linear values (any shape)."""
        x = np.clip(np.asarray(x, float), 0.0, None)
        floor = 2.0**EV_MIN
        e = np.log2(np.maximum(x, floor))
        y = self.fitted(e)
        toe = x < floor
        y = np.where(toe, x / floor * self.tone[0], y)
        sh = (e > self.knee_ev) & ~toe
        u = np.clip((e - self.knee_ev) / (self.clip_ev - self.knee_ev), 0.0, 1.0)
        y = np.where(sh, self.y_knee + (1.0 - self.y_knee) * shoulder(u, self.k), y)
        return np.clip(y, 0.0, 1.0)

    def table(self, points: int = CURVE_POINTS) -> np.ndarray:
        """Samples on the log2 grid EV_MIN .. clip_ev handed to the native code."""
        ev = np.linspace(EV_MIN, self.clip_ev, points)
        return self(2.0**ev)


def shoulder(u: np.ndarray, k: float) -> np.ndarray:
    if abs(k) < 1e-9:
        return u
    return (1.0 - np.exp(-k * u)) / (1.0 - math.exp(-k))


def _slope0(k: float) -> float:
    return 1.0 if abs(k) < 1e-9 else k / (1.0 - math.exp(-k))


def solve_shoulder_k(m: float) -> float:
    """k with shoulder'(0) = m (bisection; slope0 is increasing in k)."""
    lo, hi = -50.0, 50.0
    m = min(max(m, _slope0(lo)), _slope0(hi))
    for _ in range(200):
        mid = 0.5 * (lo + hi)
        if _slope0(mid) < m:
            lo = mid
        else:
            hi = mid
    return 0.5 * (lo + hi)


def invert_fitted(tone: np.ndarray, y: float) -> float:
    """EV where the fitted tone reaches ``y`` (monotone, below the knee)."""
    t = np.maximum.accumulate(np.asarray(tone, float))
    grid = np.linspace(EV_MIN, EV_MAX, len(t))
    return float(np.interp(y, t + np.arange(len(t)) * 1e-9, grid))


def solve_exposure(tone: np.ndarray, median_y: float, target_encoded: float | None) -> tuple[float, str]:
    """Exposure gain (log2) that puts the RAW frame's median luminance where the camera JPEG's median sits."""
    method = "camera_jpeg_median"
    if target_encoded is None or not math.isfinite(target_encoded) or target_encoded <= 0:
        target_encoded, method = DEFAULT_TARGET, "default_target"
    y_knee = float(np.interp(KNEE_EV, np.linspace(EV_MIN, EV_MAX, len(tone)), tone))
    t = min(float(target_encoded), y_knee - 1e-3)
    if not (median_y > 1e-6 and math.isfinite(median_y)):
        return 0.0, "no_signal"
    ev = invert_fitted(tone, t) - math.log2(median_y)
    return float(min(max(ev, EXPOSURE_MIN_EV), EXPOSURE_MAX_EV)), method


def jpeg_median_encoded(rgb_encoded: np.ndarray) -> float:
    """Median luma of an sRGB-encoded image, encoded (what Kotlin computes from the decoded camera JPEG)."""
    y = srgb_decode(np.asarray(rgb_encoded, float).reshape(-1, 3)) @ LUMA
    return float(srgb_encode(np.median(y)))


# ----------------------------------------------------------------------------------------------- per-pixel work


@dataclass
class RawFrame:
    raw: np.ndarray                 # (H, W) uint16 Bayer
    cfa: int                        # Camera2 colour filter arrangement 0..3
    black: tuple[float, float, float, float]  # per CFA position
    white: float
    shading: np.ndarray | None = None  # (rows, cols, 4) Camera2 order R, G_even, G_odd, B


def crop_32(width: int, height: int, zoom: float = 1.0) -> tuple[int, int, int, int]:
    """Centred 3:2 window of the zoomed field of view, left/top even (same as Kotlin RawCrop)."""
    zw, zh = width / max(zoom, 1.0), height / max(zoom, 1.0)
    if zw >= zh:
        cw, ch = zw, zw * 2.0 / 3.0
        if ch > zh:
            ch, cw = zh, zh * 1.5
    else:
        ch, cw = zh, zh * 2.0 / 3.0
        if cw > zw:
            cw, ch = zw, zw * 1.5
    cw, ch = int(round(cw)) // 2 * 2, int(round(ch)) // 2 * 2
    left = ((width - cw) // 2) // 2 * 2
    top = ((height - ch) // 2) // 2 * 2
    return left, top, cw, ch


def shading_gain_image(frame: RawFrame) -> np.ndarray:
    h, w = frame.raw.shape
    if frame.shading is None:
        return np.ones((h, w))
    sm = np.asarray(frame.shading, float)
    rows, cols = sm.shape[:2]
    fy = np.arange(h) * (rows - 1) / max(h - 1, 1)
    fx = np.arange(w) * (cols - 1) / max(w - 1, 1)
    y0 = np.minimum(np.floor(fy).astype(int), rows - 2) if rows > 1 else np.zeros(h, int)
    x0 = np.minimum(np.floor(fx).astype(int), cols - 2) if cols > 1 else np.zeros(w, int)
    ty, tx = fy - y0, fx - x0
    y1, x1 = np.minimum(y0 + 1, rows - 1), np.minimum(x0 + 1, cols - 1)
    layout = CFA_LAYOUTS[frame.cfa]
    out = np.empty((h, w))
    for p in range(4):
        py, px = p >> 1, p & 1
        colour = layout[p]
        ch = 0 if colour == 0 else 3 if colour == 2 else (1 if py == 0 else 2)
        g = sm[..., ch]
        yy, xx = slice(py, None, 2), slice(px, None, 2)
        a, b, ty_, tx_ = y0[yy][:, None], x0[xx][None, :], ty[yy][:, None], tx[xx][None, :]
        c, d = y1[yy][:, None], x1[xx][None, :]
        out[yy, xx] = g[a, b] * (1 - ty_) * (1 - tx_) + g[a, d] * (1 - ty_) * tx_ + g[c, b] * ty_ * (1 - tx_) + g[c, d] * ty_ * tx_
    return out


def normalise(frame: RawFrame) -> np.ndarray:
    raw = frame.raw.astype(float)
    h, w = raw.shape
    black = np.empty((h, w))
    for p in range(4):
        black[p >> 1 :: 2, p & 1 :: 2] = frame.black[p]
    return (raw - black) / (frame.white - black) * shading_gain_image(frame)


_K = 1.0 / 8.0
K_G = _K * np.array([[0, 0, -1, 0, 0], [0, 0, 2, 0, 0], [-1, 2, 4, 2, -1], [0, 0, 2, 0, 0], [0, 0, -1, 0, 0]], float)
K_ROW = _K * np.array([[0, 0, 0.5, 0, 0], [0, -1, 0, -1, 0], [-1, 4, 5, 4, -1], [0, -1, 0, -1, 0], [0, 0, 0.5, 0, 0]], float)
K_COL = K_ROW.T.copy()
K_DIAG = _K * np.array([[0, 0, -1.5, 0, 0], [0, 2, 0, 2, 0], [-1.5, 0, 6, 0, -1.5], [0, 2, 0, 2, 0], [0, 0, -1.5, 0, 0]], float)


def demosaic_mhc(v: np.ndarray, cfa: int) -> np.ndarray:
    """Malvar-He-Cutler (2004) on a normalised mosaic; mirror borders. Returns (H, W, 3)."""
    conv = {name: ndimage.correlate(v, k, mode="mirror") for name, k in (("g", K_G), ("row", K_ROW), ("col", K_COL), ("diag", K_DIAG))}
    layout = CFA_LAYOUTS[cfa]
    out = np.empty(v.shape + (3,))
    for p in range(4):
        py, px = p >> 1, p & 1
        sl = (slice(py, None, 2), slice(px, None, 2))
        colour = layout[p]
        out[sl + (colour,)] = v[sl]
        if colour == 1:
            row_colour = layout[(py << 1) | (1 - px)]  # horizontal neighbour
            col_colour = layout[((1 - py) << 1) | px]  # vertical neighbour
            out[sl + (row_colour,)] = conv["row"][sl]
            out[sl + (col_colour,)] = conv["col"][sl]
        else:
            out[sl + (1,)] = conv["g"][sl]
            out[sl + (2 - colour,)] = conv["diag"][sl]
    return out


def area_weights(n_in: int, n_out: int) -> np.ndarray:
    """(n_out, n_in) box-filter weights: each output sample averages the input interval it covers."""
    s = n_in / n_out
    w = np.zeros((n_out, n_in))
    for i in range(n_out):
        a, b = i * s, (i + 1) * s
        j0, j1 = int(math.floor(a)), min(int(math.ceil(b)), n_in)
        for j in range(j0, j1):
            w[i, j] = max(0.0, min(j + 1.0, b) - max(float(j), a)) / s
    return w


@dataclass
class RenderParams:
    crop: tuple[int, int, int, int]   # left, top, width, height
    out_size: tuple[int, int]         # width, height
    matrix: np.ndarray                # 3x3 balanced camera -> linear sRGB
    neutral: np.ndarray               # 3
    curve: ToneCurve
    lut: np.ndarray                   # (n, n, n, 3) [r][g][b]


def balanced_linear(frame: RawFrame, crop, neutral, matrix) -> np.ndarray:
    """Stages 1-5 on the crop: linear sRGB (H, W, 3)."""
    rgb = demosaic_mhc(normalise(frame), frame.cfa)
    l, t, w, h = crop
    rgb = rgb[t : t + h, l : l + w]
    c = np.clip(rgb / np.asarray(neutral, float), 0.0, 1.0)
    return c @ np.asarray(matrix, float).T


def render(frame: RawFrame, p: RenderParams) -> np.ndarray:
    """Full pipeline; returns sRGB-encoded float (out_h, out_w, 3) in [0, 1]."""
    lin = balanced_linear(frame, p.crop, p.neutral, p.matrix)
    ow, oh = p.out_size
    if (ow, oh) != (lin.shape[1], lin.shape[0]):
        ay, ax = area_weights(lin.shape[0], oh), area_weights(lin.shape[1], ow)
        lin = np.einsum("ij,jkc->ikc", ay, lin)
        lin = np.einsum("kj,ijc->ikc", ax, lin)
    y = p.curve(p.curve.gain * lin)
    return np.clip(apply_lut(y, p.lut), 0.0, 1.0)


def raw_stats(frame: RawFrame, crop, neutral, matrix, step: int = STATS_STEP) -> dict:
    """Exposure statistics from CFA cells (no demosaic): median linear Y, clipped fraction."""
    v = normalise(frame)
    l, t, w, h = crop
    layout = CFA_LAYOUTS[frame.cfa]
    ys = np.arange(t, t + h - 1, 2 * step)
    xs = np.arange(l, l + w - 1, 2 * step)
    cells = np.zeros((len(ys), len(xs), 3))
    count = np.zeros(3)
    for p in range(4):
        colour = layout[p]
        cells[..., colour] += v[np.ix_(ys + (p >> 1), xs + (p & 1))]
        count[colour] += 1
    cells /= count
    c = cells / np.asarray(neutral, float)
    clipped = float(np.mean(np.any(c >= 1.0, axis=-1)))
    lin = np.clip(c, 0.0, 1.0) @ np.asarray(matrix, float).T
    yv = (lin @ LUMA).reshape(-1)
    return {"median_y": float(np.median(yv)), "clipped_fraction": clipped, "cells": int(yv.size)}


# ----------------------------------------------------------------------------------------------- assets and checks


def load_tone(path: str) -> np.ndarray:
    with open(path) as f:
        meta = json.load(f)
    if not meta.get("model", "").startswith("xyz_d65 -> srgb_linear -> tone(log2) -> lut3d(srgb_encoded)"):
        raise ValueError("unexpected look model")
    tone = meta["tone"]
    if (tone["ev_min"], tone["ev_max"]) != (EV_MIN, EV_MAX):
        raise ValueError("tone grid differs from redline_cal.look")
    return np.asarray(tone["values"], float)


def load_lut(path: str) -> np.ndarray:
    return read_cube(path)


def parity(reference: np.ndarray, candidate: np.ndarray) -> dict:
    """dE2000 between two sRGB-encoded renders of the same frame."""
    a = srgb_encoded_to_lab(np.asarray(reference, float).reshape(-1, 3))
    b = srgb_encoded_to_lab(np.asarray(candidate, float).reshape(-1, 3))
    de = delta_e2000(a, b)
    return {"mean_de2000": float(de.mean()), "p99_de2000": float(np.percentile(de, 99)), "max_de2000": float(de.max())}


def neutral_chroma(rgb_encoded: np.ndarray) -> np.ndarray:
    """CIELAB chroma C*ab of sRGB-encoded colours (0 = perfectly neutral)."""
    lab = srgb_encoded_to_lab(np.asarray(rgb_encoded, float).reshape(-1, 3))
    return np.hypot(lab[:, 1], lab[:, 2])
