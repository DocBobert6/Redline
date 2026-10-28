"""Colorimetry used by the look fit: sRGB transfer, XYZ/Lab (D65), CIEDE2000."""
from __future__ import annotations

import numpy as np

# IEC 61966-2-1, D65
XYZ_TO_SRGB = np.array(
    [[3.2404542, -1.5371385, -0.4985314], [-0.9692660, 1.8760108, 0.0415560], [0.0556434, -0.2040259, 1.0572252]]
)
SRGB_TO_XYZ = np.linalg.inv(XYZ_TO_SRGB)
D65 = np.array([0.95047, 1.0, 1.08883])


def srgb_encode(lin: np.ndarray) -> np.ndarray:
    lin = np.clip(lin, 0.0, None)
    return np.where(lin <= 0.0031308, 12.92 * lin, 1.055 * np.power(lin, 1 / 2.4) - 0.055)


def srgb_decode(enc: np.ndarray) -> np.ndarray:
    enc = np.clip(enc, 0.0, 1.0)
    return np.where(enc <= 0.04045, enc / 12.92, np.power((enc + 0.055) / 1.055, 2.4))


def xyz_to_lab(xyz: np.ndarray, white: np.ndarray = D65) -> np.ndarray:
    t = xyz / white
    d = 6 / 29
    f = np.where(t > d**3, np.cbrt(t), t / (3 * d * d) + 4 / 29)
    return np.stack([116 * f[..., 1] - 16, 500 * (f[..., 0] - f[..., 1]), 200 * (f[..., 1] - f[..., 2])], axis=-1)


def srgb_encoded_to_lab(enc: np.ndarray) -> np.ndarray:
    return xyz_to_lab(srgb_decode(enc) @ SRGB_TO_XYZ.T)


def delta_e2000(lab1: np.ndarray, lab2: np.ndarray) -> np.ndarray:
    """CIEDE2000 (Sharma, Wu, Dalal 2005), kL = kC = kH = 1."""
    L1, a1, b1 = np.moveaxis(np.asarray(lab1, float), -1, 0)
    L2, a2, b2 = np.moveaxis(np.asarray(lab2, float), -1, 0)
    C1, C2 = np.hypot(a1, b1), np.hypot(a2, b2)
    Cm = (C1 + C2) / 2
    G = 0.5 * (1 - np.sqrt(Cm**7 / (Cm**7 + 25.0**7)))
    a1p, a2p = (1 + G) * a1, (1 + G) * a2
    C1p, C2p = np.hypot(a1p, b1), np.hypot(a2p, b2)
    h1p = np.degrees(np.arctan2(b1, a1p)) % 360
    h2p = np.degrees(np.arctan2(b2, a2p)) % 360
    dLp = L2 - L1
    dCp = C2p - C1p
    dh = h2p - h1p
    dh = np.where(dh > 180, dh - 360, np.where(dh < -180, dh + 360, dh))
    dh = np.where(C1p * C2p == 0, 0, dh)
    dHp = 2 * np.sqrt(C1p * C2p) * np.sin(np.radians(dh) / 2)
    Lpm = (L1 + L2) / 2
    Cpm = (C1p + C2p) / 2
    hs = h1p + h2p
    hpm = np.where(np.abs(h1p - h2p) > 180, np.where(hs < 360, (hs + 360) / 2, (hs - 360) / 2), hs / 2)
    hpm = np.where(C1p * C2p == 0, hs, hpm)
    T = (1 - 0.17 * np.cos(np.radians(hpm - 30)) + 0.24 * np.cos(np.radians(2 * hpm))
         + 0.32 * np.cos(np.radians(3 * hpm + 6)) - 0.20 * np.cos(np.radians(4 * hpm - 63)))
    dtheta = 30 * np.exp(-(((hpm - 275) / 25) ** 2))
    Rc = 2 * np.sqrt(Cpm**7 / (Cpm**7 + 25.0**7))
    Sl = 1 + 0.015 * (Lpm - 50) ** 2 / np.sqrt(20 + (Lpm - 50) ** 2)
    Sc = 1 + 0.045 * Cpm
    Sh = 1 + 0.015 * Cpm * T
    Rt = -np.sin(np.radians(2 * dtheta)) * Rc
    return np.sqrt((dLp / Sl) ** 2 + (dCp / Sc) ** 2 + (dHp / Sh) ** 2 + Rt * (dCp / Sc) * (dHp / Sh))


def skin_mask(lab: np.ndarray) -> np.ndarray:
    """Broad skin-tone region in CIELAB (all complexions): L 25-90, chroma 8-45, hue 15-70 deg."""
    L, a, b = lab[..., 0], lab[..., 1], lab[..., 2]
    c = np.hypot(a, b)
    h = np.degrees(np.arctan2(b, a)) % 360
    return (L >= 25) & (L <= 90) & (c >= 8) & (c <= 45) & (h >= 15) & (h <= 70)
