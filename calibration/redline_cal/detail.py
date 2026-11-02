"""B24 detail stage: the reference (Python) implementation of vignetting, distortion, sharpening and grain.

This module is the contract for the native code (``app/src/main/cpp/detail.cpp`` and the vignette step in
``raw_pipeline.cpp``) and for the app asset ``app/src/main/assets/colour/detail.json``.

Order in the still pipeline (docs/PLAN.md section 6, steps 7-8):

* vignette    in linear light, after the matrix (and the area downscale), before the tone curve:
              lin *= g(r), r = |p - centre| / half-diagonal of the output frame (pixel centres), g from a table of
              ``VIGNETTE_POINTS`` gains on r = 0..1 (linear interpolation, clamped). The table is the in-camera
              residual of the RF 24-70 f/2.8L's measured falloff (lensfun pa model, EV-interpolated in focal and
              log2 N) times ``alpha`` (fitted on R5 II RAW + camera JPEG pairs: the JPEG keeps 0.55 of the lens's
              falloff in EV with Peripheral Illumination Correction on).
* then, on the final 8-bit sRGB bitmap (after the 50/70 upscale):
  1. distortion (off by default, "RAW feel"): lensfun ptlens forward model, r = 1 at half the short side.
     Each output pixel is inverse-mapped: r_u = f^-1(r_d) (table over r_d), source = centre + (p - centre) *
     r_u / r_d * s, with s = min(1, 1 / max over the border of r_u / r_d) so no empty corners appear; bilinear,
     clamped at the edges.
  2. sharpen: luma unsharp mask with soft coring. Y = 0.2126 R + 0.7152 G + 0.0722 B (encoded values),
     d = Y - G_sigma * Y (separable Gaussian, radius int(3 sigma + 0.5), clamped edges),
     d' = d * d^2 / (d^2 + t^2), every channel + amount * d', clipped. Fitted on matched-scale R5 II renders.
  3. grain: the camera JPEG's noise level at the shot's ISO and brightness (fitted, ``grain`` in the asset) minus
     the noise already in the image (same estimator), added as luma noise:
     sigma_add(Y) = sqrt(max(0, target(ISO, Y)^2 - measured(Y)^2)), interpolated over the luma bins;
     n(x, y) = a deterministic hash-based Irwin-Hall(4) normal deviate (so the native output matches), filtered by
     [w, 1 - 2w, w] in x and y and divided by kappa (its Immerkaer sigma), so the grain has the camera JPEG's
     spatial correlation (coarse/fine estimator ratio) and exactly the Immerkaer sigma asked for.

Each stage of the post-pass writes 8-bit values on the phone; ``apply_detail(..., quantise=True)`` does the same.
Only Picture Style Standard is supported: every R5 II pair in the dataset is Standard (sharpness 4, fineness 2,
threshold 3); other styles need R5 II CR3 + JPEG pairs shot in that style.
"""
from __future__ import annotations

import json
import math
from dataclasses import dataclass

import numpy as np

LUMA_ENC = np.array([0.2126, 0.7152, 0.0722])
VIGNETTE_POINTS = 65
DISTORTION_TABLE = 2048
NOISE_BLOCK = 16
NOISE_BLOCK_STEP = 2      # every 2nd block in each direction (a quarter of the frame)
NOISE_QUANTILE = 0.10     # flattest decile of the blocks in a luma bin
NOISE_MIN_BLOCKS = 8
# Encoded-luma bins of the noise estimate and the grain target (edges; the last bin runs to 0.92, as in the fit).
NOISE_BINS = (0.0, 0.1, 0.2, 0.35, 0.5, 0.65, 0.8, 0.92)
IRWIN_HALL_SCALE = 1.0 / math.sqrt(4 * (256.0**2 - 1) / 12.0)  # sum of 4 uniform bytes -> unit variance


# ---------------------------------------------------------------------------------------------- vignetting


def lensfun_ev(profile, focal: float, f_number: float, r: np.ndarray) -> np.ndarray:
    """log2 V(r) of the lensfun pa model, interpolated in EV: in log2 N per measured focal (farthest distance), then
    linearly in focal. r = 1 at the image corner. Same interpolation the B24 alpha fit used."""
    r = np.asarray(r, float)
    focals = sorted({v.focal for v in profile.vignetting})

    def at_focal(f):
        rows = {}
        for v in profile.vignetting:
            if v.focal == f and (v.aperture not in rows or v.distance > rows[v.aperture].distance):
                rows[v.aperture] = v
        aps = sorted(rows)

        def ev(v):
            return np.log2(np.clip(1 + v.k1 * r**2 + v.k2 * r**4 + v.k3 * r**6, 1e-3, None))

        s = math.log2(f_number)
        if s <= math.log2(aps[0]):
            return ev(rows[aps[0]])
        for lo, hi in zip(aps, aps[1:]):
            a, b = math.log2(lo), math.log2(hi)
            if a <= s <= b:
                t = (s - a) / (b - a)
                return ev(rows[lo]) * (1 - t) + ev(rows[hi]) * t
        return ev(rows[aps[-1]])

    if focal <= focals[0]:
        return at_focal(focals[0])
    if focal >= focals[-1]:
        return at_focal(focals[-1])
    for lo, hi in zip(focals, focals[1:]):
        if lo <= focal <= hi:
            t = (focal - lo) / (hi - lo)
            return at_focal(lo) * (1 - t) + at_focal(hi) * t
    raise AssertionError


def vignette_table(profile, focal: float, f_number: float, alpha: float, points: int = VIGNETTE_POINTS) -> np.ndarray:
    """Residual gain at r = 0, 1/(points-1), ..., 1: 2^(alpha * lensfun EV)."""
    r = np.linspace(0.0, 1.0, points)
    return np.exp2(alpha * lensfun_ev(profile, focal, f_number, r))


def table_lookup(table: np.ndarray, r: np.ndarray) -> np.ndarray:
    table = np.asarray(table, float)
    f = np.clip(np.asarray(r, float), 0.0, 1.0) * (len(table) - 1)
    i = np.minimum(np.floor(f).astype(int), len(table) - 2)
    u = f - i
    return table[i] + (table[i + 1] - table[i]) * u


def radius_map(width: int, height: int) -> np.ndarray:
    """r per output pixel centre, 1 at the corner (half-diagonal)."""
    y = (np.arange(height) + 0.5 - height / 2.0)[:, None]
    x = (np.arange(width) + 0.5 - width / 2.0)[None, :]
    return np.sqrt(x * x + y * y) / math.hypot(width / 2.0, height / 2.0)


def vignette_gain(width: int, height: int, table) -> np.ndarray:
    return table_lookup(table, radius_map(width, height))


# ---------------------------------------------------------------------------------------------- distortion


def ptlens(r_u, a: float, b: float, c: float):
    return r_u * (a * r_u**3 + b * r_u**2 + c * r_u + 1.0 - a - b - c)


def undistort_radius(r_d: np.ndarray, a: float, b: float, c: float, iters: int = 30) -> np.ndarray:
    """f^-1 by Newton from r_u = r_d."""
    r_d = np.asarray(r_d, float)
    r = r_d.copy()
    d = 1.0 - a - b - c
    for _ in range(iters):
        f = r * (a * r**3 + b * r**2 + c * r + d) - r_d
        fp = 4 * a * r**3 + 3 * b * r**2 + 2 * c * r + d
        r = r - f / fp
    return r


def distortion_ratio_table(width: int, height: int, a: float, b: float, c: float, n: int = DISTORTION_TABLE):
    """(r_max, ratio[n]): r_u / r_d at r_d = r_max * i / (n - 1); r in half-short-side units; ratio(0) = 1 / d."""
    half = min(width, height) / 2.0
    r_max = math.hypot(width / 2.0, height / 2.0) / half
    rd = np.linspace(0.0, r_max, n)
    ru = undistort_radius(rd, a, b, c)
    ratio = np.empty(n)
    ratio[1:] = ru[1:] / rd[1:]
    ratio[0] = 1.0 / (1.0 - a - b - c)
    return r_max, ratio


def distortion_fill_scale(width: int, height: int, r_max: float, ratio: np.ndarray, samples: int = 1024) -> float:
    """s = min(1, 1 / max ratio over the frame border): the source stays inside the frame everywhere."""
    half = min(width, height) / 2.0
    t = np.linspace(0.0, 1.0, samples)
    xs = np.concatenate([t * width / 2, np.full(samples, width / 2)]) / half
    ys = np.concatenate([np.full(samples, height / 2), t * height / 2]) / half
    rd = np.sqrt(xs * xs + ys * ys)
    k = table_lookup(ratio, rd / r_max).max()
    return float(min(1.0, 1.0 / k))


def bilinear(img: np.ndarray, sx: np.ndarray, sy: np.ndarray) -> np.ndarray:
    h, w = img.shape[:2]
    sx = np.clip(sx, 0.0, w - 1.0)
    sy = np.clip(sy, 0.0, h - 1.0)
    x0 = np.minimum(np.floor(sx).astype(int), w - 2)
    y0 = np.minimum(np.floor(sy).astype(int), h - 2)
    fx = (sx - x0)[..., None]
    fy = (sy - y0)[..., None]
    p00, p01 = img[y0, x0], img[y0, x0 + 1]
    p10, p11 = img[y0 + 1, x0], img[y0 + 1, x0 + 1]
    return (p00 * (1 - fx) + p01 * fx) * (1 - fy) + (p10 * (1 - fx) + p11 * fx) * fy


def distort(img: np.ndarray, a: float, b: float, c: float) -> np.ndarray:
    """RAW-feel distortion of an (h, w, 3) image (pixel-centre coordinates)."""
    h, w = img.shape[:2]
    half = min(w, h) / 2.0
    r_max, ratio = distortion_ratio_table(w, h, a, b, c)
    s = distortion_fill_scale(w, h, r_max, ratio)
    yy = (np.arange(h) + 0.5 - h / 2.0)[:, None] * np.ones((1, w))
    xx = (np.arange(w) + 0.5 - w / 2.0)[None, :] * np.ones((h, 1))
    rd = np.sqrt(xx * xx + yy * yy) / half
    k = table_lookup(ratio, rd / r_max) * s
    return bilinear(np.asarray(img, float), xx * k + w / 2.0 - 0.5, yy * k + h / 2.0 - 0.5)


def distortion_percent_edge(a: float, b: float, c: float, r: float = 1.5) -> float:
    return (ptlens(r, a, b, c) / r - 1.0) * 100.0


# ---------------------------------------------------------------------------------------------- sharpening


def gaussian_kernel(sigma: float) -> np.ndarray:
    rad = int(3.0 * sigma + 0.5)
    k = np.exp(-0.5 * (np.arange(-rad, rad + 1) / sigma) ** 2)
    return k / k.sum()


def blur(y: np.ndarray, sigma: float) -> np.ndarray:
    k = gaussian_kernel(sigma)
    rad = len(k) // 2
    p = np.pad(y, ((0, 0), (rad, rad)), mode="edge")
    t = sum(k[i] * p[:, i : i + y.shape[1]] for i in range(len(k)))
    p = np.pad(t, ((rad, rad), (0, 0)), mode="edge")
    return sum(k[i] * p[i : i + y.shape[0], :] for i in range(len(k)))


def coring(d: np.ndarray, threshold: float) -> np.ndarray:
    if threshold <= 0:
        return d
    d2 = d * d
    return d * d2 / (d2 + threshold * threshold)


def sharpen(img: np.ndarray, sigma: float, amount: float, threshold: float) -> np.ndarray:
    """Luma USM on encoded (h, w, 3) values in 0..1; threshold in the same units."""
    img = np.asarray(img, float)
    y = img @ LUMA_ENC
    d = coring(y - blur(y, sigma), threshold)
    return np.clip(img + amount * d[..., None], 0.0, 1.0)


# ---------------------------------------------------------------------------------------------- noise and grain

LAPLACE_DIFF = np.array([[1, -2, 1], [-2, 4, -2], [1, -2, 1]], float)


def order_quantile(v: np.ndarray, q: float) -> float:
    v = np.sort(np.asarray(v, float).reshape(-1))
    return float(v[int(math.floor(q * (len(v) - 1)))])


def block_noise(y: np.ndarray, block: int = NOISE_BLOCK, step: int = NOISE_BLOCK_STEP):
    """Immerkaer per block (every ``step``-th block from (0, 0); pixels on the frame border skipped):
    sigma = sqrt(pi / 2) / 6 * mean |L * y| over the block's interior-valid pixels, and the block's mean luma."""
    y = np.asarray(y, float)
    h, w = y.shape
    lap = np.zeros_like(y)
    c = y[1:-1, 1:-1]
    lap[1:-1, 1:-1] = np.abs(
        y[:-2, :-2] + y[:-2, 2:] + y[2:, :-2] + y[2:, 2:] - 2 * (y[:-2, 1:-1] + y[2:, 1:-1] + y[1:-1, :-2] + y[1:-1, 2:]) + 4 * c)
    sig, mu = [], []
    for by in range(0, h // block, step):
        for bx in range(0, w // block, step):
            y0, x0 = by * block, bx * block
            ya, yb = max(y0, 1), min(y0 + block, h - 1)
            xa, xb = max(x0, 1), min(x0 + block, w - 1)
            sig.append(math.sqrt(math.pi / 2) / 6 * lap[ya:yb, xa:xb].mean())
            mu.append(y[y0 : y0 + block, x0 : x0 + block].mean())
    return np.array(sig), np.array(mu)


def noise_by_bin(y: np.ndarray, bins=NOISE_BINS, block: int = NOISE_BLOCK, step: int = NOISE_BLOCK_STEP) -> list:
    """Per luma bin: the NOISE_QUANTILE order statistic of the block sigmas (None with < NOISE_MIN_BLOCKS blocks)."""
    s, mu = block_noise(y, block, step)
    out = []
    for lo, hi in zip(bins[:-1], bins[1:]):
        m = (mu >= lo) & (mu < hi)
        out.append(order_quantile(s[m], NOISE_QUANTILE) if m.sum() >= NOISE_MIN_BLOCKS else None)
    return out


def bin_centres(bins=NOISE_BINS) -> np.ndarray:
    b = np.asarray(bins, float)
    return 0.5 * (b[:-1] + b[1:])


@dataclass
class GrainScale:
    """Camera-JPEG noise at one output width: ln sigma = c0 + c1 s + c2 s^2 + ln shape[bin], s = log2(ISO / 100)
    (ISO clamped to the fitted range); grain spectrum: unit hash noise filtered by [w, 1 - 2w, w] in x and y
    (clamped edges), which reproduces the JPEG noise's coarse/fine Immerkaer ratio; ``kappa`` is the Immerkaer
    sigma of that filtered unit field, so sigma / kappa scales it to an Immerkaer target."""
    c: list
    shape: list
    kernel_w: float
    kappa: float


@dataclass
class GrainModel:
    iso_min: float
    iso_max: float
    widths: dict  # output width (str) -> GrainScale

    def scale(self, width: int) -> GrainScale:
        return self.widths[min(self.widths, key=lambda k: abs(int(k) - width))]

    def target(self, iso: float, width: int) -> np.ndarray:
        g = self.scale(width)
        s = math.log2(min(max(float(iso), self.iso_min), self.iso_max) / 100.0)
        return math.exp(g.c[0] + g.c[1] * s + g.c[2] * s * s) * np.asarray(g.shape, float)


def grain_add_sigma(target: np.ndarray, measured: list) -> np.ndarray:
    """Per bin: sqrt(max(0, target^2 - measured^2)); a bin without a measurement adds nothing (no evidence)."""
    out = np.zeros(len(target))
    for i, (t, m) in enumerate(zip(target, measured)):
        if m is not None:
            out[i] = math.sqrt(max(0.0, t * t - m * m))
    return out


M32 = 0xFFFFFFFF


def hash32(x: np.ndarray, y: np.ndarray, seed: int) -> np.ndarray:
    """Counter hash (murmur3 finaliser over a mixed coordinate); uint32 arithmetic identical to the C++."""
    x = np.asarray(x, np.uint64)
    y = np.asarray(y, np.uint64)
    v = ((x * 0x8DA6B343) ^ (y * 0xD8163841) ^ np.uint64((seed * 0xCB1AB31F) & M32)) & M32
    v ^= v >> 16
    v = (v * 0x85EBCA6B) & M32
    v ^= v >> 13
    v = (v * 0xC2B2AE35) & M32
    v ^= v >> 16
    return v


def normal_field(width: int, height: int, seed: int) -> np.ndarray:
    yy, xx = np.mgrid[0:height, 0:width]
    h = hash32(xx, yy, seed)
    s = (h & 0xFF) + ((h >> 8) & 0xFF) + ((h >> 16) & 0xFF) + ((h >> 24) & 0xFF)
    return (s.astype(float) - 510.0) * IRWIN_HALL_SCALE


def grain_field(width: int, height: int, seed: int, kernel_w: float, kappa: float) -> np.ndarray:
    """The filtered hash noise, scaled so its Immerkaer sigma is 1."""
    n = normal_field(width, height, seed)
    k = (kernel_w, 1.0 - 2.0 * kernel_w, kernel_w)
    p = np.pad(n, ((0, 0), (1, 1)), mode="edge")
    t = k[0] * p[:, :-2] + k[1] * p[:, 1:-1] + k[2] * p[:, 2:]
    p = np.pad(t, ((1, 1), (0, 0)), mode="edge")
    return (k[0] * p[:-2] + k[1] * p[1:-1] + k[2] * p[2:]) / kappa


def field_kappa(kernel_w: float, size: int = 1024, seed: int = 12345) -> float:
    """Immerkaer sigma of the filtered unit hash field (interior pixels), for the asset."""
    f = grain_field(size, size, seed, kernel_w, 1.0)
    c = f[1:-1, 1:-1]
    lap = np.abs(f[:-2, :-2] + f[:-2, 2:] + f[2:, :-2] + f[2:, 2:] - 2 * (f[:-2, 1:-1] + f[2:, 1:-1] + f[1:-1, :-2] + f[1:-1, 2:]) + 4 * c)
    return float(math.sqrt(math.pi / 2) / 6 * lap.mean())


def grain(img: np.ndarray, add_sigma: np.ndarray, seed: int, kernel_w: float, kappa: float, bins=NOISE_BINS) -> np.ndarray:
    img = np.asarray(img, float)
    h, w = img.shape[:2]
    y = img @ LUMA_ENC
    amp = np.interp(y, bin_centres(bins), add_sigma)
    return np.clip(img + (amp * grain_field(w, h, seed, kernel_w, kappa))[..., None], 0.0, 1.0)


# ---------------------------------------------------------------------------------------------- asset


@dataclass
class DetailAsset:
    picture_style: str
    vignette_alpha: float
    vignette_f_number: float
    vignette: dict          # stop mm (str) -> gain table
    distortion: dict        # stop mm (str) -> (a, b, c)
    usm: dict               # output width (str) -> {sigma, amount, threshold}
    grain: GrainModel | None
    raw: dict

    def usm_for(self, width: int) -> dict:
        key = min(self.usm, key=lambda k: abs(int(k) - width))
        return self.usm[key]

    def stop_key(self, mm: float, table: dict) -> str:
        return min(table, key=lambda k: abs(float(k) - mm))


def load_asset(path: str) -> DetailAsset:
    with open(path) as f:
        d = json.load(f)
    if d.get("model") != "redline_detail v1":
        raise ValueError("unexpected detail asset")
    g = d.get("grain")
    gm = GrainModel(g["iso_min"], g["iso_max"], {k: GrainScale(v["c"], v["shape"], v["kernel_w"], v["kappa"])
                                                   for k, v in g["widths"].items()}) if g else None
    return DetailAsset(d["picture_style"], d["vignette"]["alpha"], d["vignette"]["f_number"],
                       {k: np.array(v) for k, v in d["vignette"]["stops"].items()},
                       {k: tuple(v) for k, v in d["distortion"]["stops"].items()},
                       d["sharpen"]["widths"], gm, d)


def quantise8(img: np.ndarray) -> np.ndarray:
    return np.floor(np.clip(img, 0.0, 1.0) * 255.0 + 0.5) / 255.0


def apply_detail(img: np.ndarray, asset: DetailAsset, *, iso: float | None, stop_mm: float, seed: int,
                 sharpen_on: bool = True, grain_on: bool = True, distortion_on: bool = False,
                 quantise: bool = False) -> tuple[np.ndarray, dict]:
    """The post-pass on an encoded (h, w, 3) image in 0..1 (the app passes its 8-bit bitmap / 255).
    ``quantise`` rounds to 8 bits after each stage, as the native RGBA8 path does."""
    q = quantise8 if quantise else (lambda x: x)
    out = np.asarray(img, float)
    info: dict = {}
    h, w = out.shape[:2]
    if distortion_on:
        a, b, c = asset.distortion[asset.stop_key(stop_mm, asset.distortion)]
        out = q(distort(out, a, b, c))
    if sharpen_on:
        p = asset.usm_for(w)
        out = q(sharpen(out, p["sigma"], p["amount"], p["threshold"]))
    if grain_on and asset.grain is not None and iso is not None:
        target = asset.grain.target(iso, w)
        measured = noise_by_bin(out @ LUMA_ENC)
        add = grain_add_sigma(target, measured)
        info.update(grain_target=target.tolist(), grain_measured=measured, grain_added=add.tolist())
        gs = asset.grain.scale(w)
        out = q(grain(out, add, seed, gs.kernel_w, gs.kappa))
    return out, info


# ---------------------------------------------------------------------------------------------- asset builder

STOPS_MM = (24, 28, 35, 50, 70)


def coarse_fine_ratio(kernel_w: float, size: int = 1024, seed: int = 777) -> float:
    """Immerkaer sigma of the 2x2-binned field over that of the field (white noise: 0.5)."""
    f = grain_field(size, size, seed, kernel_w, 1.0)
    def imm(y):
        c = y[1:-1, 1:-1]
        lap = np.abs(y[:-2, :-2] + y[:-2, 2:] + y[2:, :-2] + y[2:, 2:] - 2 * (y[:-2, 1:-1] + y[2:, 1:-1] + y[1:-1, :-2] + y[1:-1, 2:]) + 4 * c)
        return lap.mean()
    b = f.reshape(size // 2, 2, size // 2, 2).mean(axis=(1, 3))
    return float(imm(b) / imm(f))


def kernel_for_ratio(ratio: float) -> float:
    from scipy import optimize

    if ratio <= coarse_fine_ratio(0.0):
        return 0.0
    return float(optimize.brentq(lambda w: coarse_fine_ratio(w) - ratio, 0.0, 0.2, xtol=1e-4))


def build_asset(profile, fit: dict, alpha: float, f_number: float = 2.8) -> dict:
    """``fit``: the B24 detail report (``mtf`` and ``grain`` sections). Vignette and distortion tables are adapted
    from the lensfun database (CC BY-SA 3.0), so the asset carries that attribution and licence."""
    vig = {str(m): [round(float(v), 6) for v in vignette_table(profile, m, f_number, alpha)] for m in STOPS_MM}
    dist = {str(m): [round(float(v), 7) for v in profile.distortion_at(m)] for m in STOPS_MM}
    usm = {w: {"sigma": fit["mtf"][w]["usm"]["sigma_px"], "amount": fit["mtf"][w]["usm"]["amount"],
               "threshold": round(fit["mtf"][w]["usm"]["threshold_255"] / 255.0, 6)} for w in ("4000", "3000")}
    widths = {}
    for w in ("4000", "3000"):
        g = fit["grain"][w]
        kw = kernel_for_ratio(g["coarse_fine_ratio_iso1600plus"])
        widths[w] = {"c": g["c"], "shape": g["shape"], "kernel_w": round(kw, 4), "kappa": round(field_kappa(round(kw, 4)), 5)}
    return {
        "model": "redline_detail v1",
        "picture_style": "Standard",
        "picture_styles_supported": ["Standard"],
        "picture_styles_note": "every R5 II RAW + JPEG pair in the dataset is Standard (sharpness 4, fineness 2, threshold 3); "
                               "Portrait, Landscape, Neutral, Faithful, Fine Detail and Monochrome need R5 II CR3s shot in that style",
        "vignette": {"alpha": alpha, "f_number": f_number, "points": VIGNETTE_POINTS, "r": "1 at the output corner",
                     "stops": vig,
                     "fit": "JPEG residual = alpha x lensfun EV (R5 II, Peripheral Illumination Correction on)"},
        "distortion": {"model": "ptlens", "r": "1 at half the short side", "stops": dist, "default": "off (RAW feel toggle)"},
        "sharpen": {"luma": True, "coring": "d * d^2 / (d^2 + t^2)", "widths": usm},
        "grain": {"iso_min": 100.0, "iso_max": 51200.0, "units": "encoded 0..1, Immerkaer sigma", "bins": list(NOISE_BINS),
                  "widths": widths},
        "sources": {"lensfun": f"lensfun database, '{profile.model}' (vignetting measured on EOS R6 Mark II, distortion on EOS RP); "
                               "CC BY-SA 3.0, https://lensfun.github.io; the vignette and distortion tables in this file are "
                               "an adaptation and are licensed CC BY-SA 3.0",
                    "r5ii": "Redline B24 fit on public EOS R5 Mark II RAW + camera JPEG pairs (no Canon files shipped)"},
    }


def main(argv: list[str] | None = None) -> int:
    import argparse

    from .lensfun_import import parse_lensfun

    ap = argparse.ArgumentParser(description="Write the B24 detail asset (colour/detail.json)")
    ap.add_argument("lensfun_xml")
    ap.add_argument("fit_report")
    ap.add_argument("--model", default="Canon RF 24-70mm F2.8L IS USM")
    ap.add_argument("--alpha", type=float, default=0.55)
    ap.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    with open(a.lensfun_xml) as f:
        prof = parse_lensfun(f.read(), a.model)
    with open(a.fit_report) as f:
        fit = json.load(f)
    asset = build_asset(prof, fit, a.alpha)
    with open(a.out, "w") as f:
        json.dump(asset, f, indent=1)
        f.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
