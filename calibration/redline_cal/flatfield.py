"""Free flat-field check (PLAN 9.6): a white wall or overcast sky, no chart.

Applies the phone's own lens-shading GainMaps to the CFA data and measures how
flat the result is. The radial falloff left over after correction is the
"residual shading" the app must not double-correct or ignore.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from .dng_profile import GainMap


@dataclass
class FlatFieldReport:
    falloff_before_ev: float  # corner ring vs centre, before gain maps (negative = darker corners)
    falloff_after_ev: float  # after applying gain maps
    asymmetry_after_ev: float  # max - min over the four corners after correction
    clipped_fraction: float

    def passes(self, max_residual_ev: float = 0.5) -> bool:
        return abs(self.falloff_after_ev) <= max_residual_ev and self.clipped_fraction < 0.01


def apply_gain_maps(cfa: np.ndarray, maps: list[GainMap]) -> np.ndarray:
    """cfa: 2-D float array (black-subtracted, 0..1). Returns a corrected copy."""
    out = cfa.astype(float, copy=True)
    h, w = cfa.shape
    for m in maps:
        bottom = min(m.bottom, h)
        right = min(m.right, w)
        rows = np.arange(m.top, bottom, m.row_pitch)
        cols = np.arange(m.left, right, m.col_pitch)
        if rows.size == 0 or cols.size == 0:
            continue
        span_v = max(bottom - m.top, 1)
        span_h = max(right - m.left, 1)
        vv, hh = np.meshgrid((rows - m.top) / span_v, (cols - m.left) / span_h, indexing="ij")
        out[np.ix_(rows, cols)] *= m.gain_at(vv, hh)
    return out


def _green(cfa: np.ndarray, block: int) -> np.ndarray:
    """Block-mean of the full CFA (all planes), a robust luminance proxy for a grey target."""
    h, w = cfa.shape
    hb, wb = h // block, w // block
    return cfa[: hb * block, : wb * block].reshape(hb, block, wb, block).mean((1, 3))


def _rings(img: np.ndarray) -> tuple[float, float, list[float]]:
    h, w = img.shape
    yy, xx = np.mgrid[0:h, 0:w]
    r = np.hypot((yy - (h - 1) / 2) / ((h - 1) / 2), (xx - (w - 1) / 2) / ((w - 1) / 2)) / np.sqrt(2)
    centre = float(np.median(img[r < 0.15]))
    corner = float(np.median(img[r > 0.85]))
    q = []
    for ys in (slice(0, h // 6), slice(h - h // 6, h)):
        for xs in (slice(0, w // 6), slice(w - w // 6, w)):
            q.append(float(np.median(img[ys, xs])))
    return centre, corner, q


def flat_field_report(cfa: np.ndarray, maps: list[GainMap], block: int = 32) -> FlatFieldReport:
    clipped = float(np.mean(cfa >= 0.98))
    before = _green(cfa, block)
    after = _green(apply_gain_maps(cfa, maps), block)
    c0, k0, _ = _rings(before)
    c1, k1, q = _rings(after)
    ev = lambda a, b: float(np.log2(max(a, 1e-9) / max(b, 1e-9)))  # noqa: E731
    qv = [ev(x, c1) for x in q]
    return FlatFieldReport(ev(k0, c0), ev(k1, c1), max(qv) - min(qv), clipped)


def load_cfa(path: str) -> np.ndarray:
    """Black-subtracted, white-normalised CFA mosaic from a DNG via rawpy."""
    import rawpy  # type: ignore

    with rawpy.imread(path) as r:
        raw = r.raw_image_visible.astype(float)
        black = np.asarray(r.black_level_per_channel, float)
        cols = r.raw_colors_visible
        raw -= black[cols]
        return np.clip(raw / (float(r.white_level) - black.max()), 0.0, None)
