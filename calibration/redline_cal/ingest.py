"""Build the calibration dataset manifest from a folder of RAW + in-camera JPEG pairs.

    python -m redline_cal.ingest data/r5ii --out out/manifest.jsonl

Writes one JSON line per pair (see docs in calibration/README.md) and prints a summary.
Source images are never copied or committed; the manifest stores paths and hashes only.
"""
from __future__ import annotations

import argparse
import collections
import json
import os
import sys
from typing import Any

from .manifest import record, sha256_file, write_jsonl
from .metadata import Shot, run_exiftool
from .pairs import pair_by_capture_time, pair_by_stem, scan
from .policy import StandardPolicy

JPEG_MUST_MATCH = ("picture_style", "auto_lighting_optimizer", "highlight_tone_priority")


def build(root: str, exiftool: str | None = None, policy: StandardPolicy | None = None) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    policy = policy or StandardPolicy()
    raws, jpegs = scan(root)
    meta = {m["SourceFile"]: m for m in run_exiftool(raws + jpegs, exiftool)} if raws or jpegs else {}
    shots = {p: Shot.from_exif(meta.get(p, {"SourceFile": p})) for p in raws + jpegs}

    pairs, lone_raw, lone_jpeg = pair_by_stem(raws, jpegs)
    times = {p: (s.datetime_original, s.subsec, s.body_serial) for p, s in shots.items()}
    pairs += pair_by_capture_time(lone_raw, lone_jpeg, times)

    records = []
    for pr in pairs:
        rs, js = shots[pr.raw], shots[pr.jpeg]
        reasons = policy.evaluate(rs)
        for f in JPEG_MUST_MATCH:  # JPEG edited or re-rendered after capture
            jv, rv = getattr(js, f), getattr(rs, f)
            if jv is not None and rv is not None and jv != rv:
                reasons.append(f"jpeg_mismatch:{f}")
        records.append(record(rs, sha256_file(pr.raw), pr.jpeg, sha256_file(pr.jpeg), reasons))

    reasons = collections.Counter(r for rec in records for r in rec["reasons"])
    usable = [r for r in records if r["usable"]]
    summary = {
        "raw_files": len(raws),
        "jpeg_files": len(jpegs),
        "pairs": len(records),
        "usable": len(usable),
        "usable_target_lens": sum(r["target_lens"] for r in usable),
        "usable_by_stop": dict(sorted(collections.Counter(r["zoom_stop_mm"] for r in usable if r["zoom_stop_mm"]).items())),
        "usable_holdout": sum(r["split"] == "holdout" for r in usable),
        "rejections": dict(reasons.most_common()),
    }
    return records, summary


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("root", help="folder containing RAW + JPEG files (searched recursively)")
    ap.add_argument("--out", default="out/manifest.jsonl")
    ap.add_argument("--exiftool", default=None)
    ap.add_argument("--strict-alo", action="store_true", help="accept only Auto Lighting Optimizer Off")
    a = ap.parse_args(argv)
    policy = StandardPolicy(alo_allowed=frozenset({"Off"})) if a.strict_alo else StandardPolicy()
    records, summary = build(a.root, a.exiftool, policy)
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    write_jsonl(a.out, records)
    json.dump(summary, sys.stdout, indent=2)
    print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
