"""B24b touch metering: AF-area parsing and mapping, the weight fit, and the reference shift (synthetic data)."""
from __future__ import annotations

import math
import os

import numpy as np
import pytest

from redline_cal import raw_pipeline as rp
from redline_cal import spot_metering as sm

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")


def _exif(n, xs, ys, ws, hs, focus=None):
    pad = lambda v: " ".join(str(x) for x in list(v) + [0] * (n - len(v)))  # noqa: E731
    e = {"AFImageWidth": 8192, "AFImageHeight": 5464, "ValidAFPoints": n, "AFAreaXPositions": pad(xs),
         "AFAreaYPositions": pad(ys), "AFAreaWidths": pad(ws), "AFAreaHeights": pad(hs)}
    if focus is not None:
        e["AFPointsInFocus"] = " ".join(str(w) for w in focus)
    return e


def test_single_af_area_is_centred_with_y_up():
    box, n = sm.af_box(_exif(1, [0], [1366], [820], [546]))
    assert n == 1
    u0, v0, u1, v1 = box
    assert math.isclose((u0 + u1) / 2, 0.5) and math.isclose(u1 - u0, 820 / 8192)
    assert math.isclose((v0 + v1) / 2, 0.25)  # +1366 of 5464 is a quarter frame above the centre
    assert math.isclose(v1 - v0, 546 / 5464)


def test_multi_point_union_uses_the_in_focus_bitmask():
    xs, ys = [-200, 0, 200, 400] + [0] * 16, [0] * 20
    ws, hs = [180] * 20, [173] * 20
    # points 1 and 2 in focus (bits 1 and 2 of the first word); point 17 too (bit 1 of the second word)
    box, n = sm.af_box(_exif(20, xs, ys, ws, hs, focus=[0b110, 0b10]))
    assert n == 3
    assert math.isclose(box[0], 0.5 + (0 - 90) / 8192)  # point 17 sits at x = 0 too
    assert math.isclose(box[2], 0.5 + (200 + 90) / 8192)
    assert sm.af_box(_exif(20, xs, ys, ws, hs, focus=[0, 0]))[0] is None
    assert sm.af_box({"ValidAFPoints": 1})[0] is None


def test_box_to_grid_landscape_and_portrait():
    gh, gw = sm.GRID
    full = sm.box_to_grid((0.1, 0.1, 0.9, 0.9), False)
    assert full == (0, gh, 0, gw)
    # a box at the sensor's left edge lands at the bottom of a portrait (rot90) grid
    r0, r1, c0, c1 = sm.box_to_grid((0.1, 0.45, 0.2, 0.55), True)
    assert r1 == gh and c0 < gw / 2 < c1


def test_region_d_sign():
    y = np.full(sm.GRID, 0.1)
    y[10:14, 20:26] = 0.4
    assert math.isclose(sm.region_d(y.reshape(-1), (10, 14, 20, 26)), -2.0)


def test_fit_recovers_asymmetric_weights_and_ships_conservatively():
    rng = np.random.default_rng(3)
    d = rng.uniform(-2.5, 2.5, 400)
    resid = 0.3 * np.clip(d, -1, 0) + 0.0 * np.clip(d, 0, 1) + rng.normal(0, 0.05, d.size)
    w = sm.fit_weights(d, resid)
    assert abs(w[0] - 0.3) < 0.03 and abs(w[1]) < 0.03
    b = sm.bootstrap(d, resid, n=300)
    lo = np.percentile(b, 5, axis=0)
    wb, wd = sm.shipped(w, lo)
    assert wb == 0.3 and wd == 0.0
    assert sm.loo_mae(d, resid) < np.mean(np.abs(resid))


def test_spot_shift_reference():
    s = rp.Spot(w_brighter=0.25, w_darker=0.0, cap_ev=1.0, region_short_side=0.11)
    assert math.isclose(s.shift(0.05, 0.2), -0.25)                    # 2 EV brighter, capped at 1 EV
    assert math.isclose(s.shift(0.05, 0.05 * 2**0.4), -0.1)
    assert s.shift(0.05, 0.05 * 2**-1.5) == 0.0                       # darker subject: no lift
    assert s.shift(0.05, None) == 0.0 and s.shift(0.05, 0.0) == 0.0 and s.shift(0.05, float("nan")) == 0.0


@pytest.fixture(scope="module")
def tone():
    return rp.load_tone(os.path.join(ASSETS, "colour", "standard_tone.json"))


def test_shipped_asset_and_metered_exposure(tone):
    met = rp.load_metering(os.path.join(ASSETS, "colour", "metering.json"))
    assert met.spot is not None
    assert 0.0 < met.spot.w_brighter <= 0.5 and met.spot.w_darker == 0.0 and met.spot.cap_ev <= 1.0
    assert 0.05 <= met.spot.region_short_side <= 0.2
    base = rp.metered_exposure(tone, 0.05, 0.4, met)
    bright = rp.metered_exposure(tone, 0.05, 0.4, met, region_median_y=0.2)
    assert math.isclose(bright.ev - base.ev, -met.spot.w_brighter * 1.0, abs_tol=1e-12)
    assert math.isclose(bright.spot_shift_ev, bright.ev - base.ev, abs_tol=1e-12)
    dark = rp.metered_exposure(tone, 0.05, 0.4, met, region_median_y=0.01)
    assert dark.ev == base.ev and dark.spot_shift_ev == 0.0
    # the shift never moves exposure by more than w * cap
    for region in (1e-4, 0.01, 0.05, 0.3, 0.99):
        e = rp.metered_exposure(tone, 0.05, 0.4, met, region_median_y=region)
        assert abs(e.ev - base.ev) <= max(met.spot.w_brighter, met.spot.w_darker) * met.spot.cap_ev + 1e-12
