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
METERING = os.path.join(ASSETS, "colour", "metering.json")


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


# ----------------------------------------------------------------------------------------------- B23c.1 headroom lift


def _overcast_stats(rng, sky_fraction=0.55, sky=0.25, asphalt_ev=-3.3, n=40000):
    """Linear Y of an overcast frame: a uniform bright sky (the median) over dark asphalt and a treeline."""
    n_sky = int(n * sky_fraction)
    y_sky = sky * np.exp2(rng.normal(0.0, 0.12, n_sky))
    y_ground = sky * np.exp2(rng.normal(asphalt_ev, 0.4, n - n_sky))
    y = np.concatenate([y_sky, y_ground]).clip(0, 1)
    return {"median_y": float(np.median(y)), "p98_y": rp.upper_quantile(y), "p25_y": rp.upper_quantile(y, rp.P25),
            "clipped_fraction": float(np.mean(y >= 1.0))}


def test_overcast_sky_over_asphalt_is_lifted(tone):
    """B23c.1 device case: the median sits in the sky, so log2(p98 / median) is tiny and the fit alone places the sky
    like a flat mid-tone (about 157/255, ~1 EV under what an R5 II renders). The headroom lift brings it up."""
    m = rp.load_metering(METERING)
    assert m.headroom is not None
    st = _overcast_stats(np.random.default_rng(5))
    hi = rp.scene_hi(st["median_y"], st["p98_y"])
    split = rp.scene_split(st["median_y"], st["p25_y"])
    assert hi < 0.6 and split > m.headroom.split_min_ev
    base = rp.metered_exposure(tone, st["median_y"], st["p98_y"], m)  # no p25: the B23c behaviour
    lifted = rp.metered_exposure(tone, st["median_y"], st["p98_y"], m, st["p25_y"], st["clipped_fraction"])
    assert base.headroom_lift_ev == 0.0
    assert 0.8 < lifted.headroom_lift_ev <= m.headroom.lift_max_ev
    assert lifted.ev == pytest.approx(base.ev + lifted.headroom_lift_ev, abs=1e-12)
    # Rendered: the sky's p98 moves from below the shoulder knee up to the floor, still short of white.
    def rendered_p98(ev):
        c = rp.ToneCurve(tone, 2.0**ev)
        return float(c(np.array([c.gain * st["p98_y"]]))[0])
    assert rendered_p98(base.ev) < 0.72
    assert 0.78 < rendered_p98(lifted.ev) < 0.95


def test_headroom_lift_leaves_other_scenes_alone(tone):
    m = rp.load_metering(METERING)
    rng = np.random.default_rng(6)
    # A uniform wall: same small p98 / median, but no dark quarter.
    y = (0.2 * np.exp2(rng.normal(0.0, 0.15, 20000))).clip(0, 1)
    e = rp.metered_exposure(tone, float(np.median(y)), rp.upper_quantile(y), m, rp.upper_quantile(y, rp.P25), 0.0)
    assert e.headroom_lift_ev == 0.0
    # A contrasty frame with a split (overcast sky in the top fifth over a garden): p98 already above the floor.
    st = _overcast_stats(rng, sky_fraction=0.2, sky=0.6, asphalt_ev=-3.0)
    e = rp.metered_exposure(tone, st["median_y"], st["p98_y"], m, st["p25_y"], st["clipped_fraction"])
    assert e.headroom_lift_ev == 0.0
    # Clipping frame: never lifted.
    st = _overcast_stats(rng)
    e = rp.metered_exposure(tone, st["median_y"], st["p98_y"], m, st["p25_y"], 0.05)
    assert e.headroom_lift_ev == 0.0


def test_fit_headroom_recovers_a_floor():
    """Synthetic rows whose camera target lifts p98 to -1.5 EV when the split is large: the grid finds that rule."""
    rng = np.random.default_rng(8)
    rows = []
    for _ in range(80):
        hi, split = rng.uniform(0.3, 4.0), rng.uniform(0.3, 3.5)
        base = -2.7 - 0.47 * hi
        target = base + (max(-1.5 - (base + hi), 0.0) if split >= 2.0 else 0.0) + rng.normal(0, 0.05)
        rows.append({"features": {"hi": hi, "split": split}, "stats": {"clipped_fraction": 0.0}, "target_ev": target})
    h, info = mt.fit_headroom(rows, lambda r: -2.7 - 0.47 * r["features"]["hi"])
    assert h.p98_floor_ev == pytest.approx(-1.5, abs=0.11)
    assert h.split_min_ev == pytest.approx(2.0, abs=0.31)
    assert info["train_mae_ev_after"] < info["train_mae_ev_before"]
