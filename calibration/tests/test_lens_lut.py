import numpy as np
import pytest

from redline_cal.lens import blur_disc_fraction, distortion_percent, gain_to_ev, pa_vignette
from redline_cal.lut import apply_lut, identity_lut


def test_ptlens_zero_coeffs_is_identity():
    assert distortion_percent(1.5, 0.0, 0.0, 0.0) == pytest.approx(0.0)


def test_rf2470_24mm_is_barrel_at_long_edge():
    # lensfun RF 24-70 F2.8L, 24mm (docs/PLAN.md 19.1): about -1.15% at r = 1.5
    assert distortion_percent(1.5, 0.0259091, -0.0662025, 0.0194773) == pytest.approx(-1.15, abs=0.05)


def test_rf2470_70mm_is_pincushion():
    assert distortion_percent(1.5, -0.00796799, 0.0352717, -0.0302904) > 0


def test_vignette_24mm_f28_corner():
    ev = gain_to_ev(pa_vignette(1.0, -0.5654, -0.9197, 0.7083))
    assert ev == pytest.approx(-2.16, abs=0.05)


def test_blur_disc_70mm_f28_1m():
    assert blur_disc_fraction(70, 2.8, 1.0) * 100 == pytest.approx(5.23, abs=0.05)


def test_identity_lut_roundtrip():
    rgb = np.random.default_rng(0).random((100, 3))
    np.testing.assert_allclose(apply_lut(rgb, identity_lut(17)), rgb, atol=1e-9)
