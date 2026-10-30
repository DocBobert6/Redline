"""B22 RAW burst: tile alignment + robust merge in the Bayer domain. The reference (Python) implementation.

This module is the contract. The app runs the same algorithm in C++ (``app/src/main/cpp/burst_merge.cpp``) and CI
checks it against this file through the host tool (``tests/test_burst_merge.py``): the alignment (reference frame,
every tile displacement) must match exactly, because it is integer maths; the merged RAW must match within float
rounding.

Input: N frames of RAW_SENSOR samples (uint16, H x W, both even, same exposure), the per-CFA-position black levels,
the white level, and the noise model ``var(x) = S * x + O`` for a sample at normalised signal x (Camera2
SENSOR_NOISE_PROFILE, averaged over the CFA channels).

1. grey      L0[y][x] = sum of the 2x2 Bayer quad at (2y, 2x) (integer). One grey pixel = one quad, so every
             displacement found on the grey image moves whole quads and keeps the CFA phase.
2. pyramid   L1 = floor(2x2 box sum / 4) of L0, then L2, L3 = floor(4x4 box sum / 16) of the level above
             (dimensions floor-divided). A level is kept only while both its dimensions are >= COARSE_TILE.
3. reference the sharpest of the first min(3, N) frames (HDR+ picks from the first few so the shutter moment holds):
             sharpness = sum of squared horizontal and vertical neighbour differences of L1; the earliest frame wins
             ties.
4. align     coarse to fine, per alternate frame. Coarse levels (L_top .. L1): non-overlapping COARSE_TILE tiles
             (origin 8j, 8i), each initialised from the coarser level's tile under its centre
             (index = centre // (8 * factor), clamped; displacement * factor) or, when cheaper at this level, from
             the horizontal or vertical coarse neighbour on the side of the centre (three candidates, HDR+; ties keep
             the earlier), then searched over +-COARSE_RADIUS in x and y minimising the L2 distance. Finest level (L0): FINE_TILE tiles at stride FINE_TILE / 2 with
             origins (8j - 8, 8i - 8), initialised from L1 the same way (candidates compared with the L1 distance), searched over +-FINE_RADIUS with the L1
             distance. Only tile pixels inside the image count; the alternate frame is sampled with clamped
             coordinates. Candidates are visited dy-major from -r to r, and only a strictly smaller cost replaces the
             current best, so ties keep the earliest candidate.
5. weights   per L0 tile: noise variance of a grey pixel at the tile's mean level, sigma2 = 4 * (S x + O) * range^2
             with x = (mean(L0 tile) / 4 - mean(black)) / range, range = white - mean(black); v2 = 2 * sigma2 is the
             expected squared difference of two aligned frames. D = mean squared grey difference after alignment.
             Tile weight wt = TILE_C / (TILE_C + max(0, D - v2) / v2) (1 for a clean match, falling as the residual
             exceeds noise: motion, misalignment). Per quad: wq = wt when dq^2 <= PIX_K^2 * v2, else
             wt * PIX_K^2 * v2 / dq^2 (pixel-level outlier rejection).
6. merge     each tile's quads are blended with the separable raised-cosine window
             win(i) = 0.5 - 0.5 cos(2 pi (i + 0.5) / 16), which sums to 1 over the two overlapping tiles, so every
             sample gets:  out = (ref + sum_frames sum_tiles win * wq * alt) / (1 + sum_frames sum_tiles win * wq),
             alt being the alternate frame's sample at the same CFA position in the displaced quad (clamped). The
             reference keeps weight 1 everywhere; nothing is resampled (integer quad displacements), so the result is
             still a RAW mosaic with the reference frame's black and white levels.

The merged mosaic goes into the B21 pipeline as the reference frame would (its exposure is re-solved from the
merged frame's median against the normally exposed camera JPEG, which restores the burst's under-exposure).
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np

FINE_TILE = 16
FINE_STRIDE = 8
COARSE_TILE = 8
COARSE_RADIUS = 4
FINE_RADIUS = 2  # 1 loses odd displacements: L1 cannot represent them, so the x2 step can be 2 off
LEVEL_FACTORS = (2, 4, 4)  # L0 -> L1 -> L2 -> L3
REFERENCE_CANDIDATES = 3
TILE_C = 2.0
PIX_K = 3.0


def grey(raw: np.ndarray) -> np.ndarray:
    r = raw.astype(np.int64)
    return r[0::2, 0::2] + r[0::2, 1::2] + r[1::2, 0::2] + r[1::2, 1::2]


def downsample(img: np.ndarray, f: int) -> np.ndarray:
    h, w = img.shape[0] // f, img.shape[1] // f
    s = img[: h * f, : w * f].reshape(h, f, w, f).sum(axis=(1, 3))
    return s // (f * f)


def pyramid(raw: np.ndarray) -> list[np.ndarray]:
    levels = [grey(raw)]
    for f in LEVEL_FACTORS:
        nxt = downsample(levels[-1], f)
        if nxt.shape[0] < COARSE_TILE or nxt.shape[1] < COARSE_TILE:
            break
        levels.append(nxt)
    return levels


def sharpness(levels: list[np.ndarray]) -> int:
    img = levels[1] if len(levels) > 1 else levels[0]
    dx = np.diff(img, axis=1)
    dy = np.diff(img, axis=0)
    return int((dx * dx).sum() + (dy * dy).sum())


def pick_reference(pyrs: list[list[np.ndarray]]) -> int:
    n = min(REFERENCE_CANDIDATES, len(pyrs))
    scores = [sharpness(pyrs[i]) for i in range(n)]
    best = 0
    for i in range(1, n):
        if scores[i] > scores[best]:
            best = i
    return best


@dataclass
class TileGrid:
    """Tile origins (top-left) at one level; tiles may stick out of the image (only inside pixels count)."""

    size: int
    oy: np.ndarray  # (nty,)
    ox: np.ndarray  # (ntx,)

    @property
    def cy(self) -> np.ndarray:
        return self.oy + self.size // 2

    @property
    def cx(self) -> np.ndarray:
        return self.ox + self.size // 2


def coarse_grid(h: int, w: int) -> TileGrid:
    nty, ntx = -(-h // COARSE_TILE), -(-w // COARSE_TILE)
    return TileGrid(COARSE_TILE, np.arange(nty) * COARSE_TILE, np.arange(ntx) * COARSE_TILE)


def fine_grid(h: int, w: int) -> TileGrid:
    nty, ntx = (h - 1) // FINE_STRIDE + 2, (w - 1) // FINE_STRIDE + 2
    return TileGrid(FINE_TILE, np.arange(nty) * FINE_STRIDE - FINE_STRIDE, np.arange(ntx) * FINE_STRIDE - FINE_STRIDE)


def _tile_coords(grid: TileGrid, h: int, w: int):
    """Per tile pixel coordinates (nty, ntx, T, T) and the inside-image mask."""
    t = np.arange(grid.size)
    yy = grid.oy[:, None, None, None] + t[None, None, :, None]
    xx = grid.ox[None, :, None, None] + t[None, None, None, :]
    yy, xx = np.broadcast_arrays(yy, xx)
    yy = np.broadcast_to(yy, (len(grid.oy), len(grid.ox), grid.size, grid.size))
    xx = np.broadcast_to(xx, (len(grid.oy), len(grid.ox), grid.size, grid.size))
    inside = (yy >= 0) & (yy < h) & (xx >= 0) & (xx < w)
    return yy, xx, inside


def _search(ref: np.ndarray, alt: np.ndarray, grid: TileGrid, init: np.ndarray, radius: int, l1: bool) -> np.ndarray:
    h, w = ref.shape
    yy, xx, inside = _tile_coords(grid, h, w)
    ryy, rxx = np.clip(yy, 0, h - 1), np.clip(xx, 0, w - 1)
    rv = np.where(inside, ref[ryy, rxx], 0)
    best = None
    best_d = init.copy()
    for dy in range(-radius, radius + 1):
        for dx in range(-radius, radius + 1):
            cy = init[..., 0] + dy
            cx = init[..., 1] + dx
            ay = np.clip(yy + cy[:, :, None, None], 0, h - 1)
            ax = np.clip(xx + cx[:, :, None, None], 0, w - 1)
            diff = np.where(inside, rv - alt[ay, ax], 0)
            cost = (np.abs(diff) if l1 else diff * diff).sum(axis=(2, 3))
            if best is None:
                best = cost
                best_d = np.stack([cy, cx], axis=-1)
            else:
                better = cost < best
                best = np.where(better, cost, best)
                best_d = np.where(better[..., None], np.stack([cy, cx], axis=-1), best_d)
    return best_d


def _init_candidates(coarse_disp: np.ndarray | None, coarse: TileGrid | None, fine: TileGrid, factor: int) -> list[np.ndarray]:
    """Up to three initial displacements per fine tile (nty, ntx, 2): the coarse tile under its centre, then the
    horizontal and the vertical coarse neighbour on the side of the centre (HDR+), each times ``factor``."""
    shape = (len(fine.oy), len(fine.ox), 2)
    if coarse_disp is None:
        return [np.zeros(shape, dtype=np.int64)]
    span = COARSE_TILE * factor
    ny, nx = len(coarse.oy), len(coarse.ox)
    iy = np.clip(fine.cy // span, 0, ny - 1)
    ix = np.clip(fine.cx // span, 0, nx - 1)
    iy2 = np.clip(np.where(fine.cy % span >= span // 2, iy + 1, iy - 1), 0, ny - 1)
    ix2 = np.clip(np.where(fine.cx % span >= span // 2, ix + 1, ix - 1), 0, nx - 1)
    return [
        coarse_disp[iy[:, None], ix[None, :]] * factor,
        coarse_disp[iy[:, None], ix2[None, :]] * factor,
        coarse_disp[iy2[:, None], ix[None, :]] * factor,
    ]


def _pick_initial(ref, alt, grid: TileGrid, cands: list[np.ndarray], l1: bool) -> np.ndarray:
    if len(cands) == 1:
        return cands[0]
    h, w = ref.shape
    yy, xx, inside = _tile_coords(grid, h, w)
    rv = np.where(inside, ref[np.clip(yy, 0, h - 1), np.clip(xx, 0, w - 1)], 0)
    best, best_d = None, None
    for c in cands:
        ay = np.clip(yy + c[..., 0][:, :, None, None], 0, h - 1)
        ax = np.clip(xx + c[..., 1][:, :, None, None], 0, w - 1)
        diff = np.where(inside, rv - alt[ay, ax], 0)
        cost = (np.abs(diff) if l1 else diff * diff).sum(axis=(2, 3))
        if best is None:
            best, best_d = cost, c
        else:
            better = cost < best
            best = np.where(better, cost, best)
            best_d = np.where(better[..., None], c, best_d)
    return best_d


def align(ref_pyr: list[np.ndarray], alt_pyr: list[np.ndarray]) -> np.ndarray:
    """Per L0 tile displacement (nty, ntx, 2) as (dy, dx) in grey pixels (= 2 RAW pixels)."""
    disp = None
    grid = None
    for lvl in range(len(ref_pyr) - 1, 0, -1):
        g = coarse_grid(*ref_pyr[lvl].shape)
        cands = _init_candidates(disp, grid, g, LEVEL_FACTORS[lvl])
        init = _pick_initial(ref_pyr[lvl], alt_pyr[lvl], g, cands, l1=False)
        disp = _search(ref_pyr[lvl], alt_pyr[lvl], g, init, COARSE_RADIUS, l1=False)
        grid = g
    g0 = fine_grid(*ref_pyr[0].shape)
    cands = _init_candidates(disp, grid, g0, LEVEL_FACTORS[0])
    init = _pick_initial(ref_pyr[0], alt_pyr[0], g0, cands, l1=True)
    return _search(ref_pyr[0], alt_pyr[0], g0, init, FINE_RADIUS, l1=True)


def window(n: int = FINE_TILE) -> np.ndarray:
    i = np.arange(n, dtype=np.float64)
    return 0.5 - 0.5 * np.cos(2.0 * np.pi * (i + 0.5) / n)


@dataclass
class FrameStats:
    index: int
    mean_disp_px: float
    max_disp_px: float
    mean_weight: float
    rejected_fraction: float


@dataclass
class MergeResult:
    merged: np.ndarray  # float64 H x W, RAW units (black included)
    reference: int
    displacements: dict = field(default_factory=dict)  # frame index -> (nty, ntx, 2)
    frames: list = field(default_factory=list)  # FrameStats per alternate frame


def merge(frames: list[np.ndarray], black, white: float, noise_s: float, noise_o: float, reference: int | None = None) -> MergeResult:
    if not frames:
        raise ValueError("no frames")
    h, w = frames[0].shape
    if h % 2 or w % 2:
        raise ValueError("frame dimensions must be even")
    pyrs = [pyramid(f) for f in frames]
    ref_i = pick_reference(pyrs) if reference is None else reference
    ref = frames[ref_i].astype(np.float64)
    ref0 = pyrs[ref_i][0]
    h0, w0 = ref0.shape
    g0 = fine_grid(h0, w0)
    yy, xx, inside = _tile_coords(g0, h0, w0)
    ryy, rxx = np.clip(yy, 0, h0 - 1), np.clip(xx, 0, w0 - 1)
    rv = np.where(inside, ref0[ryy, rxx], 0).astype(np.float64)
    count = inside.sum(axis=(2, 3)).astype(np.float64)
    mean_level = rv.sum(axis=(2, 3)) / count
    black_mean = float(np.mean(black))
    rng = float(white) - black_mean
    x = np.clip((mean_level / 4.0 - black_mean) / rng, 0.0, 1.0)
    sigma2 = 4.0 * (noise_s * x + noise_o) * rng * rng
    v2 = np.maximum(2.0 * sigma2, 1e-12)
    win = window()
    wtile = win[:, None] * win[None, :]  # (T, T)

    num = np.zeros((h, w), dtype=np.float64)
    den = np.zeros((h0, w0), dtype=np.float64)
    result = MergeResult(merged=ref, reference=ref_i)
    for i, f in enumerate(frames):
        if i == ref_i:
            continue
        d = align(pyrs[ref_i], pyrs[i])
        result.displacements[i] = d
        alt0 = pyrs[i][0]
        ay = np.clip(yy + d[..., 0][:, :, None, None], 0, h0 - 1)
        ax = np.clip(xx + d[..., 1][:, :, None, None], 0, w0 - 1)
        dq = np.where(inside, rv - alt0[ay, ax], 0).astype(np.float64)
        dd = (dq * dq).sum(axis=(2, 3)) / count
        wt = TILE_C / (TILE_C + np.maximum(0.0, dd - v2) / v2)
        lim = (PIX_K * PIX_K) * v2[:, :, None, None]
        dq2 = dq * dq
        wq = np.where(dq2 <= lim, 1.0, lim / np.maximum(dq2, 1e-300)) * wt[:, :, None, None]
        wgt = np.where(inside, wq * wtile[None, None], 0.0)
        # Scatter: quads (yy, xx) inside the image receive weight wgt and the displaced alt quad's samples.
        sel = inside
        qy, qx = yy[sel], xx[sel]
        sy, sx = ay[sel], ax[sel]
        ww = wgt[sel]
        np.add.at(den, (qy, qx), ww)
        f64 = f.astype(np.float64)
        for u in (0, 1):
            for v in (0, 1):
                np.add.at(num, (2 * qy + u, 2 * qx + v), ww * f64[2 * sy + u, 2 * sx + v])
        mag = np.abs(d).max(axis=-1) * 2.0
        result.frames.append(
            FrameStats(i, float(mag.mean()), float(mag.max()), float(wt.mean()), float((wt < 0.5).mean()))
        )
    den_full = np.repeat(np.repeat(den, 2, axis=0), 2, axis=1)
    result.merged = (ref + num) / (1.0 + den_full)
    return result


# ------------------------------------------------------------------------------------------- synthetic bursts


def synthetic_scene(h: int, w: int, seed: int = 1) -> np.ndarray:
    """A textured linear RGB scene (h, w, 3) in 0..1 with detail at every scale (so every pyramid level has
    something to align): octaves of bilinear value noise from 512 px down to 2 px (about 1/f), coloured discs with
    hard edges, and a little per-pixel texture."""
    from scipy import ndimage

    rs = np.random.default_rng(seed)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    lum = np.full((h, w), 0.3)
    size = 512
    while size >= 2:
        gh, gw = h // size + 2, w // size + 2
        grid = rs.uniform(-1.0, 1.0, (gh, gw))
        up = ndimage.zoom(grid, size, order=1)[:h, :w]
        lum += 0.05 * up
        size //= 2
    img = np.stack([lum * 1.0, lum * 0.9, lum * 0.8], axis=-1)
    n_discs = max(40, int(h * w / 6000))
    for _ in range(n_discs):
        cy, cx = rs.uniform(0, h), rs.uniform(0, w)
        r = rs.uniform(4, 30)
        y0, y1 = max(int(cy - r), 0), min(int(cy + r) + 1, h)
        x0, x1 = max(int(cx - r), 0), min(int(cx + r) + 1, w)
        m = ((yy[y0:y1, x0:x1] - cy) ** 2 + (xx[y0:y1, x0:x1] - cx) ** 2) < r * r
        img[y0:y1, x0:x1][m] = rs.uniform(0.02, 0.6, 3)
    img += 0.03 * rs.standard_normal((h, w, 1))
    return np.clip(img, 0.0, 1.0)


def mosaic(rgb: np.ndarray) -> np.ndarray:
    """RGGB mosaic of a linear RGB image (values 0..1)."""
    h, w, _ = rgb.shape
    out = np.empty((h, w), dtype=np.float64)
    out[0::2, 0::2] = rgb[0::2, 0::2, 0]
    out[0::2, 1::2] = rgb[0::2, 1::2, 1]
    out[1::2, 0::2] = rgb[1::2, 0::2, 1]
    out[1::2, 1::2] = rgb[1::2, 1::2, 2]
    return out


def synthetic_burst(
    h: int,
    w: int,
    shifts: list[tuple[int, int]],
    black: float = 64.0,
    white: float = 1023.0,
    noise_s: float = 2e-3,
    noise_o: float = 2e-5,
    exposure: float = 0.6,
    seed: int = 7,
    moving: tuple[int, int, int] | None = None,
):
    """N RAW frames of one scene, frame k shifted by shifts[k] = (dy, dx) in grey pixels (2 RAW px), with
    Poisson-Gaussian noise of the given model. ``moving`` = (frame, y, x): a bright square pasted only into that frame.
    Returns (frames uint16, clean mosaic of frame 0 in RAW units)."""
    pad = 2 * max(max(abs(a), abs(b)) for a, b in shifts) + 4
    scene = synthetic_scene(h + 2 * pad, w + 2 * pad) * exposure
    rs = np.random.default_rng(seed)
    rng = white - black
    frames = []
    clean0 = None
    for k, (dy, dx) in enumerate(shifts):
        y0, x0 = pad + 2 * dy, pad + 2 * dx
        m = mosaic(scene[y0 : y0 + h, x0 : x0 + w])
        if moving is not None and moving[0] == k:
            _, my, mx = moving
            m[my : my + 48, mx : mx + 48] = 0.9
        var = noise_s * m + noise_o
        noisy = m + rs.standard_normal(m.shape) * np.sqrt(var)
        raw = np.clip(np.round(noisy * rng + black), 0, white).astype(np.uint16)
        if k == 0:
            clean0 = m * rng + black
        frames.append(raw)
    return frames, clean0
