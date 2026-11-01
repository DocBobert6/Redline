"""B21 RAW colour pipeline: the reference maths, and pixel parity of the native (C++) pipeline against it.

The parity tests need the host build of ``app/src/test/cpp/raw_pipeline_tool``; CI builds it and sets
``REDLINE_RAW_TOOL`` (and ``REDLINE_REQUIRE_NATIVE=1`` so a missing tool fails instead of skipping).

Gate (BUILD_PLAN B21): the native render matches the reference within dE2000 1.0 (max over every pixel) on the
same frames, compared before the final 8-bit rounding (the native float output; the app's bitmap is that value
rounded to 8 bits, checked separately), and neutrals stay neutral (daylight greys: CIELAB chroma <=
NEUTRAL_MAX_CHROMA).
"""
from __future__ import annotations

import base64
import json
import math
import os
import sys

import numpy as np
import pytest

from redline_cal import dng_profile as dp
from redline_cal import raw_pipeline as rp
from redline_cal.color import D65, XYZ_TO_SRGB

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
CUBE = os.path.join(ASSETS, "luts", "redline_standard_preview_33.cube")
METERING = os.path.join(ASSETS, "colour", "metering.json")
PARITY_MAX_DE = 1.0
NEUTRAL_MAX_CHROMA = 2.0

sys.path.insert(0, os.path.join(ROOT, "calibration", "tools"))
import make_colour_fixtures as fixtures  # noqa: E402


@pytest.fixture(scope="module")
def cal():
    return fixtures.load_profile()


@pytest.fixture(scope="module")
def tone():
    return rp.load_tone(fixtures.TONE)


@pytest.fixture(scope="module")
def lut():
    return rp.load_lut(CUBE)


@pytest.fixture(scope="module")
def tool():
    path = os.environ.get("REDLINE_RAW_TOOL")
    if not path or not os.path.exists(path):
        if os.environ.get("REDLINE_REQUIRE_NATIVE") == "1":
            pytest.fail("REDLINE_RAW_TOOL is not built")
        pytest.skip("native host tool not built (set REDLINE_RAW_TOOL)")
    return path


def daylight_neutral(cal):
    return fixtures.neutral_for(cal, (0.3127, 0.3290))


# ------------------------------------------------------------------------------------------------ reference maths


def test_warmth_retention_ramp():
    assert rp.warmth_retention(2500) == pytest.approx(0.30)
    assert rp.warmth_retention(3000) == pytest.approx(0.30)
    assert rp.warmth_retention(4000) == pytest.approx(0.15)
    assert rp.warmth_retention(5000) == 0.0
    assert rp.warmth_retention(6500) == 0.0
    assert rp.warmth_retention(2800, enabled=False) == 0.0


def test_planck_matches_known_whites():
    x, y = rp.planck_xy(2856)
    assert (x, y) == pytest.approx((0.4476, 0.4074), abs=2e-3)  # CIE illuminant A
    assert dp.xy_to_cct(rp.planck_xy(6504)) == pytest.approx(6504, rel=0.01)


def test_daylight_neutral_maps_to_white(cal):
    s = rp.solve_colour(cal, daylight_neutral(cal))
    assert s.used_forward and s.warmth == 0.0
    assert s.cct == pytest.approx(6504, rel=0.02)
    np.testing.assert_allclose(s.matrix @ np.ones(3), np.ones(3), atol=2e-3)


def test_tungsten_keeps_some_warmth(cal):
    n = fixtures.neutral_for(cal, rp.planck_xy(2856))
    warm = rp.solve_colour(cal, n)
    flat = rp.solve_colour(cal, n, warmth=False)
    assert warm.warmth == pytest.approx(0.30)
    white_warm, white_flat = warm.matrix @ np.ones(3), flat.matrix @ np.ones(3)
    np.testing.assert_allclose(white_flat, np.ones(3), atol=2e-3)
    assert white_warm[0] > white_warm[2] * 1.1  # red over blue: a warm white
    # 30 % of the way from D65 to the scene white, in XYZ.
    w, _ = rp.output_white(warm.cct)
    np.testing.assert_allclose(np.linalg.solve(XYZ_TO_SRGB, white_warm), w, atol=3e-3)
    assert not np.allclose(w, D65)


def test_colour_matrix_path_without_forward_matrices():
    c = dp.ColorCalibration(**fixtures.NO_FORWARD)
    n = fixtures.neutral_for(c, (0.3127, 0.3290))
    s = rp.solve_colour(c, n)
    assert not s.used_forward
    np.testing.assert_allclose(s.matrix @ np.ones(3), np.ones(3), atol=2e-3)


@pytest.mark.parametrize("gain", [0.05, 0.3, 1.0, 4.0])
def test_tone_curve_shoulder(tone, gain):
    c = rp.ToneCurve(tone, gain)
    # Continuous at the knee and reaching exactly 1.0 at the sensor clip.
    xk = 2.0**c.knee_ev
    assert c(np.array([xk * 0.999]))[0] == pytest.approx(c(np.array([xk * 1.001]))[0], abs=2e-3)
    assert c(np.array([2.0**c.clip_ev]))[0] == pytest.approx(1.0)
    assert c(np.array([2.0**c.clip_ev * 0.97]))[0] < 1.0
    # Monotone, and the old fit artefact (flat at 0.94 above -0.5 EV) is gone.
    ev = np.linspace(-14, c.clip_ev, 2000)
    y = c(2.0**ev)
    assert np.all(np.diff(y) >= -1e-9)
    assert np.all(np.diff(y[ev > c.knee_ev]) > 0)
    # Below the knee the fitted curve is unchanged.
    sel = ev < c.knee_ev - 0.1
    np.testing.assert_allclose(y[sel], np.interp(ev[sel], np.linspace(-14, 3, len(tone)), tone), atol=1e-9)
    # The table the native code gets reproduces the curve.
    tab = c.table()
    np.testing.assert_allclose(np.interp(ev, np.linspace(-14, c.clip_ev, len(tab)), tab), y, atol=2e-3)


def test_exposure_puts_the_median_on_target(tone):
    ev, method = rp.solve_exposure(tone, 0.09, 0.46)
    c = rp.ToneCurve(tone, 2.0**ev)
    assert method == "camera_jpeg_median"
    assert c(np.array([0.09 * 2.0**ev]))[0] == pytest.approx(0.46, abs=2e-3)
    assert rp.solve_exposure(tone, 0.09, None)[1] == "default_target"
    assert rp.solve_exposure(tone, 0.0, 0.46) == (0.0, "no_signal")
    assert rp.solve_exposure(tone, 1e-5, 0.46)[0] == rp.EXPOSURE_MAX_EV


def test_crop_and_output_size():
    assert rp.crop_32(4000, 3000) == (0, 166, 4000, 2666)
    l, t, w, h = rp.crop_32(4000, 3000, 2.0)
    assert (w, h) == (2000, 1332) and l % 2 == 0 and t % 2 == 0 and l + w / 2 == pytest.approx(2000, abs=2)
    assert rp.crop_32(3000, 4000)[2:] == (2666, 4000)
    assert rp.output_size(4000, 2666, 2910) == (2910, 1940)
    assert rp.output_size(4000, 2666, 0) == (4000, 2666)


def test_area_weights_preserve_mean():
    w = rp.area_weights(37, 11)
    np.testing.assert_allclose(w.sum(axis=1), 1.0)
    np.testing.assert_allclose(w.sum(axis=0).sum(), 11.0)


def test_demosaic_reproduces_flat_colour():
    for cfa in range(4):
        layout = rp.CFA_LAYOUTS[cfa]
        v = np.empty((12, 16))
        for p in range(4):
            v[p >> 1 :: 2, p & 1 :: 2] = (0.2, 0.5, 0.3)[layout[p]]
        np.testing.assert_allclose(rp.demosaic_mhc(v, cfa), np.broadcast_to([0.2, 0.5, 0.3], (12, 16, 3)), atol=1e-12)


def test_reference_keeps_daylight_greys_neutral(cal, tone, lut):
    n = daylight_neutral(cal)
    frame, boxes = rp.synthetic_frame(cal, n, shading=rp.synthetic_shading())
    plan = rp.plan_shot(frame, cal, n, tone, target_encoded=0.46)
    img = rp.render_plan(frame, plan, lut)
    chroma = rp.neutral_chroma(rp.patch_means(img, boxes[: rp.N_GREYS], plan.crop, plan.out_size))
    assert chroma.max() <= NEUTRAL_MAX_CHROMA, chroma
    # The blown strip renders white, not magenta.
    strip = img[-3:, -10:].reshape(-1, 3)
    assert strip.min() > 0.97


# ------------------------------------------------------------------------------------------------ fixtures are current


def _close(a, b, path=""):
    if isinstance(a, dict):
        assert a.keys() == b.keys(), path
        for k in a:
            _close(a[k], b[k], f"{path}.{k}")
    elif isinstance(a, list):
        assert len(a) == len(b), path
        for i, (x, y) in enumerate(zip(a, b)):
            _close(x, y, f"{path}[{i}]")
    elif isinstance(a, float) or isinstance(b, float):
        assert a == pytest.approx(b, rel=1e-6, abs=1e-8), path
    else:
        assert a == b, path


def test_kotlin_fixture_is_current():
    with open(fixtures.CASES_OUT) as f:
        committed = json.load(f)
    _close(json.loads(fixtures.dump(fixtures.colour_math_cases())), committed)


def test_self_check_asset_is_current():
    with open(fixtures.SELF_CHECK_OUT) as f:
        committed = json.load(f)
    fresh = json.loads(fixtures.dump(fixtures.self_check()))
    a = np.frombuffer(base64.b64decode(fresh["expected"].pop("rgb8_b64")), np.uint8).astype(int)
    b = np.frombuffer(base64.b64decode(committed["expected"].pop("rgb8_b64")), np.uint8).astype(int)
    assert np.abs(a - b).max() <= 1 and np.mean(a != b) < 0.01
    _close(fresh, committed)


# ------------------------------------------------------------------------------------------------ native parity


CASES = [
    # (white, cfa, shading, zoom, long_px)
    ("daylight", 0, False, 1.0, 0),
    ("daylight", 1, True, 1.0, 150),
    ("daylight", 2, True, 1.4, 0),
    ("daylight", 3, False, 1.0, 97),
    ("tungsten", 1, True, 1.0, 0),
    ("tungsten", 0, False, 1.37, 120),
    ("cool", 2, True, 1.0, 160),
]
WHITES = {"daylight": (0.3127, 0.3290), "tungsten": rp.planck_xy(2856), "cool": rp.planck_xy(8000)}


@pytest.mark.parametrize("white,cfa,shading,zoom,long_px", CASES)
def test_native_matches_reference(tool, cal, tone, lut, white, cfa, shading, zoom, long_px):
    n = fixtures.neutral_for(cal, WHITES[white])
    frame, boxes = rp.synthetic_frame(cal, n, cfa=cfa, shading=rp.synthetic_shading() if shading else None, seed=cfa + 7)
    ref_plan = rp.plan_shot(frame, cal, n, tone, zoom=zoom, long_px=long_px, target_encoded=0.46)
    ref = rp.render_plan(frame, ref_plan, lut)
    # The app plans from the native statistics; they must agree with the reference's.
    st = rp.native_stats(tool, frame, ref_plan.crop, ref_plan.solve.neutral, ref_plan.solve.matrix)
    assert st["median_y"] == pytest.approx(ref_plan.stats["median_y"], rel=1e-4)
    assert st["clipped_fraction"] == pytest.approx(ref_plan.stats["clipped_fraction"], abs=1e-6)
    assert st["p98_y"] == pytest.approx(ref_plan.stats["p98_y"], rel=1e-4)  # B23c: the metering fit's input
    assert st["p25_y"] == pytest.approx(ref_plan.stats["p25_y"], rel=1e-4)  # B23c.1: the headroom lift's split
    metered = rp.plan_shot(frame, cal, n, tone, zoom=zoom, long_px=long_px, stats=st, metering=rp.load_metering(METERING))
    assert metered.exposure_method == rp.METERING_METHOD
    plan = rp.plan_shot(frame, cal, n, tone, zoom=zoom, long_px=long_px, target_encoded=0.46, stats=st)
    img, _ = rp.native_render(tool, frame, plan, CUBE, threads=3)
    assert img.shape[:2] == (ref_plan.out_size[1], ref_plan.out_size[0])
    gate = rp.parity(ref, img)
    assert gate["max_de2000"] <= PARITY_MAX_DE, gate
    assert gate["mean_de2000"] <= 0.1, gate
    # The 8-bit bitmap is the float render rounded: within one code value of the rounded reference.
    img8, _ = rp.native_render(tool, frame, plan, CUBE, threads=3, precise=False)
    assert np.abs(img8 - rp.quantise8(img)).max() <= 1.0 / 255 + 1e-9
    assert np.abs(img8 - rp.quantise8(ref)).max() <= 1.0 / 255 + 1e-9
    chroma = rp.neutral_chroma(rp.patch_means(img, boxes[: rp.N_GREYS], plan.crop, plan.out_size))
    if white == "daylight":
        assert chroma.max() <= NEUTRAL_MAX_CHROMA, chroma
    elif white == "tungsten":
        lab = rp.srgb_encoded_to_lab(rp.patch_means(img, boxes[1:3], plan.crop, plan.out_size))
        assert np.all(lab[:, 2] > 3.0), lab  # greys keep a warm (yellow) cast under tungsten


def test_native_self_check_frame(tool, tone):
    """The on-device self-check frame renders to its stored expectation through the native tool too."""
    with open(fixtures.SELF_CHECK_OUT) as f:
        sc = json.load(f)
    w, h = sc["width"], sc["height"]
    raw = np.frombuffer(base64.b64decode(sc["raw_u16le_b64"]), "<u2").reshape(h, w)
    sh = np.asarray(sc["shading"]["values"]).reshape(sc["shading"]["rows"], sc["shading"]["cols"], 4)
    frame = rp.RawFrame(raw.copy(), sc["cfa"], tuple(sc["black"]), sc["white"], sh)
    cal = dp.ColorCalibration(**sc["profile"])
    st = rp.native_stats(tool, frame, rp.crop_32(w, h, sc["zoom"]), sc["expected"]["neutral"], np.reshape(sc["expected"]["matrix"], (3, 3)))
    plan = rp.plan_shot(frame, cal, sc["as_shot_neutral"], tone, zoom=sc["zoom"], long_px=sc["long_px"],
                        target_encoded=sc["target_encoded"], stats=st)
    assert plan.exposure_ev == pytest.approx(sc["expected"]["exposure_ev"], abs=1e-4)
    img, _ = rp.native_render(tool, frame, plan, CUBE, precise=False)
    ow, oh = sc["expected"]["out"]
    exp = np.frombuffer(base64.b64decode(sc["expected"]["rgb8_b64"]), np.uint8).reshape(oh, ow, 3) / 255.0
    assert np.abs(exp - img).max() <= 1.0 / 255 + 1e-9  # the 8-bit expectation, within one code value
    assert math.isfinite(st["median_y"])


# ----------------------------------------------------------------------------------------------- B23c metering


def test_metering_asset_and_solve(tone):
    m = rp.load_metering(METERING)
    assert m.coef[1] < 0  # higher-contrast scenes (bright highlights over the median) are placed lower
    lo, hi = m.hi_range
    assert 0 <= lo < hi
    # A flat scene (p98 = 2x median) and a contrasty one (p98 = 32x median), same median: the contrasty one darker.
    ev_flat, method = rp.solve_exposure_metered(tone, 0.02, 0.04, m)
    ev_hard, _ = rp.solve_exposure_metered(tone, 0.02, 0.64, m)
    assert method == rp.METERING_METHOD
    assert ev_hard < ev_flat
    assert ev_flat - ev_hard == pytest.approx(-m.coef[1] * (min(5.0, hi) - max(1.0, lo)), abs=1e-9)
    # Exposure does not change the decision: the same scene at half the level gets one EV more gain.
    assert rp.solve_exposure_metered(tone, 0.01, 0.32, m)[0] == pytest.approx(ev_hard + 1.0, abs=1e-9)
    # Missing p98: the constant fit; no signal: no gain.
    ev_c, method_c = rp.solve_exposure_metered(tone, 0.02, None, m)
    assert method_c == rp.METERING_CONSTANT_METHOD
    assert ev_c == pytest.approx(m.fallback_target_ev - math.log2(0.02))
    assert rp.solve_exposure_metered(tone, 0.0, 0.1, m) == (0.0, "no_signal")
    # Beyond the training range the spread is clamped (no extrapolation).
    assert rp.solve_exposure_metered(tone, 0.001, 1.0, m)[0] == pytest.approx(
        rp.solve_exposure_metered(tone, 0.001, 0.001 * 2.0**hi, m)[0], abs=1e-9)


def test_upper_quantile_is_the_native_order_statistic():
    v = np.arange(101, dtype=float)[::-1]
    assert rp.upper_quantile(v) == 98.0
    assert rp.upper_quantile(v, rp.P25) == 25.0
    assert rp.upper_quantile(np.array([3.0])) == 3.0
    assert rp.upper_quantile(np.arange(10.0)) == 8.0  # floor(0.98 * 9) = 8
