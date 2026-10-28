"""Read and normalize camera metadata (EXIF + Canon MakerNotes) via exiftool.

Field names were checked against exiftool 13.60 output for an EOS R5 Mark II CR3
(raw.pixls.us sample, CC0). With ``-G1`` Canon MakerNote tags are grouped as ``Canon:``.
"""
from __future__ import annotations

import json
import re
import shutil
import subprocess
from dataclasses import asdict, dataclass
from fractions import Fraction
from typing import Any, Iterable

EXIFTOOL_ARGS = ["-j", "-G1", "-a", "-Canon:all", "-IFD0:Make", "-IFD0:Model", "-ExifIFD:all"]


def find_exiftool(explicit: str | None = None) -> str:
    exe = explicit or shutil.which("exiftool")
    if not exe:
        raise FileNotFoundError("exiftool not found; install it (https://exiftool.org) or pass --exiftool")
    return exe


def run_exiftool(paths: Iterable[str], exiftool: str | None = None, batch: int = 200) -> list[dict[str, Any]]:
    exe = find_exiftool(exiftool)
    paths = list(paths)
    out: list[dict[str, Any]] = []
    for i in range(0, len(paths), batch):
        chunk = paths[i : i + batch]
        res = subprocess.run([exe, *EXIFTOOL_ARGS, *chunk], capture_output=True, text=True, check=False)
        if res.returncode not in (0, 1) or not res.stdout.strip():
            raise RuntimeError(f"exiftool failed ({res.returncode}): {res.stderr.strip()[:500]}")
        out.extend(json.loads(res.stdout))
    return out


def _get(d: dict[str, Any], *keys: str) -> Any:
    """First non-empty value among group-qualified keys; a bare name matches any group."""
    for k in keys:
        if ":" in k:
            v = d.get(k)
            if v not in (None, "", "n/a"):
                return v
        else:
            for dk, v in d.items():
                if dk.split(":")[-1] == k and v not in (None, "", "n/a"):
                    return v
    return None


def _num(v: Any) -> float | None:
    if v is None:
        return None
    if isinstance(v, (int, float)):
        return float(v)
    s = str(v).strip()
    m = re.match(r"^(\d+)/(\d+)$", s)
    if m:
        return float(Fraction(int(m.group(1)), int(m.group(2))))
    m = re.match(r"^[-+]?\d+(\.\d+)?", s)
    return float(m.group(0)) if m else None


def _str(v: Any) -> str | None:
    return None if v is None else str(v).strip()


@dataclass(frozen=True)
class Shot:
    source: str
    make: str | None
    model: str | None
    lens: str | None
    picture_style: str | None
    sharpness: float | None
    sharpness_fineness: float | None
    sharpness_threshold: float | None
    contrast: str | None
    saturation: str | None
    color_tone: str | None
    auto_lighting_optimizer: str | None
    highlight_tone_priority: str | None
    peripheral_correction: str | None
    ca_correction: str | None
    distortion_correction: str | None
    digital_lens_optimizer: str | None
    white_balance: str | None
    color_temperature: float | None
    iso: float | None
    f_number: float | None
    exposure_s: float | None
    focal_mm: float | None
    datetime_original: str | None
    subsec: str | None
    body_serial: str | None

    @classmethod
    def from_exif(cls, d: dict[str, Any]) -> "Shot":
        return cls(
            source=str(d.get("SourceFile", "")),
            make=_str(_get(d, "IFD0:Make", "Make")),
            # IFD0:Model on the R5 II reads "Canon EOS R5m2"; CanonModelID gives the marketing name.
            model=_str(_get(d, "Canon:CanonModelID", "IFD0:Model", "Model")),
            lens=_str(_get(d, "ExifIFD:LensModel", "Canon:LensModel", "LensModel")),
            picture_style=_str(_get(d, "Canon:PictureStyle", "PictureStyle")),
            sharpness=_num(_get(d, "Canon:Sharpness")),
            sharpness_fineness=_num(_get(d, "Canon:UnsharpMaskFineness")),
            sharpness_threshold=_num(_get(d, "Canon:UnsharpMaskThreshold")),
            contrast=_str(_get(d, "Canon:Contrast")),
            saturation=_str(_get(d, "Canon:Saturation")),
            color_tone=_str(_get(d, "Canon:ColorTone")),
            auto_lighting_optimizer=_str(_get(d, "Canon:AutoLightingOptimizer")),
            highlight_tone_priority=_str(_get(d, "Canon:HighlightTonePriority")),
            peripheral_correction=_str(_get(d, "Canon:PeripheralLightingSetting", "Canon:PeripheralIlluminationCorr")),
            ca_correction=_str(_get(d, "Canon:ChromaticAberrationSetting", "Canon:ChromaticAberrationCorr")),
            distortion_correction=_str(_get(d, "Canon:DistortionCorrectionSetting", "Canon:DistortionCorrection")),
            digital_lens_optimizer=_str(_get(d, "Canon:DigitalLensOptimizerSetting", "Canon:DigitalLensOptimizer")),
            white_balance=_str(_get(d, "Canon:WhiteBalance", "ExifIFD:WhiteBalance")),
            color_temperature=_num(_get(d, "Canon:ColorTemperature")),
            iso=_num(_get(d, "ExifIFD:ISO", "ISO")),
            f_number=_num(_get(d, "ExifIFD:FNumber", "FNumber")),
            exposure_s=_num(_get(d, "ExifIFD:ExposureTime", "ExposureTime")),
            focal_mm=_num(_get(d, "ExifIFD:FocalLength", "FocalLength")),
            datetime_original=_str(_get(d, "ExifIFD:DateTimeOriginal", "DateTimeOriginal")),
            subsec=_str(_get(d, "ExifIFD:SubSecTimeOriginal", "SubSecTimeOriginal")),
            body_serial=_str(_get(d, "ExifIFD:SerialNumber", "Canon:InternalSerialNumber")),
        )

    def to_dict(self) -> dict[str, Any]:
        d = asdict(self)
        d.pop("body_serial")  # never written to manifests
        return d
