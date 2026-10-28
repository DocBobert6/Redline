"""3D LUT helpers: write .cube files and apply LUTs with trilinear interpolation."""
from __future__ import annotations

import numpy as np


def identity_lut(size: int = 33) -> np.ndarray:
    g = np.linspace(0.0, 1.0, size)
    r, gg, b = np.meshgrid(g, g, g, indexing="ij")
    return np.stack([r, gg, b], axis=-1)  # [r][g][b] -> rgb


def write_cube(path: str, lut: np.ndarray, title: str = "Redline") -> None:
    size = lut.shape[0]
    with open(path, "w", encoding="ascii") as f:
        f.write(f'TITLE "{title}"\nLUT_3D_SIZE {size}\n')
        # .cube order: red varies fastest
        for bi in range(size):
            for gi in range(size):
                for ri in range(size):
                    v = lut[ri, gi, bi]
                    f.write(f"{v[0]:.6f} {v[1]:.6f} {v[2]:.6f}\n")


def apply_lut(rgb: np.ndarray, lut: np.ndarray) -> np.ndarray:
    """Trilinear lookup. rgb in [0,1], shape (..., 3)."""
    n = lut.shape[0] - 1
    x = np.clip(rgb, 0.0, 1.0) * n
    i0 = np.floor(x).astype(int).clip(0, n - 1)
    f = x - i0
    out = np.zeros_like(rgb, dtype=float)
    for dr in (0, 1):
        for dg in (0, 1):
            for db in (0, 1):
                w = (
                    (f[..., 0] if dr else 1 - f[..., 0])
                    * (f[..., 1] if dg else 1 - f[..., 1])
                    * (f[..., 2] if db else 1 - f[..., 2])
                )
                out += w[..., None] * lut[i0[..., 0] + dr, i0[..., 1] + dg, i0[..., 2] + db]
    return out
