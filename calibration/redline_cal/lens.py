"""Lens emulation math shared with the app (docs/PLAN.md, sections 7 and 19)."""
from __future__ import annotations

import math


def ptlens_distort(r_u: float, a: float, b: float, c: float) -> float:
    """lensfun ptlens model: undistorted radius -> distorted radius.

    r is normalized so r = 1 at half the short image side.
    """
    d = 1.0 - a - b - c
    return r_u * (a * r_u**3 + b * r_u**2 + c * r_u + d)


def distortion_percent(r_u: float, a: float, b: float, c: float) -> float:
    """Radial displacement in percent at r_u (+ = pincushion, - = barrel)."""
    return (ptlens_distort(r_u, a, b, c) / r_u - 1.0) * 100.0


def pa_vignette(r: float, k1: float, k2: float, k3: float) -> float:
    """lensfun pa model gain, r = 1 at the image corner."""
    return 1.0 + k1 * r**2 + k2 * r**4 + k3 * r**6


def gain_to_ev(gain: float) -> float:
    return math.log2(gain)


def blur_disc_fraction(focal_mm: float, f_number: float, subject_m: float, sensor_width_mm: float = 36.0) -> float:
    """Background-at-infinity blur disc as a fraction of frame width (thin lens)."""
    s = subject_m * 1000.0
    b = focal_mm**2 / (f_number * (s - focal_mm))
    return b / sensor_width_mm
