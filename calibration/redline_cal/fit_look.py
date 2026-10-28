"""Fit the Standard look from a B02 manifest and report hold-out accuracy.

    python -m redline_cal.fit_look out/manifest.jsonl --out out/look [--gate]

Outputs (git-ignored; only fitted results ever ship, see docs/PLAN.md legal note):
  standard_look.json       tone curve + model description
  standard_residual.cube   33^3 residual LUT
  report.json              train/hold-out dE2000, skin dE2000, per-image hold-out errors
"""
from __future__ import annotations

import argparse
import json
import os
import sys

import numpy as np

from .look import evaluate, fit_gain, fit_look, fit_look_aligned, passes_gate, save
from .manifest import read_jsonl
from .samples import load_jpeg, sample_pair


CACHE_VERSION = 2  # v2 adds per-block radius


def samples_for(rec: dict, cache_dir: str) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    path = os.path.join(cache_dir, f"{rec['id']}.v{CACHE_VERSION}.npz")
    if os.path.exists(path):
        z = np.load(path)
        return z["xyz"], z["jpeg"], z["r"]
    from .raw import decode_linear_xyz

    xyz, jpg, r = sample_pair(decode_linear_xyz(rec["raw"]), load_jpeg(rec["jpeg"]), return_radius=True)
    os.makedirs(cache_dir, exist_ok=True)
    np.savez_compressed(path, xyz=xyz, jpeg=jpg, r=r)
    return xyz, jpg, r


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("manifest")
    ap.add_argument("--out", default="out/look")
    ap.add_argument("--lut-size", type=int, default=33)
    ap.add_argument("--smooth", type=float, default=2.0)
    ap.add_argument("--target-lens-only", action="store_true")
    ap.add_argument("--no-alo", action="store_true", help="exclude pairs shot with Auto Lighting Optimizer on")
    ap.add_argument(
        "--align-exposure",
        action="store_true",
        help="fit and score up to one exposure offset per image (absorbs Auto Lighting Optimizer / metering)",
    )
    ap.add_argument(
        "--devignette",
        action="store_true",
        help="fit and remove each image's radial falloff (the JPEG has peripheral illumination corrected, the RAW does not)",
    )
    ap.add_argument("--exclude", nargs="*", default=[], help="manifest ids to leave out (e.g. edited JPEGs)")
    ap.add_argument("--gate", action="store_true", help="exit 1 unless hold-out mean dE2000 < 2.5 and skin < 2.0")
    a = ap.parse_args(argv)

    recs = [r for r in read_jsonl(a.manifest) if r["usable"] and r["id"] not in set(a.exclude)]
    if a.target_lens_only:
        recs = [r for r in recs if r["target_lens"]]
    if a.no_alo:
        recs = [r for r in recs if r["shot"].get("auto_lighting_optimizer") == "Off"]
    cache = os.path.join(a.out, "cache")
    data = {"train": [], "holdout": []}
    for r in recs:
        data[r["split"]].append((r["id"], *samples_for(r, cache)))
    if not data["train"] or not data["holdout"]:
        print("need usable pairs in both train and holdout splits", file=sys.stderr)
        return 2

    gains: dict = {}
    if a.align_exposure or a.devignette:
        look, gl = fit_look_aligned(
            [(d[1], d[2], d[3]) for d in data["train"]], n=a.lut_size, smooth=a.smooth,
            exposure=a.align_exposure, vignette=a.devignette,
        )
        gains = {d[0]: g for d, g in zip(data["train"], gl)}
        for d in data["holdout"]:
            gains[d[0]] = fit_gain(look, d[1], d[2], d[3], exposure=a.align_exposure, vignette=a.devignette)
        data = {k: [(i, gains[i].apply(x, r), y, r) for i, x, y, r in v] for k, v in data.items()}
    else:
        look = fit_look(
            np.concatenate([d[1] for d in data["train"]]), np.concatenate([d[2] for d in data["train"]]),
            n=a.lut_size, smooth=a.smooth,
        )
    xt = np.concatenate([d[1] for d in data["train"]])
    yt = np.concatenate([d[2] for d in data["train"]])
    xh = np.concatenate([d[1] for d in data["holdout"]])
    yh = np.concatenate([d[2] for d in data["holdout"]])
    report = {
        "pairs": {"train": len(data["train"]), "holdout": len(data["holdout"])},
        "train": evaluate(look, xt, yt),
        "holdout": evaluate(look, xh, yh),
        "holdout_per_image": {i: round(evaluate(look, x, y)["mean_de2000"], 3) for i, x, y, _ in data["holdout"] if len(x)},
        "settings": {"lut_size": a.lut_size, "smooth": a.smooth, "target_lens_only": a.target_lens_only, "no_alo": a.no_alo, "align_exposure": a.align_exposure, "devignette": a.devignette},
        "gains": {i: [round(g.ev, 3), round(g.k1, 3), round(g.k2, 3)] for i, g in gains.items()},
    }
    report["gate_pass"] = passes_gate(report["holdout"])
    save(look, a.out)
    with open(os.path.join(a.out, "report.json"), "w") as f:
        json.dump(report, f, indent=1)
    json.dump({k: report[k] for k in ("pairs", "holdout", "gate_pass")}, sys.stdout, indent=2)
    print()
    return 1 if a.gate and not report["gate_pass"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
