"""B23c metering fit and tone re-check: the maths on synthetic data (the real fit runs on the R5 II pairs locally)."""
from __future__ import annotations

import math
import os

import numpy as np
import pytest

from redline_cal import metering as mt
from redline_cal import raw_pipeline as rp
from redline_cal.color import XYZ_TO_SRGB

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")


@pytest.fixture(scope="module")
def tone():
    return rp.load_tone(os.path.join(ASSETS, "colour", "standard_tone.json"))


def _scene(rng, spread=2.0, n=20000):
    return np.exp2(rng.normal(-6.0, spread, n)).clip(0, 1)


def test_features_do_not_depend_on_exposure():
    rng = np.random.default_rng(1)
    y = _scene(rng) * 0.25  # keep everything below the clip so scaling is exact
    a = mt.features(mt.quantiles(y))
    b = mt.features(mt.quantiles(y * 0.125))
    assert a["hi"] == pytest.approx(b["hi"], abs=1e-9)
    assert a["lo"] == pytest.approx(b["lo"], abs=1e-9)
    assert mt.quantiles(np.array([0.5, 0.99, 1.0, 0.1]))["clipped_fraction"] == pytest.approx(0.5)


def test_fit_recovers_a_linear_metering_rule():
    rng = np.random.default_rng(2)
    rows = []
    for _ in range(40):
        f = {"hi": rng.uniform(1, 5), "lo": rng.uniform(1, 5)}
        rows.append({"features": f, "target_ev": -2.0 - 0.3 * f["hi"]})
    m = mt.fit_model("hi", rows)
    assert m.coef == pytest.approx([-2.0, -0.3], abs=1e-9)
    assert mt.loo_error("hi", rows) < 1e-9
    assert mt.loo_error("constant", rows) > 0.1
    assert mt.fit_model("constant", rows).predict({"hi": 9, "lo": 9}) == pytest.approx(np.mean([r["target_ev"] for r in rows]))


def test_target_and_gain_match_solve_exposure(tone):
    # The fitted target is an EV on the tone curve's input axis: solve_exposure with the target's encoded value
    # gives the same gain as gain_ev, so the app can take either path.
    target = mt.target_ev(tone, 0.40)
    assert rp.solve_exposure(tone, 0.02, 0.40)[0] == pytest.approx(mt.gain_ev(target, 0.02), abs=1e-6)
    assert mt.target_ev(tone, 0.40, ec=1.0) == pytest.approx(target - 1.0)
    assert mt.gain_ev(target, 0.0) == 0.0
    assert mt.gain_ev(target, 1e-9) == 0.0
    assert mt.gain_ev(100.0, 0.01) == rp.EXPOSURE_MAX_EV


def test_tone_regions_are_zero_when_the_jpeg_is_our_own_render(tone):
    rng = np.random.default_rng(3)
    y = np.exp2(rng.uniform(-10, 0, 3000))
    xyz = np.stack([y * 0.9505, y, y * 1.089], axis=1)  # D65 greys
    gain = 0.3
    jpeg = mt.render_blocks(xyz, gain, tone, None)
    reg = mt.tone_regions(xyz, jpeg, gain, tone, None, np.ones(len(y), bool))
    for k in ("black", "mid", "shoulder"):
        assert reg[k].size > 10
        assert np.max(np.abs(reg[k])) < 1e-6
    assert mt.summarise(reg["mid"])["pass"]
    # a brighter JPEG shows up as negative dL in the mid-tones
    reg2 = mt.tone_regions(xyz, np.clip(jpeg * 1.1, 0, 1), gain, tone, None, np.ones(len(y), bool))
    assert mt.summarise(reg2["mid"])["median_dL"] < -1.0


def test_render_blocks_matches_the_reference_curve(tone):
    xyz = np.array([[0.18 * 0.9505, 0.18, 0.18 * 1.089]])
    lin = xyz @ XYZ_TO_SRGB.T
    c = rp.ToneCurve(tone, 2.0**0.5)
    assert mt.render_blocks(xyz, 0.5, tone, None) == pytest.approx(c(c.gain * lin), abs=1e-9)
    assert math.isfinite(float(mt.render_blocks(xyz, -3.0, tone, None).sum()))
