"""B04: build a phone camera profile from factory calibration and check it (no chart).

    python -m redline_cal.profile_device data/phone/*.dng \
        --camera2 data/phone/camera2_back.json --flat data/phone/wall.dng \
        --profile-id baseline --out out/device_baseline.json --gate

Checks (B04 gate):
  1. Camera2 export matrices == the phone's own DNG tags (when --camera2 given).
  2. Every DNG's matrices agree with each other (the factory set is constant).
  3. The camera neutral maps to D50 white within 1 dE (forward-matrix path).
  4. Forward- and colour-matrix paths agree (warning above 3 dE, informational).
  5. Flat field: residual falloff after the phone's gain maps within 0.5 EV.
Output JSON and reports stay out of git (they describe a specific device).
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from dataclasses import asdict

from . import dng_profile as dp
from .metadata import find_exiftool

NEUTRAL_MAX_DE = 1.0
PATH_WARN_DE = 3.0
FLAT_MAX_EV = 0.5


def read_dng(path: str, exiftool: str) -> tuple[dict, bytes, bytes]:
    out = subprocess.run([exiftool, "-j", "-n", "-G1", "-s", path], capture_output=True, text=True, check=True).stdout
    tags = json.loads(out)[0]

    def blob(tag: str) -> bytes:
        return subprocess.run([exiftool, "-b", f"-SubIFD:{tag}", path], capture_output=True, check=True).stdout

    return tags, blob("OpcodeList2"), blob("OpcodeList3")


def _ifd0(tags: dict) -> dict:
    return {k: v for k, v in tags.items() if k.startswith("IFD0:")}


def _first(tags: dict, name: str):
    for k, v in tags.items():
        if k.split(":")[-1] == name:
            return v
    return None


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Profile a phone camera from factory calibration (B04)")
    ap.add_argument("dngs", nargs="+")
    ap.add_argument("--camera2", help="JSON exported by the app's capability probe (one camera)")
    ap.add_argument("--flat", help="white wall / overcast sky DNG for the flat-field check")
    ap.add_argument("--profile-id", default="device")
    ap.add_argument("--camera-id", default="0")
    ap.add_argument("--out")
    ap.add_argument("--exiftool")
    ap.add_argument("--gate", action="store_true", help="exit 1 if a B04 check fails")
    a = ap.parse_args(argv)
    et = find_exiftool(a.exiftool)

    failures: list[str] = []
    cals, noise_pts, last = [], [], None
    for path in a.dngs:
        tags, op2, op3 = read_dng(path, et)
        cal = dp.color_from_dng_tags(_ifd0(tags))
        cals.append(cal)
        xy = _first(tags, "AsShotWhiteXY")
        neutral = _first(tags, "AsShotNeutral")
        m = dp.camera_to_xyz(
            cal,
            white_xy=tuple(float(x) for x in str(xy).split()) if xy is not None else None,
            neutral=[float(x) for x in str(neutral).split()] if xy is None and neutral is not None else None,
        )
        ne = dp.neutral_error_de(m)
        ag = dp.path_agreement_de(m) if m.forward_path else float("nan")
        iso = float(_first(tags, "ISO") or 0)
        nz = _first(tags, "NoiseProfile")
        if nz is not None and iso > 0:
            pairs = dp.parse_noise_profile(nz)
            noise_pts.append((iso, sum(p[0] for p in pairs) / len(pairs), sum(p[1] for p in pairs) / len(pairs)))
        gm = [o for i, o in dp.parse_opcode_list(op2) if i == dp.OP_GAIN_MAP] if op2 else []
        wr = [o for i, o in dp.parse_opcode_list(op3) if i == dp.OP_WARP_RECTILINEAR] if op3 else []
        dist = f"distortion corner {wr[0].distortion_percent():.2f}%" if wr else "no distortion opcode"
        print(f"{path}: CCT {m.cct:.0f} K, neutral dE {ne:.3f}, FM/CM paths dE {ag:.2f}, ISO {iso:g}, "
              f"gain maps {len(gm)} (corner {max((g.corner_ev() for g in gm), default=0):.2f} EV), {dist}")
        if ne > NEUTRAL_MAX_DE:
            failures.append(f"{path}: neutral maps {ne:.2f} dE from D50 white")
        if ag == ag and ag > PATH_WARN_DE:
            print(f"  warning: forward/colour matrix paths differ by {ag:.2f} dE")
        last = (cal, gm, wr)

    for i, c in enumerate(cals[1:], 1):
        d = dp.compare_calibrations(cals[0], c, tol=1e-6)
        if d:
            failures.append(f"{a.dngs[i]} calibration differs from {a.dngs[0]}: {d}")

    if a.camera2:
        with open(a.camera2, encoding="utf-8") as fh:
            c2 = dp.color_from_camera2(json.load(fh))
        d = dp.compare_calibrations(c2, cals[0])
        print("Camera2 vs DNG:", "identical" if not d else d)
        if d:
            failures.append(f"Camera2 export differs from DNG: {d}")

    shading = None
    if a.flat:
        from .flatfield import flat_field_report, load_cfa

        _, op2, _ = read_dng(a.flat, et)
        gm = [o for i, o in dp.parse_opcode_list(op2) if i == dp.OP_GAIN_MAP]
        if not gm:
            failures.append("flat-field DNG has no GainMap opcodes")
        else:
            r = flat_field_report(load_cfa(a.flat), gm)
            shading = r.falloff_after_ev
            print(f"Flat field: {r.falloff_before_ev:+.2f} EV before, {r.falloff_after_ev:+.2f} EV after gain maps "
                  f"(corner spread {r.asymmetry_after_ev:.2f} EV, clipped {r.clipped_fraction:.2%})")
            if not r.passes(FLAT_MAX_EV):
                failures.append(f"flat field residual {r.falloff_after_ev:+.2f} EV (limit {FLAT_MAX_EV})")

    model = dp.fit_noise_model(noise_pts) if noise_pts else None
    if a.out and last:
        cal, gm, wr = last
        prof = dp.DeviceProfile(
            profile_id=a.profile_id,
            camera_id=a.camera_id,
            color=cals[0],
            noise=None,
            noise_model=model,
            shading_corner_ev=shading,
            distortion_corner_percent=wr[0].distortion_percent() if wr else None,
            source="dng+camera2" if a.camera2 else "dng",
        )
        with open(a.out, "w", encoding="utf-8") as fh:
            fh.write(prof.to_json())
        print(f"wrote {a.out}")
    if model:
        print("noise model:", asdict(model))

    for f in failures:
        print("FAIL:", f)
    print("B04 gate:", "PASS" if not failures else "FAIL")
    return 1 if (a.gate and failures) else 0


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
