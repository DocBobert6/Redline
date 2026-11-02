"""B24b: touch metering weight fitted from where the R5 II focused.

Every R5 II pair in the dataset was shot in evaluative metering, which Canon links to the active AF point. The CR3
MakerNotes (AFInfo2) record the AF area(s), so the dataset answers "how much does the R5 II's exposure follow the AF
subject?" directly, without a chart and without guessing a spot weight.

Per pair (B02 manifest, B03 train / hold-out split, B23c block cache):

* AF area: ``ValidAFPoints == 1`` takes the single area; otherwise the union of the areas flagged in
  ``AFPointsInFocus`` (a 16-bit word bitmask). AF coordinates are centred on the AF image (``AFImageWidth`` x
  ``AFImageHeight``) with positive Y upwards on EOS models (ExifTool's Canon notes; confirmed here by sharpness: the
  AF area is ~2.4x the frame's median relative block contrast with Y up, ~1.5x with Y down).
* The area is mapped onto the cached 64 x 96 block grid (central 80 % of the frame, in the JPEG's orientation; the
  cache rotated portrait frames with ``np.rot90``, so the AF box takes the same rotation).
* ``d = log2(frame median / region median)`` of block luminance (> 0: the AF subject is darker than the frame).
* Residual of the shipped metering (B23c fit + B23c.1 headroom): ``r = canon_target - predicted_target`` (EV).

The weights are the least-squares slopes of ``r`` on ``clamp(d, -cap, 0)`` and ``clamp(d, 0, cap)`` (no intercept)
on the train split, with a bootstrap spread and leave-one-out error; the hold-out is scored once. The app reads the
result from ``colour/metering.json`` (``spot``) as ``raw_pipeline.Spot``.
"""
from __future__ import annotations

import argparse
import json
import math
import os

import numpy as np

from . import metering as mt

GRID = mt.GRID
CENTER = mt.CENTER
CAP_EV = 1.0          # largest |d| the slope acts on (the train split's |d| p90 is ~1.6 EV; capped for safety)
ROUND = 0.05          # weights are shipped rounded to 0.05


def _ints(v) -> list[int]:
    return [int(x) for x in str(v).split()]


def af_box(e: dict) -> tuple[tuple[float, float, float, float] | None, int]:
    """AF area as (u0, v0, u1, v1) normalised to the sensor frame (v downwards), and the number of points used.
    None when no point is in focus (multi-point modes) or the tags are missing."""
    try:
        w_af, h_af, n = int(e["AFImageWidth"]), int(e["AFImageHeight"]), int(e["ValidAFPoints"])
        ws, hs, xs, ys = (_ints(e[k])[:n] for k in ("AFAreaWidths", "AFAreaHeights", "AFAreaXPositions", "AFAreaYPositions"))
    except (KeyError, ValueError, TypeError):
        return None, 0
    if n < 1 or w_af <= 0 or h_af <= 0:
        return None, 0
    if n == 1:
        idx = [0]
    else:
        words = [v & 0xFFFF for v in _ints(e.get("AFPointsInFocus", ""))]
        idx = [i for i in range(n) if i // 16 < len(words) and (words[i // 16] >> (i % 16)) & 1]
    if not idx:
        return None, 0
    b = np.array([(0.5 + xs[i] / w_af - ws[i] / w_af / 2, 0.5 - ys[i] / h_af - hs[i] / h_af / 2,
                   0.5 + xs[i] / w_af + ws[i] / w_af / 2, 0.5 - ys[i] / h_af + hs[i] / h_af / 2) for i in idx])
    return (float(b[:, 0].min()), float(b[:, 1].min()), float(b[:, 2].max()), float(b[:, 3].max())), len(idx)


def box_to_grid(box, portrait: bool, grid=GRID, center: float = CENTER) -> tuple[int, int, int, int]:
    """Block rows [r0, r1) and columns [c0, c1) covering ``box`` on the cache grid (np.rot90 for portrait)."""
    u0, v0, u1, v1 = box
    if portrait:  # np.rot90 (counter-clockwise): u' = v, v' = 1 - u
        u0, v0, u1, v1 = v0, 1 - u1, v1, 1 - u0
    gh, gw = grid

    def g(t: float) -> float:
        return (t - (1 - center) / 2) / center

    c0, c1 = int(math.floor(g(u0) * gw)), int(math.ceil(g(u1) * gw))
    r0, r1 = int(math.floor(g(v0) * gh)), int(math.ceil(g(v1) * gh))
    return max(r0, 0), min(r1, gh), max(c0, 0), min(c1, gw)


def region_d(y_blocks: np.ndarray, rows: tuple[int, int, int, int], grid=GRID) -> float:
    """log2(frame median / region median) of block luminance."""
    y = np.maximum(np.asarray(y_blocks, float).reshape(grid), 2.0**-20)
    r0, r1, c0, c1 = rows
    return float(math.log2(np.median(y) / np.median(y[r0:r1, c0:c1])))


def design(d: np.ndarray, cap: float = CAP_EV) -> np.ndarray:
    d = np.asarray(d, float)
    return np.c_[np.clip(d, -cap, 0.0), np.clip(d, 0.0, cap)]


def fit_weights(d: np.ndarray, resid: np.ndarray, cap: float = CAP_EV) -> np.ndarray:
    """(w_brighter, w_darker): no-intercept least squares of the residual on the split, capped d."""
    w, *_ = np.linalg.lstsq(design(d, cap), np.asarray(resid, float), rcond=None)
    return w


def loo_mae(d: np.ndarray, resid: np.ndarray, cap: float = CAP_EV) -> float:
    x, e = design(d, cap), np.asarray(resid, float)
    errs = []
    for i in range(len(e)):
        m = np.arange(len(e)) != i
        w, *_ = np.linalg.lstsq(x[m], e[m], rcond=None)
        errs.append(abs(e[i] - x[i] @ w))
    return float(np.mean(errs))


def bootstrap(d: np.ndarray, resid: np.ndarray, cap: float = CAP_EV, n: int = 3000, seed: int = 1) -> np.ndarray:
    x, e = design(d, cap), np.asarray(resid, float)
    rs = np.random.default_rng(seed)
    out = []
    for _ in range(n):
        i = rs.integers(0, len(e), len(e))
        out.append(np.linalg.lstsq(x[i], e[i], rcond=None)[0])
    return np.array(out)


def shipped(w: np.ndarray, lo: np.ndarray) -> tuple[float, float]:
    """The weights the app ships: rounded to 0.05; a weight whose bootstrap 5th percentile is not above zero is not
    supported by the data and ships as 0 (conservative: no shift the data does not show)."""
    out = []
    for wi, li in zip(w, lo):
        out.append(0.0 if li <= 0 or wi <= 0 else round(round(float(wi) / ROUND) * ROUND, 2))
    return out[0], out[1]


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("manifest")
    ap.add_argument("--exif", required=True, help="B23c exiftool output (CanonExposureMode, ExposureCompensation)")
    ap.add_argument("--af", required=True, help="exiftool -j -n with the AFInfo2 tags and Orientation per RAW")
    ap.add_argument("--tone", required=True)
    ap.add_argument("--metering", required=True, help="the shipped colour/metering.json")
    ap.add_argument("--cache", required=True, help="B23c block cache")
    ap.add_argument("--out", required=True)
    ap.add_argument("--asset", help="write the spot block into this metering.json")
    a = ap.parse_args(argv)

    from .raw_pipeline import load_metering, load_tone

    tone = load_tone(a.tone)
    met = load_metering(a.metering)
    af = {os.path.realpath(e["SourceFile"]): e for e in json.load(open(a.af))}
    rows, areas = [], []
    for r in mt._load_records(a.manifest, a.exif):
        e = af.get(os.path.realpath(r["raw"]))
        box, npts = af_box(e) if e else (None, 0)
        if box is None:
            continue
        z = np.load(os.path.join(a.cache, r["id"] + ".npz"))
        st = json.loads(str(z["stats"]))
        g = box_to_grid(box, int(e.get("Orientation", 1)) in (5, 6, 7, 8))
        if g[1] - g[0] < 2 or g[3] - g[2] < 2:
            continue
        f = mt.features(st)
        t, _ = met.target(1.0, 2.0 ** f["hi"])
        pred = t + (met.headroom.lift(t, 1.0, 2.0 ** f["hi"], 2.0 ** -f["split"], st["clipped_fraction"]) if met.headroom else 0.0)
        ec, _ = mt._ec(r)
        target = mt.target_ev(tone, st["jpeg_median_encoded"], ec)
        rows.append({"id": r["id"], "split": r["split"], "points": npts, "d": region_d(z["xyz"][:, 1], g),
                     "resid": target - pred})
        # full-frame area of the AF box (the grid covers the central CENTER x CENTER of the frame)
        areas.append((g[1] - g[0]) * (g[3] - g[2]) / (GRID[0] * GRID[1]) * CENTER * CENTER)
    tr = [x for x in rows if x["split"] == "train"]
    ho = [x for x in rows if x["split"] == "holdout"]
    d, e = np.array([x["d"] for x in tr]), np.array([x["resid"] for x in tr])
    dh, eh = np.array([x["d"] for x in ho]), np.array([x["resid"] for x in ho])
    w = fit_weights(d, e)
    b = bootstrap(d, e)
    lo, hi = np.percentile(b, 5, axis=0), np.percentile(b, 95, axis=0)
    wb, wd = shipped(w, lo)
    ws = np.array([wb, wd])
    side = math.sqrt(float(np.median(areas)) * 1.5)  # square region on a 3:2 frame (short side 1, long side 1.5)
    report = {
        "pairs": {"train": len(tr), "holdout": len(ho)},
        "cap_ev": CAP_EV,
        "fit": {"w_brighter": round(float(w[0]), 4), "w_darker": round(float(w[1]), 4),
                "bootstrap_p5": [round(float(v), 4) for v in lo], "bootstrap_p95": [round(float(v), 4) for v in hi]},
        "shipped": {"w_brighter": wb, "w_darker": wd},
        "train_mae_ev": {"before": round(float(np.mean(np.abs(e))), 4),
                         "after": round(float(np.mean(np.abs(e - design(d) @ ws))), 4),
                         "loo_after_fitted": round(loo_mae(d, e), 4)},
        "holdout_mae_ev": {"before": round(float(np.mean(np.abs(eh))), 4),
                           "after": round(float(np.mean(np.abs(eh - design(dh) @ ws))), 4),
                           "pairs_brighter_subject": int(np.sum(dh < 0))},
        "af_area_frame_fraction_median": round(float(np.median(areas)), 5),
        "region_short_side": round(side, 3),
        "rows": rows,
    }
    os.makedirs(a.out, exist_ok=True)
    with open(os.path.join(a.out, "spot_report.json"), "w") as fh:
        json.dump(report, fh, indent=1)
    if a.asset:
        m = json.load(open(a.asset))
        m["spot"] = asset_block(report)
        with open(a.asset, "w", newline="\n") as fh:
            json.dump(m, fh, indent=1)
            fh.write("\n")
    print(json.dumps({k: v for k, v in report.items() if k != "rows"}, indent=1))
    return 0


def asset_block(report: dict) -> dict:
    return {
        "model": "r5ii_af_spot v1",
        "description": "B24b touch metering: d = log2(frame median / tapped-region median); the mid-tone target moves by "
                       "w_brighter * clamp(d, -cap_ev, 0) + w_darker * clamp(d, 0, cap_ev) (a log-domain blend of the "
                       "region median into the frame median). Fitted from the AF point of the R5 II pairs (evaluative "
                       "metering, AF-linked; redline_cal.spot_metering): the R5 II holds a bright AF subject down but "
                       "does not lift a dark one. region_short_side: tap region side / frame short side (median R5 II "
                       "AF area).",
        "w_brighter": report["shipped"]["w_brighter"],
        "w_darker": report["shipped"]["w_darker"],
        "cap_ev": report["cap_ev"],
        "region_short_side": report["region_short_side"],
        "fit": {"pairs_train": report["pairs"]["train"], "pairs_holdout": report["pairs"]["holdout"],
                "w_brighter_fit": report["fit"]["w_brighter"], "w_darker_fit": report["fit"]["w_darker"],
                "bootstrap_p5": report["fit"]["bootstrap_p5"], "bootstrap_p95": report["fit"]["bootstrap_p95"],
                "holdout_mae_ev_before": report["holdout_mae_ev"]["before"],
                "holdout_mae_ev_after": report["holdout_mae_ev"]["after"]},
    }


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
