"""RAW decoding to scene-linear data via rawpy (LibRaw >= 0.22 reads the EOS R5 Mark II)."""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np


@dataclass(frozen=True)
class RawInfo:
    shape: tuple[int, int]
    cfa: str
    black_levels: tuple[int, ...]
    white_level: int
    rgb_xyz_matrix: list[list[float]]
    camera_wb: list[float]


def _rawpy():
    try:
        import rawpy  # type: ignore
    except ImportError as e:  # pragma: no cover
        raise ImportError("rawpy is required for RAW decoding: pip install -r calibration/requirements.txt") from e
    return rawpy


def raw_info(path: str) -> RawInfo:
    rawpy = _rawpy()
    with rawpy.imread(path) as r:
        return RawInfo(
            shape=tuple(r.raw_image.shape),
            cfa=r.color_desc.decode(),
            black_levels=tuple(int(b) for b in r.black_level_per_channel),
            white_level=int(r.white_level),
            rgb_xyz_matrix=r.rgb_xyz_matrix[:3].astype(float).tolist(),
            camera_wb=[float(x) for x in r.camera_whitebalance],
        )


def decode_linear_xyz(path: str, half_size: bool = True, use_camera_wb: bool = True) -> np.ndarray:
    """Demosaic to linear CIE XYZ (D65-adapted by LibRaw), float32 in [0, ~1]."""
    rawpy = _rawpy()
    with rawpy.imread(path) as r:
        rgb = r.postprocess(
            gamma=(1, 1),
            no_auto_bright=True,
            output_bps=16,
            use_camera_wb=use_camera_wb,
            output_color=rawpy.ColorSpace.XYZ,
            half_size=half_size,
            highlight_mode=rawpy.HighlightMode.Clip,
        )
    return rgb.astype(np.float32) / 65535.0
