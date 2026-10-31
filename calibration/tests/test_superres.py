"""B23 handheld multi-frame super-resolution: the reference's quality on synthetic bursts, and parity of the native
(C++) merge with it.

The parity tests need the host build of ``app/src/test/cpp/superres_tool``; CI builds it and sets ``REDLINE_SR_TOOL``
(with ``REDLINE_REQUIRE_NATIVE=1`` a missing tool fails instead of skipping). The linear-render parity uses
``REDLINE_RAW_TOOL`` (the B21 tool's linear modes).

Parity contract: same reference frame and exactly the same integer displacement for every tile of every frame
(integer maths, shared with B22); flows (sub-pixel, motion model) within FLOW_TOL RAW px on all but a sliver of tiles
(a tile sitting exactly on the model threshold may flip); merged camera RGB within RGB_MEAN_TOL / RGB_P999_TOL.
"""
from __future__ import annotations

import math
import os
import subprocess

import numpy as np
import pytest

from redline_cal import burst_merge as bm
from redline_cal import raw_pipeline as rp
from redline_cal import superres as sr

BLACK = [64.0, 64.0, 64.0, 64.0]
WHITE = 1023.0
NS, NO = 2e-4, 2e-6
FLOW_TOL = 1e-3
RGB_MEAN_TOL = 1e-5
RGB_P999_TOL = 1e-3
PAD = sr.MARGIN + 16


def tool(env: str):
    path = os.environ.get(env)
    if path and os.path.exists(path):
        return path
    if os.environ.get("REDLINE_REQUIRE_NATIVE") == "1":
        pytest.fail(f"{env} not set or missing")
    pytest.skip(f"{env} not set (host tool not built)")


def scene_case(shifts, crop_px=(128, 96), scale=2.0, seed=1, grating=None, moving=None, reference=0, known=False):
    cw, ch = crop_px
    h, w = ch + 2 * PAD, cw + 2 * PAD
    big = sr.hires_scene(h + 16, w + 16, seed=seed, grating=grating)
    frames = sr.textured_burst(big, shifts, h, w, noise_s=NS, noise_o=NO, moving=moving)
    crop = (PAD, PAD, cw, ch)
    out = (int(round(cw * scale)), int(round(ch * scale)))
    r = sr.merge(frames, 0, BLACK, WHITE, NS, NO, crop, out, reference=reference, known_shifts=shifts if known else None)
    ry = PAD + (np.arange(out[1]) + 0.5) * ch / out[1] - 0.5
    rx = PAD + (np.arange(out[0]) + 0.5) * cw / out[0] - 0.5
    s0 = shifts[reference]
    truth = sr.scene_truth(big, ry + s0[0], rx + s0[1])
    single = sr.single_frame_upscale(frames[reference], 0, BLACK, WHITE, crop, out)
    return frames, r, truth, single, crop, out


def rmse(a, b, border=12):
    d = (a - b)[border:-border, border:-border]
    return float(np.sqrt(np.mean(d * d)))


@pytest.fixture(scope="module")
def handheld8():
    return scene_case(sr.random_shifts(8, seed=5))


# ------------------------------------------------------------------------------------------- reference quality


def test_region_is_even_and_clamped():
    assert sr.region_of((100, 50, 400, 300), 640, 480) == (36, 0, 528, 414)
    l, t, w, h = sr.region_of((2, 2, 30, 20), 33 * 2, 31 * 2)
    assert l % 2 == 0 and t % 2 == 0 and w % 2 == 0 and h % 2 == 0
    with pytest.raises(ValueError):
        sr.region_of((1, 2, 30, 20), 64, 64)


def test_subpixel_flow_accuracy(handheld8):
    _, r, *_ = handheld8
    shifts = sr.random_shifts(8, seed=5)
    errs = []
    for i, f in r.flows.items():
        want = np.array(shifts[r.reference]) - np.array(shifts[i])
        errs.append(np.abs(f[3:-3, 3:-3] - want).reshape(-1))
    e = np.concatenate(errs)
    assert np.median(e) < 0.08, np.median(e)
    assert np.percentile(e, 90) < 0.2, np.percentile(e, 90)


def test_handheld_burst_beats_single_frame(handheld8):
    _, r, truth, single, *_ = handheld8
    assert r.coverage > 0.45 and r.sigma == (sr.SIGMA_G, sr.SIGMA_RB)
    gain = rmse(single, truth) / rmse(r.rgb, truth)
    assert gain > 1.35, gain


def test_tripod_burst_widens_the_kernel():
    _, r, truth, single, *_ = scene_case(sr.random_shifts(6, still=True), crop_px=(96, 64))
    assert r.coverage == pytest.approx(0.0)
    assert r.sigma == (sr.SIGMA_WIDE_G, sr.SIGMA_WIDE_RB)
    # No real gain without sub-pixel spread, but no kernel artefacts either: close to the single frame.
    assert rmse(r.rgb, truth) < 1.3 * rmse(single, truth)


def test_one_frame_is_a_wide_kernel_reconstruction():
    _, r, truth, single, *_ = scene_case([(0.0, 0.0)], crop_px=(96, 64))
    assert r.coverage == 0.0 and r.sigma == (sr.SIGMA_WIDE_G, sr.SIGMA_WIDE_RB)
    assert np.isfinite(r.rgb).all()
    assert rmse(r.rgb, truth) < 1.3 * rmse(single, truth)


def test_moving_subject_is_rejected():
    shifts = sr.random_shifts(8, seed=5)
    sq = (3, PAD + 30, PAD + 40, 32)
    _, r, truth, *_ = scene_case(shifts, moving=sq)
    f3 = next(f for f in r.frames if f.index == 3)
    others = [f.rejected_fraction for f in r.frames if f.index != 3]
    assert f3.rejected_fraction > max(others) + 0.01
    # The square must not ghost into the result: error there stays at the static scene's level.
    a, b = int(30 * 2), int(62 * 2)
    c, d = int(40 * 2), int(72 * 2)
    err = np.abs(r.rgb[a:b, c:d] - truth[a:b, c:d]).mean()
    assert err < 0.03, err


def amplitude(img, ry, rx, f, theta):
    yy, xx = np.meshgrid(ry, rx, indexing="ij")
    return sr.fit_amplitude(img @ sr.LUMA_W, yy, xx, f, theta)


def test_detail_beyond_nyquist_on_a_grating_with_texture():
    """A grating at 0.65 cycles/RAW px (above the sensor's 0.5 Nyquist) on textured ground, estimated alignment:
    the merge keeps it, one frame upscaled loses it (the motion model keeps the aliased grating tiles aligned)."""
    f, theta, amp = 0.65, 0.12, 0.25
    shifts = sr.random_shifts(8, seed=5)
    cw, ch = 160, 160
    frames, r, _, single, crop, out = scene_case(shifts, crop_px=(cw, ch), scale=2.05, grating=(f, theta, amp))
    h = ch + 2 * PAD + 16
    lo, hi = h / 4 + 6, 3 * h / 4 - 6
    ry = PAD + (np.arange(out[1]) + 0.5) * ch / out[1] - 0.5
    rx = PAD + (np.arange(out[0]) + 0.5) * cw / out[0] - 0.5
    iy = np.where((ry > lo) & (ry < hi))[0]
    ix = np.where((rx > lo) & (rx < hi))[0]
    sub = np.ix_(iy, ix)
    m_sr = amplitude(r.rgb[sub], ry[iy], rx[ix], f, theta) / amp
    m_one = amplitude(single[sub], ry[iy], rx[ix], f, theta) / amp
    assert m_sr > 0.12 and m_one < 0.05, (m_sr, m_one)
    assert max(fr.model_resolved for fr in r.frames) > 0.1  # the grating's tiles took the motion model


def test_render_linear_matches_render_without_resampling():
    """Stages 4-8 on the demosaicked crop equal the full B21 render at crop size."""
    rng = np.random.default_rng(3)
    raw = rng.integers(64, 1000, (64, 96)).astype(np.uint16)
    frame = rp.RawFrame(raw, 0, (64.0,) * 4, 1023.0, rp.synthetic_shading(4, 5))
    crop = (8, 8, 72, 48)
    matrix = np.array([[1.6, -0.4, -0.2], [-0.3, 1.5, -0.2], [0.0, -0.5, 1.5]])
    neutral = np.array([0.5, 1.0, 0.7])
    tone = np.linspace(0.0, 1.0, 64) ** 0.5
    curve = rp.ToneCurve(tone, 2.0)
    lut = np.stack(np.meshgrid(*(np.linspace(0, 1, 5),) * 3, indexing="ij"), -1)
    full = rp.render(frame, rp.RenderParams(crop, (72, 48), matrix, neutral, curve, lut))
    cam = rp.demosaic_mhc(rp.normalise(frame), 0)[8:56, 8:80]
    lin = rp.render_linear(cam, matrix, neutral, curve, lut)
    np.testing.assert_allclose(lin, full, atol=1e-12)
    st = rp.linear_stats(cam, neutral, matrix, step=4)
    assert st["cells"] == 12 * 18 and 0.0 <= st["clipped_fraction"] <= 1.0


# ------------------------------------------------------------------------------------------- native parity


def check_parity(frames, crop, out, shading=None, reference=-1):
    t = tool("REDLINE_SR_TOOL")
    ref = sr.merge(frames, 0, BLACK, WHITE, NS, NO, crop, out, shading=shading, reference=None if reference < 0 else reference)
    rgb, flows, disps, stats = sr.native_merge(t, frames, 0, BLACK, WHITE, NS, NO, crop, out, shading=shading, reference=reference)
    assert stats["reference"] == ref.reference
    assert tuple(stats["region"]) == ref.region
    for i, d in ref.displacements.items():
        np.testing.assert_array_equal(disps[i], d)
        fe = np.abs(flows[i] - ref.flows[i]).max(axis=-1)
        assert (fe > FLOW_TOL).mean() <= 0.01, (i, (fe > FLOW_TOL).mean())
    assert stats["coverage"] == pytest.approx(ref.coverage, abs=0.02)
    d = np.abs(rgb - ref.rgb)
    assert d.mean() < RGB_MEAN_TOL and np.percentile(d, 99.9) < RGB_P999_TOL, (d.mean(), np.percentile(d, 99.9), d.max())
    for f in stats["frames"]:
        rf = next(x for x in ref.frames if x.index == f["index"])
        assert f["mean_robustness"] == pytest.approx(rf.mean_robustness, abs=2e-3)
        assert f["model_resolved"] == pytest.approx(rf.model_resolved, abs=0.01)
    return stats


def test_native_parity_handheld_grating_shading():
    shifts = sr.random_shifts(8, seed=5)
    h, w = 128 + 2 * 80, 192 + 2 * 80
    big = sr.hires_scene(h + 16, w + 16, seed=2, grating=(0.6, 0.12, 0.2))
    frames = sr.textured_burst(big, shifts, h, w, noise_s=NS, noise_o=NO)
    check_parity(frames, (80, 80, 192, 128), (394, 262), shading=rp.synthetic_shading())


def test_native_parity_whole_quad_shifts_auto_reference():
    frames, _ = bm.synthetic_burst(256, 320, [(0, 0), (2, -1), (-3, 2), (1, 3)], noise_s=NS, noise_o=NO)
    stats = check_parity(frames, (64, 64, 192, 128), (288, 192))
    assert stats["coverage"] == pytest.approx(0.0, abs=1e-9)  # whole quads: one phase


def test_native_parity_single_frame_and_fixed_reference():
    shifts = sr.random_shifts(4, seed=8)
    h, w = 96 + 2 * PAD, 128 + 2 * PAD
    big = sr.hires_scene(h + 16, w + 16, seed=4)
    frames = sr.textured_burst(big, shifts, h, w, noise_s=NS, noise_o=NO)
    check_parity(frames[:1], (PAD, PAD, 128, 96), (256, 192), reference=0)
    check_parity(frames, (PAD, PAD, 128, 96), (200, 150), reference=2)


def test_native_self_check_passes():
    t = tool("REDLINE_SR_TOOL")
    import json

    out = json.loads(subprocess.run([t, "selfcheck", "2"], check=True, capture_output=True, text=True).stdout)
    assert out["passed"], out
    assert out["gain"] > 1.3 and out["flow_error_px"] < 0.1


def test_native_render_linear_parity(tmp_path):
    t = tool("REDLINE_RAW_TOOL")
    rng = np.random.default_rng(11)
    w, h = 40, 30
    cam = np.clip(rng.uniform(-0.05, 1.1, (h, w, 3)), -0.1, 1.2)
    matrix = np.array([[1.7, -0.5, -0.2], [-0.25, 1.45, -0.2], [0.02, -0.6, 1.58]])
    neutral = np.array([0.55, 1.0, 0.72])
    tone = np.linspace(0.0, 1.0, 64) ** 0.6
    curve = rp.ToneCurve(tone, 1.7)
    cube = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "assets", "luts", "redline_standard_preview_33.cube")
    lut = rp.load_lut(cube)
    want = rp.render_linear(cam, matrix, neutral, curve, lut)
    lp = tmp_path / "lin.f32"
    np.ascontiguousarray(cam.transpose(2, 0, 1), dtype="<f4").tofile(lp)
    table = curve.table().astype("<f4")
    cp = tmp_path / "curve.f32"
    table.tofile(cp)
    lines = [f"linear {w} {h} {lp}", "matrix " + " ".join(f"{v:.9g}" for v in matrix.reshape(-1)),
             "neutral " + " ".join(f"{v:.9g}" for v in neutral), f"out {w} {h}", f"gain {curve.gain:.9g}",
             f"curve {len(table)} {rp.EV_MIN:.9g} {curve.clip_ev:.9g} {cp}", f"cube {cube}", "threads 2", "step 3"]
    pp = tmp_path / "p.txt"
    pp.write_text("\n".join(lines) + "\n")
    op = tmp_path / "out.f32"
    subprocess.run([t, "linearf", str(pp), str(op)], check=True, capture_output=True)
    got = np.fromfile(op, dtype="<f4").reshape(h, w, 3)
    assert rp.parity(want, got)["max_de2000"] < 0.5
    out = subprocess.run([t, "linearstats", str(pp)], check=True, capture_output=True, text=True).stdout.split()
    st = rp.linear_stats(cam, neutral, matrix, step=3)
    assert float(out[0]) == pytest.approx(st["median_y"], abs=1e-5)
    assert float(out[1]) == pytest.approx(st["clipped_fraction"], abs=1e-6)
    assert int(out[2]) == st["cells"]
    assert math.isfinite(float(out[0]))
