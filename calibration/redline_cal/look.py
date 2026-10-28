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


def fit_residual_lut(base: np.ndarray, target: np.ndarray, n: int = 33, smooth: float = 2.0, prior: float = 1e-3) -> np.ndarray:
    """Solve min |W D - (t - b_id)|^2 + smooth*|Lap D|^2 + prior*|D|^2 for D = LUT - identity."""
    ident = identity_lut(n).reshape(-1, 3)
    W = _trilinear_weights(base, n)
    resid = target - W @ ident
    Lp = _laplacian_3d(n)
    scale = max(1.0, len(base) / n**3)  # keep regularization strength independent of sample count
    A = (W.T @ W + smooth * scale * (Lp.T @ Lp) + prior * scale * sparse.identity(n**3)).tocsr()
    D = np.zeros((n**3, 3))
    for c in range(3):
        D[:, c], _ = cg(A, W.T @ resid[:, c], rtol=1e-8, maxiter=2000)
    return np.clip(ident + D, 0.0, 1.0).reshape(n, n, n, 3)


def fit_look(xyz: np.ndarray, target_enc: np.ndarray, n: int = 33, smooth: float = 2.0) -> Look:
    tone = fit_tone(xyz, target_enc)
    look = Look(tone=tone, lut=identity_lut(n))
    look.lut = fit_residual_lut(look.base(xyz), target_enc, n=n, smooth=smooth)
    return look


EXPOSURE_GRID = np.linspace(-1.0, 1.0, 41)


def best_exposure(look: Look, xyz: np.ndarray, target_enc: np.ndarray, grid: np.ndarray = EXPOSURE_GRID) -> float:
    """Per-image exposure offset (EV) that best matches the target's lightness.

    The camera's Auto Lighting Optimizer and metering choose a per-scene brightness the
    phone's own auto-exposure will never reproduce, so the look is judged up to one global
    exposure number per image. Median |dL*| keeps local-tone outliers from dominating.
    """
    if len(xyz) == 0:
        return 0.0
    lt = srgb_encoded_to_lab(target_enc)[:, 0]
    errs = [np.median(np.abs(srgb_encoded_to_lab(look.render(xyz * 2.0**g))[:, 0] - lt)) for g in grid]
    return float(grid[int(np.argmin(errs))])


def fit_look_aligned(
    images: list[tuple[np.ndarray, np.ndarray]], n: int = 33, smooth: float = 2.0, iters: int = 3
) -> tuple[Look, list[float]]:
    """Alternate between fitting the look and each training image's exposure offset."""
    ev = [0.0] * len(images)
    look = None
    for _ in range(iters):
        x = np.concatenate([a * 2.0**e for (a, _), e in zip(images, ev)])
        y = np.concatenate([b for _, b in images])
        look = fit_look(x, y, n=n, smooth=smooth)
        ev = [best_exposure(look, a, b) for a, b in images]
        m = float(np.mean(ev))  # keep the curve anchored to the camera's average metering
        ev = [e - m for e in ev]
    return look, ev


def evaluate(look: Look, xyz: np.ndarray, target_enc: np.ndarray) -> dict:
    lab_t = srgb_encoded_to_lab(target_enc)
    de = delta_e2000(srgb_encoded_to_lab(look.render(xyz)), lab_t)
    sk = skin_mask(lab_t)
    return {
        "n": int(len(de)),
        "mean_de2000": float(de.mean()),
        "median_de2000": float(np.median(de)),
        "p95_de2000": float(np.percentile(de, 95)),
        "skin_n": int(sk.sum()),
        "skin_mean_de2000": float(de[sk].mean()) if sk.any() else None,
    }


def passes_gate(report: dict, mean_max: float = 2.5, skin_max: float = 2.0) -> bool:
    return report["mean_de2000"] < mean_max and (report["skin_mean_de2000"] is None or report["skin_mean_de2000"] < skin_max)


def save(look: Look, out_dir: str, title: str = "Redline Standard") -> None:
    import os

    from .lut import write_cube

    os.makedirs(out_dir, exist_ok=True)
    write_cube(os.path.join(out_dir, "standard_residual.cube"), look.lut, title=title)
    with open(os.path.join(out_dir, "standard_look.json"), "w") as f:
        json.dump(look.to_json(), f, indent=1)
