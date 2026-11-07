"""B31 simulated depth of field: the reference (redline_cal.bokeh) follows PLAN 19.5, and the native code
(app/src/main/cpp/bokeh.cpp via bokeh_tool) matches it.

CI builds the host tool and sets REDLINE_BOKEH_TOOL (REDLINE_REQUIRE_NATIVE=1 makes a missing tool fail instead of skip).
"""
from __future__ import annotations

import math
import os
import subprocess

import numpy as np
import pytest

from redline_cal import bokeh as bk


def native(env: str) -> str:
    path = os.environ.get(env)
    if not path or not os.path.exists(path):
        if os.environ.get("REDLINE_REQUIRE_NATIVE") == "1":
            pytest.fail(f"{env} is not built")
        pytest.skip(f"native host tool not built (set {env})")
    return path


def tone_curve(points: int = 256, lo: float = -14.0, hi: float = 1.0) -> bk.Tone:
    """A smooth S-ish curve shaped like the native table: sRGB-ish encode with a shoulder reaching 1 at 2^hi."""
    e = lo + (hi - lo) * np.arange(points) / (points - 1)
    x = np.exp2(e) / np.exp2(hi)
    y = np.where(x <= 0.0031308, 12.92 * x, 1.055 * np.power(x, 1 / 2.4) - 0.055)
    y = np.clip(y * 1.02, 0, 1)
    y[-1] = 1.0
    return bk.Tone(y.astype(np.float32), lo, hi, -1.5, 0.0)


@pytest.mark.parametrize("focal", [24, 35, 50, 70])
@pytest.mark.parametrize("s", [1, 2, 4])
def test_table_19_5(focal: int, s: int) -> None:
    want = bk.TABLE_19_5[focal][s] / 100.0
    got = bk.coc_frac(focal, 2.8, s)
    assert abs(got - want) / want < 0.10
    assert abs(bk.coc_frac(focal, 4.0, s) / got - 0.7) < 0.01


def test_phone_gap() -> None:
    # 19.5: a phone at ~f/6 equivalent leaves sqrt(1 - (2.8/6)^2) of the f/2.8 disc to add; nothing at f/8
    assert bk.phone_factor(2.8, 6.0) == pytest.approx(math.sqrt(1 - (2.8 / 6) ** 2))
    assert bk.phone_factor(8.0, 6.0) == 0.0


def widths(kd: np.ndarray, ux: float, uy: float) -> tuple[float, float]:
    """Extent of the kernel support along u and across it (projected, px)."""
    k = kd.shape[0] // 2
    ys, xs = np.nonzero(kd > 0.02 * kd.max())
    ys, xs = ys - k, xs - k
    a = xs * ux + ys * uy
    b = -xs * uy + ys * ux
    return float(a.max() - a.min() + 1), float(b.max() - b.min() + 1)


def test_kernel_shapes() -> None:
    r = 30.0
    kd = bk.kernel_dense(r, bk.blade_beta(2.8))
    assert kd.sum() == pytest.approx(1.0)
    ws = [widths(kd, math.cos(t), math.sin(t))[0] for t in np.linspace(0, math.pi, 12, endpoint=False)]
    assert min(ws) / max(ws) > 0.96
    for f, lo, hi in ((24, 0.60, 0.70), (70, 0.75, 0.85)):
        v = (1 / math.sqrt(2), 1 / math.sqrt(2))
        h, ux, uy = bk.cat_h(r, f, 2.8, *v)
        kc = bk.kernel_dense(r, bk.blade_beta(2.8), h, ux, uy)
        radial, tangential = widths(kc, ux, uy)
        frac = radial / (2 * r + 1)
        assert lo <= frac <= hi, (f, frac)
        assert tangential > radial
        h56, _, _ = bk.cat_h(r, f, 5.6, *v)
        assert h56 == 0.0


def scene(w: int = 120, h: int = 80, seed: int = 3):
    rng = np.random.default_rng(seed)
    yy, xx = np.mgrid[0:h, 0:w]
    bg = 0.25 + 0.15 * np.sin(xx / 5.0)[..., None] * np.array([0.3, 1.0, 0.6]) + 0.05 * rng.random((h, w, 3))
    img = bg.copy()
    subj = (np.abs(xx - w * 0.5) < w * 0.15) & (np.abs(yy - h * 0.55) < h * 0.3)
    img[subj] = np.array([0.8, 0.2, 0.15]) + 0.05 * rng.random((subj.sum(), 3))
    for (px, py) in ((10, 10), (w - 15, 12), (12, h - 12)):
        img[py, px] = 1.0
    dw, dh = 42, 28
    dy, dx = np.mgrid[0:dh, 0:dw]
    depth = 0.1 + 0.3 * (dy / dh)  # floor rising toward the camera
    sd = (np.abs((dx + 0.5) / dw - 0.5) < 0.15) & (np.abs((dy + 0.5) / dh - 0.55) < 0.3)
    depth = np.where(sd, 0.8, depth)
    mask = sd.astype(np.float64)
    return np.clip(img, 0, 1).astype(np.float32), depth.astype(np.float32), mask.astype(np.float32)


def test_gate() -> None:
    img, depth, mask = scene()
    t = tone_curve()
    r = bk.render(img, depth, mask, t, bk.Params(50, 2.8, 2.0, confidence=0.3))
    assert not r.applied and "confidence" in r.reason
    r = bk.render(img, depth, mask, t, bk.Params(50, 8.0, 2.0, phone_n=6.0, confidence=0.9))
    assert not r.applied and "phone" in r.reason


def test_reference_subject_sharp() -> None:
    img, depth, mask = scene()
    t = tone_curve()
    r = bk.render(img, depth, mask, t, bk.Params(70, 2.8, 2.0, 0.5, 0.55, phone_n=20.0, confidence=0.9))
    assert r.applied
    h, w = img.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w]
    inner = (np.abs(xx - w * 0.5) < w * 0.15 - 3) & (np.abs(yy - h * 0.55) < h * 0.3 - 3)
    assert np.abs(r.image[inner] - img[inner]).max() < 0.02
    far = (yy < 15) & (np.abs(xx - w * 0.5) > w * 0.3)
    assert r.image[far].std() < img[far].std()


def run_native(tool, tmp_path, img, depth, mask, tone, p: bk.Params, gain=None, threads=2):
    h, w = img.shape[:2]
    img.astype(np.float32).tofile(tmp_path / "in.f32")
    depth.astype(np.float32).tofile(tmp_path / "d.f32")
    if mask is not None:
        mask.astype(np.float32).tofile(tmp_path / "m.f32")
    tone.table.astype(np.float32).tofile(tmp_path / "c.f32")
    gw = gh = 1
    if gain is not None:
        gain.astype(np.float32).tofile(tmp_path / "g.f32")
        gh, gw = gain.shape
    args = [tool, str(w), str(h), str(tmp_path / "in.f32"), str(depth.shape[1]), str(depth.shape[0]), str(tmp_path / "d.f32"),
            str(tmp_path / "m.f32") if mask is not None else "-", str(tmp_path / "c.f32"), str(tone.lo), str(tone.hi),
            str(tmp_path / "g.f32") if gain is not None else "-", str(gw), str(gh), str(tone.knee_ev), str(tone.slope),
            str(p.focal_mm), str(p.aperture), str(p.focus_m), str(p.focus_x), str(p.focus_y), str(p.phone_n),
            "0" if math.isinf(p.far_m) else str(p.far_m), str(p.max_coc), str(p.work_max), str(p.confidence), str(threads),
            str(tmp_path / "out.f32"), str(tmp_path / "rad.f32")]
    out = subprocess.run(args, capture_output=True, text=True, check=True).stdout.split()
    res = np.fromfile(tmp_path / "out.f32", np.float32).reshape(h, w, 3)
    return out, res


@pytest.mark.parametrize("focal,n,fx,work,with_gain", [
    (70, 2.8, 0.5, 1536, False),   # cat's-eye + longitudinal CA
    (35, 4.0, 0.5, 1536, True),    # HDR continuation, cat's-eye half
    (24, 8.0, 0.5, 40, False),     # stopped down (polygon), working grid smaller than the still
    (50, 2.8, 0.1, 1536, False),   # focus on the background: foreground blur
])
def test_native_parity(tmp_path, focal, n, fx, work, with_gain) -> None:
    tool = native("REDLINE_BOKEH_TOOL")
    img, depth, mask = scene(240, 160)
    t = tone_curve()
    gain = None
    if with_gain:
        t.slope = 0.6
        gain = np.zeros((20, 30), np.float32)
        gain[1:5, 1:5] = 1.5
    p = bk.Params(focal, n, 0.3 if focal >= 50 else 0.25, fx, 0.55 if fx == 0.5 else 0.1, phone_n=24.0, max_coc=0.25, work_max=work, confidence=0.9)
    ref = bk.render(img, depth, mask, t, p, gain)
    assert ref.applied
    out, res = run_native(tool, tmp_path, img, depth, mask, t, p, gain)
    assert out[1] == "1"
    assert int(out[3]) == ref.stats["kmax"]
    map_n = np.fromfile(tmp_path / "rad.f32", np.float32)
    rho_w, _ = bk.radius_map(depth, mask, img, p)
    assert np.abs(map_n - rho_w.ravel()).max() < 1e-3 * max(1.0, ref.stats["cap"])
    err = np.abs(res - ref.image)
    assert err.max() < 2e-3, float(err.max())


def halo_scene(wall: bool):
    """B40: a dark near subject over a sharp mid-grey background (focus behind it); [wall]: a bright wall beyond the
    grey (top and right), at least 40 px from the subject's lower-left edge."""
    h, w = 240, 360
    yy, xx = np.mgrid[0:h, 0:w]
    cx, cy = 170, 120
    subj = ((xx - cx) / 60.0) ** 2 + ((yy - cy) / 80.0) ** 2 < 1
    img = np.full((h, w, 3), 0.5)
    if wall:
        img[(yy < cy - 10) | (xx > cx + 20)] = 1.0
    img[subj] = 0.08
    dy, dx = np.mgrid[0:40, 0:60]
    sd = (((dx + 0.5) / 60 * w - cx) / 60.0) ** 2 + (((dy + 0.5) / 40 * h - cy) / 80.0) ** 2 < 1
    depth = np.where(sd, 1.0, 0.2).astype(np.float32)
    return img.astype(np.float32), depth, sd.astype(np.float32), (xx < cx - 30) & (yy > cy + 30)


@pytest.mark.parametrize("focal", [35, 50, 70])
def test_no_halo_at_blurred_subject_edge(focal: int) -> None:
    t = tone_curve()
    p = bk.Params(focal, 2.8, 8.0, 0.03, 0.97, phone_n=20.0, confidence=0.9)
    img, depth, mask, grey_side = halo_scene(False)
    g = bk.render(img, depth, mask, t, p)
    assert g.applied and g.stats["front"]
    # two tones: no rim brighter than both the subject and the background
    assert g.image.max() <= 0.5 + 1 / 255
    # the occluded fill comes from the nearest background: a far bright wall leaves the grey side unchanged
    img2, _, _, _ = halo_scene(True)
    r = bk.render(img2, depth, mask, t, p)
    assert np.abs(r.image - g.image)[grey_side].max() < 0.005

