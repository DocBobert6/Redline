"""B22 burst align + merge: the reference's quality on synthetic bursts, and parity of the native (C++) merge with it.

The parity tests need the host build of ``app/src/test/cpp/burst_merge_tool``; CI builds it and sets
``REDLINE_BURST_TOOL`` (with ``REDLINE_REQUIRE_NATIVE=1`` a missing tool fails instead of skipping).

Parity contract: same reference frame and exactly the same displacement for every tile of every frame (integer
maths), merged RAW within MERGE_TOL RAW units (float accumulation order differs).
"""
from __future__ import annotations

import json
import os
import subprocess

import numpy as np
import pytest

from redline_cal import burst_merge as bm

BLACK = [64.0, 64.0, 64.0, 64.0]
WHITE = 1023.0
NOISE_S, NOISE_O = 2e-3, 2e-5
MERGE_TOL = 0.05
SHIFTS8 = [(0, 0), (3, -2), (-5, 4), (1, 7), (-2, -6), (6, 1), (0, 3), (-4, -1)]


def interior(h0, w0, margin=16):
    g = bm.fine_grid(h0, w0)
    iy = (g.oy >= margin) & (g.oy + bm.FINE_TILE <= h0 - margin)
    ix = (g.ox >= margin) & (g.ox + bm.FINE_TILE <= w0 - margin)
    return np.ix_(iy, ix)


def clean_frame(h, w, shifts, k):
    """Noise-free mosaic of frame k (RAW units), rebuilt the way synthetic_burst builds it."""
    pad = 2 * max(max(abs(a), abs(b)) for a, b in shifts) + 4
    scene = bm.synthetic_scene(h + 2 * pad, w + 2 * pad) * 0.6
    y0, x0 = pad + 2 * shifts[k][0], pad + 2 * shifts[k][1]
    return bm.mosaic(scene[y0 : y0 + h, x0 : x0 + w]) * (WHITE - BLACK[0]) + BLACK[0]


@pytest.fixture(scope="module")
def burst8():
    frames, _ = bm.synthetic_burst(384, 512, SHIFTS8)
    return frames


@pytest.fixture(scope="module")
def merged8(burst8):
    return bm.merge(burst8, BLACK, WHITE, NOISE_S, NOISE_O, reference=0)


# ------------------------------------------------------------------------------------------- reference quality


def test_pyramid_and_grids():
    raw = np.arange(64 * 48, dtype=np.uint16).reshape(48, 64)
    p = bm.pyramid(raw)
    assert [lv.shape for lv in p] == [(24, 32), (12, 16)]  # L2 would be 3x4 < 8: dropped
    assert p[0][0, 0] == int(raw[0, 0]) + raw[0, 1] + raw[1, 0] + raw[1, 1]
    g = bm.fine_grid(24, 32)
    assert g.oy[0] == -8 and len(g.oy) == (24 - 1) // 8 + 2
    # The raised-cosine window sums to 1 over the two overlapping tiles.
    win = bm.window()
    np.testing.assert_allclose(win[:8] + win[8:], 1.0, atol=1e-12)


def test_alignment_recovers_whole_quad_shifts(merged8):
    for k, d in merged8.displacements.items():
        want = np.array(SHIFTS8[0]) - np.array(SHIFTS8[k])
        ok = (d == want).all(axis=-1)[interior(192, 256)]
        assert ok.mean() >= 0.99, (k, ok.mean())


def test_noise_reduction_near_sqrt_n(merged8):
    clean = clean_frame(384, 512, SHIFTS8, 0)
    m = 32
    frame0 = bm.synthetic_burst(384, 512, SHIFTS8)[0][0].astype(float)
    single = np.sqrt(np.mean((frame0 - clean)[m:-m, m:-m] ** 2))
    merged = np.sqrt(np.mean((merged8.merged - clean)[m:-m, m:-m] ** 2))
    ratio = single / merged
    assert ratio >= 0.9 * np.sqrt(len(SHIFTS8)), ratio


def test_identical_frames_are_identity():
    frames, _ = bm.synthetic_burst(96, 128, [(0, 0)])
    r = bm.merge([frames[0]] * 3, BLACK, WHITE, NOISE_S, NOISE_O)
    np.testing.assert_allclose(r.merged, frames[0].astype(float), atol=1e-9)
    assert all(f.max_disp_px == 0 and f.mean_weight > 0.999 for f in r.frames)


def test_moving_object_is_rejected():
    shifts = [(0, 0), (2, 1), (-1, 3), (1, -2)]
    frames, _ = bm.synthetic_burst(256, 320, shifts, moving=(2, 100, 140))
    r = bm.merge(frames, BLACK, WHITE, NOISE_S, NOISE_O, reference=0)
    clean = clean_frame(256, 320, shifts, 0)
    # Where the square sits in the alternate frame (in reference coordinates).
    dy, dx = 2 * (shifts[0][0] - shifts[2][0]), 2 * (shifts[0][1] - shifts[2][1])
    y0, x0 = 100 - dy + 4, 140 - dx + 4
    region = (slice(y0, y0 + 40), slice(x0, x0 + 40))
    ghost_merged = np.abs(r.merged[region] - clean[region]).mean()
    # Baseline: the frames aligned with the true shifts and averaged plainly (no rejection).
    naive = np.mean(
        [np.roll(f.astype(float), (2 * (s[0] - shifts[0][0]), 2 * (s[1] - shifts[0][1])), axis=(0, 1)) for f, s in zip(frames, shifts)],
        axis=0,
    )
    ghost_avg = np.abs(naive[region] - clean[region]).mean()
    print(f"ghost merged {ghost_merged:.2f} vs plain average {ghost_avg:.2f}")
    assert ghost_merged < 0.25 * ghost_avg, (ghost_merged, ghost_avg)
    stats = {f.index: f for f in r.frames}
    assert stats[2].rejected_fraction > stats[1].rejected_fraction


def test_reference_is_the_sharpest_of_the_first_three():
    from scipy import ndimage

    frames, _ = bm.synthetic_burst(128, 160, [(0, 0), (0, 0), (0, 0), (0, 0)])
    blurred = [ndimage.uniform_filter(f.astype(float), 5).astype(np.uint16) for f in frames]
    burst = [blurred[0], frames[1], blurred[2], frames[3]]
    assert bm.pick_reference([bm.pyramid(f) for f in burst]) == 1


def test_bad_input():
    with pytest.raises(ValueError):
        bm.merge([], BLACK, WHITE, NOISE_S, NOISE_O)
    with pytest.raises(ValueError):
        bm.merge([np.zeros((10, 11), np.uint16)], BLACK, WHITE, NOISE_S, NOISE_O)


# ------------------------------------------------------------------------------------------- native parity


@pytest.fixture(scope="module")
def tool():
    path = os.environ.get("REDLINE_BURST_TOOL")
    if not path or not os.path.exists(path):
        if os.environ.get("REDLINE_REQUIRE_NATIVE") == "1":
            pytest.fail("REDLINE_BURST_TOOL is not built")
        pytest.skip("native host tool not built (set REDLINE_BURST_TOOL)")
    return path


def run_native(tool, tmp_path, frames, reference=-1, threads=3, white=WHITE):
    h, w = frames[0].shape
    paths = []
    for i, f in enumerate(frames):
        p = tmp_path / f"f{i}.raw"
        f.astype("<u2").tofile(p)
        paths.append(str(p))
    params = tmp_path / "params.txt"
    params.write_text(
        f"width {w} height {h} black {' '.join(str(b) for b in BLACK)} white {white} noise {NOISE_S} {NOISE_O} "
        f"threads {threads} reference {reference} frames {len(paths)} " + " ".join(paths)
    )
    out, disp = tmp_path / "out.f32", tmp_path / "disp.i32"
    res = subprocess.run([tool, str(params), str(out), str(disp)], capture_output=True, text=True, check=True)
    stats = json.loads(res.stdout)
    merged = np.fromfile(out, dtype="<f4").reshape(h, w)
    d = np.fromfile(disp, dtype="<i4").reshape(len(frames), stats["tiles_y"], stats["tiles_x"], 2)
    return merged, d, stats


CASES = {
    "8 frames, auto reference": dict(shape=(384, 512), shifts=SHIFTS8, reference=None, moving=None),
    "odd tile counts": dict(shape=(202, 334), shifts=[(0, 0), (2, -3), (-4, 1)], reference=None, moving=None),
    "moving object, fixed reference": dict(shape=(256, 320), shifts=[(0, 0), (2, 1), (-1, 3)], reference=1, moving=(2, 100, 140)),
    "two frames": dict(shape=(128, 192), shifts=[(1, 1), (0, 0)], reference=None, moving=None),
}


@pytest.mark.parametrize("name", list(CASES))
def test_native_matches_reference(tool, tmp_path, name):
    c = CASES[name]
    frames, _ = bm.synthetic_burst(*c["shape"], c["shifts"], moving=c["moving"])
    ref = bm.merge(frames, BLACK, WHITE, NOISE_S, NOISE_O, reference=c["reference"])
    merged, disp, stats = run_native(tool, tmp_path, frames, -1 if c["reference"] is None else c["reference"])
    assert stats["reference"] == ref.reference
    for k, d in ref.displacements.items():
        np.testing.assert_array_equal(disp[k], d, err_msg=f"frame {k} displacements")
    err = np.abs(merged.astype(np.float64) - ref.merged)
    assert err.max() <= MERGE_TOL, err.max()
    by_index = {f["index"]: f for f in stats["frames"]}
    for f in ref.frames:
        assert by_index[f.index]["mean_weight"] == pytest.approx(f.mean_weight, abs=1e-5)
        assert by_index[f.index]["rejected_fraction"] == pytest.approx(f.rejected_fraction, abs=1e-6)


def test_native_is_deterministic_across_thread_counts(tool, tmp_path):
    frames, _ = bm.synthetic_burst(192, 256, SHIFTS8[:4])
    a, da, _ = run_native(tool, tmp_path, frames, threads=1)
    b, db, _ = run_native(tool, tmp_path, frames, threads=4)
    np.testing.assert_array_equal(da, db)
    assert np.abs(a - b).max() <= 1e-3


def test_native_matches_reference_14bit(tool, tmp_path):
    """B22a: a 14-bit white level puts the grey above the narrow-accumulator kernels' range, so the native code takes
    its general distance path; the alignment must still match the reference exactly."""
    white = 16383.0
    frames, _ = bm.synthetic_burst(192, 256, SHIFTS8[:4], white=white)
    ref = bm.merge(frames, BLACK, white, NOISE_S, NOISE_O)
    merged, disp, stats = run_native(tool, tmp_path, frames, white=white)
    assert stats["reference"] == ref.reference
    for k, d in ref.displacements.items():
        np.testing.assert_array_equal(disp[k], d, err_msg=f"frame {k} displacements")
    assert np.abs(merged.astype(np.float64) - ref.merged).max() <= MERGE_TOL * 16


def test_grey_saturates_at_16_bits():
    raw = np.full((4, 4), 20000, dtype=np.uint16)
    assert bm.grey(raw).max() == 65535


def test_native_streaming_holds_at_most_three_frames(tool, tmp_path):
    frames, _ = bm.synthetic_burst(128, 192, SHIFTS8)
    _, _, stats = run_native(tool, tmp_path, frames)
    assert stats["max_held_frames"] == 3
    assert stats["threads"] == 3
