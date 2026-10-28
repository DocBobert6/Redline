"""Dataset manifest: one JSON object per RAW+JPEG pair."""
from __future__ import annotations

import hashlib
import json
from typing import Any, Iterable

from .metadata import Shot

ZOOM_STOPS_MM = (24, 28, 35, 50, 70)
TARGET_LENS = "RF24-70mm F2.8 L IS USM"
MANIFEST_VERSION = 1


def sha256_file(path: str, chunk: int = 1 << 20) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while b := f.read(chunk):
            h.update(b)
    return h.hexdigest()


def nearest_stop(focal_mm: float | None) -> int | None:
    if focal_mm is None:
        return None
    return min(ZOOM_STOPS_MM, key=lambda s: abs(s - focal_mm))


def is_target_lens(lens: str | None) -> bool:
    if not lens:
        return False
    # Exact match: excludes the RF24-70mm F2.8 L IS USM Z, which renders differently.
    return lens.replace(" ", "").upper() == "RF24-70MMF2.8LISUSM"


def split_for(raw_sha: str, holdout_fraction: float = 0.2) -> str:
    """Deterministic split from the RAW file hash, stable across machines and reruns."""
    bucket = int(raw_sha[:8], 16) / 0xFFFFFFFF
    return "holdout" if bucket < holdout_fraction else "train"


def record(raw: Shot, raw_sha: str, jpeg_path: str, jpeg_sha: str, reasons: list[str]) -> dict[str, Any]:
    return {
        "v": MANIFEST_VERSION,
        "id": raw_sha[:16],
        "raw": raw.source,
        "jpeg": jpeg_path,
        "raw_sha256": raw_sha,
        "jpeg_sha256": jpeg_sha,
        "usable": not reasons,
        "reasons": reasons,
        "split": split_for(raw_sha),
        "target_lens": is_target_lens(raw.lens),
        "zoom_stop_mm": nearest_stop(raw.focal_mm) if is_target_lens(raw.lens) else None,
        "shot": raw.to_dict(),
    }


def write_jsonl(path: str, records: Iterable[dict[str, Any]]) -> int:
    n = 0
    with open(path, "w", encoding="utf-8") as f:
        for r in records:
            f.write(json.dumps(r, sort_keys=True) + "\n")
            n += 1
    return n


def read_jsonl(path: str) -> list[dict[str, Any]]:
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]
