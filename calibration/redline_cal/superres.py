"""B23 handheld multi-frame super-resolution at the 50 and 70 mm stops. The reference (Python) implementation.

This module is the contract. The app runs the same algorithm in C++ (``app/src/main/cpp/superres.cpp``) and CI
checks it against this file through the host tool (``tests/test_superres.py``): reference frame and integer tile
displacements exactly (integer maths, shared with the B22 burst merge), sub-pixel offsets within float rounding, the
merged linear RGB within a small tolerance (float32 on the phone, float64 here).

The idea is Wronski et al. 2019 ("Handheld multi-frame super-resolution"): a handheld burst samples the scene at
slightly different sub-pixel positions (hand shake, OIS), so the RAW samples of all frames, placed where they
really landed, fill a grid finer than the sensor's. Each output pixel gathers the nearby Bayer samples of every frame
into its own colour channel with a kernel, so demosaicking is skipped: the merge reconstructs RGB directly.

Input: N RAW_SENSOR frames (uint16, H x W, even), the CFA layout (Camera2 0..3), per-CFA-position black levels, the
white level, the noise model ``var(x) = S x + O`` (normalised signal), the optional lens shading map (Camera2 layout,
as B21), the stop's 3:2 crop (left, top, width, height; left/top even, as ``raw_pipeline.crop_32``) and the output
size (Wo, Ho), normally larger than the crop (3000 x 2000 at 50 and 70 mm).

1. region     only the crop plus MARGIN RAW px on every side (clamped to the frame, kept even) is read: every later
              step works in region coordinates (origin (rt, rl) = the region's top-left), which keep the CFA phase.
2. reference  the sharpest of the first min(3, N) frames, by the B22 rule (``burst_merge.sharpness`` of the region's
              pyramid; earliest wins ties), or a fixed index.
3. align      B22 coarse-to-fine integer tile alignment (``burst_merge.align``) on the region's grey pyramid: per
              fine tile (16 x 16 grey px at stride 8, tile i centred on grey 8 i = RAW 16 i), (dy, dx) in grey px
              (alt(q + d) ~ ref(q)).
4. sub-pixel  per tile, LK_ITERS Gauss-Newton steps of inverse compositional Lucas-Kanade on the grey level, from the
              integer displacement: gradients of the reference by central differences (zero on the border rows and
              columns), over the tile's inside pixels that are not on the border; H = sum J J^T plus LK_REG trace(H) on the
              diagonal; per step e = alt(q + d) - ref(q) with alt sampled bilinearly (clamped), d -= H^-1 sum J e, then d
              is clamped to +-SUBPIX_CLAMP of the start. The tile's texture strength is the smaller eigenvalue of the
              unregularised H. Flow in RAW px: f = 2 d.
5. motion     the frame's global motion: an affine flow f(y, x) = [1, y / MODEL_SCALE, x / MODEL_SCALE] P fitted to the
              flows of the tiles wholly inside the region, weighted by texture strength (tiles below MODEL_MIN_STRENGTH x
              the strongest get no weight): start from the weighted median translation, then weighted least squares on
              the tiles within each band of MODEL_BANDS of the current prediction (max-abs per axis; fewer than
              MODEL_MIN_TILES tiles: no model). A tile whose flow is more than MODEL_TOL RAW px (either axis) from the
              model takes the model's flow. This is what keeps periodic detail (fabric, brick, a resolution chart)
              usable: above a quarter cycle per RAW px the grey level aliases, the tile search and LK lock onto the
              alias, and the frame's motion, seen on the textured tiles, is the only right answer there. A subject that
              really moved also takes the global flow and is then rejected by the robustness step.
   flow       f at any RAW position (y, x) of the region is the bilinear interpolation of the tile flows at the tile
              centres (16 i, 16 j): fi = y / 16, i0 = clip(floor(fi), 0, nty - 2), t = clip(fi - i0, 0, 1) (same in x).
6. robustness per alternate frame, at quad (grey) resolution: g = (L0 / 4 - mean(black)) / range for the reference,
              and for the alternate its grey sampled bilinearly (clamped) at q + f(2 q + 0.5) / 2. 3x3 box means
              (edges clamped) mr, ma; local variance of the reference sv = max(box(g^2) - mr^2, 0);
              noise of the mean difference sd2 = 2 (S clip(mr, 0, 1) + O) / 36; d2 = (mr - ma)^2;
              lim = ROB_KN^2 sd2 + ROB_KL^2 sv; R = 1 when d2 <= lim, else lim / d2 (the B22 per-quad rule, with the
              local texture allowed for: aliasing differs between frames exactly where there is texture). Then a 3x3
              minimum (edges clamped), quantised to 8 bits: R = round(255 R) / 255. The reference has R = 1.
7. gather     output pixel (oy, ox) sits at region RAW position ry = (ct - rt) + (oy + 0.5) ch / Ho - 0.5 (same in x).
              For each frame: a = r (reference) or a = r + f(r) (alternate), and R = the frame's robustness at the
              reference quad (clip(floor((ry + 0.5) / 2)), same in x). The 4 x 4 RAW samples around a, rows
              floor(ay) - 1 .. floor(ay) + 2 (mirror, reflect-101, into the region, which keeps the CFA phase), each
              add to their own colour c: num_c += w v, den_c += w, with
              w = R * K_c(ky - 1 - qy / KERNEL_Q) * K_c(kx - 1 - qx / KERNEL_Q), q = floor(KERNEL_Q (a - floor(a)) + 0.5)
              (the sub-pixel phase quantised to 1/64 px), K_c(t) = exp(-t^2 / (2 sigma_c^2)), sigma_c from the burst's
              coverage (``kernel_sigmas``): SIGMA_G / SIGMA_RB (green / red and blue) at coverage >= COV_HI, the wide
              SIGMA_WIDE_G / SIGMA_WIDE_RB (close to a plain demosaic's sharpness) at <= COV_LO, linear between. A narrow
              kernel is what resolves detail beyond the sensor's Nyquist, but only where other frames filled the gaps
              between same-colour samples; a tripod burst or a single frame needs the wide one.
              v = (raw - black[p]) / (white - black[p]) * shade (as B21 stage 1).
8. output     rgb_c = num_c / den_c: normalised, shading-corrected camera RGB (B21's stage-1/2 values, not yet
              balanced or clipped) on the Wo x Ho grid of the crop. It goes into the B21 pipeline from stage 4
              (``raw_pipeline.render_linear``): balance + clip, matrix, tone curve, LUT; nothing is resampled.

Streaming: steps 1-6 run per frame on arrival (the region is copied and the frame's buffer handed back); step 7 runs
once all frames are in, over all frames at once (so no full-size accumulator is needed).

Sub-pixel coverage: per interior tile, the RAW-px phases of all frames (reference 0) fall into 4 x 4 bins of 0.5 px
centred on 0, 0.5, 1, 1.5 (floor(mod(f + 0.25, 2) * 2)); coverage = (mean over tiles of bins hit - 1) / (min(16, N) - 1),
so 0 for a tripod burst or one frame and 1 when every frame landed on its own phase. Low coverage widens the kernel
(step 7) and is reported: the merge is then a denoise + kernel upscale with no real resolution gain.

Measured (synthetic, ``tests/test_superres.py`` and calibration/tools/superres_quality.py): MTF10 of luma on a grating
blended into texture, 8 or 12 frames with random handheld shifts up to 3 RAW px: ~0.77 cycles/RAW px for the merge
against ~0.55 for one frame demosaicked and upscaled, so about 1.4x linear (the plan's 1.5-2x estimate is not reached
by this metric without sharpening; the 1 px pixel aperture caps it near 0.8). Error against the true scene on texture:
1.5-1.75x lower than one frame upscaled.
On real sensor texture (calibration/tools/superres_quality.py: private DNGs as the scene at 2x, a probe grating,
real-valued handheld shifts, estimated alignment, 8 frames): 1.14-1.38x at both stops, about 1.26x on average; the
texture error there is not lower than one frame's (estimated alignment on dense real texture is the limit).
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np
from scipy import ndimage

from . import burst_merge as bm
from .raw_pipeline import CFA_LAYOUTS, RawFrame, shading_gain_image

MARGIN = 64
SUBPIX_CLAMP = 1.0
LK_ITERS = 3
LK_REG = 0.01
MODEL_SCALE = 1000.0  # RAW px per unit of the affine model's y and x terms (conditioning)
MODEL_MIN_STRENGTH = 0.05
MODEL_MIN_TILES = 12
MODEL_BANDS = (3.0, 1.5, 1.0)
MODEL_TOL = 0.5
KERNEL_Q = 64
SIGMA_G = 0.25
SIGMA_RB = 0.35
SIGMA_WIDE_G = 0.65
SIGMA_WIDE_RB = 0.75
COV_LO = 0.2
COV_HI = 0.45
ROB_KN = 3.0
ROB_KL = 1.0
OUTPUT_LONG = 3000  # 3000 x 2000 (6 MP) at 50 and 70 mm (PLAN 5)


def region_of(crop, width: int, height: int) -> tuple[int, int, int, int]:
    """(left, top, width, height) of the crop plus MARGIN, clamped to the frame, even."""
    l, t, w, h = crop
    if l % 2 or t % 2 or w % 2 or h % 2:
        raise ValueError("crop must be even")
    rl, rt = max(0, l - MARGIN), max(0, t - MARGIN)
    rr, rb = min(width, l + w + MARGIN), min(height, t + h + MARGIN)
    rr -= (rr - rl) % 2
    rb -= (rb - rt) % 2
    return rl, rt, rr - rl, rb - rt


def bilinear(img: np.ndarray, y: np.ndarray, x: np.ndarray) -> np.ndarray:
    """Bilinear sample of ``img`` at (y, x), coordinates clamped to the image (needs h, w >= 2)."""
    h, w = img.shape
    y = np.clip(y, 0.0, h - 1.0)
    x = np.clip(x, 0.0, w - 1.0)
    y0 = np.minimum(np.floor(y).astype(np.int64), h - 2)
    x0 = np.minimum(np.floor(x).astype(np.int64), w - 2)
    ty, tx = y - y0, x - x0
    return (img[y0, x0] * (1 - ty) * (1 - tx) + img[y0, x0 + 1] * (1 - ty) * tx + img[y0 + 1, x0] * ty * (1 - tx)
            + img[y0 + 1, x0 + 1] * ty * tx)


def lucas_kanade(ref0, alt0, grid, start: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Per-tile displacement (nty, ntx, 2) in grey px refined from ``start`` (float, grey px) by LK_ITERS Gauss-Newton
    (inverse compositional Lucas-Kanade) steps, kept within +-SUBPIX_CLAMP of ``start``; and the tile's texture
    strength (the smaller eigenvalue of its gradient matrix), which weights the motion model fit."""
    h, w = ref0.shape
    r = ref0.astype(np.float64)
    a = alt0.astype(np.float64)
    gy = np.zeros_like(r)
    gx = np.zeros_like(r)
    gy[1:-1] = (r[2:] - r[:-2]) * 0.5
    gx[:, 1:-1] = (r[:, 2:] - r[:, :-2]) * 0.5
    yy, xx, inside = bm._tile_coords(grid, h, w)
    inside = inside & (yy >= 1) & (yy <= h - 2) & (xx >= 1) & (xx <= w - 2)
    yc = np.clip(yy, 0, h - 1)
    xc = np.clip(xx, 0, w - 1)
    jy = np.where(inside, gy[yc, xc], 0.0)
    jx = np.where(inside, gx[yc, xc], 0.0)
    hyy = (jy * jy).sum((2, 3))
    hxx = (jx * jx).sum((2, 3))
    hxy = (jy * jx).sum((2, 3))
    strength = 0.5 * (hyy + hxx - np.sqrt((hyy - hxx) ** 2 + 4.0 * hxy * hxy))
    reg = LK_REG * (hyy + hxx)
    hyy = hyy + reg
    hxx = hxx + reg
    det = hyy * hxx - hxy * hxy
    ok = det > 0.0
    sdet = np.where(ok, det, 1.0)
    rv = np.where(inside, r[yc, xc], 0.0)
    start = np.asarray(start, dtype=np.float64)
    d = start.copy()
    for _ in range(LK_ITERS):
        py = yy + d[..., 0][:, :, None, None]
        px = xx + d[..., 1][:, :, None, None]
        e = np.where(inside, bilinear(a, py, px) - rv, 0.0)
        by = (jy * e).sum((2, 3))
        bx = (jx * e).sum((2, 3))
        d[..., 0] -= np.where(ok, (hxx * by - hxy * bx) / sdet, 0.0)
        d[..., 1] -= np.where(ok, (hyy * bx - hxy * by) / sdet, 0.0)
        d = np.clip(d, start - SUBPIX_CLAMP, start + SUBPIX_CLAMP)
    return d, strength


def tile_centres_raw(grid: bm.TileGrid) -> tuple[np.ndarray, np.ndarray]:
    """RAW coordinates of the fine tile centres (16 i, 16 j)."""
    return 2.0 * grid.cy.astype(np.float64), 2.0 * grid.cx.astype(np.float64)


def fit_motion(flow: np.ndarray, strength: np.ndarray, grid: bm.TileGrid, h0: int, w0: int) -> np.ndarray | None:
    """The frame's global motion: an affine flow f(y, x) = [1, y, x] P (RAW px, P is 3 x 2), fitted robustly to the
    interior tile flows weighted by texture strength. None when too few textured tiles agree."""
    cy, cx = tile_centres_raw(grid)
    iy = (grid.oy >= 0) & (grid.oy + bm.FINE_TILE <= h0)
    ix = (grid.ox >= 0) & (grid.ox + bm.FINE_TILE <= w0)
    Y, X = np.meshgrid(cy, cx, indexing="ij")
    sel = iy[:, None] & ix[None, :]
    wt = np.where(sel, strength, 0.0)
    if wt.max() <= 0:
        return None
    wt = np.where(wt >= MODEL_MIN_STRENGTH * wt.max(), wt, 0.0)
    f = flow.reshape(-1, 2)
    w = wt.reshape(-1)
    a = np.stack([np.ones(w.size), Y.reshape(-1) / MODEL_SCALE, X.reshape(-1) / MODEL_SCALE], axis=1)
    # Start: the weighted median translation; then affine fits on the tiles that agree, tightening the band.
    t = np.empty(2)
    for c in range(2):
        o = np.argsort(f[:, c])
        cw = np.cumsum(w[o])
        t[c] = f[o[np.searchsorted(cw, 0.5 * cw[-1])], c]
    pred = np.broadcast_to(t, f.shape)
    p = None
    for band in MODEL_BANDS:
        inl = (np.abs(f - pred).max(axis=1) <= band) & (w > 0)
        if inl.sum() < MODEL_MIN_TILES:
            return None
        sw = np.sqrt(w[inl])[:, None]
        p, *_ = np.linalg.lstsq(a[inl] * sw, f[inl] * sw, rcond=None)
        pred = a @ p
    return p


def motion_flow(p: np.ndarray, y, x):
    """Flow (fy, fx) of the motion model at RAW positions."""
    return (p[0, 0] + p[1, 0] * (y / MODEL_SCALE) + p[2, 0] * (x / MODEL_SCALE),
            p[0, 1] + p[1, 1] * (y / MODEL_SCALE) + p[2, 1] * (x / MODEL_SCALE))


def frame_flow(ref0, alt0, grid, disp) -> tuple[np.ndarray, float]:
    """Steps 4-5: tile flows in RAW px (nty, ntx, 2) and the fraction of tiles re-solved from the motion model."""
    h0, w0 = ref0.shape
    d1, strength = lucas_kanade(ref0, alt0, grid, disp.astype(np.float64))
    flow = 2.0 * d1
    p = fit_motion(flow, strength, grid, h0, w0)
    if p is None:
        return flow, 0.0
    cy, cx = tile_centres_raw(grid)
    Y, X = np.meshgrid(cy, cx, indexing="ij")
    my, mx = motion_flow(p, Y, X)
    model = np.stack([my, mx], axis=-1)
    off = np.abs(flow - model).max(axis=-1) > MODEL_TOL
    flow = np.where(off[..., None], model, flow)
    return flow, float(off.mean())


def interp_flow(flow: np.ndarray, y: np.ndarray, x: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Bilinear flow (RAW px) at region RAW positions; flow is (nty, ntx, 2) at tile centres (16 i, 16 j)."""
    nty, ntx = flow.shape[:2]

    def axis(v, n):
        f = v / 16.0
        i0 = np.clip(np.floor(f), 0, max(n - 2, 0)).astype(np.int64)
        t = np.clip(f - i0, 0.0, 1.0)
        return i0, np.minimum(i0 + 1, n - 1), t

    iy0, iy1, ty = axis(y, nty)
    ix0, ix1, tx = axis(x, ntx)
    out = []
    for c in (0, 1):
        g = flow[..., c]
        v = (g[iy0, ix0] * (1 - ty) * (1 - tx) + g[iy0, ix1] * (1 - ty) * tx + g[iy1, ix0] * ty * (1 - tx) + g[iy1, ix1] * ty * tx)
        out.append(v)
    return out[0], out[1]


def box3(img: np.ndarray) -> np.ndarray:
    return ndimage.uniform_filter(img, size=3, mode="nearest")


def robustness(ref0: np.ndarray, alt0: np.ndarray, flow: np.ndarray, black_mean: float, rng: float, noise_s: float, noise_o: float) -> np.ndarray:
    h0, w0 = ref0.shape
    gr = (ref0.astype(np.float64) / 4.0 - black_mean) / rng
    ga = (alt0.astype(np.float64) / 4.0 - black_mean) / rng
    qy, qx = np.mgrid[0:h0, 0:w0].astype(np.float64)
    fy, fx = interp_flow(flow, 2.0 * qy + 0.5, 2.0 * qx + 0.5)
    py = np.clip(qy + fy / 2.0, 0.0, h0 - 1.0)
    px = np.clip(qx + fx / 2.0, 0.0, w0 - 1.0)
    y0 = np.minimum(np.floor(py).astype(np.int64), h0 - 1)
    x0 = np.minimum(np.floor(px).astype(np.int64), w0 - 1)
    ty, tx = py - y0, px - x0
    y1, x1 = np.minimum(y0 + 1, h0 - 1), np.minimum(x0 + 1, w0 - 1)
    warped = ga[y0, x0] * (1 - ty) * (1 - tx) + ga[y0, x1] * (1 - ty) * tx + ga[y1, x0] * ty * (1 - tx) + ga[y1, x1] * ty * tx
    mr, ma = box3(gr), box3(warped)
    sv = np.maximum(box3(gr * gr) - mr * mr, 0.0)
    sd2 = 2.0 * (noise_s * np.clip(mr, 0.0, 1.0) + noise_o) / 36.0
    d2 = (mr - ma) ** 2
    lim = ROB_KN * ROB_KN * sd2 + ROB_KL * ROB_KL * sv
    r = np.where(d2 <= lim, 1.0, lim / np.maximum(d2, 1e-300))
    r = ndimage.minimum_filter(r, size=3, mode="nearest")
    return np.floor(r * 255.0 + 0.5) / 255.0


def kernel_table(sigma: float) -> np.ndarray:
    """(KERNEL_Q + 1, 4): K(k - 1 - q / KERNEL_Q) for phase q and tap k."""
    q = np.arange(KERNEL_Q + 1)[:, None] / KERNEL_Q
    k = np.arange(4)[None, :]
    t = k - 1 - q
    return np.exp(-(t * t) / (2.0 * sigma * sigma))


def mirror(i: np.ndarray, n: int) -> np.ndarray:
    period = 2 * (n - 1)
    i = np.abs(i) % period
    return np.where(i >= n, period - i, i)


@dataclass
class SrFrameStats:
    index: int
    mean_disp_px: float
    mean_abs_subpix: float  # mean |o| in RAW px
    mean_robustness: float
    rejected_fraction: float  # quads with R < 0.5
    model_resolved: float = 0.0  # tiles whose flow disagreed with the frame's motion model and were re-solved from it


@dataclass
class SrResult:
    rgb: np.ndarray  # (Ho, Wo, 3) normalised camera RGB
    reference: int
    region: tuple
    flows: dict = field(default_factory=dict)  # frame index -> (nty, ntx, 2) RAW px
    displacements: dict = field(default_factory=dict)  # frame index -> integer (nty, ntx, 2) grey px
    frames: list = field(default_factory=list)
    coverage: float = 0.0
    sigma: tuple = (SIGMA_G, SIGMA_RB)


def coverage(flows: dict, n_frames: int, grid: bm.TileGrid, h0: int, w0: int, margin: int = 16) -> float:
    """Sub-pixel diversity of the burst, 0 (every frame on the reference's phase: a tripod, or one frame) to 1."""
    if n_frames < 2 or not flows:
        return 0.0
    iy = (grid.oy >= margin) & (grid.oy + bm.FINE_TILE <= h0 - margin)
    ix = (grid.ox >= margin) & (grid.ox + bm.FINE_TILE <= w0 - margin)
    if not iy.any() or not ix.any():
        iy = np.ones_like(iy)
        ix = np.ones_like(ix)
    nty, ntx = len(grid.oy), len(grid.ox)
    hits = np.zeros((nty, ntx, 16), dtype=bool)
    hits[..., 0] = True  # the reference: phase (0, 0)
    for f in flows.values():
        by = np.floor(np.mod(f[..., 0] + 0.25, 2.0) * 2.0).astype(np.int64) % 4
        bx = np.floor(np.mod(f[..., 1] + 0.25, 2.0) * 2.0).astype(np.int64) % 4
        hits[np.arange(nty)[:, None], np.arange(ntx)[None, :], by * 4 + bx] = True
    sel = hits[np.ix_(iy, ix)]
    return float((sel.sum(axis=-1).mean() - 1.0) / (min(16, n_frames) - 1))


def kernel_sigmas(cov: float) -> tuple[float, float]:
    """(sigma green, sigma red/blue): narrow for a well-spread burst, wide (close to a plain demosaic) without spread."""
    s = min(max((cov - COV_LO) / (COV_HI - COV_LO), 0.0), 1.0)
    return SIGMA_WIDE_G + s * (SIGMA_G - SIGMA_WIDE_G), SIGMA_WIDE_RB + s * (SIGMA_RB - SIGMA_WIDE_RB)


def merge(frames: list[np.ndarray], cfa: int, black, white: float, noise_s: float, noise_o: float, crop, out_size,
          shading: np.ndarray | None = None, reference: int | None = None, known_shifts=None) -> SrResult:
    """``known_shifts`` (measurement only): the true per-frame scene shifts (sy, sx) in RAW px; the flow is then the
    known constant s_ref - s_k instead of the estimated one (isolates the reconstruction from the alignment)."""
    if not frames:
        raise ValueError("no frames")
    H, W = frames[0].shape
    rl, rt, rw, rh = region_of(crop, W, H)
    cl, ct, cw, ch = crop
    wo, ho = out_size
    regs = [f[rt : rt + rh, rl : rl + rw] for f in frames]
    pyrs = [bm.pyramid(r) for r in regs]
    ref_i = bm.pick_reference(pyrs) if reference is None else reference
    ref0 = pyrs[ref_i][0]
    h0, w0 = ref0.shape
    g0 = bm.fine_grid(h0, w0)
    black_mean = float(np.mean(black))
    rng = float(white) - black_mean
    res = SrResult(rgb=None, reference=ref_i, region=(rl, rt, rw, rh))
    robs = {}
    for i in range(len(frames)):
        if i == ref_i:
            continue
        d = bm.align(pyrs[ref_i], pyrs[i])
        flow, resolved = frame_flow(ref0, pyrs[i][0], g0, d)
        o = flow / 2.0 - d
        if known_shifts is not None:
            flow = np.empty(d.shape, dtype=np.float64)
            flow[..., 0] = known_shifts[ref_i][0] - known_shifts[i][0]
            flow[..., 1] = known_shifts[ref_i][1] - known_shifts[i][1]
            o = flow / 2.0 - d
        res.displacements[i] = d
        res.flows[i] = flow
        r = robustness(ref0, pyrs[i][0], flow, black_mean, rng, noise_s, noise_o)
        robs[i] = r
        mag = np.abs(d).max(axis=-1) * 2.0
        res.frames.append(SrFrameStats(i, float(mag.mean()), float(np.abs(2.0 * o).mean()), float(r.mean()), float((r < 0.5).mean()),
                                       resolved if known_shifts is None else 0.0))
    res.coverage = coverage(res.flows, len(frames), g0, h0, w0)

    # Normalisation of the region (B21 stage 1).
    full = RawFrame(np.zeros((H, W), np.uint16), cfa, tuple(black), white, shading)
    gain = shading_gain_image(full)[rt : rt + rh, rl : rl + rw]
    blk = np.empty((rh, rw))
    for p in range(4):
        blk[p >> 1 :: 2, p & 1 :: 2] = black[p]
    scale = gain / (white - blk)
    layout = CFA_LAYOUTS[cfa]
    colour = np.empty((rh, rw), np.int64)
    for p in range(4):
        colour[p >> 1 :: 2, p & 1 :: 2] = layout[p]
    sg, srb = kernel_sigmas(res.coverage)
    res.sigma = (sg, srb)
    tables = {0: kernel_table(sg), 1: kernel_table(srb)}

    oy = np.arange(ho, dtype=np.float64)
    ox = np.arange(wo, dtype=np.float64)
    ry = (ct - rt) + (oy + 0.5) * ch / ho - 0.5
    rx = (cl - rl) + (ox + 0.5) * cw / wo - 0.5
    RY, RX = np.meshgrid(ry, rx, indexing="ij")
    qy = np.clip(np.floor((RY + 0.5) / 2.0).astype(np.int64), 0, h0 - 1)
    qx = np.clip(np.floor((RX + 0.5) / 2.0).astype(np.int64), 0, w0 - 1)
    num = np.zeros((ho, wo, 3))
    den = np.zeros((ho, wo, 3))
    for i in range(len(frames)):
        v = (regs[i].astype(np.float64) - blk) * scale
        if i == ref_i:
            ay, ax, rob = RY, RX, 1.0
        else:
            fy, fx = interp_flow(res.flows[i], RY, RX)
            ay, ax = RY + fy, RX + fx
            rob = robs[i][qy, qx]
        fy0, fx0 = np.floor(ay), np.floor(ax)
        py = np.floor((ay - fy0) * KERNEL_Q + 0.5).astype(np.int64)
        px = np.floor((ax - fx0) * KERNEL_Q + 0.5).astype(np.int64)
        fy0 = fy0.astype(np.int64)
        fx0 = fx0.astype(np.int64)
        for ky in range(4):
            iy = mirror(fy0 - 1 + ky, rh)
            for kx in range(4):
                ix = mirror(fx0 - 1 + kx, rw)
                c = colour[iy, ix]
                cls = (c != 1).astype(np.int64)
                wgt = np.where(cls == 0, tables[0][py, ky] * tables[0][px, kx], tables[1][py, ky] * tables[1][px, kx]) * rob
                val = v[iy, ix]
                for cc in range(3):
                    m = c == cc
                    num[..., cc] += np.where(m, wgt * val, 0.0)
                    den[..., cc] += np.where(m, wgt, 0.0)
    res.rgb = num / den
    return res


# ------------------------------------------------------------------------------------------- measurement

GRATING_AMP = 0.35
GRATING_BASE = 0.45
GAIN_FREQS = (0.1, 0.2, 0.3, 0.35, 0.4, 0.45, 0.5, 0.55, 0.6, 0.65, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95, 1.0)
MTF_THRESHOLD = 0.1
LUMA_W = np.array([0.2126729, 0.7151522, 0.0721750])  # the grey scene is equal in camera RGB; resolution is judged on luma


def grating_value(y, x, f: float, theta: float):
    """Box-integrated (1 RAW px aperture) grey grating at scene RAW coordinates (pixel centres)."""
    ky, kx = f * math.sin(theta), f * math.cos(theta)
    amp = GRATING_AMP * np.sinc(kx) * np.sinc(ky)
    return GRATING_BASE + amp * np.cos(2.0 * math.pi * (kx * x + ky * y))


def grating_burst(h: int, w: int, shifts, f: float, theta: float, black=64.0, white=1023.0, noise_s=2e-4, noise_o=2e-6, seed=3):
    """Frames of a grey grating, frame k's scene shifted by shifts[k] = (sy, sx) RAW px (any real values)."""
    rs = np.random.default_rng(seed)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    out = []
    for sy, sx in shifts:
        m = grating_value(yy + sy, xx + sx, f, theta)
        noisy = m + rs.standard_normal(m.shape) * np.sqrt(noise_s * m + noise_o)
        out.append(np.clip(np.round(noisy * (white - black) + black), 0, white).astype(np.uint16))
    return out


def fit_amplitude(img: np.ndarray, sy: np.ndarray, sx: np.ndarray, f: float, theta: float) -> float:
    """Amplitude of the grating at its true frequency, least squares, img sampled at scene coords (sy, sx)."""
    ky, kx = f * math.sin(theta), f * math.cos(theta)
    ph = 2.0 * math.pi * (kx * sx + ky * sy)
    a = np.stack([np.ones(img.size), np.cos(ph).ravel(), np.sin(ph).ravel()], axis=1)
    coef, *_ = np.linalg.lstsq(a, img.ravel(), rcond=None)
    return float(math.hypot(coef[1], coef[2]))


def mtf_limit(freqs, mtf, threshold: float = MTF_THRESHOLD) -> float:
    """Highest frequency before the MTF first drops below ``threshold`` (linear interpolation)."""
    for i in range(1, len(freqs)):
        if mtf[i] < threshold <= mtf[i - 1]:
            t = (mtf[i - 1] - threshold) / (mtf[i - 1] - mtf[i])
            return float(freqs[i - 1] + t * (freqs[i] - freqs[i - 1]))
    return float(freqs[-1]) if mtf[-1] >= threshold else float(freqs[0])


def single_frame_upscale(frame: np.ndarray, cfa: int, black, white: float, crop, out_size) -> np.ndarray:
    """The single-frame baseline: B21 normalise + MHC demosaic, crop, bicubic upscale to the output grid."""
    from .raw_pipeline import demosaic_mhc, normalise

    rgb = demosaic_mhc(normalise(RawFrame(frame, cfa, tuple(black), white)), cfa)
    cl, ct, cw, ch = crop
    wo, ho = out_size
    ry = ct + (np.arange(ho) + 0.5) * ch / ho - 0.5
    rx = cl + (np.arange(wo) + 0.5) * cw / wo - 0.5
    RY, RX = np.meshgrid(ry, rx, indexing="ij")
    return np.stack([ndimage.map_coordinates(rgb[..., c], [RY, RX], order=3, mode="mirror") for c in range(3)], axis=-1)


@dataclass
class GainResult:
    freqs: list
    mtf_sr: list
    mtf_single: list
    mtf_sr1: list  # the same kernel reconstruction from the reference frame alone
    limit_sr: float
    limit_single: float
    limit_sr1: float

    @property
    def gain(self) -> float:
        return self.limit_sr / self.limit_single

    @property
    def gain_vs_sr1(self) -> float:
        return self.limit_sr / self.limit_sr1


def measure_gain(shifts, scale: float, theta: float = 0.12, crop_px: int = 96, freqs=GAIN_FREQS, black=64.0, white=1023.0,
                 merge_fn=None, border: int = 12, known: bool = True) -> GainResult:
    """Resolution gain on synthetic grating bursts with known sub-pixel shifts: MTF10 of the super-res merge versus
    the single-frame demosaic + bicubic upscale (and versus the kernel reconstruction of one frame)."""
    merge_fn = merge_fn or merge
    pad = MARGIN + 8
    h = w = crop_px + 2 * pad
    crop = (pad, pad, crop_px, crop_px)
    wo = ho = int(round(crop_px * scale))
    ry = pad + (np.arange(ho) + 0.5) * crop_px / ho - 0.5
    RY, RX = np.meshgrid(ry, ry, indexing="ij")
    inner = (slice(border, ho - border), slice(border, wo - border))
    m_sr, m_one, m_sr1 = [], [], []
    bl = [black] * 4
    for f in freqs:
        frames = grating_burst(h, w, shifts, f, theta, black, white)
        r = merge_fn(frames, 0, bl, white, 2e-4, 2e-6, crop, (wo, ho), reference=0, known_shifts=shifts if known else None)
        sy0, sx0 = shifts[0]
        y = r.rgb @ LUMA_W
        m_sr.append(fit_amplitude(y[inner], (RY + sy0)[inner], (RX + sx0)[inner], f, theta) / GRATING_AMP)
        r1 = merge_fn(frames[:1], 0, bl, white, 2e-4, 2e-6, crop, (wo, ho), reference=0)
        m_sr1.append(fit_amplitude((r1.rgb @ LUMA_W)[inner], (RY + sy0)[inner], (RX + sx0)[inner], f, theta) / GRATING_AMP)
        one = single_frame_upscale(frames[0], 0, bl, white, crop, (wo, ho)) @ LUMA_W
        m_one.append(fit_amplitude(one[inner], (RY + sy0)[inner], (RX + sx0)[inner], f, theta) / GRATING_AMP)
    fl = list(freqs)
    return GainResult(fl, m_sr, m_one, m_sr1, mtf_limit(fl, m_sr), mtf_limit(fl, m_one), mtf_limit(fl, m_sr1))


def random_shifts(n: int, seed: int = 5, span: float = 3.0, still: bool = False):
    """Handheld-like shifts: frame 0 at 0, the rest uniform in +-span RAW px (``still``: whole quads only, a tripod)."""
    rs = np.random.default_rng(seed)
    out = [(0.0, 0.0)]
    for _ in range(n - 1):
        if still:
            out.append((0.0, 0.0))
        else:
            out.append((float(rs.uniform(-span, span)), float(rs.uniform(-span, span))))
    return out


# ------------------------------------------------------------------------------------------- textured bursts

SUPERSAMPLE = 4  # the continuous scene is drawn at 4x the RAW resolution


def hires_scene(h: int, w: int, seed: int = 1, grating=None) -> np.ndarray:
    """A linear RGB scene at SUPERSAMPLE x the RAW resolution (h, w in RAW px): the B22 synthetic texture, upsampled
    (cubic), and optionally a grey grating (f cycles/RAW px, angle theta, amplitude) over the central quarter with the
    texture's fine detail kept on it, so the tiles on the grating still see the scene's motion."""
    os_ = SUPERSAMPLE
    s = bm.synthetic_scene(h + 8, w + 8, seed=seed)
    big = np.clip(np.stack([ndimage.zoom(s[..., c], os_, order=3) for c in range(3)], -1), 0.0, 1.0)
    if grating is not None:
        f, th, amp = grating
        hh, ww = big.shape[:2]
        yy, xx = np.mgrid[0:hh, 0:ww] / os_
        g = 0.45 + amp * np.cos(2.0 * math.pi * (f * math.cos(th) * xx + f * math.sin(th) * yy))
        lum = big.mean(-1)
        g = g + 0.5 * (lum - ndimage.uniform_filter(lum, 64))
        m = np.zeros((hh, ww), bool)
        m[hh // 4 : 3 * hh // 4, ww // 4 : 3 * ww // 4] = True
        big[m] = g[m][:, None]
    return big


def render_shifted(big: np.ndarray, sy: float, sx: float, h: int, w: int) -> np.ndarray:
    """RAW-resolution RGB of the scene shifted by (sy, sx) RAW px, each pixel integrating its 1 x 1 RAW px area."""
    os_ = SUPERSAMPLE
    sh = np.stack([ndimage.shift(big[..., c], (-sy * os_, -sx * os_), order=3, mode="reflect") for c in range(3)], -1)
    return sh[: h * os_, : w * os_].reshape(h, os_, w, os_, 3).mean(axis=(1, 3))


def scene_truth(big: np.ndarray, ys: np.ndarray, xs: np.ndarray) -> np.ndarray:
    """The scene integrated over 1 RAW px boxes centred at RAW coordinates ys x xs (the ideal sensor value there)."""
    os_ = SUPERSAMPLE
    box = np.stack([ndimage.uniform_filter(big[..., c], os_, mode="reflect") for c in range(3)], -1)
    yy, xx = np.meshgrid(ys * os_ + (os_ - 1) / 2.0, xs * os_ + (os_ - 1) / 2.0, indexing="ij")
    return np.stack([ndimage.map_coordinates(box[..., c], [yy, xx], order=1) for c in range(3)], -1)


def textured_burst(big: np.ndarray, shifts, h: int, w: int, black=64.0, white=1023.0, noise_s=2e-4, noise_o=2e-6, seed=3,
                   moving=None) -> list[np.ndarray]:
    """RGGB RAW frames of the scene, frame k shifted by shifts[k] (real RAW px), with Poisson-Gaussian noise.
    ``moving`` = (frame, y, x, size): a bright square only in that frame (a subject that moved)."""
    rs = np.random.default_rng(seed)
    out = []
    for k, (sy, sx) in enumerate(shifts):
        m = bm.mosaic(render_shifted(big, sy, sx, h, w))
        if moving is not None and moving[0] == k:
            _, my, mx, size = moving
            m[my : my + size, mx : mx + size] = 0.9
        noisy = m + rs.standard_normal(m.shape) * np.sqrt(np.maximum(noise_s * m + noise_o, 0.0))
        out.append(np.clip(np.round(noisy * (white - black) + black), 0, white).astype(np.uint16))
    return out


# ------------------------------------------------------------------------------------------- native host tool


def native_merge(tool: str, frames: list[np.ndarray], cfa: int, black, white: float, noise_s: float, noise_o: float, crop,
                 out_size, shading: np.ndarray | None = None, reference: int = -1, threads: int = 4, repeat: int = 1):
    """Runs the C++ merge through the host tool: (rgb (Ho, Wo, 3), flows {index: (nty, ntx, 2)},
    displacements {index: (nty, ntx, 2)}, stats dict)."""
    import json
    import os
    import subprocess
    import tempfile

    h, w = frames[0].shape
    wo, ho = out_size
    with tempfile.TemporaryDirectory() as d:
        paths = []
        for i, f in enumerate(frames):
            p = os.path.join(d, f"f{i}.u16")
            np.ascontiguousarray(f, dtype="<u2").tofile(p)
            paths.append(p)
        lines = [f"width {w}", f"height {h}", f"cfa {cfa}", "black " + " ".join(f"{b:.9g}" for b in black), f"white {white:.9g}",
                 f"noise {noise_s:.9g} {noise_o:.9g}", f"threads {threads}", f"reference {reference}",
                 "crop " + " ".join(str(int(v)) for v in crop), f"out {wo} {ho}", f"repeat {repeat}"]
        if shading is None:
            lines.append("shading none")
        else:
            sh = np.ascontiguousarray(shading, dtype="<f4")
            sp = os.path.join(d, "shading.f32")
            sh.tofile(sp)
            lines.append(f"shading {sh.shape[0]} {sh.shape[1]} {sp}")
        lines.append(f"frames {len(paths)} " + " ".join(paths))
        pp = os.path.join(d, "params.txt")
        with open(pp, "w") as fh:
            fh.write("\n".join(lines) + "\n")
        op, fp, dp = (os.path.join(d, n) for n in ("out.f32", "flows.f32", "disp.i32"))
        res = subprocess.run([tool, "merge", pp, op, fp, dp], check=True, capture_output=True, text=True)
        stats = json.loads(res.stdout)
        rgb = np.fromfile(op, dtype="<f4").reshape(3, ho, wo).transpose(1, 2, 0).astype(np.float64)
        nty, ntx = stats["tiles_y"], stats["tiles_x"]
        fl = np.fromfile(fp, dtype="<f4").reshape(len(frames), nty, ntx, 2).astype(np.float64)
        dd = np.fromfile(dp, dtype="<i4").reshape(len(frames), nty, ntx, 2)
    ref = stats["reference"]
    flows = {i: fl[i] for i in range(len(frames)) if i != ref}
    disps = {i: dd[i] for i in range(len(frames)) if i != ref}
    return rgb, flows, disps, stats

