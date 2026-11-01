"""B23c: metering (mid-tone placement) and tone re-check fitted from R5 II RAW + camera-JPEG pairs.

Until B23c the app placed a shot's exposure where the phone's own camera JPEG put its median (exposure method
``camera_jpeg_median``), so Redline inherited the phone's bright, safe metering. Here the target comes from the
R5 II dataset instead.

Per pair (B02 manifest, the same train / hold-out split as B03):

* ``scene``: luminance quantiles of the RAW frame, developed to linear XYZ (LibRaw, camera WB, half size), relative
  to the sensor clip. Ratios between quantiles do not depend on exposure, so a phone frame gives the same features.
* ``target``: where the camera JPEG puts the scene median on the input axis of the fitted Standard tone curve,
  ``t = invert(tone, jpeg_median_encoded)`` (an EV; the RAW is relative to the sensor clip, so the gain our pipeline
  needs to reproduce the camera's mid-tone is ``t - log2(raw median)``). Shots in an auto-exposure mode (Av / Tv / P)
  have their exposure compensation taken out, so the target is the camera's own metering at 0 EV.

The metering model predicts the mid-tone target ``t = invert(tone, M)`` (EV on the tone curve's input axis) from
scale-free scene features, so on the phone ``gain = t - log2(phone median)``. Candidates (constant, linear in the
highlight spread, linear in highlight and shadow spread) are compared by leave-one-out error on the train split,
and the winner is reported on the hold-out only once.

The tone re-check renders each hold-out pair's RAW blocks through the shipped tone curve + shoulder + LUT at the
pair's own placement (so metering error does not leak into the tone check) and compares lightness with the camera
JPEG per region: black point (JPEG L* < 15), mid-tones (L* 30-70) and the highlight shoulder (gained input above the
shoulder knee), plus chroma of near-white blocks (specular desaturation).
"""
from __future__ import annotations

import argparse
import json
import math
import os
import sys
from dataclasses import dataclass

import numpy as np

from .color import srgb_decode, srgb_encode, srgb_encoded_to_lab, XYZ_TO_SRGB
from .lut import apply_lut, read_cube
from .raw_pipeline import EXPOSURE_MAX_EV, EXPOSURE_MIN_EV, KNEE_EV, LUMA, ToneCurve, invert_fitted

QUANTILES = (1.0, 2.0, 5.0, 10.0, 25.0, 50.0, 75.0, 90.0, 95.0, 98.0, 99.0)
CLIP_LEVEL = 0.98          # a RAW value at or above this fraction of the clip counts as clipped
AE_MODES = {1, 2, 3}       # Canon CanonExposureMode: 1 Program AE, 2 Tv, 3 Av (4 Manual, 0 Full auto excluded too)
GRID = (64, 96)            # block grid over the frame for the tone re-check
CENTER = 0.8               # central fraction used for blocks (the JPEG's peripheral correction acts in the corners)
EXCLUDE = ("9092841d",)    # B03: excluded pair (prefix of the manifest id)

# B23c gate regions (B03 lightness gate: median |dL*| < 2.0)
GATE_DL = 2.0
BLACK_L = 15.0
MID_L = (30.0, 70.0)
NEAR_WHITE_L = 90.0


# ----------------------------------------------------------------------------------------------- scene features


def quantiles(y: np.ndarray) -> dict:
    """log2 luminance quantiles (relative to the sensor clip) and the clipped fraction of a linear Y sample."""
    y = np.asarray(y, float).reshape(-1)
    q = np.percentile(np.maximum(y, 2.0**-20), QUANTILES)
    return {
        "q": {f"p{p:g}": float(np.log2(v)) for p, v in zip(QUANTILES, q)},
        "clipped_fraction": float(np.mean(y >= CLIP_LEVEL)),
    }


def features(stats: dict) -> dict:
    """Scale-free scene features: highlight spread log2(p98 / p50), shadow spread log2(p50 / p5)."""
    q = stats["q"]
    return {"hi": q["p98"] - q["p50"], "lo": q["p50"] - q["p5"]}


MODELS = {
    "constant": (),
    "hi": ("hi",),
    "hi_lo": ("hi", "lo"),
}


@dataclass
class MeteringModel:
    name: str
    terms: tuple
    coef: np.ndarray  # intercept first

    def predict(self, feats: dict) -> float:
        x = np.array([1.0] + [feats[t] for t in self.terms])
        return float(x @ self.coef)

    def to_json(self) -> dict:
        return {"name": self.name, "terms": list(self.terms), "coef": [round(float(c), 6) for c in self.coef]}


def fit_model(name: str, rows: list[dict]) -> MeteringModel:
    terms = MODELS[name]
    x = np.array([[1.0] + [r["features"][t] for t in terms] for r in rows])
    y = np.array([r["target_ev"] for r in rows])
    coef, *_ = np.linalg.lstsq(x, y, rcond=None)
    return MeteringModel(name, terms, coef)


def loo_error(name: str, rows: list[dict]) -> float:
    """Leave-one-out mean absolute error (EV) on ``rows``."""
    errs = []
    for i in range(len(rows)):
        m = fit_model(name, rows[:i] + rows[i + 1:])
        errs.append(abs(m.predict(rows[i]["features"]) - rows[i]["target_ev"]))
    return float(np.mean(errs))


def target_ev(tone: np.ndarray, jpeg_median: float, ec: float = 0.0) -> float:
    """Mid-tone target on the tone curve's input axis (EV), with ``ec`` (exposure compensation) taken out."""
    return invert_fitted(tone, jpeg_median) - ec


def gain_ev(target: float, median_y: float) -> float:
    """Exposure gain (log2) that puts a frame's median luminance at ``target`` (clamped like solve_exposure)."""
    if not (median_y > 1e-6 and math.isfinite(median_y)):
        return 0.0
    return float(min(max(target - math.log2(median_y), EXPOSURE_MIN_EV), EXPOSURE_MAX_EV))


# ----------------------------------------------------------------------------------------------- per-pair extraction


def _aligned(xyz: np.ndarray, jpeg: np.ndarray) -> np.ndarray:
    """Crop the developed RAW to the JPEG's field (LibRaw keeps a few border pixels more) at the JPEG's scale."""
    from .samples import center_crop_to_aspect

    sy, sx = xyz.shape[0] / jpeg.shape[0], xyz.shape[1] / jpeg.shape[1]
    s = min(sy, sx)
    th, tw = round(jpeg.shape[0] * s), round(jpeg.shape[1] * s)
    y0, x0 = (xyz.shape[0] - th) // 2, (xyz.shape[1] - tw) // 2
    xyz = xyz[y0:y0 + th, x0:x0 + tw]
    return center_crop_to_aspect(xyz, jpeg.shape[1] / jpeg.shape[0])


def _blocks(img: np.ndarray, gh: int, gw: int, center: float):
    h, w = img.shape[:2]
    dy, dx = int(h * (1 - center) / 2), int(w * (1 - center) / 2)
    img = img[dy:h - dy, dx:w - dx]
    h, w = img.shape[:2]
    bh, bw = h // gh, w // gw
    v = img[: bh * gh, : bw * gw].reshape(gh, bh, gw, bw, -1).astype(np.float64)
    return v.mean(axis=(1, 3)).reshape(-1, img.shape[2]), v.std(axis=(1, 3)).reshape(-1, img.shape[2]), \
        v.max(axis=(1, 3)).reshape(-1, img.shape[2])


def load_jpeg_half(path: str, size: tuple[int, int]) -> np.ndarray:
    """Camera JPEG decoded at about ``size`` (w, h) through the DCT draft mode (fast), sRGB-encoded 0..1."""
    from PIL import Image, ImageOps

    with Image.open(path) as im:
        im.draft("RGB", size)
        im = ImageOps.exif_transpose(im).convert("RGB")
        return np.asarray(im, dtype=np.float32) / 255.0


def extract_pair(raw_path: str, jpeg_path: str) -> tuple[dict, dict]:
    """Scene stats + JPEG median + block arrays for one pair (real files; slow)."""
    from .raw import decode_linear_xyz

    xyz = decode_linear_xyz(raw_path)
    jpeg = load_jpeg_half(jpeg_path, (xyz.shape[1], xyz.shape[0]))
    if (jpeg.shape[0] > jpeg.shape[1]) != (xyz.shape[0] > xyz.shape[1]):
        xyz = np.rot90(xyz)  # portrait JPEG (EXIF-rotated) vs landscape RAW: compare in the JPEG's orientation
    xyz = _aligned(xyz, jpeg)
    y = xyz[::4, ::4, 1]
    stats = quantiles(y)
    jl = srgb_decode(jpeg[::4, ::4].reshape(-1, 3)) @ LUMA
    stats["median_y"] = float(np.median(y))
    stats["jpeg_median_encoded"] = float(srgb_encode(np.median(jl)))
    mx, sx, xmax = _blocks(xyz, *GRID, CENTER)
    mj, sj, _ = _blocks(jpeg, *GRID, CENTER)
    blocks = {"xyz": mx.astype(np.float32), "xyz_std": sx.astype(np.float32), "xyz_max": xmax.astype(np.float32),
              "jpeg": mj.astype(np.float32), "jpeg_std": sj.astype(np.float32)}
    return stats, blocks


# ----------------------------------------------------------------------------------------------- tone re-check


def render_blocks(xyz: np.ndarray, gain_log2: float, tone: np.ndarray, lut: np.ndarray | None,
                  knee_ev: float = KNEE_EV) -> np.ndarray:
    """B21 stages 5-8 on developed XYZ blocks: linear sRGB, tone curve + shoulder at ``gain``, residual LUT."""
    lin = np.clip(np.asarray(xyz, float) @ XYZ_TO_SRGB.T, 0.0, None)
    curve = ToneCurve(tone, 2.0**gain_log2, knee_ev=knee_ev)
    y = curve(curve.gain * lin)
    return np.clip(apply_lut(y, lut), 0.0, 1.0) if lut is not None else y


def tone_regions(xyz: np.ndarray, jpeg: np.ndarray, gain_log2: float, tone: np.ndarray, lut: np.ndarray | None,
                 flat: np.ndarray, knee_ev: float = KNEE_EV, region_ev: float | None = None) -> dict:
    """Signed dL* (prediction - JPEG) per region on flat blocks, and near-white chroma."""
    pred = render_blocks(xyz, gain_log2, tone, lut, knee_ev)
    lp = srgb_encoded_to_lab(pred.reshape(-1, 3))
    lj = srgb_encoded_to_lab(np.asarray(jpeg, float).reshape(-1, 3))
    lin_y = np.clip(np.asarray(xyz, float)[:, 1], 1e-9, None)
    ev_in = np.log2(lin_y) + gain_log2
    d = lp[:, 0] - lj[:, 0]
    regions = {
        "black": flat & (lj[:, 0] < BLACK_L),
        "mid": flat & (lj[:, 0] >= MID_L[0]) & (lj[:, 0] <= MID_L[1]),
        "shoulder": flat & (ev_in >= (knee_ev if region_ev is None else region_ev)),
    }
    out = {k: d[m] for k, m in regions.items()}
    nw = flat & (lj[:, 0] >= NEAR_WHITE_L)
    out["near_white_chroma_pred"] = np.hypot(lp[nw, 1], lp[nw, 2])
    out["near_white_chroma_jpeg"] = np.hypot(lj[nw, 1], lj[nw, 2])
    return out


def flat_mask(blocks: dict) -> np.ndarray:
    """Flat blocks (tolerant of small geometric differences) with signal; clipping is kept (shoulder / speculars)."""
    mx, sx, sj = blocks["xyz"], blocks["xyz_std"], blocks["jpeg_std"]
    rel = sx / (mx + 1e-4)
    return (sj.max(axis=1) < 0.03) & ((rel.max(axis=1) < 0.15) | (blocks["xyz_max"][:, 1] >= CLIP_LEVEL)) & (mx[:, 1] > 0)


def summarise(dl: np.ndarray) -> dict:
    dl = np.asarray(dl, float)
    if dl.size == 0:
        return {"n": 0}
    return {"n": int(dl.size), "median_abs_dL": round(float(np.median(np.abs(dl))), 3),
            "median_dL": round(float(np.median(dl)), 3), "mean_abs_dL": round(float(np.mean(np.abs(dl))), 3),
            "pass": bool(np.median(np.abs(dl)) < GATE_DL)}


def block_radius(gh: int = GRID[0], gw: int = GRID[1], aspect: float = 1.5, center: float = CENTER) -> np.ndarray:
    """Distance of each block centre from the optical centre, 0 centre .. 1 frame corner (half-diagonal)."""
    yy, xx = np.mgrid[0:gh, 0:gw]
    fy = (1 - center) / 2 + center * (yy + 0.5) / gh - 0.5
    fx = ((1 - center) / 2 + center * (xx + 0.5) / gw - 0.5) * aspect
    return (np.hypot(fy, fx) / np.hypot(0.5, 0.5 * aspect)).reshape(-1)


CENTRAL_RADIUS = 0.5  # blocks inside this radius (fraction of the half-diagonal) are used to align exposure


def tone_check(rows: list[dict], tone: np.ndarray, lut: np.ndarray, align: bool, model: "MeteringModel | None" = None,
               knee_ev: float = KNEE_EV, region_ev: float | None = None) -> dict:
    """Region dL* (prediction - camera JPEG) over ``rows``: pooled stats plus per-image signed medians."""
    acc = {"black": [], "mid": [], "shoulder": [], "near_white_chroma_pred": [], "near_white_chroma_jpeg": []}
    per_image = {}
    for r in rows:
        b = r["blocks"]
        aspect = 1.5 if b["xyz"].shape[0] == GRID[0] * GRID[1] else 1.5
        rad = block_radius(aspect=aspect)
        flat = flat_mask(b)
        if model is not None:
            gain = gain_ev(model.predict(r["features"]) + r.get("ec", 0.0), 2.0 ** r["stats"]["q"]["p50"])
        else:
            gain = r["target_ev_raw"] - r["stats"]["q"]["p50"]
        if align:
            central = flat & (rad < CENTRAL_RADIUS)
            for _ in range(3):
                reg = tone_regions(b["xyz"], b["jpeg"], gain, tone, lut, central, knee_ev)
                if reg["mid"].size < 20:
                    break
                gain -= float(np.median(reg["mid"])) / 25.0  # ~25 L* per EV in the mid-tones; converges in a few steps
            flat = flat & (rad < CENTRAL_RADIUS) if align else flat
        reg = tone_regions(b["xyz"], b["jpeg"], gain, tone, lut, flat, knee_ev, region_ev)
        for k in acc:
            acc[k].append(reg[k])
        per_image[r["id"]] = {k: (round(float(np.median(reg[k])), 2) if reg[k].size >= 10 else None)
                              for k in ("black", "mid", "shoulder")}
    cat = {k: np.concatenate(v) if v else np.array([]) for k, v in acc.items()}
    out = {k: summarise(cat[k]) for k in ("black", "mid", "shoulder")}
    for k in ("black", "shoulder"):
        meds = [v[k] for v in per_image.values() if v[k] is not None]
        out[k]["images"] = len(meds)
        out[k]["images_darker"] = int(sum(m < 0 for m in meds))
    nwp, nwj = cat["near_white_chroma_pred"], cat["near_white_chroma_jpeg"]
    out["near_white"] = {"n": int(nwp.size),
                         "median_chroma_pred": round(float(np.median(nwp)), 3) if nwp.size else None,
                         "median_chroma_jpeg": round(float(np.median(nwj)), 3) if nwj.size else None}
    out["per_image"] = per_image
    return out


# ----------------------------------------------------------------------------------------------- CLI


def _load_records(manifest: str, exif_path: str | None) -> list[dict]:
    exif = {}
    if exif_path:
        for e in json.load(open(exif_path)):
            exif[os.path.realpath(e["SourceFile"])] = e
    recs = []
    for line in open(manifest):
        r = json.loads(line)
        if not r.get("usable") or r["id"].startswith(EXCLUDE):
            continue
        r["exif"] = exif.get(os.path.realpath(r["raw"]), {})
        recs.append(r)
    return recs


def _ec(rec: dict) -> tuple[float, bool]:
    e = rec.get("exif", {})
    ae = int(e.get("CanonExposureMode", -1)) in AE_MODES
    return (float(e.get("ExposureCompensation", 0.0)) if ae else 0.0), ae


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("manifest")
    ap.add_argument("--exif", help="exiftool -j -n output with CanonExposureMode and ExposureCompensation per RAW")
    ap.add_argument("--tone", required=True, help="the shipped standard_tone.json")
    ap.add_argument("--lut", required=True, help="the shipped residual .cube")
    ap.add_argument("--cache", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--extract-only", action="store_true")
    ap.add_argument("--asset", help="write the fitted model here (app asset colour/metering.json)")
    ap.add_argument("--limit", type=int, default=0, help="extract at most this many uncached pairs, then stop")
    ap.add_argument("--shard", default="0/1", help="i/n: extract only pairs i, i+n, ... (parallel extraction)")
    a = ap.parse_args(argv)

    from .raw_pipeline import load_tone

    tone = load_tone(a.tone)
    lut = read_cube(a.lut)
    os.makedirs(a.cache, exist_ok=True)
    recs = _load_records(a.manifest, a.exif)
    done = 0
    si, sn = (int(v) for v in a.shard.split("/"))
    for r in recs[si::sn]:
        f = os.path.join(a.cache, r["id"] + ".npz")
        if os.path.exists(f):
            continue
        if a.limit and done >= a.limit:
            print(f"extracted {done}; more pairs remain", file=sys.stderr)
            return 3
        stats, blocks = extract_pair(r["raw"], r["jpeg"])
        np.savez_compressed(f, stats=json.dumps(stats), **blocks)
        done += 1
    if a.extract_only:
        return 0

    rows = []
    for r in recs:
        z = np.load(os.path.join(a.cache, r["id"] + ".npz"))
        stats = json.loads(str(z["stats"]))
        ec, ae = _ec(r)
        rows.append({
            "id": r["id"], "split": r["split"], "ae": ae, "ec": ec, "stats": stats,
            "features": features(stats),
            "target_ev": target_ev(tone, stats["jpeg_median_encoded"], ec),
            "target_ev_raw": target_ev(tone, stats["jpeg_median_encoded"], 0.0),
            "blocks": {k: z[k] for k in ("xyz", "xyz_std", "xyz_max", "jpeg", "jpeg_std")},
        })
    report = evaluate(rows, tone, lut)
    os.makedirs(a.out, exist_ok=True)
    with open(os.path.join(a.out, "metering_report.json"), "w") as fh:
        json.dump(report, fh, indent=1)
    if a.asset:
        with open(a.asset, "w", newline="\n") as fh:
            json.dump(asset_json(report), fh, indent=1)
            fh.write("\n")
    summary = {k: report[k] for k in ("pairs", "selection", "metering_holdout")}
    summary["tone_holdout_matched"] = {k: v for k, v in report["tone_holdout"]["matched"].items() if k != "per_image"}
    print(json.dumps(summary, indent=1))
    return 0


def asset_json(report: dict) -> dict:
    """The app's colour/metering.json: what ``raw_pipeline.load_metering`` and Kotlin ``ColourMath.Metering`` read."""
    m = report["metering_holdout"]
    if m["model"]["terms"] != ["hi"]:
        raise ValueError("the app implements the 'hi' metering model only")
    return {
        "model": "r5ii_metering v1",
        "description": "B23c: mid-tone target (EV on the Standard tone curve's input axis) = coef[0] + coef[1] * "
                       "clamp(log2(p98_y / median_y), hi_range). Fitted from R5 II RAW + camera JPEG pairs "
                       "(redline_cal.metering); exposure compensation of auto-exposure shots taken out.",
        "coef": m["model"]["coef"],
        "hi_range": report["hi_range_train"],
        "fallback_target_ev": report["constant_target_ev"],
        "fit": {"pairs_train": report["pairs"]["train"], "pairs_holdout": report["pairs"]["holdout"],
                "loo_mae_ev_train": report["selection"]["loo_mae_ev_train"][m["model"]["name"]],
                "holdout_mae_ev": m["mae_ev"], "holdout_median_abs_ev": m["median_abs_ev"],
                "holdout_mae_ev_default_target": m["default_target_0_46_mae_ev"]},
    }


def evaluate(rows: list[dict], tone: np.ndarray, lut: np.ndarray) -> dict:
    train = [r for r in rows if r["split"] == "train"]
    hold = [r for r in rows if r["split"] == "holdout"]
    selection = {name: round(loo_error(name, train), 4) for name in MODELS}
    best = min(selection, key=selection.get)
    # prefer the simpler model unless the richer one is clearly better (0.05 EV)
    order = list(MODELS)
    for name in order:
        if selection[name] <= selection[best] + 0.05:
            best = name
            break
    model = fit_model(best, train)
    default_target = invert_fitted(tone, 0.46)

    def errs(pred_fn, rs):
        return np.array([pred_fn(r) - r["target_ev"] for r in rs])

    lo_hi = (min(r["features"]["hi"] for r in train), max(r["features"]["hi"] for r in train))

    def clamped(f):
        return {**f, "hi": min(max(f["hi"], lo_hi[0]), lo_hi[1])}

    e_model = errs(lambda r: model.predict(clamped(r["features"])), hold)
    e_default = errs(lambda r: default_target, hold)
    l_mid = []
    for r in hold:
        # what the predicted placement does to the mid-tone: L* of the tone curve at the target vs the JPEG
        pt = model.predict(clamped(r["features"]))
        y_pred = float(np.interp(pt, np.linspace(-14, 3, len(tone)), tone))
        l_mid.append(float(srgb_encoded_to_lab(np.array([[y_pred] * 3]))[0, 0]
                           - srgb_encoded_to_lab(np.array([[r["stats"]["jpeg_median_encoded"]] * 3]))[0, 0]))
    metering = {
        "model": model.to_json(),
        "n": len(hold),
        "mae_ev": round(float(np.mean(np.abs(e_model))), 3),
        "median_abs_ev": round(float(np.median(np.abs(e_model))), 3),
        "bias_ev": round(float(np.mean(e_model)), 3),
        "max_abs_ev": round(float(np.max(np.abs(e_model))), 3),
        "median_abs_dL_midtone": round(float(np.median(np.abs(l_mid))), 3),
        "default_target_0_46_mae_ev": round(float(np.mean(np.abs(e_default))), 3),
        "per_image_ev_error": {r["id"]: round(float(e), 3) for r, e in zip(hold, e_model)},
        "target_encoded_at_constant": round(float(np.interp(model.coef[0], np.linspace(-14, 3, len(tone)), tone)), 4)
        if not model.terms else None,
    }
    # tone re-check on the hold-out on matched scenes: each pair at its own placement, then (as B03's
    # --align-exposure) a per-image exposure offset that zeroes the median mid-tone dL* in the central region, which
    # the camera JPEG's peripheral-illumination correction does not touch; metering error is reported above
    tone_hold = {"matched": tone_check(hold, tone, lut, align=True),
                 "own_placement": tone_check(hold, tone, lut, align=False),
                 "metered": tone_check(hold, tone, lut, align=False, model=model)}
    tone_train = tone_check(train, tone, lut, align=True)
    his = [r["features"]["hi"] for r in train]
    const = fit_model("constant", train)
    return {
        "hi_range_train": [round(float(min(his)), 4), round(float(max(his)), 4)],
        "constant_target_ev": round(float(const.coef[0]), 6),
        "pairs": {"train": len(train), "holdout": len(hold), "ae_train": sum(r["ae"] for r in train),
                  "ae_holdout": sum(r["ae"] for r in hold)},
        "selection": {"loo_mae_ev_train": selection, "chosen": best},
        "metering_holdout": metering,
        "tone_holdout": tone_hold,
        "tone_train_matched": tone_train,
        "train_targets": {r["id"]: round(r["target_ev"], 3) for r in train},
    }


if __name__ == "__main__":
    raise SystemExit(main())
