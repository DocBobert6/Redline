"""B04: factory-calibration profile maths on synthetic data (no device data in git)."""
import json

import numpy as np
import pytest

from redline_cal import dng_profile as dp
from redline_cal import flatfield as ff
from redline_cal import lensfun_import as lf

# Synthetic but realistic matrices (not any real device's).
CM_A = [[1.10, -0.60, 0.03], [-0.30, 1.15, 0.15], [0.00, 0.14, 0.65]]
CM_D65 = [[0.84, -0.20, -0.12], [-0.58, 1.48, 0.10], [-0.19, 0.40, 0.46]]


def _fm_from_cm(cm, white_xyz):
    """Forward matrix consistent with a colour matrix (white -> D50 by Bradford)."""
    cam_to_xyz = np.linalg.inv(np.asarray(cm))
    neutral = np.asarray(cm) @ white_xyz
    fm = dp.bradford(white_xyz, dp.D50_XYZ) @ cam_to_xyz @ np.diag(neutral)
    return (fm * (dp.D50_XYZ[1] / (fm @ np.ones(3))[1])).tolist()


A_XYZ = dp.xy_to_xyz((0.44757, 0.40745))
D65_XYZ = dp.xy_to_xyz((0.31271, 0.32902))


def _cal(**kw):
    base = dict(
        illuminant1=17,
        illuminant2=21,
        color_matrix1=CM_A,
        color_matrix2=CM_D65,
        forward_matrix1=_fm_from_cm(CM_A, A_XYZ),
        forward_matrix2=_fm_from_cm(CM_D65, D65_XYZ),
        calibration1=[[1.01, 0, 0], [0, 1, 0], [0, 0, 0.99]],
        calibration2=[[1.01, 0, 0], [0, 1, 0], [0, 0, 0.99]],
    )
    base.update(kw)
    return dp.ColorCalibration(**base)


def test_forward_matrix_rows_sum_to_d50():
    for fm in (_cal().forward_matrix1, _cal().forward_matrix2):
        assert np.allclose(np.asarray(fm) @ np.ones(3), dp.D50_XYZ, atol=1e-6)


def test_interpolation_weight_inverse_cct_and_clamp():
    assert dp.interpolation_weight(2856, 2856, 6504) == pytest.approx(1.0)
    assert dp.interpolation_weight(6504, 2856, 6504) == pytest.approx(0.0)
    assert dp.interpolation_weight(1500, 2856, 6504) == 1.0
    assert dp.interpolation_weight(10000, 2856, 6504) == 0.0
    mid = 1 / ((1 / 2856 + 1 / 6504) / 2)
    assert dp.interpolation_weight(mid, 2856, 6504) == pytest.approx(0.5)


def test_mccamy_cct():
    assert dp.xy_to_cct((0.31271, 0.32902)) == pytest.approx(6504, abs=10)
    assert dp.xy_to_cct((0.44757, 0.40745)) == pytest.approx(2856, abs=10)


@pytest.mark.parametrize("xy", [(0.44757, 0.40745), (0.31271, 0.32902), (0.3457, 0.3585), (0.38, 0.38)])
def test_neutral_maps_to_d50_white(xy):
    m = dp.camera_to_xyz(_cal(), white_xy=xy)
    assert dp.neutral_error_de(m) < 1e-6
    assert dp.neutral_error_de(m, use_forward=False) < 1e-6


def test_paths_agree_for_consistent_matrices_at_reference_illuminants():
    m = dp.camera_to_xyz(_cal(), white_xy=(0.31271, 0.32902))
    assert dp.path_agreement_de(m) < 1e-3


def test_white_from_neutral_round_trip():
    cal = _cal()
    m = dp.camera_to_xyz(cal, white_xy=(0.38, 0.38))
    xy, cct, g = dp.solve_white(cal, neutral=m.camera_neutral)
    assert xy[0] == pytest.approx(0.38, abs=1e-4) and xy[1] == pytest.approx(0.38, abs=1e-4)
    assert 0 < g < 1


def test_single_illuminant_and_missing_forward_fallback():
    cal = _cal(illuminant2=None, color_matrix2=None, forward_matrix1=None, forward_matrix2=None, calibration2=None)
    m = dp.camera_to_xyz(cal, white_xy=(0.3457, 0.3585))
    assert m.forward_path is None and dp.neutral_error_de(m) < 1e-6
    with pytest.raises(dp.ProfileError):
        dp.path_agreement_de(m)


def test_invalid_metadata_rejected():
    with pytest.raises(dp.ProfileError):
        _cal(color_matrix1=[[0, 0, 0], [0, 0, 0], [0, 0, 0]]).validate()
    with pytest.raises(dp.ProfileError):
        _cal(illuminant1=99).validate()
    with pytest.raises(dp.ProfileError):
        dp.color_from_camera2({"SENSOR_REFERENCE_ILLUMINANT1": 21})
    with pytest.raises(dp.ProfileError):
        dp.color_from_dng_tags({"IFD0:Model": "x"})


def test_camera2_and_dng_sources_compare():
    c = _cal()
    flat = lambda m: [round(x, 4) for row in m for x in row]  # noqa: E731
    cam2 = {
        "SENSOR_REFERENCE_ILLUMINANT1": 17,
        "SENSOR_REFERENCE_ILLUMINANT2": 21,
        "SENSOR_COLOR_TRANSFORM1": flat(c.color_matrix1),
        "SENSOR_COLOR_TRANSFORM2": flat(c.color_matrix2),
        "SENSOR_FORWARD_MATRIX1": flat(c.forward_matrix1),
        "SENSOR_FORWARD_MATRIX2": flat(c.forward_matrix2),
        "SENSOR_CALIBRATION_TRANSFORM1": flat(c.calibration1),
        "SENSOR_CALIBRATION_TRANSFORM2": flat(c.calibration2),
    }
    dng = {
        "IFD0:CalibrationIlluminant1": 17,
        "IFD0:CalibrationIlluminant2": 21,
        "IFD0:ColorMatrix1": " ".join(map(str, cam2["SENSOR_COLOR_TRANSFORM1"])),
        "IFD0:ColorMatrix2": " ".join(map(str, cam2["SENSOR_COLOR_TRANSFORM2"])),
        "IFD0:ForwardMatrix1": " ".join(map(str, cam2["SENSOR_FORWARD_MATRIX1"])),
        "IFD0:ForwardMatrix2": " ".join(map(str, cam2["SENSOR_FORWARD_MATRIX2"])),
        "IFD0:CameraCalibration1": " ".join(map(str, cam2["SENSOR_CALIBRATION_TRANSFORM1"])),
        "IFD0:CameraCalibration2": " ".join(map(str, cam2["SENSOR_CALIBRATION_TRANSFORM2"])),
    }
    assert dp.compare_calibrations(dp.color_from_camera2(cam2), dp.color_from_dng_tags(dng)) == []
    cam2["SENSOR_FORWARD_MATRIX2"][0] += 0.01
    assert dp.compare_calibrations(dp.color_from_camera2(cam2), dp.color_from_dng_tags(dng))


def test_noise_profile_parse_and_model():
    pairs = dp.parse_noise_profile("1e-5 2e-7 0.8e-5 1.5e-7 1e-5 2e-7")
    assert len(pairs) == 3
    assert dp.noise_sigma(0.0, *pairs[0]) == pytest.approx(np.sqrt(2e-7))
    with pytest.raises(dp.ProfileError):
        dp.parse_noise_profile("1e-5")
    with pytest.raises(dp.ProfileError):
        dp.parse_noise_profile("-1 0")
    pts = [(iso, 2e-7 * iso, 1e-11 * iso**2 + 5e-8) for iso in (50, 100, 400, 1600)]
    m = dp.fit_noise_model(pts)
    assert m.s_slope == pytest.approx(2e-7)
    s, o = m.at(800)
    assert s == pytest.approx(1.6e-4) and o == pytest.approx(1e-11 * 800**2 + 5e-8, rel=1e-6)


def _vignette_gainmap(planes=1, pv=9, ph=12, corner_gain=4.0):
    v, h = np.meshgrid(np.linspace(0, 1, pv), np.linspace(0, 1, ph), indexing="ij")
    r2 = ((v - 0.5) ** 2 + (h - 0.5) ** 2) / 0.5
    g = 1 + (corner_gain - 1) * r2
    return dp.GainMap(0, 0, 60, 80, 0, 1, 1, 1, pv, ph, 1 / (pv - 1), 1 / (ph - 1), 0.0, 0.0, planes, g[..., None].repeat(planes, 2))


def test_opcode_round_trip():
    gm = _vignette_gainmap()
    wr = dp.WarpRectilinear([(0.98, 0.1, -0.27, 0.19, 0.0, 0.0)], (0.5, 0.5))
    ops = dp.parse_opcode_list(dp.build_opcode_list([(dp.OP_GAIN_MAP, gm), (dp.OP_WARP_RECTILINEAR, wr), (42, b"\x00\x01")]))
    assert ops[0][0] == dp.OP_GAIN_MAP and np.allclose(ops[0][1].gains, gm.gains, atol=1e-6)
    assert ops[0][1].corner_ev() == pytest.approx(2.0, abs=0.05)
    assert ops[1][1].distortion_percent() == pytest.approx(0.0, abs=1e-9)
    assert ops[2] == (42, b"\x00\x01")
    with pytest.raises(dp.ProfileError):
        dp.parse_opcode_list(b"\x00")


def test_flat_field_gain_map_flattens_vignetted_wall():
    gm = _vignette_gainmap()
    yy, xx = np.mgrid[0:60, 0:80]
    gain = gm.gain_at(yy / 60, xx / 80)
    wall = 0.5 / gain
    r = ff.flat_field_report(wall, [gm], block=4)
    assert r.falloff_before_ev < -1.0
    assert abs(r.falloff_after_ev) < 0.05 and r.passes()


def test_device_profile_json_round_trip():
    p = dp.DeviceProfile("synthetic", "0", _cal(), noise=[(1e-5, 2e-7)], noise_model=dp.NoiseModel(1e-7, 1e-11, 5e-8, 3))
    q = dp.DeviceProfile.from_json(p.to_json())
    assert q.color.color_matrix2 == p.color.color_matrix2 and q.noise_model.samples == 3
    assert json.loads(p.to_json())["profile_id"] == "synthetic"


LENSFUN_XML = """<lensdatabase version="2">
 <lens><maker>Example</maker><model>Example 24-70mm F2.8</model><mount>X</mount><cropfactor>1</cropfactor>
  <calibration>
   <distortion model="ptlens" focal="24" a="0.02" b="-0.06" c="0.02"/>
   <distortion model="ptlens" focal="70" a="0.00" b="0.02" c="0.00"/>
   <vignetting model="pa" focal="24" aperture="2.8" distance="1000" k1="-0.6" k2="0.0" k3="0.1"/>
   <vignetting model="pa" focal="24" aperture="5.6" distance="1000" k1="-0.4" k2="0.0" k3="0.1"/>
   <tca model="poly3" focal="24" vr="1" vb="1"/>
  </calibration></lens>
</lensdatabase>"""


def test_lensfun_import_and_interpolation():
    p = lf.parse_lensfun(LENSFUN_XML, "example 24-70mm f2.8")
    assert p.focals() == [24.0, 70.0] and p.apertures(24.0) == [2.8, 5.6]
    a, b, c = p.distortion_at(47.0)
    assert a == pytest.approx(0.01) and b == pytest.approx(-0.02)
    k1, _, _ = p.vignetting_at(24.0, 2.8 * 2**0.5)  # one stop from both ends -> midpoint
    assert k1 == pytest.approx(-0.5, abs=1e-6)
    assert p.vignetting_at(30.0, 1.4)[0] == pytest.approx(-0.6)
    with pytest.raises(KeyError):
        lf.parse_lensfun(LENSFUN_XML, "nope")
