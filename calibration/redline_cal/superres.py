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
4. sub-pixel  per tile, the L2 tile distance D(d) of ``burst_merge`` (inside pixels, clamped alternate; exact
              integers) at d, d +- (0, 1) and d +- (1, 0); per axis o = (D- - D+) / (2 (D- - 2 D0 + D+)) when the
              denominator is > 0, else 0, clamped to +-SUBPIX_CLAMP. Flow in RAW px: f = 2 (d + o).
5. flow       f at any RAW position (y, x) of the region is the bilinear interpolation of the tile flows at the tile
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
              (the sub-pixel phase quantised to 1/64 px), K_c(t) = exp(-t^2 / (2 sigma_c^2)), sigma = SIGMA_G for green,
              SIGMA_RB for red and blue. v = (raw - black[p]) / (white - black[p]) * shade (as B21 stage 1).
8. output     rgb_c = num_c / den_c: normalised, shading-corrected camera RGB (B21's stage-1/2 values, not yet
              balanced or clipped) on the Wo x Ho grid of the crop. It goes into the B21 pipeline from stage 4
              (``raw_pipeline.render_linear``): balance + clip, matrix, tone curve, LUT; nothing is resampled.

Streaming: steps 1-6 run per frame on arrival (the region is copied and the frame's buffer handed back); step 7 runs
once all frames are in, over all frames at once (so no full-size accumulator is needed).

Sub-pixel coverage (reported): per interior tile, the RAW-px phases (f mod 2) of all frames (reference 0) fall into
4 x 4 bins of 0.5 px; coverage = mean over tiles of (bins hit) / min(16, N). A tripod burst covers one bin (1/N):
the merge then is a denoise + kernel upscale with no real resolution gain.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np
from scipy import ndimage

from . import burst_merge as bm
from .raw_pipeline import CFA_LAYOUTS, RawFrame, shading_gain_image

MARGIN = 64
SUBPIX_CLAMP = 0.5
KERNEL_Q = 64
SIGMA_G = 0.25  # WIP: provisional (synthetic MTF sweep 2026-10-31), not final
SIGMA_RB = 0.35  # WIP: provisional
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


def tile_cost_l2(ref0: np.ndarray, alt0: np.ndarray, grid: bm.TileGrid, disp: np.ndarray) -> np.ndarray:
    """B22 L2 tile distance per tile (exact integers) for the displacement field ``disp`` (nty, ntx, 2)."""
    h, w = ref0.shape
    yy, xx, inside = bm._tile_coords(grid, h, w)
    rv = np.where(inside, ref0[np.clip(yy, 0, h - 1), np.clip(xx, 0, w - 1)], 0).astype(np.int64)
    ay = np.clip(yy + disp[..., 0][:, :, None, None], 0, h - 1)
    ax = np.clip(xx + disp[..., 1][:, :, None, None], 0, w - 1)
    d = np.where(inside, rv - alt0[ay, ax].astype(np.int64), 0)
    return (d * d).sum(axis=(2, 3))


def subpixel(ref0, alt0, grid, disp) -> np.ndarray:
    """Per-tile sub-pixel offsets (nty, ntx, 2) in grey px from parabola fits of the L2 distance."""
    c0 = tile_cost_l2(ref0, alt0, grid, disp).astype(np.float64)
    out = np.zeros(disp.shape, dtype=np.float64)
    for axis in (0, 1):
        e = np.zeros(2, dtype=np.int64)
        e[axis] = 1
        cm = tile_cost_l2(ref0, alt0, grid, disp - e).astype(np.float64)
        cp = tile_cost_l2(ref0, alt0, grid, disp + e).astype(np.float64)
        den = 2.0 * (cm - 2.0 * c0 + cp)
        o = np.where(den > 0, (cm - cp) / np.where(den > 0, den, 1.0), 0.0)
        out[..., axis] = np.clip(o, -SUBPIX_CLAMP, SUBPIX_CLAMP)
    return out


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


@dataclass
class SrResult:
    rgb: np.ndarray  # (Ho, Wo, 3) normalised camera RGB
    reference: int
    region: tuple
    flows: dict = field(default_factory=dict)  # frame index -> (nty, ntx, 2) RAW px
    displacements: dict = field(default_factory=dict)  # frame index -> integer (nty, ntx, 2) grey px
    frames: list = field(default_factory=list)
    coverage: float = 0.0


def coverage(flows: dict, n_frames: int, grid: bm.TileGrid, h0: int, w0: int, margin: int = 16) -> float:
    iy = (grid.oy >= margin) & (grid.oy + bm.FINE_TILE <= h0 - margin)
    ix = (grid.ox >= margin) & (grid.ox + bm.FINE_TILE <= w0 - margin)
    if not iy.any() or not ix.any():
        iy = np.ones_like(iy)
        ix = np.ones_like(ix)
    nty, ntx = len(grid.oy), len(grid.ox)
    hits = np.zeros((nty, ntx, 16), dtype=bool)
    hits[..., 0] = True  # the reference: phase (0, 0)
    for f in flows.values():
        by = np.floor(np.mod(f[..., 0], 2.0) * 2.0).astype(np.int64) % 4
        bx = np.floor(np.mod(f[..., 1], 2.0) * 2.0).astype(np.int64) % 4
        hits[np.arange(nty)[:, None], np.arange(ntx)[None, :], by * 4 + bx] = True
    sel = hits[np.ix_(iy, ix)]
    return float(sel.sum(axis=-1).mean() / min(16, n_frames))


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
        o = subpixel(ref0, pyrs[i][0], g0, d)
        flow = 2.0 * (d + o)
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
        res.frames.append(SrFrameStats(i, float(mag.mean()), float(np.abs(2.0 * o).mean()), float(r.mean()), float((r < 0.5).mean())))
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
    tables = {0: kernel_table(SIGMA_G), 1: kernel_table(SIGMA_RB)}

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
