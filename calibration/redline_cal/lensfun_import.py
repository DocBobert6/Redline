"""Import a lens's measured distortion (ptlens) and vignetting (pa) from a lensfun XML file.

lensfun's database is CC BY-SA 3.0. Download it yourself (see calibration/README.md);
the generated JSON keeps its attribution and licence fields.

    python -m redline_cal.lensfun_import data/lensfun/mil-canon.xml \
        --model "Canon RF 24-70mm F2.8L IS USM" --out out/lens_rf24-70.json
"""
from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass, field


@dataclass
class Distortion:
    focal: float
    a: float
    b: float
    c: float


@dataclass
class Vignetting:
    focal: float
    aperture: float
    distance: float
    k1: float
    k2: float
    k3: float


@dataclass
class LensProfile:
    model: str
    maker: str
    crop_factor: float
    distortion: list[Distortion] = field(default_factory=list)
    vignetting: list[Vignetting] = field(default_factory=list)
    source: str = "lensfun database"
    licence: str = "CC BY-SA 3.0"

    def focals(self) -> list[float]:
        return sorted({d.focal for d in self.distortion} | {v.focal for v in self.vignetting})

    def apertures(self, focal: float) -> list[float]:
        return sorted({v.aperture for v in self.vignetting if v.focal == focal})

    def distortion_at(self, focal: float) -> tuple[float, float, float]:
        """Linear interpolation between measured focal lengths (clamped)."""
        pts = sorted(self.distortion, key=lambda d: d.focal)
        if not pts:
            raise ValueError("no distortion data")
        if focal <= pts[0].focal:
            p = pts[0]
            return p.a, p.b, p.c
        for lo, hi in zip(pts, pts[1:]):
            if lo.focal <= focal <= hi.focal:
                t = (focal - lo.focal) / (hi.focal - lo.focal)
                return tuple(getattr(lo, k) * (1 - t) + getattr(hi, k) * t for k in "abc")  # type: ignore[return-value]
        p = pts[-1]
        return p.a, p.b, p.c

    def vignetting_at(self, focal: float, aperture: float) -> tuple[float, float, float]:
        """Nearest measured focal, then interpolate in aperture stops (log2 N), farthest distance."""
        if not self.vignetting:
            raise ValueError("no vignetting data")
        import math

        nf = min(self.focals(), key=lambda f: abs(f - focal))
        rows = [v for v in self.vignetting if v.focal == nf]
        far = max(v.distance for v in rows)
        rows = sorted((v for v in rows if v.distance == far), key=lambda v: v.aperture)
        s = math.log2(aperture)
        if s <= math.log2(rows[0].aperture):
            r = rows[0]
            return r.k1, r.k2, r.k3
        for lo, hi in zip(rows, rows[1:]):
            a, b = math.log2(lo.aperture), math.log2(hi.aperture)
            if a <= s <= b:
                t = (s - a) / (b - a)
                return tuple(getattr(lo, k) * (1 - t) + getattr(hi, k) * t for k in ("k1", "k2", "k3"))  # type: ignore[return-value]
        r = rows[-1]
        return r.k1, r.k2, r.k3


def _matches(lens: ET.Element, model: str) -> bool:
    want = model.lower().replace(" ", "")
    return any((m.text or "").lower().replace(" ", "") == want for m in lens.findall("model"))


def parse_lensfun(xml_text: str, model: str) -> LensProfile:
    root = ET.fromstring(xml_text)
    for lens in root.iter("lens"):
        if not _matches(lens, model):
            continue
        name = next((m.text for m in lens.findall("model") if "lang" not in m.attrib), model) or model
        maker = next((m.text for m in lens.findall("maker") if "lang" not in m.attrib), "") or ""
        crop = float((lens.findtext("cropfactor") or "1").strip())
        prof = LensProfile(name.strip(), maker.strip(), crop)
        cal = lens.find("calibration")
        if cal is None:
            raise ValueError(f"lens '{name}' has no calibration data")
        for d in cal.findall("distortion"):
            if d.get("model") != "ptlens":
                continue
            prof.distortion.append(Distortion(float(d.get("focal")), float(d.get("a", 0)), float(d.get("b", 0)), float(d.get("c", 0))))
        for v in cal.findall("vignetting"):
            if v.get("model") != "pa":
                continue
            prof.vignetting.append(
                Vignetting(float(v.get("focal")), float(v.get("aperture")), float(v.get("distance", 1000)), float(v.get("k1", 0)), float(v.get("k2", 0)), float(v.get("k3", 0)))
            )
        if not prof.distortion and not prof.vignetting:
            raise ValueError(f"lens '{name}' has no ptlens/pa data")
        return prof
    raise KeyError(f"lens '{model}' not found")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("xml")
    ap.add_argument("--model", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    with open(a.xml, encoding="utf-8") as fh:
        prof = parse_lensfun(fh.read(), a.model)
    with open(a.out, "w", encoding="utf-8") as fh:
        json.dump(asdict(prof), fh, indent=2)
    print(f"{prof.model}: {len(prof.distortion)} distortion, {len(prof.vignetting)} vignetting entries, focals {prof.focals()}")
    return 0


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
