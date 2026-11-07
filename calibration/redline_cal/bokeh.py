"""B31: simulated depth of field, the reference (the contract for app/src/main/cpp/bokeh.cpp).

Input: the rendered still (encoded 0..1 RGB, i.e. the tone curve's output), the B30 depth maps (relative inverse depth
normalised 0..1 with 1 = near, person mask, the scalar confidence), the tone curve the still was rendered with, an
optional B25 HDR gain map, and the virtual lens (focal, aperture, focus distance and point). PLAN 7.4 and 19.5 are the
source for every number marked [PLAN]; the rest is marked [ESTIMATE] with the reason.

1. Gate: no render when the depth confidence is below 0.35 (B30 ``USABLE``) or when the phone's own depth of field is
   already as deep as the chosen aperture (nothing to add); the reason is returned.

2. Circle of confusion [PLAN 7.4, 19.5]. Thin lens on a 36 mm wide full-frame sensor:
       b = f^2 / (N (s - f)) * |d - s| / d          (mm; f focal, N f-number, s focus distance, d object distance)
   rewritten on inverse depth D = 1/d:  b = f^2 / (N (s - f)) * |D_s - D| / D_s, so the blur depends only on the
   inverse-depth difference from the focus plane. Diameter in output pixels = b / 36 * W.
   Depth scale: the model output r is treated as AFFINE inverse depth, D = D_s + a (r - r_f). Two anchors fix it:
     - the focus point: r_f = median of r in a small window at the AF/tap point has D = 1/s (s from AF/tap, metres);
     - the background: the 2nd percentile r_bg (the farthest 2 % of the frame) is at ``far_m`` (default infinity,
       D = 0).
   The offset only decides what counts as "beyond infinity" (clamped to D = 0); the scale a sets every blur size.
   With far_m = infinity the farthest content gets exactly the 19.5 "background at infinity" disc, the upper bound for
   that focus distance: outdoors it is right; indoors (a wall at distance w) it over-blurs every out-of-focus depth by
   the same factor w / (w - s), e.g. 1.33x for a wall at 4 m behind a subject at 1 m, 1.11x for 10 m. A caller with a better
   far distance (hardware depth, a scene class) passes far_m. When the focus point is itself near the background
   (r_f - r_bg < 0.1) the span is floored at 0.1 so the foreground still gets a finite scale.
   Phone gap [PLAN 19.5, "add the difference, not the whole blur"]: the phone's own disc at the same framing is
   b_p = b * N / N_phone (N_phone = the phone's full-frame-equivalent f-number for the crop being rendered); the added
   blur is sqrt(max(0, b^2 - b_p^2)) (Gaussian-like blurs add in quadrature). Cap: diameter <= max_coc (8 % of W).
   Matte: when the focus point is on a person (mask median >= 0.5) the person mask, limited to depths within 0.1-0.2 of
   r_f (smooth), pulls the blur to zero (the B30 mask is sharper at hair and glasses edges than the depth).
   Edge refinement [PLAN 7.4]: colour guided filter (He et al. 2010/2013, guide = the encoded RGB box-averaged to a
   working grid whose long side is <= work_max = 1024, window radius = two depth-map pixels on that grid (the depth's
   own uncertainty), eps 1e-3) on the signed radius map / cap. A colour guide, because subject/background edges are
   often iso-luminant (red jumper on green foliage). Certainty c = 1 - clamp(|grad depth| / 0.08) on the depth grid
   (and 1 - 4m(1 - m) of the mask when the focus is on a person; min with the B30 per-pixel confidence when given):
   the radius in the uncertain band around a depth edge is re-estimated from the certain pixels on the same side of
   the image edge, q = GF(c p) / GF(c) (blended to plain GF(p) where GF(c) < 0.05..0.2), which moves the soft,
   up-sampled depth edge onto the image edge (hair, glasses frames).

3. Kernel [PLAN 19.5]: 9 rounded blades (one vertex up). Boundary radius
       R(theta) = r (1 - beta (1 - cos(pi/9) / cos(phi))),  phi = ((theta - pi/2) mod 2pi/9) - pi/9,
   beta = min(0.5, 0.25 log2(N / 2.8)) [ESTIMATE: "near-circular" at f/2.8 (beta 0), the polygon shows slightly when
   stopped down; at most 3 % flat depth]. Illumination: even with a faint brighter rim, (1 + 0.15) S(r) - 0.15 S(0.8 r)
   [ESTIMATE "faint"], no rings; the rim is skipped below a 4 px (output) radius. Cat's-eye [PLAN 19.5 ESTIMATE]: the shape is
   intersected with two discs of radius r centred at +-h u (u the unit radial direction), radial width fraction
   w = 1 - (1 - w_c) |v| t, h = r (1 - w); |v| = distance from the frame centre / half-diagonal; w_c = 0.65 at 24 mm
   (60-70 %) to 0.80 at 70 mm (75-85 %), linear in focal; t = clamp(log2(5.6 / N), 0, 1): full at f/2.8, half at f/4,
   round by f/5.6 (PLAN: "round by f/4-f/5.6", tracking the 19.2 mechanical vignetting jump).
   Discretisation: each pixel row dy in [-K, K] (K = ceil(r) + 1) is one span: chords at 4 sub-rows dy + {-3, -1, 1,
   3} / 8 averaged (row weight = non-empty sub-rows / 4); horizontal coverage exact for the span. Normalised to sum 1.
   Gather: out(x) = sum K(dx, dy) in(x - dx, y - dy), zero outside the image.

4. Linear light [PLAN 7.4]: the tone curve (the native RenderSpec table on log2 gained linear values) is inverted per
   channel (monotone envelope, piecewise linear in log2; a 1025-entry table on the encoded value, interpolated
   linearly). With a B25 gain map (mean log2 HDR gain per cell, any resolution, bilinear) the luma above the knee is
   recovered from the HDR continuation L_hdr = L_k (x / x_k)^p (x_k = 2^knee, L = sRGB-decoded curve output, p its
   slope): x_hdr = x_k (L_sdr 2^g / L_k)^(1/p), the pixel scaled by max(1, x_hdr / x) (cap 2^8). Without a gain map
   clipped values stay at the curve's top (2^curveHi). The blur is rendered on these values and the curve re-applied.
   The 3D LUT after the curve is not inverted (it is close to identity in tone; this is an approximation).

5. Layers ("scatter as gather" with depth order): signed radius rho (> 0 behind focus, < 0 in front). Ladder of radii
   R_k = 2^((k - 1) / 2) for k <= 8 (1 .. 11.3 px), then quarter-octave steps 11.3 * 2^((k - 8) / 4), up to the cap;
   a pixel's |rho| < 0.25 snaps to 0; below 1 it splits between the sharp layer (w0 = 1 - rho^2) and layer 1; above,
   between the two ladder layers around it, linearly in area (rho^2), so the blended disc has rho's second moment.
   The full-resolution radius is quantised to 1/64 px (int16, as the native code stores it). Each layer is gathered
   with its kernel at level q (largest power of two with R_k / q >= 2, at least 1; B31.1, was 4) at radius
   sqrt((R_k / q)^2 - 1) for q > 1 (the down/up-sampling's own spread taken off the second moment): box
   down-sampled by q (partial edge boxes average what exists), gathered at that radius (kernel per 32 x 32 level-px
   tile when the cat's-eye is on, built at the tile centre), bilinearly up-sampled. Layers of one side are summed
   (premultiplied rgb + one alpha per colour lane): P_b, A_b behind, P_f, A_f in front. Composite, back to front, per
   colour lane l (each normalised by its own alpha, so a lane whose ladder position differs (CA) can never shift the
   colour at a layer or depth seam; B31.1):
       bgN = P_b / A_b (A_b > 1e-4), else P_f / A_f (A_f > 1e-4), else the pixel     (hole fill behind the subject)
             (a pixel that is itself in front: see "occluded background" below)
       mid = w0 c + (1 - w0) bgN                                                      (sharp layer over the background)
       out = A P_f / A_f + (1 - A) mid, A = min(A_f, 1)                              (front blur over everything)
   so the background never covers sharp foreground and the sharp subject never smears into the background; the front
   blur spreads over the subject (physically right). A pixel with w0 = 1 and max A_f < 1e-6 keeps its input exactly.
   B31.1 occluded background: a pixel in front is covered by the front blur and its own sharp share, cov = A_f + w0;
   out = (P_f + w0 c) / cov when cov >= 1, else P_f + w0 c + (1 - cov) H with H = P_b / A_b (A_b > 1e-4), else
   s R + (1 - s) P_f / A_f, s = smoothstep(0.02, 0.1, 1 - cov) smoothstep(0, 0.25, near), near = the share of non-front
   pixels in the fill pyramid's cell about the pixel's blur radius (level round(log2(|rho| / 8))): a dip in coverage
   between two front surfaces (inside a front object) is not a hole to the background. What a blurred foreground pixel lets show through
   near its edge is the scene behind it, not the foreground's own colour (which made the foreground blur end at a hard edge on the subject: it
   spread outward only). R is a push-pull fill of the non-front pixels (sharp and behind): their linear colour summed
   in 8 x 8 cells (partial edge cells count what exists), pushed to 1 x 1 by 2 x 2 sums, pulled back
   v_L = a_L m_L + (1 - a_L) up(v_L+1) (a_L = non-front share of the cell, m_L their mean), bilinear to full res.
6. Longitudinal CA [PLAN 19.5: magenta in front, green behind, 70 mm f/2.8 only]: on at |f - 70| < 0.5 and N < 2.85,
   the colour lanes' ladder positions use |rho| (1 + e): behind focus G +0.05, R/B -0.05; in front R/B +0.05,
   G -0.05 [ESTIMATE "slight"]; scales with the blur, faded in by smoothstep(4, 12 px) of the radius (B31.1: none on
   the partly blurred pixels of an in-focus/out-of-focus seam); the sharp weight uses the nominal radius, each lane its own alpha
   (B31.1: a shared alpha divided a lane's premultiplied sum by another lane's coverage, which blew up to saturated
   green/magenta where that coverage was small: the stair-stepped fringe of the first device run).
"""
from __future__ import annotations

import functools
import math
from dataclasses import dataclass, field

import numpy as np

FF_WIDTH_MM = 36.0
USABLE = 0.35
BG_PCT = 0.02
MIN_SPAN = 0.1
FOCUS_WIN = 0.02
PERSON_BAND = (0.1, 0.2)
GF_EPS = 1e-3
CERT_GRAD = 0.08
CERT_LO, CERT_HI = 0.05, 0.2
WORK_MAX = 1024
MAX_COC = 0.08
SNAP = 0.25
R0 = 2.0  # B31.1: was 4 (level radius 4..8); 2..4 with the second-moment shrink below, ~5x less gather work
TILE = 32
RIM = 0.15
RIM_INNER = 0.8
RIM_MIN_R = 4.0
BLADES = 9
CA_EPS = 0.05
CA_RAMP = (4.0, 12.0)  # B31.1: the CA fades in with the blur radius (px): none on the partly blurred seam pixels
HDR_CAP_EV = 8.0
INV_POINTS = 1024
KLUMA = (0.2126729, 0.7151522, 0.0721750)
# PLAN 19.5 background-at-infinity disc, % of frame width at f/2.8 (focal -> {s: %}).
TABLE_19_5 = {24: {1: 0.59, 2: 0.29, 4: 0.14}, 35: {1: 1.26, 2: 0.62, 4: 0.31},
              50: {1: 2.61, 2: 1.27, 4: 0.63}, 70: {1: 5.23, 2: 2.52, 4: 1.24}}


# ------------------------------------------------------------------------------------------------ optics

def coc_mm(f_mm: float, n: float, s_m: float, d_m: float) -> float:
    """Thin-lens blur disc diameter (mm) of an object at d_m (math.inf allowed) with focus at s_m."""
    s = s_m * 1000.0
    base = f_mm * f_mm / (n * (s - f_mm))
    if math.isinf(d_m):
        return base
    d = d_m * 1000.0
    return base * abs(d - s) / d


def coc_frac(f_mm: float, n: float, s_m: float, d_m: float = math.inf) -> float:
    """Blur disc diameter as a fraction of the frame width."""
    return coc_mm(f_mm, n, s_m, d_m) / FF_WIDTH_MM


def phone_factor(n: float, n_phone: float) -> float:
    """Share of the full-frame disc left to add after the phone's own blur (quadrature)."""
    return math.sqrt(max(0.0, 1.0 - (n / n_phone) ** 2))


def blade_beta(n: float) -> float:
    return min(0.5, max(0.0, 0.25 * math.log2(n / 2.8)))


def cat_corner_width(f_mm: float) -> float:
    return 0.65 + 0.15 * min(max((f_mm - 24.0) / 46.0, 0.0), 1.0)


def cat_strength(n: float) -> float:
    return min(max(math.log2(5.6 / n), 0.0), 1.0)


def ca_active(f_mm: float, n: float) -> bool:
    return abs(f_mm - 70.0) < 0.5 and n < 2.85


# ------------------------------------------------------------------------------------------------ kernel

def _blade_inside(x: float, y: float, r: float, beta: float) -> bool:
    rho = math.hypot(x, y)
    if rho == 0.0:
        return True
    sector = 2.0 * math.pi / BLADES
    th = math.atan2(y, x) - math.pi / 2.0
    phi = th - sector * math.floor(th / sector) - math.pi / BLADES
    edge = r * (1.0 - beta * (1.0 - math.cos(math.pi / BLADES) / math.cos(phi)))
    return rho <= edge


@functools.lru_cache(maxsize=1 << 16)
def _blade_chord(y: float, r: float, beta: float):
    if not _blade_inside(0.0, y, r, beta):
        return None
    ends = []
    for sgn in (1.0, -1.0):
        lo, hi = 0.0, r + 1.0
        for _ in range(40):
            mid = 0.5 * (lo + hi)
            if _blade_inside(sgn * mid, y, r, beta):
                lo = mid
            else:
                hi = mid
        ends.append(sgn * 0.5 * (lo + hi))
    return ends[1], ends[0]


def _disc_chord(y: float, cx: float, cy: float, r: float):
    dy = y - cy
    if abs(dy) > r:
        return None
    half = math.sqrt(r * r - dy * dy)
    return cx - half, cx + half


def shape_chord(y: float, r: float, beta: float, h: float, ux: float, uy: float):
    """Row chord (xl, xr) of the aperture at height y, or None."""
    c = _blade_chord(y, r, beta)
    if c is None:
        return None
    xl, xr = c
    if h > 0.0:
        for sgn in (1.0, -1.0):
            d = _disc_chord(y, sgn * h * ux, sgn * h * uy, r)
            if d is None:
                return None
            xl, xr = max(xl, d[0]), min(xr, d[1])
        if xr <= xl:
            return None
    return xl, xr


def kernel_terms(r: float, beta: float, h: float, ux: float, uy: float, r_out: float | None = None):
    """Span terms (dy, xl, xr, weight) of the normalised kernel at level radius r (rim from the output radius r_out)."""
    k = int(math.ceil(r)) + 1
    shapes = [(1.0, 1.0)]
    if (r if r_out is None else r_out) >= RIM_MIN_R:
        shapes = [(1.0, 1.0 + RIM), (RIM_INNER, -RIM)]
    terms = []
    for scale, wt in shapes:
        rs, hs = r * scale, h * scale
        for dy in range(-k, k + 1):
            n, sl, sr = 0, 0.0, 0.0
            for sub in (-0.375, -0.125, 0.125, 0.375):
                c = shape_chord(dy + sub, rs, beta, hs, ux, uy)
                if c is not None:
                    n += 1
                    sl += c[0]
                    sr += c[1]
            if n:
                terms.append((dy, sl / n, sr / n, wt * n / 4.0))
    total = sum(w * (xr - xl) for _, xl, xr, w in terms)
    return [(dy, xl, xr, w / total) for dy, xl, xr, w in terms], k


def span_cov(xl: float, xr: float):
    """(lo, [coverage of dx = lo..hi])."""
    lo, hi = int(math.floor(xl + 0.5)), int(math.ceil(xr - 0.5))
    hi = max(hi, lo)
    return lo, [max(0.0, min(dx + 0.5, xr) - max(dx - 0.5, xl)) for dx in range(lo, hi + 1)]


def kernel_dense(r: float, beta: float, h: float = 0.0, ux: float = 1.0, uy: float = 0.0,
                 r_out: float | None = None) -> np.ndarray:
    terms, k = kernel_terms(r, beta, h, ux, uy, r_out)
    kk = np.zeros((2 * k + 1, 2 * k + 1))
    for dy, xl, xr, w in terms:
        lo, cov = span_cov(xl, xr)
        for i, c in enumerate(cov):
            kk[dy + k, lo + i + k] += w * c
    return kk


def cat_h(r: float, f_mm: float, n: float, vx: float, vy: float):
    """(h, ux, uy) for a kernel of level radius r at normalised frame position v."""
    t = cat_strength(n)
    m = math.hypot(vx, vy)
    if t <= 0.0 or m < 1e-6:
        return 0.0, 1.0, 0.0
    m1 = min(m, 1.0)
    w = 1.0 - (1.0 - cat_corner_width(f_mm)) * m1 * t
    return r * (1.0 - w), vx / m, vy / m


# ------------------------------------------------------------------------------------------------ tone

@dataclass
class Tone:
    table: np.ndarray
    lo: float
    hi: float
    knee_ev: float = -1.5
    slope: float = 0.0  # B25 slope p (0: no HDR)


def curve_fwd(t: Tone, x: np.ndarray) -> np.ndarray:
    """raw_pipeline Curve::atX."""
    tab = t.table.astype(np.float64)
    n = tab.size
    x = np.asarray(x, np.float64)
    floor = 2.0 ** t.lo
    out = np.zeros_like(x)
    pos = x > 0
    small = pos & (x < floor)
    out[small] = np.minimum(x[small] / floor * tab[0], 1.0)
    big = pos & ~small
    f = (np.log2(np.where(big, x, 1.0)) - t.lo) * ((n - 1) / (t.hi - t.lo))
    top = big & (f >= n - 1)
    out[top] = min(max(tab[-1], 0.0), 1.0)
    mid = big & ~top
    i = np.clip(np.floor(f).astype(int), 0, n - 2)
    u = f - i
    y = tab[i] + (tab[np.minimum(i + 1, n - 1)] - tab[i]) * u
    out[mid] = np.clip(y[mid], 0.0, 1.0)
    return out


def inverse_table(t: Tone) -> np.ndarray:
    """x at v = j / INV_POINTS, j = 0..INV_POINTS."""
    tab = t.table.astype(np.float64)
    n = tab.size
    env = np.maximum.accumulate(tab) + np.arange(n) * 1e-9
    grid = t.lo + (t.hi - t.lo) * np.arange(n) / (n - 1)
    out = np.zeros(INV_POINTS + 1)
    for j in range(INV_POINTS + 1):
        v = j / INV_POINTS
        if v <= env[0]:
            out[j] = (v / env[0] if env[0] > 0 else 1.0) * 2.0 ** t.lo
        elif v >= env[-1]:
            out[j] = 2.0 ** t.hi
        else:
            i = int(np.searchsorted(env, v, side="right")) - 1
            e = grid[i] + (grid[i + 1] - grid[i]) * (v - env[i]) / (env[i + 1] - env[i])
            out[j] = 2.0 ** e
    return out


def curve_inv(inv: np.ndarray, v: np.ndarray) -> np.ndarray:
    v = np.clip(np.asarray(v, np.float64), 0.0, 1.0) * INV_POINTS
    j = np.minimum(np.floor(v).astype(int), INV_POINTS - 1)
    return inv[j] + (inv[j + 1] - inv[j]) * (v - j)


def srgb_decode(v: np.ndarray) -> np.ndarray:
    v = np.clip(v, 0.0, 1.0)
    return np.where(v <= 0.04045, v / 12.92, ((v + 0.055) / 1.055) ** 2.4)


def linearise(enc: np.ndarray, t: Tone, gain_log2: np.ndarray | None = None) -> np.ndarray:
    x = curve_inv(inverse_table(t), enc)
    if gain_log2 is not None and t.slope > 0:
        h, w = enc.shape[:2]
        g = resize_bilinear(gain_log2.astype(np.float64), w, h)
        y = x[..., 0] * KLUMA[0] + x[..., 1] * KLUMA[1] + x[..., 2] * KLUMA[2]
        xk = 2.0 ** t.knee_ev
        lk = float(srgb_decode(curve_fwd(t, np.array([xk])))[0])
        lsdr = srgb_decode(curve_fwd(t, y))
        on = (g > 0) & (y > xk)
        yh = xk * np.power(np.maximum(lsdr * np.exp2(np.where(on, g, 0.0)) / lk, 1e-12), 1.0 / t.slope)
        s = np.where(on, np.clip(yh / np.maximum(y, 1e-12), 1.0, 2.0 ** HDR_CAP_EV), 1.0)
        x = x * s[..., None]
    return x


# ------------------------------------------------------------------------------------------------ maps

def resize_bilinear(src: np.ndarray, out_w: int, out_h: int) -> np.ndarray:
    """float [h, w] -> [out_h, out_w], half-pixel centres, clamped edges (as depth_post)."""
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
    return a * (1 - wy) + b * wy


def box_down(img: np.ndarray, q: int) -> np.ndarray:
    """[H, W, C] -> [ceil(H/q), ceil(W/q), C], partial edge boxes average what exists."""
    if q == 1:
        return img.copy()
    h, w, c = img.shape
    hq, wq = -(-h // q), -(-w // q)
    pad = np.zeros((hq * q, wq * q, c))
    cnt = np.zeros((hq * q, wq * q, 1))
    pad[:h, :w] = img
    cnt[:h, :w] = 1
    s = pad.reshape(hq, q, wq, q, c).sum(axis=(1, 3))
    n = cnt.reshape(hq, q, wq, q, 1).sum(axis=(1, 3))
    return s / n


def up_bilinear(img: np.ndarray, q: int, w: int, h: int) -> np.ndarray:
    """Level image -> full res: x_level = (x + 0.5) / q - 0.5, clamped."""
    if q == 1:
        return img
    hl, wl = img.shape[:2]
    fy = np.clip((np.arange(h) + 0.5) / q - 0.5, 0, hl - 1)
    fx = np.clip((np.arange(w) + 0.5) / q - 0.5, 0, wl - 1)
    y0 = np.minimum(np.floor(fy).astype(int), hl - 1)
    x0 = np.minimum(np.floor(fx).astype(int), wl - 1)
    y1 = np.minimum(y0 + 1, hl - 1)
    x1 = np.minimum(x0 + 1, wl - 1)
    wy = (fy - y0)[:, None, None]
    wx = (fx - x0)[None, :, None]
    a = img[y0][:, x0] * (1 - wx) + img[y0][:, x1] * wx
    b = img[y1][:, x0] * (1 - wx) + img[y1][:, x1] * wx
    return a * (1 - wy) + b * wy


def box_mean(f: np.ndarray, r: int) -> np.ndarray:
    """(2r+1)^2 mean with a clamped window (divided by the pixels inside)."""
    h, w = f.shape
    c = np.zeros((h + 1, w + 1))
    c[1:, 1:] = np.cumsum(np.cumsum(f, 0), 1)
    y0 = np.clip(np.arange(h) - r, 0, h)
    y1 = np.clip(np.arange(h) + r + 1, 0, h)
    x0 = np.clip(np.arange(w) - r, 0, w)
    x1 = np.clip(np.arange(w) + r + 1, 0, w)
    s = c[y1][:, x1] - c[y0][:, x1] - c[y1][:, x0] + c[y0][:, x0]
    n = (y1 - y0)[:, None] * (x1 - x0)[None, :]
    return s / n


def guided_filter(guide: np.ndarray, ps: list[np.ndarray], r: int, eps: float) -> list[np.ndarray]:
    """He et al. colour guided filter: guide [h, w, 3], each p [h, w] (the guide's inverse covariance is shared)."""
    h, w = guide.shape[:2]
    mi = [box_mean(guide[..., c], r) for c in range(3)]
    sig = np.zeros((h, w, 3, 3))
    for i in range(3):
        for j in range(i, 3):
            v = box_mean(guide[..., i] * guide[..., j], r) - mi[i] * mi[j]
            sig[..., i, j] = v
            sig[..., j, i] = v
    inv = np.linalg.inv(sig + eps * np.eye(3))
    out = []
    for p in ps:
        mp = box_mean(p, r)
        cov_ip = np.stack([box_mean(guide[..., c] * p, r) - mi[c] * mp for c in range(3)], axis=-1)
        a = np.einsum("hwij,hwj->hwi", inv, cov_ip)
        b = mp - (a[..., 0] * mi[0] + a[..., 1] * mi[1] + a[..., 2] * mi[2])
        q = box_mean(b, r)
        for c in range(3):
            q = q + box_mean(a[..., c], r) * guide[..., c]
        out.append(q)
    return out


def grad_mag(f: np.ndarray) -> np.ndarray:
    """Central differences, clamped edges (as depth_post.grad_mag)."""
    p = np.pad(f.astype(np.float64), 1, mode="edge")
    gx = (p[1:-1, 2:] - p[1:-1, :-2]) * 0.5
    gy = (p[2:, 1:-1] - p[:-2, 1:-1]) * 0.5
    return np.sqrt(gx * gx + gy * gy)


def pct(a: np.ndarray, q: float) -> float:
    s = np.sort(a.ravel())
    return float(s[int(np.floor(q * (s.size - 1) + 0.5))])


def smoothstep(a: float, b: float, x):
    t = np.clip((np.asarray(x, np.float64) - a) / (b - a), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def focus_sample(m: np.ndarray, fx: float, fy: float) -> float:
    """Median of m in a (2R+1)^2 window at the focus point (window kept inside the map)."""
    h, w = m.shape
    r = max(1, int(math.floor(FOCUS_WIN * max(w, h) + 0.5)))
    r = min(r, (w - 1) // 2, (h - 1) // 2)
    cx = min(max(int(math.floor(fx * w)), r), w - 1 - r)
    cy = min(max(int(math.floor(fy * h)), r), h - 1 - r)
    win = np.sort(m[cy - r:cy + r + 1, cx - r:cx + r + 1].ravel())
    return float(win[(win.size - 1) // 2])


# ------------------------------------------------------------------------------------------------ render

@dataclass
class Params:
    focal_mm: float
    aperture: float
    focus_m: float
    focus_x: float = 0.5
    focus_y: float = 0.5
    phone_n: float = 6.0
    far_m: float = math.inf
    max_coc: float = MAX_COC
    work_max: int = WORK_MAX
    confidence: float = 1.0
    refine: bool = True  # guided filter (off only for tests)


@dataclass
class Result:
    applied: bool
    reason: str = ""
    image: np.ndarray | None = None
    radius: np.ndarray | None = None  # signed radius map, output px
    stats: dict = field(default_factory=dict)


LADDER_FINE = 8  # from layer 8 (R = 2^3.5 = 11.3 px) on, quarter-octave steps


def ladder_radius(k: int) -> float:
    if k <= 0:
        return 0.0
    if k <= LADDER_FINE:
        return 2.0 ** ((k - 1) / 2.0)
    return 2.0 ** 3.5 * 2.0 ** ((k - LADDER_FINE) / 4.0)


def ladder_max(cap_r: float) -> int:
    k = 1
    while ladder_radius(k) < cap_r:
        k += 1
    return k


def ladder_pos(a: np.ndarray, kmax: int) -> np.ndarray:
    """Fractional ladder position u of radius a (>= 0): a^2 below 1 (sharp layer 0 to layer 1), else k + the share of
    the area between R_k and R_k+1 (area-linear, so the blend of the two discs has the radius' second moment)."""
    a = np.asarray(a, np.float64)
    r = np.array([ladder_radius(k) for k in range(kmax + 2)])
    big = a >= 1.0
    sa = np.where(big, a, 1.0)
    k = np.where(sa < 2.0 ** 3.5, 1 + np.floor(2.0 * np.log2(sa)), LADDER_FINE + np.floor(4.0 * np.log2(sa / 2.0 ** 3.5)))
    k = np.clip(k.astype(int), 1, kmax)
    for _ in range(2):
        k = np.where((k < kmax) & (r[np.minimum(k + 1, kmax + 1)] <= sa), k + 1, k)
        k = np.where((k > 1) & (r[k] > sa), k - 1, k)
    rk, rk1 = r[k], r[np.minimum(k + 1, kmax + 1)]
    fr = (sa * sa - rk * rk) / (rk1 * rk1 - rk * rk)
    u = np.where(k >= kmax, float(kmax), k + np.clip(fr, 0.0, 1.0))
    return np.where(big, u, a * a)


def level_radius(rk: float, q: int) -> float:
    """B31.1: the level kernel radius. Box down-sampling (variance q^2/12) and bilinear up-sampling (q^2/6) widen a
    disc gathered at level q by q^2/4 per axis (a uniform disc of radius R has R^2/4): shrink it to keep the rendered
    second moment, sqrt((R/q)^2 - 1) for q > 1 (at least half of R/q)."""
    r = rk / q
    return math.sqrt(max(r * r - 1.0, 0.25 * r * r)) if q > 1 else r


def level_of(rk: float) -> int:
    q = 1
    while rk / (2 * q) >= R0:
        q *= 2
    return q


def radius_map(depth: np.ndarray, mask: np.ndarray | None, enc: np.ndarray, p: Params,
               conf_map: np.ndarray | None = None) -> tuple[np.ndarray, dict]:
    """Signed blur radius (output px) on the working grid; returns (map [hh, ww], info)."""
    h, w = enc.shape[:2]
    fw = max(1, -(-max(w, h) // p.work_max))
    work = box_down(enc.astype(np.float64), fw)
    hh, ww = work.shape[:2]
    dep = depth.astype(np.float64)
    r_f = focus_sample(dep, p.focus_x, p.focus_y)
    r_bg = pct(dep, BG_PCT)
    d_s = 1.0 / p.focus_m
    d_far = 0.0 if (math.isinf(p.far_m) or p.far_m <= p.focus_m) else 1.0 / p.far_m
    a = (d_s - d_far) / max(r_f - r_bg, MIN_SPAN)
    rw = resize_bilinear(dep, ww, hh)
    dd = np.maximum(d_s + a * (rw - r_f), 0.0)
    f = p.focal_mm
    base = f * f / (p.aperture * (p.focus_m * 1000.0 - f))
    b = base * (d_s - dd) / d_s * phone_factor(p.aperture, p.phone_n)
    cap = p.max_coc * w / 2.0
    rho = np.clip(b / FF_WIDTH_MM * w / 2.0, -cap, cap)
    on_person = False
    if mask is not None:
        mk = mask.astype(np.float64)
        if focus_sample(mk, p.focus_x, p.focus_y) >= 0.5:
            on_person = True
            m = resize_bilinear(mk, ww, hh) * (1.0 - smoothstep(PERSON_BAND[0], PERSON_BAND[1], np.abs(rw - r_f)))
            rho = rho * (1.0 - np.clip(m, 0.0, 1.0))
    gr = max(1, int(math.floor(2.0 * max(ww / dep.shape[1], hh / dep.shape[0]) + 0.5)))
    if p.refine:
        # certainty of the radius map: low on depth edges (the up-sampled depth is soft and may be off by a pixel) and
        # on an undecided person mask (as the B30 per-pixel confidence); the uncertain band is filled edge-aware from
        # the certain pixels on the same side of the image edge (normalised guided filter), the rest guided as is
        cert = 1.0 - np.clip(grad_mag(dep) / CERT_GRAD, 0.0, 1.0)
        if on_person:
            cert = np.minimum(cert, 1.0 - 4.0 * mk * (1.0 - mk))
        if conf_map is not None:
            cert = np.minimum(cert, conf_map.astype(np.float64))
        c = np.clip(resize_bilinear(cert, ww, hh), 0.0, 1.0)
        pn = rho / cap
        q_p, q_cp, q_c = guided_filter(work, [pn, c * pn, c], gr, GF_EPS)
        t = smoothstep(CERT_LO, CERT_HI, q_c)
        q = t * q_cp / np.maximum(q_c, CERT_LO) + (1.0 - t) * q_p
        rho = np.clip(q * cap, -cap, cap)
    info = dict(r_f=r_f, r_bg=r_bg, scale=a, d_far=d_far, cap=cap, work=(ww, hh), on_person=on_person, gf_r=gr)
    return rho, info


def layer_weights(rho: np.ndarray, kmax: int, ca: bool) -> tuple[np.ndarray, np.ndarray]:
    """rho [H, W] -> (w0 [H, W], weights [kmax + 1, H, W, 4]) on the pixel's own side (lanes r, g, b, alpha)."""
    a = np.abs(rho)
    a = np.where(a < SNAP, 0.0, a)
    w0 = np.maximum(0.0, 1.0 - ladder_pos(a, kmax))
    wts = np.zeros((kmax + 1,) + rho.shape + (4,))
    behind = rho > 0
    for lane in range(4):
        al = a
        if ca and lane < 3:
            e_b = CA_EPS if lane == 1 else -CA_EPS
            e = np.where(behind, e_b, -e_b) * smoothstep(CA_RAMP[0], CA_RAMP[1], a)
            al = np.where(a >= 1.0, np.maximum(a * (1.0 + e), 1.0), a)
        u = ladder_pos(al, kmax)
        for kk in range(1, kmax + 1):
            wts[kk, ..., lane] = np.where(a > 0, np.maximum(0.0, 1.0 - np.abs(u - kk)), 0.0)
    return w0, wts


def gather(src: np.ndarray, r: float, beta: float, f_mm: float, n: float, q: int, w: int, h: int,
           r_out: float | None = None) -> np.ndarray:
    """Gather a level image [hl, wl, C] with the aperture at level radius r (cat's-eye per tile)."""
    hl, wl = src.shape[:2]
    out = np.zeros_like(src)
    hd = math.hypot(w, h) / 2.0
    tiled = cat_strength(n) > 0
    tiles_y = range(0, hl, TILE) if tiled else [0]
    tiles_x = range(0, wl, TILE) if tiled else [0]
    for ty in tiles_y:
        for tx in tiles_x:
            if tiled:
                vx = ((tx + TILE / 2.0) * q - w / 2.0) / hd
                vy = ((ty + TILE / 2.0) * q - h / 2.0) / hd
                hh, ux, uy = cat_h(r, f_mm, n, vx, vy)
                y0, y1, x0, x1 = ty, min(ty + TILE, hl), tx, min(tx + TILE, wl)
            else:
                hh, ux, uy = 0.0, 1.0, 0.0
                y0, y1, x0, x1 = 0, hl, 0, wl
            kd = kernel_dense(r, beta, hh, ux, uy, r_out)
            k = kd.shape[0] // 2
            pad = np.zeros((hl + 2 * k, wl + 2 * k, src.shape[2]))
            pad[k:k + hl, k:k + wl] = src
            acc = np.zeros((y1 - y0, x1 - x0, src.shape[2]))
            for iy in range(2 * k + 1):
                for ix in range(2 * k + 1):
                    wgt = kd[iy, ix]
                    if wgt == 0.0:
                        continue
                    dy, dx = iy - k, ix - k
                    acc += wgt * pad[y0 - dy + k:y1 - dy + k, x0 - dx + k:x1 - dx + k]
            out[y0:y1, x0:x1] = acc
    return out


def render(enc: np.ndarray, depth: np.ndarray, mask: np.ndarray | None, tone: Tone, p: Params,
           gain_log2: np.ndarray | None = None) -> Result:
    """enc [H, W, 3] encoded 0..1 -> Result (image [H, W, 3] encoded)."""
    if not p.confidence >= USABLE:
        return Result(False, f"depth confidence {p.confidence:.2f} below {USABLE}")
    k_phone = phone_factor(p.aperture, p.phone_n)
    if k_phone <= 0.0:
        return Result(False, f"phone depth of field (f/{p.phone_n:g} equivalent) already as deep as f/{p.aperture:g}")
    h, w = enc.shape[:2]
    rho_w, info = radius_map(depth, mask, enc, p)
    rho = np.clip(np.floor(resize_bilinear(rho_w, w, h) * 64.0 + 0.5), -32767, 32767) / 64.0  # int16, 1/64 px
    cap = info["cap"]
    kmax = ladder_max(cap)
    ca = ca_active(p.focal_mm, p.aperture)
    beta = blade_beta(p.aperture)
    x = linearise(enc.astype(np.float64), tone, gain_log2)
    w0, wts = layer_weights(rho, kmax, ca)
    # premultiplied rgb, then one alpha per colour lane (r, g, b)
    acc = {1: np.zeros((h, w, 6)), -1: np.zeros((h, w, 6))}
    for side in (1, -1):
        sel = (rho > 0) if side > 0 else (rho < 0)
        for kk in range(1, kmax + 1):
            wk = np.where(sel[..., None], wts[kk][..., :3], 0.0)
            if not wk.any():
                continue
            lay = np.concatenate([wk * x, wk], axis=2)
            rk = ladder_radius(kk)
            q = level_of(rk)
            lv = box_down(lay, q)
            g = gather(lv, level_radius(rk, q), beta, p.focal_mm, p.aperture, q, w, h, rk)
            acc[side] += up_bilinear(g, q, w, h)
    pb, pf = acc[1], acc[-1]
    ab, af = pb[..., 3:6], pf[..., 3:6]
    front = (rho < 0) & (np.abs(rho) >= SNAP)
    rear, near = rear_fill(x, front, rho) if front.any() else (x, np.zeros(rho.shape))
    fgn = np.where(af > 1e-4, pf[..., :3] / np.maximum(af, 1e-12), x)
    bgb = pb[..., :3] / np.maximum(ab, 1e-12)
    bgn = np.where(ab > 1e-4, bgb, fgn)
    mid = w0[..., None] * x + (1.0 - w0[..., None]) * bgn
    a_f = np.minimum(af, 1.0)  # B31.1: overlapping front spreads (A_f > 1) are normalised, never subtract the background
    out = a_f * fgn + (1.0 - a_f) * mid
    # B31.1, a pixel that is itself in front: its own sharp share w0 and the front blur cover it (cov = A_f + w0); what
    # shows through the rest is the scene behind it: the behind blur, else the occluded background R, faded in only
    # where the coverage really falls off (its edge; deep inside, cov wobbles around 1 and the front colour stays)
    cov = af + w0[..., None]
    s_edge = smoothstep(REAR_EDGE[0], REAR_EDGE[1], 1.0 - cov) * smoothstep(0.0, REAR_NEAR, near)[..., None]
    hid = np.where(ab > 1e-4, bgb, s_edge * rear + (1.0 - s_edge) * fgn)
    own = pf[..., :3] + w0[..., None] * x
    out_f = np.where(cov >= 1.0, own / np.maximum(cov, 1e-12), own + (1.0 - cov) * hid)
    out = np.where(front[..., None], out_f, out)
    res = curve_fwd(tone, out)
    keep = (w0 >= 1.0) & (af.max(axis=2) < 1e-6)
    res = np.where(keep[..., None], enc, res)
    info.update(kmax=kmax, ca=ca, beta=beta, phone_factor=k_phone, max_radius=float(np.abs(rho).max()),
                front=bool(front.any()))
    return Result(True, "", res, rho, info)


REAR_Q = 8
REAR_EDGE = (0.02, 0.1)  # 1 - coverage over which the occluded background fades in
REAR_NEAR = 0.25  # share of non-front pixels near the pixel (at its blur scale) for it in full


def box_sum(img: np.ndarray, q: int) -> np.ndarray:
    """[H, W, C] -> [ceil(H/q), ceil(W/q), C] sums (partial edge boxes sum what exists)."""
    h, w, c = img.shape
    hq, wq = -(-h // q), -(-w // q)
    pad = np.zeros((hq * q, wq * q, c))
    pad[:h, :w] = img
    return pad.reshape(hq, q, wq, q, c).sum(axis=(1, 3))


def nearest_fill(cells: np.ndarray) -> np.ndarray:
    """B40: [H, W, 5] cell sums (C r g b, W, N) -> [H, W, 3] mean non-front colour, an empty cell (W = 0) taking its
    nearest non-empty cell's: two-pass chamfer propagation of the nearest source (exact squared cell distances, strict
    improvement only, fixed neighbour order), as bokeh.cpp does. The B31.1 push-pull blended every cell with its coarser
    levels by the non-front share, so behind a front edge it pulled in colour from far away (a bright wall beyond a
    mid-grey door) and lit a rim brighter than both the subject and the background beside it."""
    ch, cw = cells.shape[:2]
    src = np.where(cells[..., 3].reshape(-1) > 0, np.arange(ch * cw), -1)

    def d2(i: int, j: int) -> int:
        dx, dy = i % cw - j % cw, i // cw - j // cw
        return dx * dx + dy * dy

    def relax(i: int, nx: int, ny: int) -> None:
        if nx < 0 or ny < 0 or nx >= cw or ny >= ch:
            return
        c = src[ny * cw + nx]
        if c < 0:
            return
        if src[i] < 0 or d2(i, c) < d2(i, src[i]):
            src[i] = c

    for y in range(ch):
        for x in range(cw):
            i = y * cw + x
            relax(i, x - 1, y)
            relax(i, x - 1, y - 1)
            relax(i, x, y - 1)
            relax(i, x + 1, y - 1)
    for y in range(ch - 1, -1, -1):
        for x in range(cw - 1, -1, -1):
            i = y * cw + x
            relax(i, x + 1, y)
            relax(i, x + 1, y + 1)
            relax(i, x, y + 1)
            relax(i, x - 1, y + 1)
    flat = cells.reshape(-1, 5)
    out = np.zeros((ch * cw, 3))
    ok = src >= 0
    out[ok] = flat[src[ok], :3] / flat[src[ok], 3:4]
    return out.reshape(ch, cw, 3)


def rear_fill(x: np.ndarray, front: np.ndarray, rho: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """B31.1: the occluded background behind front pixels (B40: each empty 8 x 8 cell takes its nearest non-empty
    cell's mean non-front linear colour, see nearest_fill), and the share of non-front pixels near each pixel (the
    pyramid level whose cell is about the pixel's blur radius): a front
    pixel with no non-front content within its blur is inside a front object, and nothing behind shows through it."""
    h, w = front.shape
    m = (~front).astype(np.float64)[..., None]
    lv = box_sum(np.concatenate([x * m, m, np.ones_like(m)], axis=2), REAR_Q)  # C (3), W, N
    levels = [lv]
    while lv.shape[0] > 1 or lv.shape[1] > 1:
        lv = box_sum(lv, 2)
        levels.append(lv)
    v = nearest_fill(levels[0])
    rear = up_bilinear(v, REAR_Q, w, h)
    sel = np.clip(np.floor(np.log2(np.maximum(np.abs(rho), 1.0) / REAR_Q) + 0.5), 0, len(levels) - 1).astype(int)
    near = np.zeros((h, w))
    for li in np.unique(sel):
        lvl = levels[li]
        share = up_bilinear((lvl[..., 3] / lvl[..., 4])[..., None], REAR_Q * 2 ** int(li), w, h)[..., 0]
        near = np.where(sel == li, share, near)
    return rear, near
