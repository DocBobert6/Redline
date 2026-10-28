import json

import numpy as np
import pytest

from redline_cal.color import SRGB_TO_XYZ, XYZ_TO_SRGB, delta_e2000, skin_mask, srgb_decode, srgb_encode, xyz_to_lab
from redline_cal.look import Look, evaluate, fit_look, passes_gate, save
from redline_cal.samples import center_crop_to_aspect, sample_pair

# Sharma, Wu, Dalal (2005) CIEDE2000 test data
SHARMA = [
    ((50.0, 2.6772, -79.7751), (50.0, 0.0, -82.7485), 2.0425),
    ((50.0, 3.1571, -77.2803), (50.0, 0.0, -82.7485), 2.8615),
    ((50.0, 2.8361, -74.0200), (50.0, 0.0, -82.7485), 3.4412),
    ((50.0, -1.3802, -84.2814), (50.0, 0.0, -82.7485), 1.0000),
    ((50.0, 0.0, 0.0), (50.0, -1.0, 2.0), 2.3669),
    ((50.0, 2.5, 0.0), (61.0, -5.0, 29.0), 22.8977),
    ((50.0, 2.5, 0.0), (56.0, -27.0, -3.0), 31.9030),
]


@pytest.mark.parametrize("a,b,expected", SHARMA)
def test_ciede2000_reference(a, b, expected):
    assert delta_e2000(np.array(a), np.array(b)) == pytest.approx(expected, abs=1e-4)
    assert delta_e2000(np.array(b), np.array(a)) == pytest.approx(expected, abs=1e-4)


def test_srgb_roundtrip_and_white():
    x = np.linspace(0, 1, 101)
    assert np.allclose(srgb_decode(srgb_encode(x)), x, atol=1e-7)
    lab = xyz_to_lab(np.array([1.0, 1.0, 1.0]) @ SRGB_TO_XYZ.T)
    assert lab == pytest.approx([100, 0, 0], abs=0.01)


def test_skin_mask():
    assert skin_mask(np.array([65.0, 15.0, 18.0]))  # typical skin
    assert not skin_mask(np.array([50.0, -40.0, 30.0]))  # foliage


def _camera(xyz):
    """Stand-in for an in-camera JPEG engine: S-curve, +sat, hue twist in reds, cross-talk."""
    lin = np.clip(xyz @ XYZ_TO_SRGB.T, 0, None)
    lin = lin @ np.array([[1.12, -0.08, -0.04], [-0.05, 1.10, -0.05], [-0.02, -0.10, 1.12]]).T
    lin = np.clip(lin, 0, None)
    curve = lambda v: (v * 1.6) / (v * 1.6 + 0.35) * 1.22  # noqa: E731  filmic shoulder
    enc = srgb_encode(np.clip(curve(lin), 0, 1))
    r, g = enc[:, 0], enc[:, 1]
    enc[:, 1] = np.clip(g + 0.03 * np.clip(r - g, 0, None), 0, 1)
    return np.clip(enc, 0, 1)


def _scene(n, seed):
    rng = np.random.default_rng(seed)
    rgb = rng.random((n, 3)) ** 2.2 * rng.uniform(0.05, 1.0, (n, 1))
    return np.clip(rgb, 1e-4, None) @ SRGB_TO_XYZ.T


def test_fit_recovers_synthetic_camera():
    xt, xh = _scene(60000, 1), _scene(15000, 2)
    look = fit_look(xt, _camera(xt))
    rep = evaluate(look, xh, _camera(xh))
    assert rep["mean_de2000"] < 1.0, rep
    assert rep["p95_de2000"] < 2.5, rep
    assert passes_gate(rep)
    # tone-curve-only baseline should be clearly worse than tone + LUT
    base = Look(tone=look.tone, lut=np.stack(np.meshgrid(*[np.linspace(0, 1, 33)] * 3, indexing="ij"), -1))
    assert evaluate(base, xh, _camera(xh))["mean_de2000"] > rep["mean_de2000"] * 1.5


def test_tone_curve_monotone_and_bounded():
    xt = _scene(20000, 3)
    look = fit_look(xt, _camera(xt))
    assert np.all(np.diff(look.tone) >= 0) and look.tone.min() >= 0 and look.tone.max() <= 1
    assert look.apply_tone(np.array([0.0]))[0] == 0.0


def test_unseen_colors_fall_back_smoothly():
    # train only on near-neutral colors; saturated hold-out must stay sane (no LUT blow-ups)
    rng = np.random.default_rng(4)
    g = rng.uniform(0.01, 0.9, (20000, 1))
    neutral = np.clip(g * (1 + rng.normal(0, 0.03, (20000, 3))), 1e-4, None) @ SRGB_TO_XYZ.T
    look = fit_look(neutral, _camera(neutral))
    sat = _scene(5000, 5)
    out = look.render(sat)
    assert np.isfinite(out).all() and out.min() >= 0 and out.max() <= 1
    assert evaluate(look, sat, _camera(sat))["mean_de2000"] < 8


def test_gate_logic():
    ok = {"color_mean_de2000": 2.4, "skin_color_mean_de2000": 1.9, "median_de2000": 1.9, "median_abs_dL": 1.9}
    assert passes_gate(ok)
    assert not passes_gate({**ok, "skin_color_mean_de2000": 2.1})
    assert not passes_gate({**ok, "color_mean_de2000": 2.6, "skin_color_mean_de2000": None})
    assert not passes_gate({**ok, "median_abs_dL": 2.1})
    assert not passes_gate({**ok, "median_de2000": 2.1})


def test_save_outputs(tmp_path):
    xt = _scene(5000, 6)
    save(fit_look(xt, _camera(xt), n=9), str(tmp_path))
    cube = (tmp_path / "standard_residual.cube").read_text().splitlines()
    assert "LUT_3D_SIZE 9" in cube[1] and len(cube) == 2 + 9**3
    meta = json.loads((tmp_path / "standard_look.json").read_text())
    assert len(meta["tone"]["values"]) == 257


def test_sample_pair_aligns_and_filters():
    rng = np.random.default_rng(7)
    patches = rng.uniform(0.05, 0.6, (12, 18, 3))
    lin = np.kron(patches, np.ones((40, 40, 1)))  # 480x720 flat patches
    xyz = np.pad(lin @ SRGB_TO_XYZ.T, ((6, 6), (10, 10), (0, 0)), mode="edge")  # RAW has extra margins
    jpeg = srgb_encode(lin)
    x, j = sample_pair(xyz, jpeg, grid=(24, 36), center=0.9, raw_scale=1.0)
    assert len(x) > 100  # blocks straddling patch edges are rejected as not flat
    assert np.allclose(srgb_encode(x @ XYZ_TO_SRGB.T), j, atol=0.02)


def test_center_crop_to_aspect():
    assert center_crop_to_aspect(np.zeros((100, 300, 3)), 1.5).shape[:2] == (100, 150)
    assert center_crop_to_aspect(np.zeros((300, 300, 3)), 1.5).shape[:2] == (200, 300)


def test_exposure_alignment_recovers_offset():
    from redline_cal.look import best_exposure, fit_look_aligned

    rng = np.random.default_rng(3)
    imgs = []
    for ev in (-0.5, 0.0, 0.5):
        xyz = rng.uniform(0.02, 0.6, (400, 3))
        imgs.append((xyz * 2.0**ev, srgb_encode(np.clip(xyz @ XYZ_TO_SRGB.T, 0, 1))))
    look, gains = fit_look_aligned(imgs, n=9)
    assert np.allclose([g.ev for g in gains], [0.5, 0.0, -0.5], atol=0.11)
    assert abs(best_exposure(look, imgs[0][0] * 2.0, imgs[0][1]) - (-0.5)) < 0.11


def test_devignette_recovers_radial_falloff():
    from redline_cal.look import fit_look_aligned

    rng = np.random.default_rng(5)
    imgs, truth = [], []
    for k1 in (-1.0, -0.5, -1.5):
        xyz = rng.uniform(0.02, 0.5, (1500, 3)) @ SRGB_TO_XYZ.T  # in gamut
        r = rng.uniform(0, 0.8, 1500)
        target = srgb_encode(np.clip(xyz @ XYZ_TO_SRGB.T, 0, 1))
        imgs.append((xyz * (2.0 ** (k1 * r**2))[:, None], target, r))  # raw darker at the edge
        truth.append(xyz)
    look, gains = fit_look_aligned(imgs, n=9, exposure=False, vignette=True)
    for g, k1 in zip(gains, (1.0, 0.5, 1.5)):  # recovered log2 gain matches the falloff it undoes
        for rr in (0.4, 0.8):
            assert abs(g.k1 * rr**2 + g.k2 * rr**4 - k1 * rr**2) < 0.08
    x = np.concatenate([g.apply(a, r) for (a, _, r), g in zip(imgs, gains)])
    y = np.concatenate([b for _, b, _ in imgs])
    oracle = fit_look(np.concatenate(truth), y, n=9)  # same model on vignette-free input
    assert evaluate(look, x, y)["mean_de2000"] < evaluate(oracle, np.concatenate(truth), y)["mean_de2000"] + 0.3


def test_sample_pair_radius():
    xyz = np.full((60, 90, 3), 0.2)
    jpeg = np.full((60, 90, 3), 0.5)
    x, j, r = sample_pair(xyz, jpeg, grid=(6, 9), center=0.9, raw_scale=1.0, return_radius=True)
    assert len(r) == len(x) == 54 and r.min() > 0 and r.max() < 0.95
