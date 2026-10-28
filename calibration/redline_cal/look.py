"""Fit the "scene-linear XYZ -> Standard sRGB JPEG" look (docs/PLAN.md 9.1).

Model, applied in order (this is exactly what the app's GPU shader runs):
  1. linear sRGB = XYZ_TO_SRGB @ XYZ (white-balanced scene XYZ, D65)
  2. per-channel tone curve T: linear -> encoded [0,1], sampled on a log2 grid
  3. 33^3 residual LUT R indexed by the tone-mapped RGB -> final sRGB (encoded)

T is fitted on the neutral response (luminance), R absorbs everything else
(hue twists, saturation, cross-channel tone). R is solved as a smoothness-
regularized least-squares problem and pulled towards identity, so colors the
dataset never shows fall back to the tone curve alone.
"""
from __future__ import annotations

import json
from dataclasses import dataclass

import numpy as np
from scipy import sparse
from scipy.sparse.linalg import cg

from .color import XYZ_TO_SRGB, delta_e2000, skin_mask, srgb_encode, srgb_decode, srgb_encoded_to_lab
from .lut import apply_lut, identity_lut

EV_MIN, EV_MAX, TONE_POINTS = -14.0, 3.0, 257


@dataclass
class Look:
    tone: np.ndarray  # (TONE_POINTS,) encoded output at log2 grid EV_MIN..EV_MAX
    lut: np.ndarray   # (n, n, n, 3)

    @property
    def tone_ev(self) -> np.ndarray:
        return np.linspace(EV_MIN, EV_MAX, len(self.tone))

    def apply_tone(self, lin: np.ndarray) -> np.ndarray:
        ev = np.log2(np.clip(lin, 2.0**EV_MIN, None))
        out = np.interp(ev, self.tone_ev, self.tone)
        # below the grid: linear toe through the origin
        floor = 2.0**EV_MIN
        return np.where(lin < floor, np.clip(lin, 0, None) / floor * self.tone[0], out)

    def base(self, xyz: np.ndarray) -> np.ndarray:
        return np.clip(self.apply_tone(xyz @ XYZ_TO_SRGB.T), 0.0, 1.0)

    def render(self, xyz: np.ndarray) -> np.ndarray:
        return np.clip(apply_lut(self.base(xyz), self.lut), 0.0, 1.0)

    def to_json(self) -> dict:
        return {
            "model": "xyz_d65 -> srgb_linear -> tone(log2) -> lut3d(srgb_encoded)",
            "tone": {"ev_min": EV_MIN, "ev_max": EV_MAX, "values": [round(float(v), 6) for v in self.tone]},
            "lut_size": int(self.lut.shape[0]),
        }


def fit_tone(xyz: np.ndarray, target_enc: np.ndarray, points: int = TONE_POINTS, min_count: int = 20) -> np.ndarray:
    """Neutral tone curve: robust median of target luminance per log2(Y) bin, made monotone."""
    y_in = np.clip(xyz[:, 1], 2.0**EV_MIN, None)
    y_out = srgb_encode(srgb_decode(target_enc) @ np.array([0.2126729, 0.7151522, 0.0721750]))
    ev = np.log2(y_in)
    grid = np.linspace(EV_MIN, EV_MAX, points)
    step = grid[1] - grid[0]
    idx = np.clip(np.round((ev - EV_MIN) / step).astype(int), 0, points - 1)
    med = np.full(points, np.nan)
    for i in np.unique(idx):
        sel = y_out[idx == i]
        if len(sel) >= min_count:
            med[i] = np.median(sel)
    known = ~np.isnan(med)
    if known.sum() < 4:
        raise ValueError("not enough tonal coverage to fit a tone curve")
    # fill gaps: interpolate inside, sRGB-shaped extrapolation outside
    tone = np.interp(grid, grid[known], med[known])
    lo, hi = np.argmax(known), points - 1 - np.argmax(known[::-1])
    if lo > 0:
        tone[:lo] = med[lo] * srgb_encode(2.0 ** grid[:lo]) / srgb_encode(2.0 ** grid[lo])
    tone[hi + 1 :] = np.minimum(1.0, med[hi] + (grid[hi + 1 :] - grid[hi]) * max(0.0, (1.0 - med[hi])) / max(1e-6, EV_MAX - grid[hi]))
    tone = np.convolve(np.pad(tone, 3, mode="edge"), np.ones(7) / 7, mode="valid")
    return np.clip(np.maximum.accumulate(tone), 0.0, 1.0)


def _laplacian_3d(n: int) -> sparse.csr_matrix:
    d1 = sparse.diags([np.ones(n - 1), -2 * np.ones(n), np.ones(n - 1)], [-1, 0, 1], format="lil")
    d1[0, 0] = d1[-1, -1] = -1  # Neumann boundary
    eye = sparse.identity(n)
    return (sparse.kron(sparse.kron(d1, eye), eye) + sparse.kron(sparse.kron(eye, d1), eye) + sparse.kron(sparse.kron(eye, eye), d1)).tocsr()


def _trilinear_weights(rgb: np.ndarray, n: int) -> sparse.csr_matrix:
    x = np.clip(rgb, 0, 1) * (n - 1)
    i0 = np.clip(np.floor(x).astype(int), 0, n - 2)
    f = x - i0
    rows, cols, vals = [], [], []
    m = np.arange(len(rgb))
    for dr in (0, 1):
        for dg in (0, 1):
            for db in (0, 1):
                w = (f[:, 0] if dr else 1 - f[:, 0]) * (f[:, 1] if dg else 1 - f[:, 1]) * (f[:, 2] if db else 1 - f[:, 2])
                node = ((i0[:, 0] + dr) * n + (i0[:, 1] + dg)) * n + (i0[:, 2] + db)
                rows.append(m)
                cols.append(node)
                vals.append(w)
    return sparse.csr_matrix((np.concatenate(vals), (np.concatenate(rows), np.concatenate(cols))), shape=(len(rgb), n**3))


def fit_residual_lut(
    base: np.ndarray, target: np.ndarray, n: int = 33, smooth: float = 2.0, prior: float = 1e-3, robust_iters: int = 0,
) -> np.ndarray:
    """Solve min |W D - (t - b_id)|^2 + smooth*|Lap D|^2 + prior*|D|^2 for D = LUT - identity.

    With ``robust_iters`` the data term is re-weighted (Huber, IRLS) so samples whose
    error comes from scene-specific tone, not the look, stop dragging the LUT off.
    """
    ident = identity_lut(n).reshape(-1, 3)
    W = _trilinear_weights(base, n)
    resid = target - W @ ident
    Lp = _laplacian_3d(n)
    scale = max(1.0, len(base) / n**3)  # keep regularization strength independent of sample count
    reg = smooth * scale * (Lp.T @ Lp) + prior * scale * sparse.identity(n**3)
    w = np.ones(len(base))
    D = np.zeros((n**3, 3))
    for it in range(robust_iters + 1):
        Ww = sparse.diags(w) @ W
        A = (W.T @ Ww + reg).tocsr()
        for c in range(3):
            D[:, c], _ = cg(A, Ww.T @ resid[:, c], x0=D[:, c], rtol=1e-8, maxiter=2000)
        if it < robust_iters:
            e = np.linalg.norm(W @ D - resid, axis=1)
            k = 1.5 * 1.4826 * np.median(e) + 1e-6
            w = np.minimum(1.0, k / np.maximum(e, 1e-12))
    return np.clip(ident + D, 0.0, 1.0).reshape(n, n, n, 3)


ROBUST_ITERS = 0  # set by the CLI's --robust


def fit_look(xyz: np.ndarray, target_enc: np.ndarray, n: int = 33, smooth: float = 2.0, robust_iters: int | None = None) -> Look:
    tone = fit_tone(xyz, target_enc)
    look = Look(tone=tone, lut=identity_lut(n))
    ri = ROBUST_ITERS if robust_iters is None else robust_iters
    look.lut = fit_residual_lut(look.base(xyz), target_enc, n=n, smooth=smooth, robust_iters=ri)
    return look


EXPOSURE_GRID = np.linspace(-1.0, 1.0, 41)
CENTRE_R = 0.35


def best_exposure(look: Look, xyz: np.ndarray, target_enc: np.ndarray, grid: np.ndarray = EXPOSURE_GRID) -> float:
    """Per-image exposure offset (EV) that best matches the target's lightness (median |dL*|)."""
    if len(xyz) == 0:
        return 0.0
    lt = srgb_encoded_to_lab(target_enc)[:, 0]
    errs = [np.median(np.abs(srgb_encoded_to_lab(look.render(xyz * 2.0**g))[:, 0] - lt)) for g in grid]
    return float(grid[int(np.argmin(errs))])


def implied_log2_gain(look: Look, xyz: np.ndarray, target_enc: np.ndarray) -> np.ndarray:
    """log2 of the gain on each sample that makes its tone-mapped green match the target's.

    Inverts the (monotone) tone curve on the green channel, which carries most luminance.
    """
    lin_g = np.clip((xyz @ XYZ_TO_SRGB.T)[:, 1], 2.0**EV_MIN, None)
    tone = np.maximum.accumulate(look.tone)
    t = np.clip(target_enc[:, 1], tone[0], tone[-1])
    ev_t = np.interp(t, tone + np.arange(len(tone)) * 1e-9, look.tone_ev)
    return ev_t - np.log2(lin_g)


@dataclass
class Gain:
    """Per-image nuisance gain: log2 g(r) = ev + k1 r^2 + k2 r^4 (r = normalised radius)."""

    ev: float = 0.0
    k1: float = 0.0
    k2: float = 0.0

    def apply(self, xyz: np.ndarray, r: np.ndarray | None) -> np.ndarray:
        if r is None:
            return xyz * 2.0**self.ev
        lg = self.ev + self.k1 * r**2 + self.k2 * r**4
        return xyz * (2.0**lg)[:, None]


def fit_gain(
    look: Look, xyz: np.ndarray, target_enc: np.ndarray, r: np.ndarray | None,
    exposure: bool = True, vignette: bool = True, iters: int = 5,
) -> Gain:
    """Robust (IRLS, Huber) fit of the per-image exposure and radial falloff.

    The camera JPEG has the lens's peripheral illumination corrected and its exposure set by
    the camera's metering/ALO; neither belongs to the colour look, and the phone handles both
    with its own lens shading and auto-exposure. Samples in the tone curve's flat ends carry
    no gain information and are ignored.
    """
    if len(xyz) < 8 or not (exposure or (vignette and r is not None)):
        return Gain()
    y = implied_log2_gain(look, xyz, target_enc)
    t = target_enc[:, 1]
    ok = (t > 0.05) & (t < 0.95) & np.isfinite(y)
    if ok.sum() < 8:
        return Gain()
    cols = []
    if exposure:
        cols.append(np.ones(ok.sum()))
    if vignette and r is not None:
        cols += [r[ok] ** 2, r[ok] ** 4]
    A = np.stack(cols, axis=1)
    yb = y[ok]
    w = np.ones(len(yb))
    p = np.zeros(A.shape[1])
    for _ in range(iters):
        sw = np.sqrt(w)
        p, *_ = np.linalg.lstsq(A * sw[:, None], yb * sw, rcond=None)
        res = np.abs(yb - A @ p)
        scale = 1.4826 * np.median(res) + 1e-6
        w = np.minimum(1.0, 1.5 * scale / np.maximum(res, 1e-12))
    i = 0
    g = Gain()
    if exposure:
        g.ev = float(p[0])
        i = 1
    if vignette and r is not None:
        g.k1, g.k2 = float(p[i]), float(p[i + 1])
    return g


def fit_look_aligned(
    images: list[tuple], n: int = 33, smooth: float = 2.0, iters: int = 3,
    exposure: bool = True, vignette: bool = False,
) -> tuple[Look, list[Gain]]:
    """Alternate between fitting the look and each training image's nuisance gain.

    ``images`` holds (xyz, target) or (xyz, target, radius) tuples.
    """
    imgs = [(im[0], im[1], im[2] if len(im) > 2 else None) for im in images]
    gains = [Gain() for _ in imgs]
    look = None
    centred = vignette and all(r is not None for _, _, r in imgs)
    for _ in range(iters):
        x = np.concatenate([g.apply(a, r) for (a, _, r), g in zip(imgs, gains)])
        y = np.concatenate([b for _, b, _ in imgs])
        if centred:
            # The falloff is only identifiable against a look anchored where there is none:
            # fit the look on the image centres, then the radial gains against it.
            c = np.concatenate([r for _, _, r in imgs]) < CENTRE_R
            if c.sum() > 1000:
                x, y = x[c], y[c]
        look = fit_look(x, y, n=n, smooth=smooth)
        # implied gains are relative to the raw samples, so each fit is the total gain
        gains = [fit_gain(look, a, b, r, exposure=exposure, vignette=vignette) for a, b, r in imgs]
        if exposure:  # keep the curve anchored to the camera's average metering
            m = float(np.mean([g.ev for g in gains]))
            for g in gains:
                g.ev -= m
    if centred:  # final look from every (now devignetted) sample
        x = np.concatenate([g.apply(a, r) for (a, _, r), g in zip(imgs, gains)])
        look = fit_look(x, np.concatenate([b for _, b, _ in imgs]), n=n, smooth=smooth)
    return look, gains


def evaluate(look: Look, xyz: np.ndarray, target_enc: np.ndarray) -> dict:
    """Hold-out metrics, with colour and lightness scored separately.

    ``color_*`` is CIEDE2000 with the prediction's L* replaced by the target's, i.e. the
    hue/chroma error alone. Lightness is scored by the median |dL*|, because the camera
    JPEG's per-scene tone adjustments make the lightness tail a property of the scene, not
    of the global look.
    """
    lab_t = srgb_encoded_to_lab(target_enc)
    lab_p = srgb_encoded_to_lab(look.render(xyz))
    de = delta_e2000(lab_p, lab_t)
    lab_c = lab_p.copy()
    lab_c[:, 0] = lab_t[:, 0]
    dc = delta_e2000(lab_c, lab_t)
    dl = np.abs(lab_p[:, 0] - lab_t[:, 0])
    sk = skin_mask(lab_t)
    return {
        "n": int(len(de)),
        "mean_de2000": float(de.mean()),
        "median_de2000": float(np.median(de)),
        "p95_de2000": float(np.percentile(de, 95)),
        "color_mean_de2000": float(dc.mean()),
        "median_abs_dL": float(np.median(dl)),
        "mean_abs_dL": float(dl.mean()),
        "skin_n": int(sk.sum()),
        "skin_mean_de2000": float(de[sk].mean()) if sk.any() else None,
        "skin_color_mean_de2000": float(dc[sk].mean()) if sk.any() else None,
    }


# skin colour is provisional at 2.5: the hold-out "skin" is a CIELAB colour box (it also
# catches wood, sand, brick); real skin is re-checked against the chart skin patches in B04.
GATE = {"color_mean": 2.5, "skin_color_mean": 2.5, "median_de": 2.0, "median_abs_dL": 2.0}


def passes_gate(report: dict, gate: dict = GATE) -> bool:
    """B03 gate (v2, 2026-10-28): colour mean < 2.5, skin colour < 2.5 (provisional), median dE and median |dL*| < 2.0."""
    skin = report.get("skin_color_mean_de2000")
    return (
        report["color_mean_de2000"] < gate["color_mean"]
        and (skin is None or skin < gate["skin_color_mean"])
        and report["median_de2000"] < gate["median_de"]
        and report["median_abs_dL"] < gate["median_abs_dL"]
    )


def save(look: Look, out_dir: str, title: str = "Redline Standard") -> None:
    import os

    from .lut import write_cube

    os.makedirs(out_dir, exist_ok=True)
    write_cube(os.path.join(out_dir, "standard_residual.cube"), look.lut, title=title)
    with open(os.path.join(out_dir, "standard_look.json"), "w") as f:
        json.dump(look.to_json(), f, indent=1)
