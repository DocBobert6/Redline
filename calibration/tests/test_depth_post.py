"""B30 depth pre/post-processing: the reference (redline_cal.depth_post) behaves as documented, and the native code
(app/src/main/cpp/depth_post.cpp via depth_post_tool) matches it.

CI builds the host tool and sets REDLINE_DEPTH_TOOL (REDLINE_REQUIRE_NATIVE=1 makes a missing tool fail instead of skip).
"""
from __future__ import annotations

import os
import subprocess

import numpy as np
import pytest

from redline_cal import depth_post as dp

CW, CH, SW, SH = 420, 280, 256, 144


def native(env: str) -> str:
    path = os.environ.get(env)
    if not path or not os.path.exists(path):
        if os.environ.get("REDLINE_REQUIRE_NATIVE") == "1":
            pytest.fail(f"{env} is not built")
        pytest.skip(f"native host tool not built (set {env})")
    return path


def still(w: int, h: int, seed: int = 4) -> np.ndarray:
    rng = np.random.default_rng(seed)
    yy, xx = np.mgrid[0:h, 0:w]
    r = (xx * 200 // w).astype(np.uint8)
    g = (yy * 200 // h).astype(np.uint8)
    b = ((xx // 17 + yy // 13) % 2 * 120 + rng.integers(0, 20, (h, w))).astype(np.uint8)
    rgba = np.stack([r, g, b, np.full_like(r, 255)], -1)
    rgba[: h // 8, : w // 8, :3] = 255  # the still's top-left marker
    return rgba


def scene_maps(c: dp.Canvas):
    """A near block with an aligned image edge and a person mask on a far ramp (canvas coordinates)."""
    yy, xx = np.mgrid[0:CH, 0:CW]
    inside = (xx >= c.ax + c.aw // 3) & (xx < c.ax + 2 * c.aw // 3) & (yy >= 60)
    depth = np.where(inside, 5.0, 0.5 + 1.5 * (CH - yy) / CH).astype(np.float32)
    rgb = np.repeat(np.where(inside, 0.8, 0.2)[..., None], 3, -1).astype(np.float32)
    syy, sxx = np.mgrid[0:SH, 0:SW]
    seg = ((sxx + 0.5) * CW / SW >= c.ax + c.aw // 3) & ((sxx + 0.5) * CW / SW < c.ax + 2 * c.aw // 3) & ((syy + 0.5) * CH / SH >= 60)
    return depth, seg.astype(np.float32), rgb


def test_canvas_and_letterbox():
    c = dp.canvas(2910, 1939, 0)
    assert (c.ax, c.ay, c.aw, c.ah) == (0, 0, 420, 280)
    c = dp.canvas(2910, 1939, 90)
    assert (c.ax, c.aw, c.ah) == (116, 187, 280)
    out, seg, c = dp.prep(still(300, 200), 90)
    assert out.shape == (CH, CW, 3) and seg.shape == (SH, SW, 3)
    assert np.all(out[:, : c.ax] == dp.LETTERBOX)
    # clockwise 90: the still's top-left marker is upright top-right
    assert out[c.ay + 1, c.ax + c.aw - 2].min() > 0.99


def test_analysis_flat_and_wrong_person():
    flat = dp.analyse(np.full((50, 80), 2.0), np.zeros((50, 80)), np.full((50, 80, 3), 0.4))
    assert flat.confidence == 0 and not flat.usable
    c = dp.canvas(600, 400, 0)
    depth, seg, rgb = scene_maps(c)
    mask = dp.resize_bilinear(seg, CW, CH)
    good = dp.analyse(depth, mask, rgb)
    assert good.usable and good.person_nearer > 0.95
    bad = dp.analyse(np.where(mask > 0.5, 0.3, depth), mask, rgb)
    assert not bad.usable and bad.person_nearer < 0.05


@pytest.mark.parametrize("rot", [0, 90, 180, 270])
def test_native_prep_matches_reference(tmp_path, rot):
    tool = native("REDLINE_DEPTH_TOOL")
    img = still(301, 203)
    ref, ref_seg, c = dp.prep(img, rot)
    (tmp_path / "in.rgba").write_bytes(img.tobytes())
    out = subprocess.run([tool, "prep", "301", "203", str(rot), str(tmp_path / "in.rgba"), str(tmp_path / "c.f32"), str(tmp_path / "s.f32")],
                         check=True, capture_output=True, text=True).stdout.split()
    assert out[1:] == [str(c.ax), str(c.ay), str(c.aw), str(c.ah)]
    got = np.fromfile(tmp_path / "c.f32", np.float32).reshape(CH, CW, 3)
    seg = np.fromfile(tmp_path / "s.f32", np.float32).reshape(SH, SW, 3)
    assert np.abs(got - ref).max() < 1e-5
    assert np.abs(seg - ref_seg).max() < 1e-4


@pytest.mark.parametrize("rot", [0, 90, 270])
def test_native_finish_matches_reference(tmp_path, rot):
    tool = native("REDLINE_DEPTH_TOOL")
    c = dp.canvas(600, 400, rot)
    depth, seg, rgb = scene_maps(c)
    rng = np.random.default_rng(rot)
    depth = (depth + rng.normal(0, 0.02, depth.shape)).astype(np.float32)
    ref, a = dp.finish(depth, seg, rgb, c)
    depth.tofile(tmp_path / "d.f32")
    seg.astype(np.float32).tofile(tmp_path / "s.f32")
    rgb.astype(np.float32).tofile(tmp_path / "c.f32")
    out = subprocess.run([tool, "finish", "600", "400", str(rot), str(tmp_path / "d.f32"), str(tmp_path / "s.f32"), str(tmp_path / "c.f32"),
                          str(tmp_path / "o.u16")], check=True, capture_output=True, text=True).stdout.split()
    v = dict(zip(out[3::2], out[4::2]))
    ow, oh = int(out[1]), int(out[2])
    assert (oh, ow) == ref.shape[:2]
    got = np.fromfile(tmp_path / "o.u16", np.uint16).reshape(oh, ow, 3)
    assert np.abs(got.astype(int) - ref.astype(int)).max() <= 2
    assert float(v["conf"]) == pytest.approx(a.confidence, abs=1e-4)
    assert float(v["spread"]) == pytest.approx(a.spread, rel=1e-4)
    assert float(v["person_nearer"]) == pytest.approx(a.person_nearer, abs=1e-4)
    assert float(v["edge_agree"]) == pytest.approx(a.edge_agree, abs=1e-3)


def test_matte_path_person_dominant_and_flat_wall():
    """B31.1: a person filling much of the frame on a floor as near as they are passes on the matte (the depth term alone
    collapses); a flat wall, and a flat wall with an undecided mask, still fail; the 0.35 threshold is unchanged."""
    h, w = 120, 200
    yy, xx = np.mgrid[0:h, 0:w]
    inside = (xx >= 20) & (xx < 180) & (yy >= 10) & (yy < 110)
    depth = 0.5 + 1.0 * yy / h
    mask = inside.astype(np.float64)
    rgb = np.repeat(np.where(inside, 0.75, 0.2)[..., None], 3, 2)
    a = dp.analyse(depth, mask, rgb)
    assert a.depth_confidence < dp.USABLE
    assert a.usable and a.matte_score > 0.9
    wall = 1.0 + 0.002 * xx / w
    flat = dp.analyse(wall, np.zeros((h, w)), np.full((h, w, 3), 0.5))
    assert not flat.usable and flat.matte_score == 0.0
    unsure = np.where(xx < 70, 0.45 + 0.15 * ((xx + yy) % 2), 0.0)
    u = dp.analyse(wall, unsure, np.full((h, w, 3), 0.5))
    assert not u.usable and u.mask_clarity < 0.4
    assert dp.USABLE == 0.35
