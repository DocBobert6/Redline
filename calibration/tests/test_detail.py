"""B24 detail stage: the reference maths (vignette, distortion, sharpening, noise estimate, grain), the shipped
asset, and parity of the native code (``app/src/main/cpp/detail.cpp`` via ``detail_tool``; the vignette inside the
RAW render via ``raw_pipeline_tool``) against ``redline_cal.detail``.

CI builds the host tools and sets REDLINE_DETAIL_TOOL / REDLINE_RAW_TOOL (REDLINE_REQUIRE_NATIVE=1 makes a missing
tool fail instead of skip).
"""
from __future__ import annotations

import json
import math
import os
import subprocess
import sys

import numpy as np
import pytest

from redline_cal import detail as dt
from redline_cal import lensfun_import as lf
from redline_cal import raw_pipeline as rp

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
ASSET = os.path.join(ASSETS, "colour", "detail.json")
CUBE = os.path.join(ASSETS, "luts", "redline_standard_preview_33.cube")

sys.path.insert(0, os.path.join(ROOT, "calibration", "tools"))
import make_colour_fixtures as fixtures  # noqa: E402


def native(env: str) -> str:
    path = os.environ.get(env)
    if not path or not os.path.exists(path):
        if os.environ.get("REDLINE_REQUIRE_NATIVE") == "1":
            pytest.fail(f"{env} is not built")
        pytest.skip(f"native host tool not built (set {env})")
    return path


@pytest.fixture(scope="module")
def asset():
    return dt.load_asset(ASSET)


# ------------------------------------------------------------------------------------------------ asset


def test_asset_is_standard_only_and_complete(asset):
    assert asset.picture_style == "Standard"
    assert asset.raw["picture_styles_supported"] == ["Standard"]
    assert set(asset.vignette) == {"24", "28", "35", "50", "70"} == set(asset.distortion)
    assert set(asset.usm) == {"4000", "3000"}
    assert "CC BY-SA 3.0" in asset.raw["sources"]["lensfun"]
    assert asset.raw["distortion"]["default"].startswith("off")


def test_vignette_tables_are_the_fitted_residual(asset):
    assert asset.vignette_alpha == pytest.approx(0.55)
    for mm, t in asset.vignette.items():
        assert len(t) == dt.VIGNETTE_POINTS
        assert t[0] == pytest.approx(1.0)
        assert np.all(np.diff(t) <= 1e-9), mm  # monotone falloff
    # PLAN 19.2: 24 mm f/2.8 corner -2.16 EV uncorrected -> 0.55 x = about -1.19 EV in the camera JPEG.
    assert math.log2(asset.vignette["24"][-1]) == pytest.approx(0.55 * -2.164, abs=0.02)
    assert math.log2(asset.vignette["70"][-1]) == pytest.approx(0.55 * -1.508, abs=0.02)


def test_distortion_coefficients_match_the_plan(asset):
    a, b, c = asset.distortion["24"]
    assert dt.distortion_percent_edge(a, b, c) == pytest.approx(-1.15, abs=0.05)  # PLAN 19.1: barrel at 24 mm
    a, b, c = asset.distortion["70"]
    assert dt.distortion_percent_edge(a, b, c) == pytest.approx(1.00, abs=0.05)   # pincushion at 70 mm


def test_grain_target_rises_with_iso(asset):
    t = [asset.grain.target(i, 4000)[3] for i in (100, 800, 3200, 12800, 51200)]
    assert all(b > a for a, b in zip(t, t[1:]))
    assert 0.2 / 255 < t[0] < 0.6 / 255 and 1.5 / 255 < t[-1] < 3.5 / 255
    assert asset.grain.target(25, 4000)[3] == pytest.approx(t[0])  # clamped to the fitted range


# ------------------------------------------------------------------------------------------------ reference maths


def test_lensfun_ev_interpolation():
    xml = """<lensdatabase version="2"><lens><maker>X</maker><model>test 24-70mm</model><cropfactor>1</cropfactor>
    <calibration>
      <vignetting model="pa" focal="24" aperture="2.8" distance="10" k1="-0.5" k2="0" k3="0"/>
      <vignetting model="pa" focal="24" aperture="5.6" distance="10" k1="-0.3" k2="0" k3="0"/>
      <vignetting model="pa" focal="70" aperture="2.8" distance="10" k1="-0.3" k2="0" k3="0"/>
      <vignetting model="pa" focal="70" aperture="5.6" distance="10" k1="-0.1" k2="0" k3="0"/>
    </calibration></lens></lensdatabase>"""
    p = lf.parse_lensfun(xml, "test 24-70mm")
    ev24 = dt.lensfun_ev(p, 24, 2.8, np.array([1.0]))[0]
    assert ev24 == pytest.approx(math.log2(0.5))
    mid = dt.lensfun_ev(p, 47, 2.8, np.array([1.0]))[0]
    assert mid == pytest.approx(0.5 * (math.log2(0.5) + math.log2(0.7)))
    t = dt.vignette_table(p, 24, 2.8, 0.55, points=5)
    assert t[-1] == pytest.approx(2 ** (0.55 * math.log2(0.5)))


def test_distortion_inverse_and_fill():
    a, b, c = 0.0259091, -0.0662025, 0.0194773
    r = np.linspace(0.05, 1.8, 50)
    assert np.allclose(dt.ptlens(dt.undistort_radius(r, a, b, c), a, b, c), r, atol=1e-10)
    img = np.random.default_rng(0).uniform(0, 1, (60, 90, 3))
    assert np.allclose(dt.distort(img, 0, 0, 0), img, atol=1e-9)
    r_max, ratio = dt.distortion_ratio_table(90, 60, a, b, c)
    s = dt.distortion_fill_scale(90, 60, r_max, ratio)
    assert 0.97 < s <= 1.0


def test_sharpen_matches_fitted_transfer():
    """Without coring the USM is linear: its gain at f matches 1 + a (1 - exp(-2 pi^2 sigma^2 f^2))."""
    n = 256
    x = np.arange(n)
    f = 0.125
    y = 0.5 + 0.05 * np.cos(2 * np.pi * f * x)
    img = np.repeat(np.tile(y, (n, 1))[..., None], 3, axis=2)
    out = dt.sharpen(img, 1.2, 0.7, 0.0)
    gain = (out[n // 2, 64:192, 1] - 0.5).std() / (img[n // 2, 64:192, 1] - 0.5).std()
    want = 1 + 0.7 * (1 - math.exp(-2 * (math.pi * 1.2 * f) ** 2))
    assert gain == pytest.approx(want, rel=0.02)
    flat = np.full((20, 30, 3), 0.4)
    assert np.allclose(dt.sharpen(flat, 1.2, 0.7, 0.03), flat)


def test_noise_estimate_and_grain_hit_their_target():
    img = np.full((256, 384, 3), 0.43)
    add = np.full(7, 2.0 / 255)
    kw = 0.114
    kappa = dt.field_kappa(kw)
    out = dt.grain(img, add, seed=5, kernel_w=kw, kappa=kappa)
    m = dt.noise_by_bin(out @ dt.LUMA_ENC)
    assert m[3] == pytest.approx(2.0 / 255, rel=0.1)
    assert np.allclose(out[..., 0], out[..., 1])  # luma grain
    # correlated like the camera JPEG: coarse/fine ratio well above white noise's 0.5
    assert dt.coarse_fine_ratio(kw) == pytest.approx(0.845, abs=0.03)
    assert dt.grain_add_sigma(np.array([3.0, 1.0]), [4.0, None]).tolist() == [0.0, 0.0]


def test_hash_is_stable():
    v = dt.hash32(np.array([0, 1, 4000]), np.array([0, 2, 2666]), 42)
    assert v.dtype == np.uint64 and np.all(v < 2**32)
    f = dt.normal_field(512, 512, 3)
    assert abs(f.mean()) < 0.01 and f.var() == pytest.approx(1.0, abs=0.02)


def test_vignette_in_render_linear():
    cam = np.full((30, 40, 3), 0.2)
    curve = rp.ToneCurve(np.linspace(0, 1, 64) ** 0.6, 1.0)
    lut = rp.load_lut(CUBE)
    flat = rp.render_linear(cam, np.eye(3), np.ones(3), curve, lut)
    vig = rp.render_linear(cam, np.eye(3), np.ones(3), curve, lut, vignette=np.array([1.0, 1.0, 0.5]))
    assert np.allclose(vig[15, 20], flat[15, 20], atol=1e-3)
    assert vig[0, 0, 1] < flat[0, 0, 1] - 0.02


# ------------------------------------------------------------------------------------------------ native parity


def run_tool(tool, tmp_path, img, asset, mode, *, stop_mm, iso, seed, sharpen_on=True, grain_on=True, distortion_on=False):
    h, w = img.shape[:2]
    lines = [f"width {w}", f"height {h}", "threads 3"]
    if distortion_on:
        a, b, c = asset.distortion[asset.stop_key(stop_mm, asset.distortion)]
        lines.append(f"distort {a:.9g} {b:.9g} {c:.9g}")
    else:
        lines.append("distort none")
    if sharpen_on:
        p = asset.usm_for(w)
        lines.append(f"sharpen {p['sigma']:.9g} {p['amount']:.9g} {p['threshold']:.9g}")
    else:
        lines.append("sharpen 1 0 0")
    if grain_on:
        t = asset.grain.target(iso, w)
        g = asset.grain.scale(w)
        lines.append("grain " + " ".join(f"{v:.9g}" for v in t) + f" {g.kernel_w:.9g} {g.kappa:.9g} {seed}")
    else:
        lines.append("grain none")
    pp = tmp_path / "p.txt"
    pp.write_text("\n".join(lines) + "\n")
    ip, op = tmp_path / "in.bin", tmp_path / "out.bin"
    if mode == "applyf":
        np.ascontiguousarray(img, dtype="<f4").tofile(ip)
    else:
        np.ascontiguousarray(np.round(img * 255), dtype=np.uint8).tofile(ip)
    res = subprocess.run([tool, mode, str(pp), str(ip), str(op)], check=True, capture_output=True, text=True).stdout.split()
    if mode == "applyf":
        out = np.fromfile(op, dtype="<f4").reshape(h, w, 3).astype(float)
    else:
        out = np.fromfile(op, dtype=np.uint8).reshape(h, w, 3).astype(float) / 255.0
    i = res.index("measured")
    measured = [float(v) for v in res[i + 1 : i + 8]]
    return out, measured, float(res[1])


def scene(w=300, h=200, seed=1):
    rng = np.random.default_rng(seed)
    yy, xx = np.mgrid[0:h, 0:w]
    base = 0.15 + 0.7 * (xx / w)
    base = base + 0.12 * ((xx // 9 + yy // 9) % 2) * (yy < h // 2)
    img = np.stack([base, base * 0.95 + 0.02, base * 0.9 + 0.03], -1)
    img = img + rng.normal(0, 0.004, img.shape)
    return np.clip(np.round(img * 255) / 255, 0, 1)


@pytest.mark.parametrize("distortion_on,stop_mm,iso", [(False, 35, 3200), (True, 24, 6400), (True, 70, 400)])
def test_native_detail_matches_reference(tmp_path, asset, distortion_on, stop_mm, iso):
    tool = native("REDLINE_DETAIL_TOOL")
    img = scene()
    ref, info = dt.apply_detail(img, asset, iso=iso, stop_mm=stop_mm, seed=9, distortion_on=distortion_on)
    got, measured, _ = run_tool(tool, tmp_path, img, asset, "applyf", stop_mm=stop_mm, iso=iso, seed=9, distortion_on=distortion_on)
    assert np.abs(got - ref).max() < 2e-3
    for a, b in zip(measured, info["grain_measured"]):
        assert (a < 0) == (b is None)
        if b is not None:
            assert a == pytest.approx(b, rel=1e-3, abs=1e-6)
    # The app's RGBA8 path: 8-bit after each stage, as apply_detail(quantise=True).
    ref8, _ = dt.apply_detail(img, asset, iso=iso, stop_mm=stop_mm, seed=9, distortion_on=distortion_on, quantise=True)
    got8, _, _ = run_tool(tool, tmp_path, img, asset, "apply8", stop_mm=stop_mm, iso=iso, seed=9, distortion_on=distortion_on)
    d = np.abs(got8 - ref8) * 255
    assert d.max() <= 2.0 and (d > 0.5).mean() < 0.01


def test_native_vignette_matches_reference(asset):
    tool = native("REDLINE_RAW_TOOL")
    cal = fixtures.load_profile()
    tone = rp.load_tone(fixtures.TONE)
    lut = rp.load_lut(CUBE)
    n = fixtures.neutral_for(cal, (0.3127, 0.3290))
    frame, _ = rp.synthetic_frame(cal, n, cfa=1, seed=3)
    plan = rp.plan_shot(frame, cal, n, tone, long_px=150, target_encoded=0.46)
    st = rp.native_stats(tool, frame, plan.crop, plan.solve.neutral, plan.solve.matrix)
    plan = rp.plan_shot(frame, cal, n, tone, long_px=150, target_encoded=0.46, stats=st)
    table = asset.vignette["24"]
    ref = rp.render(frame, rp.RenderParams(plan.crop, plan.out_size, plan.solve.matrix, plan.solve.neutral, plan.curve, lut,
                                           vignette=table))
    img, _ = rp.native_render(tool, frame, plan, CUBE, threads=3, vignette=table)
    gate = rp.parity(ref, img)
    assert gate["max_de2000"] <= 1.0, gate
    plain, _ = rp.native_render(tool, frame, plan, CUBE, threads=3)
    r = dt.radius_map(img.shape[1], img.shape[0])
    assert img[r > 0.8].mean() < 0.9 * plain[r > 0.8].mean()  # the outer ring is darker than without the vignette
    assert np.abs(img[r < 0.1] - plain[r < 0.1]).max() < 0.01  # the centre is not


def test_detail_render_cost_host(tmp_path, asset):
    """Informational: the post-pass on a full 4000 x 2667 frame (the app's 24/35 output) on the host."""
    tool = native("REDLINE_DETAIL_TOOL")
    img = np.clip(scene(4000, 2667, seed=2), 0, 1)
    _, _, ms = run_tool(tool, tmp_path, img, asset, "apply8", stop_mm=35, iso=3200, seed=1)
    print(f"detail_ms host 4000x2667 sharpen+grain: {ms:.1f}")
    assert ms > 0
