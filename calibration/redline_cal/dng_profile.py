"""Phone colour/noise/shading/distortion profiles from factory calibration (PLAN 9.6, 9.8).

No colour chart is needed. Every Camera2 RAW-capable phone stores per-unit
factory calibration (reference illuminants, colour/forward/calibration
matrices, noise profile, lens shading, distortion). The same values are written
into the phone's own DNGs, so this module reads them from either source:

* a Camera2 export (JSON written by the app's capability probe), or
* a DNG (tags read with exiftool; opcode lists parsed here).

Implements the DNG 1.6 spec section 6 camera->XYZ(D50) mapping with
two-illuminant interpolation, the Poisson-Gaussian noise model, GainMap
(OpcodeList2) and WarpRectilinear (OpcodeList3) parsing.
"""
from __future__ import annotations

import json
import math
import struct
from dataclasses import asdict, dataclass, field
from typing import Any

import numpy as np

D50_XYZ = np.array([0.9642, 1.0, 0.8249])  # DNG PCS white (ICC D50)

# EXIF LightSource / Camera2 SENSOR_REFERENCE_ILLUMINANT codes -> CCT (K).
ILLUMINANT_CCT = {
    1: 5500.0,  # daylight
    2: 4150.0,  # fluorescent
    3: 2856.0,  # tungsten
    4: 5500.0,  # flash
    9: 5500.0,  # fine weather
    10: 6500.0,  # cloudy
    11: 7500.0,  # shade
    12: 6430.0,  # daylight fluorescent
    13: 5000.0,  # day white fluorescent
    14: 4150.0,  # cool white fluorescent
    15: 3450.0,  # white fluorescent
    16: 2940.0,  # warm white fluorescent (F4)
    17: 2856.0,  # standard A
    18: 4874.0,  # standard B
    19: 6774.0,  # standard C
    20: 5503.0,  # D55
    21: 6504.0,  # D65
    22: 7504.0,  # D75
    23: 5003.0,  # D50
    24: 3200.0,  # ISO studio tungsten
}

BRADFORD = np.array([[0.8951, 0.2664, -0.1614], [-0.7502, 1.7135, 0.0367], [0.0389, -0.0685, 1.0296]])


class ProfileError(ValueError):
    """Calibration metadata is missing or unusable; caller must fall back."""


# --------------------------------------------------------------------------- colour


@dataclass
class ColorCalibration:
    illuminant1: int
    illuminant2: int | None
    color_matrix1: list[list[float]]
    color_matrix2: list[list[float]] | None = None
    forward_matrix1: list[list[float]] | None = None
    forward_matrix2: list[list[float]] | None = None
    calibration1: list[list[float]] | None = None
    calibration2: list[list[float]] | None = None
    analog_balance: list[float] = field(default_factory=lambda: [1.0, 1.0, 1.0])

    def validate(self) -> None:
        for name in ("color_matrix1", "color_matrix2", "forward_matrix1", "forward_matrix2", "calibration1", "calibration2"):
            m = getattr(self, name)
            if m is None:
                continue
            a = np.asarray(m, dtype=float)
            if a.shape != (3, 3) or not np.all(np.isfinite(a)):
                raise ProfileError(f"{name} must be a finite 3x3 matrix")
            if abs(np.linalg.det(a)) < 1e-9:
                raise ProfileError(f"{name} is singular")
        if self.illuminant1 not in ILLUMINANT_CCT:
            raise ProfileError(f"unsupported reference illuminant {self.illuminant1}")
        if self.color_matrix2 is not None and self.illuminant2 not in ILLUMINANT_CCT:
            raise ProfileError(f"unsupported reference illuminant {self.illuminant2}")


def _m(v: Any) -> list[list[float]] | None:
    if v is None:
        return None
    if isinstance(v, str):
        v = [float(x) for x in v.split()]
    a = np.asarray(v, dtype=float).reshape(-1)
    if a.size != 9:
        raise ProfileError(f"expected 9 values, got {a.size}")
    return a.reshape(3, 3).tolist()


def xy_to_xyz(xy: tuple[float, float]) -> np.ndarray:
    x, y = xy
    return np.array([x / y, 1.0, (1.0 - x - y) / y])


def xyz_to_xy(xyz: np.ndarray) -> tuple[float, float]:
    s = float(np.sum(xyz))
    return float(xyz[0] / s), float(xyz[1] / s)


def xy_to_cct(xy: tuple[float, float]) -> float:
    """McCamy's approximation; accurate to a few K over 2000-12500 K, enough for weighting."""
    x, y = xy
    n = (x - 0.3320) / (0.1858 - y)
    return 449.0 * n**3 + 3525.0 * n**2 + 6823.3 * n + 5520.33


def interpolation_weight(cct: float, cct1: float, cct2: float) -> float:
    """DNG spec: linear in inverse CCT, clamped; returns weight of matrix 1."""
    if cct1 == cct2:
        return 1.0
    lo, hi = (cct1, cct2) if cct1 < cct2 else (cct2, cct1)
    c = min(max(cct, lo), hi)
    g = (1.0 / c - 1.0 / cct2) / (1.0 / cct1 - 1.0 / cct2)
    return float(min(max(g, 0.0), 1.0))


def _blend(a: Any, b: Any, g: float) -> np.ndarray | None:
    if a is None:
        return None
    a = np.asarray(a, float)
    if b is None:
        return a
    return g * a + (1.0 - g) * np.asarray(b, float)


def bradford(src_xyz: np.ndarray, dst_xyz: np.ndarray) -> np.ndarray:
    s = BRADFORD @ src_xyz
    d = BRADFORD @ dst_xyz
    return np.linalg.inv(BRADFORD) @ np.diag(d / s) @ BRADFORD


@dataclass
class CameraToXYZ:
    white_xy: tuple[float, float]
    cct: float
    weight1: float
    camera_neutral: list[float]
    forward_path: list[list[float]] | None  # camera RGB -> XYZ D50 via forward matrices
    color_path: list[list[float]]  # camera RGB -> XYZ D50 via colour matrices + Bradford


def _xyz_to_camera(cal: ColorCalibration, g: float) -> np.ndarray:
    cm = _blend(cal.color_matrix1, cal.color_matrix2, g)
    cc = _blend(cal.calibration1, cal.calibration2, g)
    cc = np.eye(3) if cc is None else cc
    ab = np.diag(cal.analog_balance)
    return ab @ cc @ cm


def solve_white(cal: ColorCalibration, *, white_xy: tuple[float, float] | None = None, neutral: list[float] | None = None) -> tuple[tuple[float, float], float, float]:
    """Return (white xy, CCT, weight1) from AsShotWhiteXY or AsShotNeutral (iterative, DNG spec)."""
    cal.validate()
    cct1 = ILLUMINANT_CCT[cal.illuminant1]
    cct2 = ILLUMINANT_CCT[cal.illuminant2] if cal.illuminant2 is not None else cct1
    if white_xy is not None:
        cct = xy_to_cct(white_xy)
        return white_xy, cct, interpolation_weight(cct, cct1, cct2)
    if neutral is None:
        raise ProfileError("need white_xy or neutral")
    xy = (0.3457, 0.3585)  # start at D50
    g = 0.5
    for _ in range(30):
        cct = xy_to_cct(xy)
        g = interpolation_weight(cct, cct1, cct2)
        xyz = np.linalg.solve(_xyz_to_camera(cal, g), np.asarray(neutral, float))
        new = xyz_to_xy(xyz)
        if abs(new[0] - xy[0]) + abs(new[1] - xy[1]) < 1e-7:
            xy = new
            break
        xy = new
    cct = xy_to_cct(xy)
    return xy, cct, interpolation_weight(cct, cct1, cct2)


def camera_to_xyz(cal: ColorCalibration, *, white_xy: tuple[float, float] | None = None, neutral: list[float] | None = None) -> CameraToXYZ:
    xy, cct, g = solve_white(cal, white_xy=white_xy, neutral=neutral)
    x2c = _xyz_to_camera(cal, g)
    white = xy_to_xyz(xy)
    cam_neutral = x2c @ white
    cam_neutral = cam_neutral / cam_neutral.max()
    # Colour-matrix path: camera -> XYZ at scene white, adapt to D50.
    color_path = bradford(white, D50_XYZ) @ np.linalg.inv(x2c)
    color_path = color_path * (D50_XYZ[1] / (color_path @ cam_neutral)[1])
    forward = None
    fm = _blend(cal.forward_matrix1, cal.forward_matrix2, g)
    if fm is not None:
        cc = _blend(cal.calibration1, cal.calibration2, g)
        cc = np.eye(3) if cc is None else cc
        abcc = np.diag(cal.analog_balance) @ cc
        ref_neutral = np.linalg.solve(abcc, cam_neutral)
        d = np.diag(1.0 / ref_neutral)
        forward = fm @ d @ np.linalg.inv(abcc)
    return CameraToXYZ(xy, cct, g, cam_neutral.tolist(), None if forward is None else forward.tolist(), color_path.tolist())


def _lab_d50(xyz: np.ndarray) -> np.ndarray:
    t = xyz / D50_XYZ
    f = np.where(t > (6 / 29) ** 3, np.cbrt(t), t / (3 * (6 / 29) ** 2) + 4 / 29)
    return np.array([116 * f[1] - 16, 500 * (f[0] - f[1]), 200 * (f[1] - f[2])])


def neutral_error_de(m: CameraToXYZ, use_forward: bool = True) -> float:
    """dE76 (Lab D50) between the mapped camera neutral and the PCS white; 0 = perfect."""
    mat = m.forward_path if (use_forward and m.forward_path is not None) else m.color_path
    xyz = np.asarray(mat) @ np.asarray(m.camera_neutral)
    xyz = xyz / xyz[1]
    return float(np.linalg.norm(_lab_d50(xyz) - _lab_d50(D50_XYZ)))


def path_agreement_de(m: CameraToXYZ, probes: np.ndarray | None = None) -> float:
    """Max dE76 between forward-matrix and colour-matrix paths over camera-RGB probes.

    The two factory matrix sets are fitted independently, so their agreement is a
    free sanity check of the calibration (large values mean one set is bad).
    """
    if m.forward_path is None:
        raise ProfileError("no forward matrices")
    if probes is None:
        n = np.asarray(m.camera_neutral)
        probes = np.array([n, n * [1, 0.8, 0.8], n * [0.8, 1, 0.8], n * [0.8, 0.8, 1], n * [1, 0.9, 0.7], n * [0.5, 0.5, 0.5]])
    fp, cp = np.asarray(m.forward_path), np.asarray(m.color_path)
    return float(max(np.linalg.norm(_lab_d50(fp @ p) - _lab_d50(cp @ p)) for p in probes))


# --------------------------------------------------------------------------- noise


def parse_noise_profile(v: Any) -> list[tuple[float, float]]:
    """DNG NoiseProfile / Camera2 SENSOR_NOISE_PROFILE -> [(S, O)] per CFA plane."""
    if isinstance(v, str):
        v = [float(x) for x in v.split()]
    a = [float(x) for x in np.asarray(v, float).reshape(-1)]
    if len(a) < 2 or len(a) % 2:
        raise ProfileError("noise profile needs (S, O) pairs")
    pairs = [(a[i], a[i + 1]) for i in range(0, len(a), 2)]
    for s, o in pairs:
        if s <= 0 or o < 0 or not math.isfinite(s) or not math.isfinite(o):
            raise ProfileError("noise profile values out of range")
    return pairs


def noise_sigma(signal: np.ndarray | float, s: float, o: float) -> np.ndarray:
    """Poisson-Gaussian model, signal normalised to [0, 1]: sigma = sqrt(S*x + O)."""
    x = np.clip(np.asarray(signal, float), 0.0, None)
    return np.sqrt(s * x + o)


@dataclass
class NoiseModel:
    """S(iso) = s_slope*iso ; O(iso) = o_quad*iso^2 + o_floor (read noise before/after gain)."""

    s_slope: float
    o_quad: float
    o_floor: float
    samples: int

    def at(self, iso: float) -> tuple[float, float]:
        return self.s_slope * iso, max(self.o_quad * iso * iso + self.o_floor, 0.0)


def fit_noise_model(points: list[tuple[float, float, float]]) -> NoiseModel:
    """Fit from (iso, S, O) samples (any number of captures)."""
    if not points:
        raise ProfileError("no noise samples")
    iso = np.array([p[0] for p in points], float)
    s = np.array([p[1] for p in points], float)
    o = np.array([p[2] for p in points], float)
    s_slope = float(np.dot(iso, s) / np.dot(iso, iso))
    if len(points) >= 2 and np.ptp(iso) > 0:
        a = np.stack([iso**2, np.ones_like(iso)], 1)
        (oq, of), *_ = np.linalg.lstsq(a, o, rcond=None)
    else:
        oq, of = float(o[0] / iso[0] ** 2), 0.0
    return NoiseModel(s_slope, float(oq), float(of), len(points))


# --------------------------------------------------------------------------- opcodes


@dataclass
class GainMap:
    top: int
    left: int
    bottom: int
    right: int
    plane: int
    planes: int
    row_pitch: int
    col_pitch: int
    points_v: int
    points_h: int
    spacing_v: float
    spacing_h: float
    origin_v: float
    origin_h: float
    map_planes: int
    gains: np.ndarray  # (points_v, points_h, map_planes)

    def gain_at(self, v: np.ndarray, h: np.ndarray) -> np.ndarray:
        """Bilinear gain at relative image coords (0..1), map plane 0."""
        fv = np.clip((np.asarray(v, float) - self.origin_v) / self.spacing_v, 0, self.points_v - 1)
        fh = np.clip((np.asarray(h, float) - self.origin_h) / self.spacing_h, 0, self.points_h - 1)
        v0 = np.minimum(np.floor(fv).astype(int), self.points_v - 2) if self.points_v > 1 else np.zeros_like(fv, int)
        h0 = np.minimum(np.floor(fh).astype(int), self.points_h - 2) if self.points_h > 1 else np.zeros_like(fh, int)
        tv, th = fv - v0, fh - h0
        g = self.gains[..., 0]
        v1 = np.minimum(v0 + 1, self.points_v - 1)
        h1 = np.minimum(h0 + 1, self.points_h - 1)
        return (g[v0, h0] * (1 - tv) * (1 - th) + g[v0, h1] * (1 - tv) * th + g[v1, h0] * tv * (1 - th) + g[v1, h1] * tv * th)

    def corner_ev(self) -> float:
        return float(math.log2(self.gains[..., 0].max() / self.gains[..., 0].min()))


@dataclass
class WarpRectilinear:
    planes: list[tuple[float, float, float, float, float, float]]  # kr0..kr3, kt0, kt1
    center: tuple[float, float]  # normalised (x, y)

    def radial_scale(self, r: np.ndarray | float, plane: int = 0) -> np.ndarray:
        kr0, kr1, kr2, kr3, _, _ = self.planes[plane]
        r = np.asarray(r, float)
        return kr0 + kr1 * r**2 + kr2 * r**4 + kr3 * r**6

    def distortion_percent(self, r: float = 1.0, plane: int = 0) -> float:
        """Displacement at normalised radius r (DNG: r = 1 at the farthest corner)."""
        return float((self.radial_scale(r, plane) - 1.0) * 100.0)


OP_WARP_RECTILINEAR = 1
OP_GAIN_MAP = 9


def parse_opcode_list(blob: bytes) -> list[tuple[int, Any]]:
    """Parse a DNG opcode list (always big-endian). Unknown opcodes are returned raw."""
    if len(blob) < 4:
        raise ProfileError("opcode list too short")
    (count,) = struct.unpack_from(">I", blob, 0)
    off = 4
    out: list[tuple[int, Any]] = []
    for _ in range(count):
        op_id, _ver, _flags, n = struct.unpack_from(">IIII", blob, off)
        off += 16
        p = blob[off : off + n]
        if len(p) != n:
            raise ProfileError("truncated opcode")
        off += n
        if op_id == OP_GAIN_MAP:
            ints = struct.unpack_from(">8I", p, 0)
            pv, ph = struct.unpack_from(">II", p, 32)
            sv, sh, ov, oh = struct.unpack_from(">4d", p, 40)
            (mp,) = struct.unpack_from(">I", p, 72)
            k = pv * ph * mp
            g = np.frombuffer(p, dtype=">f4", count=k, offset=76).astype(float).reshape(pv, ph, mp)
            out.append((op_id, GainMap(*ints, pv, ph, sv, sh, ov, oh, mp, g)))
        elif op_id == OP_WARP_RECTILINEAR:
            (np_,) = struct.unpack_from(">I", p, 0)
            planes = [struct.unpack_from(">6d", p, 4 + 48 * i) for i in range(np_)]
            cx, cy = struct.unpack_from(">2d", p, 4 + 48 * np_)
            out.append((op_id, WarpRectilinear([tuple(x) for x in planes], (cx, cy))))
        else:
            out.append((op_id, p))
    return out


def build_opcode_list(ops: list[tuple[int, Any]]) -> bytes:
    """Inverse of parse_opcode_list for GainMap / WarpRectilinear (used by tests)."""
    body = b""
    for op_id, o in ops:
        if op_id == OP_GAIN_MAP:
            p = struct.pack(">8I", o.top, o.left, o.bottom, o.right, o.plane, o.planes, o.row_pitch, o.col_pitch)
            p += struct.pack(">II4dI", o.points_v, o.points_h, o.spacing_v, o.spacing_h, o.origin_v, o.origin_h, o.map_planes)
            p += np.asarray(o.gains, dtype=">f4").tobytes()
        elif op_id == OP_WARP_RECTILINEAR:
            p = struct.pack(">I", len(o.planes)) + b"".join(struct.pack(">6d", *pl) for pl in o.planes) + struct.pack(">2d", *o.center)
        else:
            p = bytes(o)
        body += struct.pack(">IIII", op_id, 0x01030000, 0, len(p)) + p
    return struct.pack(">I", len(ops)) + body


# --------------------------------------------------------------------------- device profile


@dataclass
class DeviceProfile:
    """What ships per phone camera (calibration/devices/<id>.json; never names private data)."""

    profile_id: str
    camera_id: str
    color: ColorCalibration
    noise: list[tuple[float, float]] | None = None
    noise_model: NoiseModel | None = None
    shading_corner_ev: float | None = None
    distortion_corner_percent: float | None = None
    source: str = "camera2"

    def to_json(self) -> str:
        return json.dumps(asdict(self), indent=2)

    @staticmethod
    def from_json(text: str) -> "DeviceProfile":
        d = json.loads(text)
        d["color"] = ColorCalibration(**d["color"])
        if d.get("noise") is not None:
            d["noise"] = [tuple(p) for p in d["noise"]]
        if d.get("noise_model") is not None:
            d["noise_model"] = NoiseModel(**d["noise_model"])
        p = DeviceProfile(**d)
        p.color.validate()
        return p


def color_from_dng_tags(tags: dict[str, Any]) -> ColorCalibration:
    """Build from exiftool -n output (keys with or without group prefixes)."""

    def g(k: str) -> Any:
        for key, val in tags.items():
            if key.split(":")[-1] == k:
                return val
        return None

    if g("ColorMatrix1") is None:
        raise ProfileError("DNG has no ColorMatrix1")
    ill2 = g("CalibrationIlluminant2")
    ab = g("AnalogBalance")
    cal = ColorCalibration(
        illuminant1=int(g("CalibrationIlluminant1") or 21),
        illuminant2=int(ill2) if ill2 is not None and g("ColorMatrix2") is not None else None,
        color_matrix1=_m(g("ColorMatrix1")),
        color_matrix2=_m(g("ColorMatrix2")),
        forward_matrix1=_m(g("ForwardMatrix1")),
        forward_matrix2=_m(g("ForwardMatrix2")),
        calibration1=_m(g("CameraCalibration1")),
        calibration2=_m(g("CameraCalibration2")),
        analog_balance=[float(x) for x in str(ab).split()] if ab is not None else [1.0, 1.0, 1.0],
    )
    cal.validate()
    return cal


def color_from_camera2(d: dict[str, Any]) -> ColorCalibration:
    """Build from the app's Camera2 export (keys = CameraCharacteristics names)."""
    if d.get("SENSOR_COLOR_TRANSFORM1") is None or d.get("SENSOR_REFERENCE_ILLUMINANT1") is None:
        raise ProfileError("camera reports no SENSOR_COLOR_TRANSFORM1 (no RAW calibration)")
    has2 = d.get("SENSOR_COLOR_TRANSFORM2") is not None
    cal = ColorCalibration(
        illuminant1=int(d["SENSOR_REFERENCE_ILLUMINANT1"]),
        illuminant2=int(d["SENSOR_REFERENCE_ILLUMINANT2"]) if has2 else None,
        color_matrix1=_m(d["SENSOR_COLOR_TRANSFORM1"]),
        color_matrix2=_m(d.get("SENSOR_COLOR_TRANSFORM2")),
        forward_matrix1=_m(d.get("SENSOR_FORWARD_MATRIX1")),
        forward_matrix2=_m(d.get("SENSOR_FORWARD_MATRIX2")),
        calibration1=_m(d.get("SENSOR_CALIBRATION_TRANSFORM1")),
        calibration2=_m(d.get("SENSOR_CALIBRATION_TRANSFORM2")),
    )
    cal.validate()
    return cal


def compare_calibrations(a: ColorCalibration, b: ColorCalibration, tol: float = 1e-3) -> list[str]:
    """Differences between two calibrations; [] means identical within tol.

    Camera2 rationals and DNG SRATIONALs round differently, so tol defaults to 1e-3.
    """
    diffs: list[str] = []
    if a.illuminant1 != b.illuminant1 or a.illuminant2 != b.illuminant2:
        diffs.append(f"illuminants {a.illuminant1}/{a.illuminant2} vs {b.illuminant1}/{b.illuminant2}")
    for name in ("color_matrix1", "color_matrix2", "forward_matrix1", "forward_matrix2", "calibration1", "calibration2"):
        x, y = getattr(a, name), getattr(b, name)
        if (x is None) != (y is None):
            diffs.append(f"{name}: present in only one source")
        elif x is not None:
            d = float(np.max(np.abs(np.asarray(x) - np.asarray(y))))
            if d > tol:
                diffs.append(f"{name}: max diff {d:.5f}")
    return diffs
