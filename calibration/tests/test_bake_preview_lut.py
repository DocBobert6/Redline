"""B20: the preview-LUT baker runs end to end on a tiny synthetic look."""
import importlib.util
import json
import os

import numpy as np
import pytest

from redline_cal.color import SRGB_TO_XYZ, srgb_decode, srgb_encode
from redline_cal.look import EV_MAX, EV_MIN, TONE_POINTS, Look
from redline_cal.lut import apply_lut, identity_lut, read_cube, write_cube

_PATH = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "tools", "bake_preview_lut.py")
_spec = importlib.util.spec_from_file_location("bake_preview_lut", _PATH)
bake_preview_lut = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(bake_preview_lut)


def _synthetic_look_dir(tmp_path, n=5):
    # Tone curve = the sRGB transfer itself; residual = a mild channel twist (red up, blue down).
    ev = np.linspace(EV_MIN, EV_MAX, TONE_POINTS)
    tone = np.clip(srgb_encode(2.0**ev), 0, 1)
    ident = identity_lut(n)
    lut = ident.copy()
    lut[..., 0] = np.clip(ident[..., 0] * 1.05, 0, 1)
    lut[..., 2] = ident[..., 2] * 0.9
    d = tmp_path / "look"
    d.mkdir()
    write_cube(str(d / "standard_residual.cube"), lut, title="synthetic")
    with open(d / "standard_look.json", "w") as f:
        json.dump(Look(tone=tone, lut=lut).to_json(), f)
    with open(d / "report.json", "w") as f:
        json.dump({"holdout": {"color_mean_de2000": 1.0, "median_de2000": 1.0, "median_abs_dL": 1.0}, "settings": {"robust": 3}}, f)
    return d, lut


def test_read_cube_roundtrip(tmp_path):
    lut = np.random.default_rng(1).random((4, 4, 4, 3))
    write_cube(str(tmp_path / "a.cube"), lut)
    assert np.allclose(read_cube(str(tmp_path / "a.cube")), lut, atol=1e-6)


def test_read_cube_rejects_bad_count(tmp_path):
    (tmp_path / "bad.cube").write_text("LUT_3D_SIZE 2\n0 0 0\n1 1 1\n")
    with pytest.raises(ValueError):
        read_cube(str(tmp_path / "bad.cube"))


def test_residual_mode_cli_writes_parseable_cube(tmp_path):
    d, lut = _synthetic_look_dir(tmp_path)
    out = tmp_path / "out" / "preview.cube"
    assert bake_preview_lut.main(["--look-dir", str(d), "--out", str(out), "--size", "9", "--source-name", "synthetic"]) == 0
    text = out.read_text()
    assert "mode=residual" in text and "robust=3" in text and "DOMAIN_MAX 1.0 1.0 1.0" in text
    baked = read_cube(str(out))
    assert baked.shape == (9, 9, 9, 3)
    assert baked.min() >= 0.0 and baked.max() <= 1.0
    # Residual mode is R indexed by the encoded preview value.
    grid = identity_lut(9).reshape(-1, 3)
    assert np.allclose(baked.reshape(-1, 3), np.clip(apply_lut(grid, lut), 0, 1), atol=1e-6)


def test_compose_mode_matches_the_scene_model(tmp_path):
    d, _ = _synthetic_look_dir(tmp_path)
    look = bake_preview_lut.load_look(str(d))
    baked = bake_preview_lut.bake(look, size=5, mode="compose", exposure_ev=0.0)
    grid = identity_lut(5).reshape(-1, 3)
    expected = look.render(srgb_decode(grid) @ SRGB_TO_XYZ.T)
    assert np.allclose(baked.reshape(-1, 3), expected, atol=1e-6)
    # With T = the sRGB transfer, compose and residual agree up to the tone grid (decode then T ~ identity).
    residual = bake_preview_lut.bake(look, size=5, mode="residual")
    assert np.allclose(baked, residual, atol=5e-3)


def test_unknown_mode_and_model_are_refused(tmp_path):
    d, _ = _synthetic_look_dir(tmp_path)
    look = bake_preview_lut.load_look(str(d))
    with pytest.raises(ValueError):
        bake_preview_lut.bake(look, mode="nope")
    meta = json.loads((d / "standard_look.json").read_text())
    meta["model"] = "something else"
    (d / "standard_look.json").write_text(json.dumps(meta))
    with pytest.raises(ValueError):
        bake_preview_lut.load_look(str(d))
