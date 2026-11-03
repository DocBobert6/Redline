#!/usr/bin/env python3
"""B30: the depth self-check scene (synthetic, so the repo carries no third-party photo).

420x280 sRGB: sky, a textured ground plane receding in perspective, three boxes at different distances and a person-like
figure in front. Used by the on-device GPU self-check, the emulator test and the export parity check.
Writes app/src/main/assets/depth/selfcheck_420x280.png (deterministic: no randomness beyond a fixed seed).
"""
import sys

import numpy as np
from PIL import Image

W, H = 420, 280


def scene():
    rng = np.random.default_rng(30)
    y, x = np.mgrid[0:H, 0:W].astype(np.float64)
    img = np.zeros((H, W, 3))
    horizon = 110.0
    # sky: blue gradient
    t = np.clip(y / horizon, 0, 1)[..., None]
    img[:] = (1 - t) * np.array([0.35, 0.55, 0.85]) + t * np.array([0.75, 0.82, 0.90])
    # ground: perspective checker (u, v on the plane), warm grey, darker far away
    g = y > horizon
    dz = np.where(g, (y - horizon), 1.0)
    v = 2000.0 / dz
    u = (x - W / 2) * v / 400.0
    checker = ((np.floor(u / 1.5) + np.floor(v / 1.5)) % 2)[..., None]
    ground = np.array([0.45, 0.40, 0.33]) * (0.75 + 0.25 * checker) * (0.6 + 0.4 * np.clip(dz / (H - horizon), 0, 1))[..., None]
    img = np.where(g[..., None], ground, img)

    def box(x0, y0, w, h, col):
        img[int(y0):int(y0 + h), int(x0):int(x0 + w)] = col
        # lit top face
        img[int(y0):int(y0 + max(2, h * 0.12)), int(x0):int(x0 + w)] = np.minimum(1, np.array(col) * 1.25)

    box(40, 95, 30, 22, [0.55, 0.25, 0.20])   # far
    box(300, 90, 60, 45, [0.20, 0.40, 0.25])  # middle
    box(330, 150, 80, 85, [0.30, 0.30, 0.55])  # near, right
    # person: head + neck + torso + arms, left of centre, standing on the near ground
    cx = 170.0
    head = ((x - cx) ** 2 / 15.0 ** 2 + (y - 75) ** 2 / 19.0 ** 2) <= 1
    hair = head & (y < 68)
    torso = (np.abs(x - cx) < 38 - np.clip((y - 105) * 0.05, 0, 6)) & (y > 98) & (y < 230)
    shoulders = ((x - cx) ** 2 / 46.0 ** 2 + (y - 112) ** 2 / 18.0 ** 2) <= 1
    neck = (np.abs(x - cx) < 7) & (y > 88) & (y < 102)
    legs = ((np.abs(x - (cx - 15)) < 13) | (np.abs(x - (cx + 15)) < 13)) & (y >= 230)
    arm_l = (np.abs(x - (cx - 48) - (y - 110) * 0.15) < 9) & (y > 108) & (y < 210)
    arm_r = (np.abs(x - (cx + 48) + (y - 110) * 0.15) < 9) & (y > 108) & (y < 210)
    skin = np.array([0.85, 0.65, 0.52])
    shirt = np.array([0.80, 0.18, 0.16])
    trousers = np.array([0.15, 0.18, 0.28])
    img[torso | shoulders] = shirt
    img[arm_l | arm_r] = shirt * 0.9
    img[legs] = trousers
    img[head | neck] = skin
    img[hair] = np.array([0.20, 0.13, 0.08])
    # mild shading and sensor-like noise
    img *= (0.92 + 0.08 * np.cos((x - W / 2) / W * np.pi))[..., None]
    img += rng.normal(0, 0.004, img.shape)
    return (np.clip(img, 0, 1) * 255 + 0.5).astype(np.uint8)


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/depth/selfcheck_420x280.png"
    Image.fromarray(scene()).save(out, optimize=True)
    print("wrote", out)
