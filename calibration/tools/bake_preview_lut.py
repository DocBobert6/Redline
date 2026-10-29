"""Bake the display-referred 33^3 preview LUT for the B20 GPU look preview.

The fitted Standard look (B03, redline_cal.look) is scene-referred:

    white-balanced scene XYZ (D65) -> linear sRGB -> per-channel tone curve T (log2 grid) -> residual LUT R -> sRGB (encoded)

The live preview the app gets from the camera HAL is NOT scene-linear: it is the phone's own display-referred
sRGB rendering (its tone curve, local tone mapping and saturation are already applied). Until B21 feeds true
scene-linear RAW data, the preview LUT can only be an approximation. This script bakes an sRGB-encoded-in ->
sRGB-encoded-out LUT in one of two modes:

``residual`` (default, the one bundled in the app)
    out = R(e). The HAL preview value e is treated as the output of a neutral tone curve, which is exactly what
    R is indexed by (R's input is T(scene), sRGB-encoded). Assumption: the phone's own preview tone curve is
    close enough to the Standard tone curve T that only R's colour rendering (hue twists, saturation,
    cross-channel tone) needs adding. Tone stays the phone's.

``compose``
    The literal composition: lin = sRGB_decode(e); XYZ = M_sRGB->XYZ(D65) @ lin * 2^ev; out = R(T(XYZ_TO_SRGB @ XYZ)).
    Assumption: decoded preview values are scene-linear up to the exposure gain ``--exposure-ev``. They are not
    (decoding undoes the sRGB transfer, not the phone's tone curve), so this applies a second S-curve on top of
    the phone's: with the bundled fit, mid-grey e = 0.46 renders to about 0.68 at 0 EV, and shadows crush when
    the exposure is lowered to hold mid-grey. Kept for comparison and for B21-era experiments.

Both modes clamp to [0, 1]. The app mixes the LUT result with the unmodified preview in linear light using an
adjustable strength (0..1), so an over-strong approximation can be dialled back on the device.

Usage::

    python calibration/tools/bake_preview_lut.py --look-dir data/out/look_v3_2.0 \\
        --out app/src/main/assets/luts/redline_standard_preview_33.cube --source-name look_v3_2.0
"""
from __future__ import annotations

import argparse
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from redline_cal.color import SRGB_TO_XYZ, srgb_decode  # noqa: E402
from redline_cal.look import Look  # noqa: E402
from redline_cal.lut import apply_lut, identity_lut, read_cube  # noqa: E402

MODES = ("residual", "compose")


def load_look(look_dir: str) -> Look:
    with open(os.path.join(look_dir, "standard_look.json")) as f:
        meta = json.load(f)
    model = meta.get("model", "")
    if not model.startswith("xyz_d65 -> srgb_linear -> tone(log2) -> lut3d(srgb_encoded)"):
        raise ValueError(f"unexpected look model: {model!r}")
    tone = np.asarray(meta["tone"]["values"], dtype=float)
    lut = read_cube(os.path.join(look_dir, "standard_residual.cube"))
    if lut.shape[0] != meta.get("lut_size", lut.shape[0]):
        raise ValueError("standard_look.json lut_size does not match the .cube")
    return Look(tone=tone, lut=lut)


def bake(look: Look, size: int = 33, mode: str = "residual", exposure_ev: float = 0.0) -> np.ndarray:
    """Return a [r][g][b] -> rgb LUT indexed by sRGB-encoded preview values, giving sRGB-encoded output."""
    if mode not in MODES:
        raise ValueError(f"mode must be one of {MODES}")
    grid = identity_lut(size).reshape(-1, 3)  # encoded inputs on the unit cube
    if mode == "residual":
        out = apply_lut(grid, look.lut)
    else:
        xyz = (srgb_decode(grid) @ SRGB_TO_XYZ.T) * 2.0**exposure_ev
        out = look.render(xyz)
    return np.clip(out, 0.0, 1.0).reshape(size, size, size, 3)


def write_preview_cube(path: str, lut: np.ndarray, title: str, comments: list[str]) -> None:
    size = lut.shape[0]
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", encoding="ascii", newline="\n") as f:
        for c in comments:
            f.write(f"# {c}\n")
        f.write(f'TITLE "{title}"\nLUT_3D_SIZE {size}\nDOMAIN_MIN 0.0 0.0 0.0\nDOMAIN_MAX 1.0 1.0 1.0\n')
        for bi in range(size):  # .cube order: red fastest
            for gi in range(size):
                for ri in range(size):
                    v = lut[ri, gi, bi]
                    f.write(f"{v[0]:.6f} {v[1]:.6f} {v[2]:.6f}\n")


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--look-dir", required=True, help="fit output with standard_look.json + standard_residual.cube")
    p.add_argument("--out", required=True)
    p.add_argument("--size", type=int, default=33)
    p.add_argument("--mode", choices=MODES, default="residual")
    p.add_argument("--exposure-ev", type=float, default=0.0, help="compose mode only: gain on decoded preview values")
    p.add_argument("--source-name", default=None, help="name of the fit run, recorded in the .cube header")
    p.add_argument("--title", default="Redline Standard look preview")
    a = p.parse_args(argv)

    look = load_look(a.look_dir)
    lut = bake(look, a.size, a.mode, a.exposure_ev)
    source = a.source_name or os.path.basename(os.path.normpath(a.look_dir))
    comments = [
        "Redline B20 look preview LUT: sRGB-encoded preview in -> sRGB-encoded out (display-referred).",
        f"Baked by calibration/tools/bake_preview_lut.py, mode={a.mode}"
        + (f", exposure_ev={a.exposure_ev}" if a.mode == "compose" else "")
        + f", from Standard look fit '{source}'.",
        "Approximate until B21: the camera HAL preview is already tone-mapped, not scene-linear.",
    ]
    report_path = os.path.join(a.look_dir, "report.json")
    if os.path.exists(report_path):
        with open(report_path) as f:
            rep = json.load(f)
        h = rep.get("holdout", {})
        s = rep.get("settings", {})
        flags = [k for k in ("align_exposure", "devignette") if s.get(k)]
        if s.get("robust"):
            flags.append(f"robust={s['robust']}")
        comments.append(
            "Fit hold-out: colour dE2000 %.2f, median dE2000 %.2f, median |dL*| %.2f, skin colour %.2f; fit flags: %s"
            % (
                h.get("color_mean_de2000", float("nan")),
                h.get("median_de2000", float("nan")),
                h.get("median_abs_dL", float("nan")),
                h.get("skin_color_mean_de2000", float("nan")),
                ", ".join(flags) or "none",
            )
        )
    write_preview_cube(a.out, lut, a.title, comments)
    diff = np.abs(lut - identity_lut(a.size))
    print(f"wrote {a.out}: {a.size}^3, mode {a.mode}, mean |out - in| {diff.mean():.4f}, max {diff.max():.4f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
