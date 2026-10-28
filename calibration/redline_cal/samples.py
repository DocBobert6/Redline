"""Turn an aligned RAW (linear XYZ) + JPEG pair into robust color samples."""
from __future__ import annotations

import numpy as np


def _block_stats(img: np.ndarray, gh: int, gw: int) -> tuple[np.ndarray, np.ndarray]:
    h, w = img.shape[:2]
    bh, bw = h // gh, w // gw
    v = img[: bh * gh, : bw * gw].reshape(gh, bh, gw, bw, -1).astype(np.float64)
    return v.mean(axis=(1, 3)), v.std(axis=(1, 3))


def center_crop_to_aspect(img: np.ndarray, aspect: float) -> np.ndarray:
    h, w = img.shape[:2]
    if w / h > aspect:
        nw = int(round(h * aspect))
        x0 = (w - nw) // 2
        return img[:, x0 : x0 + nw]
    nh = int(round(w / aspect))
    y0 = (h - nh) // 2
    return img[y0 : y0 + nh]


def sample_pair(
    xyz: np.ndarray,
    jpeg_enc: np.ndarray,
    grid: tuple[int, int] = (96, 144),
    center: float = 0.8,
    max_std: float = 0.03,
    raw_scale: float | None = 0.5,
) -> tuple[np.ndarray, np.ndarray]:
    """Block-average both images on a common grid and keep flat, unclipped blocks.

    Flat blocks make the pairing tolerant of the small geometric differences between
    the RAW development and the camera JPEG (crop margins, residual lens correction);
    the central region avoids the corners where in-camera vignetting correction acts.
    """
    # LibRaw's developed frame carries a few extra border pixels compared with the camera JPEG
    # (R5 II half-size: 4111x2744 vs JPEG 8192x5464 / 2). raw_scale = RAW px per JPEG px.
    if raw_scale is not None:
        th, tw = round(jpeg_enc.shape[0] * raw_scale), round(jpeg_enc.shape[1] * raw_scale)
        if xyz.shape[0] >= th and xyz.shape[1] >= tw:
            y0, x0 = (xyz.shape[0] - th) // 2, (xyz.shape[1] - tw) // 2
            xyz = xyz[y0 : y0 + th, x0 : x0 + tw]
    xyz = center_crop_to_aspect(xyz, jpeg_enc.shape[1] / jpeg_enc.shape[0])

    def crop(a):
        h, w = a.shape[:2]
        dy, dx = int(h * (1 - center) / 2), int(w * (1 - center) / 2)
        return a[dy : h - dy, dx : w - dx]

    mx, sx = _block_stats(crop(xyz), *grid)
    mj, sj = _block_stats(crop(jpeg_enc), *grid)
    mx, mj = mx.reshape(-1, 3), mj.reshape(-1, 3)
    rel = sx.reshape(-1, 3) / (mx + 1e-4)
    flat = (sj.reshape(-1, 3).max(axis=1) < max_std) & (rel.max(axis=1) < 0.15)
    unclipped = (mj.max(axis=1) < 0.98) & (mj.min(axis=1) > 0.02) & (mx.min(axis=1) > 0)
    keep = flat & unclipped
    return mx[keep], mj[keep]


def load_jpeg(path: str) -> np.ndarray:
    from PIL import Image, ImageOps

    with Image.open(path) as im:
        im = ImageOps.exif_transpose(im).convert("RGB")
        return np.asarray(im, dtype=np.float32) / 255.0
