"""B30: depth post-processing reference (the contract for app/src/main/cpp/depth_post.cpp).

The app runs Depth Anything V2 Small (relative inverse depth, 420x280) and the MediaPipe selfie segmenter (person
probability, 256x144) on a downscaled copy of the rendered still. This module defines what happens around the models:

1. ``downscale_area``: the still (RGBA8, any size) to the model input (float RGB 0..1) by box averaging over each output
   pixel's integer source box [floor(x * sx), floor((x + 1) * sx)).
2. ``resize_bilinear``: the person mask to the depth grid (half-pixel centres, clamped edges).
3. ``analyse``: the depth confidence. A scalar 0..1 and a per-pixel map, from three cues:
   - range: (p98 - p2) / p98 of the inverse depth; a flat scene (a wall) has nothing to blur by depth;
   - edges: the share of strong depth edges (top 5 %, at least 0.02 per pixel on the normalised map) that have an image
     edge within one pixel (luma gradient above twice its median); depth edges that float free of image edges are where
     a monocular model hallucinates;
   - person: when the segmenter finds a person (1-90 % of the frame), the share of person pixels nearer than the median
     of an 8-pixel ring around them; a person behind their background means the depth is wrong exactly where bokeh
     matters most.
   confidence = smoothstep(0.15, 0.5, range) * (0.4 + 0.6 * edges) * person. ``usable`` = confidence >= 0.35: below
   that, B31 renders no bokeh (PLAN 7.4: "don't apply when depth confidence is low").
   Per pixel: min(1 - clamp(|grad depth| / 0.08), 1 - 4 m (1 - m)): low on depth edges and on an undecided mask.

Percentiles are nearest-rank on the sorted values: index floor(q * (n - 1) + 0.5).
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np

USABLE = 0.35
RANGE_LO, RANGE_HI = 0.15, 0.5
EDGE_Q = 0.95
EDGE_MIN = 0.02
EDGE_GRAD_LIMIT = 0.08
RING = 8


def pct(a: np.ndarray, q: float) -> float:
    s = np.sort(a.ravel())
    return float(s[int(np.floor(q * (s.size - 1) + 0.5))])


def smoothstep(a: float, b: float, x: float) -> float:
    t = min(max((x - a) / (b - a), 0.0), 1.0)
    return t * t * (3 - 2 * t)


def downscale_area(rgba: np.ndarray, out_w: int, out_h: int) -> np.ndarray:
    """rgba uint8 [H, W, 4] -> float32 RGB [out_h, out_w, 3] in 0..1."""
    h, w = rgba.shape[:2]
    out = np.zeros((out_h, out_w, 3), np.float64)
    xs = [int(np.floor(x * w / out_w)) for x in range(out_w + 1)]
    ys = [int(np.floor(y * h / out_h)) for y in range(out_h + 1)]
    f = rgba[..., :3].astype(np.float64)
    for oy in range(out_h):
        y0, y1 = ys[oy], max(ys[oy + 1], ys[oy] + 1)
        band = f[y0:y1]
        for ox in range(out_w):
            x0, x1 = xs[ox], max(xs[ox + 1], xs[ox] + 1)
            out[oy, ox] = band[:, x0:x1].mean(axis=(0, 1))
    return (out / 255.0).astype(np.float32)


def resize_bilinear(src: np.ndarray, out_w: int, out_h: int) -> np.ndarray:
    """float [h, w] -> [out_h, out_w], half-pixel centres, clamped edges."""
    h, w = src.shape
    fy = np.clip((np.arange(out_h) + 0.5) * h / out_h - 0.5, 0, h - 1)
    fx = np.clip((np.arange(out_w) + 0.5) * w / out_w - 0.5, 0, w - 1)
    y0 = np.minimum(np.floor(fy).astype(int), h - 1)
    x0 = np.minimum(np.floor(fx).astype(int), w - 1)
    y1 = np.minimum(y0 + 1, h - 1)
    x1 = np.minimum(x0 + 1, w - 1)
    wy = (fy - y0)[:, None]
    wx = (fx - x0)[None, :]
    a = src[y0][:, x0] * (1 - wx) + src[y0][:, x1] * wx
    b = src[y1][:, x0] * (1 - wx) + src[y1][:, x1] * wx
    return (a * (1 - wy) + b * wy).astype(np.float32)


def grad_mag(f: np.ndarray) -> np.ndarray:
    p = np.pad(f.astype(np.float64), 1, mode="edge")
    gx = (p[1:-1, 2:] - p[1:-1, :-2]) * 0.5
    gy = (p[2:, 1:-1] - p[:-2, 1:-1]) * 0.5
    return np.sqrt(gx * gx + gy * gy)


def max_filter(f: np.ndarray, r: int) -> np.ndarray:
    """(2r+1)^2 square maximum, clamped edges (separable)."""
    h, w = f.shape
    p = np.pad(f, ((0, 0), (r, r)), mode="edge")
    m = np.max(np.stack([p[:, i:i + w] for i in range(2 * r + 1)]), axis=0)
    p = np.pad(m, ((r, r), (0, 0)), mode="edge")
    return np.max(np.stack([p[i:i + h] for i in range(2 * r + 1)]), axis=0)


@dataclass
class Analysis:
    confidence: float
    usable: bool
    p2: float
    p98: float
    spread: float
    range_score: float
    edge_agree: float | None
    edge_score: float
    person_coverage: float
    person_nearer: float | None
    person_score: float
    conf_map: np.ndarray
    norm: np.ndarray


def analyse(depth: np.ndarray, mask: np.ndarray, rgb: np.ndarray) -> Analysis:
    """depth, mask [H, W] float; rgb [H, W, 3] in 0..1 (the model input)."""
    d = depth.astype(np.float64)
    n = d.size
    p2, p98 = pct(d, 0.02), pct(d, 0.98)
    spread = (p98 - p2) / max(p98, 1e-6)
    range_score = smoothstep(RANGE_LO, RANGE_HI, spread)
    dn = np.clip((d - p2) / max(p98 - p2, 1e-6), 0, 1)
    gd = grad_mag(dn)
    luma = rgb[..., 0] * 0.299 + rgb[..., 1] * 0.587 + rgb[..., 2] * 0.114
    gl = grad_mag(luma)
    strong = gd > max(EDGE_MIN, pct(gd, EDGE_Q))
    if strong.sum() < 0.002 * n:
        edge_agree, edge_score = None, 0.5
    else:
        tl = max(0.01, 2 * pct(gl, 0.5))
        near = max_filter(gl, 1) > tl
        edge_agree = float((strong & near).sum() / strong.sum())
        edge_score = min(max((edge_agree - 0.3) / 0.5, 0.0), 1.0)
    person = mask > 0.5
    coverage = float(person.mean())
    person_nearer, person_score = None, 1.0
    if 0.01 <= coverage <= 0.9:
        ring = (max_filter(person.astype(np.float64), RING) > 0.5) & ~person
        if ring.sum() >= 50:
            bg = pct(dn[ring], 0.5)
            person_nearer = float((dn[person] > bg).mean())
            person_score = min(max((person_nearer - 0.5) / 0.4, 0.0), 1.0)
    conf = range_score * (0.4 + 0.6 * edge_score) * person_score
    m = mask.astype(np.float64)
    cmap = np.minimum(1 - np.clip(gd / EDGE_GRAD_LIMIT, 0, 1), 1 - 4 * m * (1 - m))
    return Analysis(conf, conf >= USABLE, p2, p98, spread, range_score, edge_agree, edge_score, coverage, person_nearer,
                    person_score, cmap.astype(np.float32), dn.astype(np.float32))


# ---------------------------------------------------------------------------------------------- prep / finish (B30)

LETTERBOX = 0.5


@dataclass
class Canvas:
    canvas_w: int
    canvas_h: int
    ax: int
    ay: int
    aw: int
    ah: int
    rot: int


def canvas(width: int, height: int, rot: int, canvas_w: int = 420, canvas_h: int = 280) -> Canvas:
    rot %= 360
    uw, uh = (height, width) if rot in (90, 270) else (width, height)
    s = min(canvas_w / uw, canvas_h / uh)
    aw = max(1, min(canvas_w, int(np.floor(uw * s + 0.5))))
    ah = max(1, min(canvas_h, int(np.floor(uh * s + 0.5))))
    return Canvas(canvas_w, canvas_h, (canvas_w - aw) // 2, (canvas_h - ah) // 2, aw, ah, rot)


def upright(img: np.ndarray, rot: int) -> np.ndarray:
    """The still's pixels (sensor orientation) rotated clockwise by rot to upright."""
    return np.rot90(img, k=-(rot // 90) % 4).copy()


def prep(rgba: np.ndarray, rot: int, canvas_w: int = 420, canvas_h: int = 280, seg_w: int = 256, seg_h: int = 144):
    """rgba uint8 [H, W, 4] -> (canvas float [ch, cw, 3], seg input [seg_h, seg_w, 3], Canvas)."""
    c = canvas(rgba.shape[1], rgba.shape[0], rot, canvas_w, canvas_h)
    up = upright(rgba, c.rot)
    out = np.full((canvas_h, canvas_w, 3), LETTERBOX, np.float32)
    out[c.ay:c.ay + c.ah, c.ax:c.ax + c.aw] = downscale_area(up, c.aw, c.ah)
    seg = np.stack([resize_bilinear(out[..., k], seg_w, seg_h) for k in range(3)], -1)
    return out, seg, c


def finish(depth_canvas: np.ndarray, seg_out: np.ndarray, rgb_canvas: np.ndarray, c: Canvas):
    """-> (packed uint16 [outH, outW, 3] in the still's orientation, Analysis); no distortion warp here (detail.py's)."""
    mask = np.clip(resize_bilinear(seg_out, c.canvas_w, c.canvas_h), 0, 1)
    sl = (slice(c.ay, c.ay + c.ah), slice(c.ax, c.ax + c.aw))
    d, m, rgb = depth_canvas[sl], mask[sl], rgb_canvas[sl]
    a = analyse(d, m, rgb)
    k = (c.rot // 90) % 4  # back to the still's orientation: counter-clockwise by rot
    sd, sm, sc = (np.rot90(x, k=k) for x in (d, m, a.conf_map))
    dmin, dmax = float(d.min()), float(d.max())
    span = max(dmax - dmin, 1e-6)
    packed = np.stack([
        np.floor(np.clip((sd - dmin) / span, 0, 1) * 65535 + 0.5),
        np.floor(np.clip(sm, 0, 1) * 65535 + 0.5),
        np.floor(np.clip(sc, 0, 1) * 65535 + 0.5),
    ], -1).astype(np.uint16)
    return packed, a
