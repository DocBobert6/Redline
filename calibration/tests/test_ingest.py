import json
import os
import shutil

import pytest

from redline_cal import ingest
from redline_cal.manifest import is_target_lens, nearest_stop, read_jsonl, split_for
from redline_cal.metadata import Shot
from redline_cal.pairs import pair_by_capture_time, pair_by_stem
from redline_cal.policy import StandardPolicy

FIX = os.path.join(os.path.dirname(__file__), "fixtures")


def exif(**over):
    with open(os.path.join(FIX, "r5ii_raw_exif.json")) as f:
        d = json.load(f)
    d.update(over)
    return d


def test_parses_real_r5ii_metadata():
    s = Shot.from_exif(exif())
    assert s.model == "EOS R5 Mark II"  # not IFD0's "Canon EOS R5m2"
    assert s.picture_style == "Standard"
    assert s.exposure_s == pytest.approx(1 / 200)
    assert s.focal_mm == 135.0
    assert s.iso == 640
    assert s.sharpness == 4 and s.sharpness_fineness == 2 and s.sharpness_threshold == 3


def test_real_sample_rejected_for_htp():
    assert StandardPolicy().evaluate(Shot.from_exif(exif())) == ["htp:On"]


def test_default_standard_accepted():
    assert StandardPolicy().evaluate(Shot.from_exif(exif(**{"Canon:HighlightTonePriority": "Off"}))) == []


@pytest.mark.parametrize(
    "over,reason",
    [
        ({"Canon:PictureStyle": "Portrait"}, "picture_style:Portrait"),
        ({"Canon:Contrast": "+2"}, "contrast:+2"),
        ({"Canon:AutoLightingOptimizer": "High"}, "alo:High"),
        ({"Canon:CanonModelID": "EOS R5"}, "model:EOS R5"),
        ({"Canon:DigitalLensOptimizerSetting": "Standard"}, "dlo:Standard"),
    ],
)
def test_rejections(over, reason):
    over = {"Canon:HighlightTonePriority": "Off", **over}
    assert reason in StandardPolicy().evaluate(Shot.from_exif(exif(**over)))


def test_strict_alo():
    s = Shot.from_exif(exif(**{"Canon:HighlightTonePriority": "Off"}))
    assert StandardPolicy(alo_allowed=frozenset({"Off"})).evaluate(s) == ["alo:Standard"]


def test_missing_makernotes_flagged():
    s = Shot.from_exif({"SourceFile": "x.CR3", "Canon:CanonModelID": "EOS R5 Mark II"})
    assert "missing_makernotes" in StandardPolicy().evaluate(s)


def test_target_lens_and_stops():
    assert is_target_lens("RF24-70mm F2.8 L IS USM")
    assert not is_target_lens("RF24-70mm F2.8 L IS USM Z")
    assert not is_target_lens("RF24-105mm F4 L IS USM")
    assert [nearest_stop(f) for f in (24, 30, 33, 45, 62, 70)] == [24, 28, 35, 50, 70, 70]


def test_split_is_deterministic():
    assert split_for("00000000" + "0" * 56) == "holdout"
    assert split_for("ffffffff" + "0" * 56) == "train"


def test_pair_by_stem(tmp_path):
    raws = [str(tmp_path / "a" / "IMG_1.CR3"), str(tmp_path / "a" / "IMG_2.CR3")]
    jpegs = [str(tmp_path / "a" / "img_1.JPG"), str(tmp_path / "b" / "IMG_2.JPG")]
    pairs, lone_raw, lone_jpeg = pair_by_stem(raws, jpegs)
    assert [(p.raw, p.jpeg) for p in pairs] == [(raws[0], jpegs[0])]
    assert lone_raw == [raws[1]] and lone_jpeg == [jpegs[1]]


def test_pair_by_capture_time():
    t = {"r.CR3": ("2024:08:20 12:15:58", "48", "1"), "j.jpg": ("2024:08:20 12:15:58", "48", "1"), "k.jpg": ("x", "1", "1")}
    assert [(p.raw, p.jpeg) for p in pair_by_capture_time(["r.CR3"], ["j.jpg", "k.jpg"], t)] == [("r.CR3", "j.jpg")]


def test_build_end_to_end(tmp_path, monkeypatch):
    for n in ("IMG_1.CR3", "IMG_1.JPG", "IMG_2.CR3", "IMG_2.JPG", "IMG_3.CR3"):
        (tmp_path / n).write_bytes(n.encode())
    good = {"Canon:HighlightTonePriority": "Off", "Canon:LensModel": "RF24-70mm F2.8 L IS USM",
            "ExifIFD:LensModel": "RF24-70mm F2.8 L IS USM", "ExifIFD:FocalLength": "35.0 mm"}

    def fake(paths, exe=None):
        out = []
        for p in paths:
            over = dict(good)
            if "IMG_2" in p:
                over["Canon:PictureStyle"] = "Fine Detail"
            out.append(exif(SourceFile=p, **over))
        return out

    monkeypatch.setattr(ingest, "run_exiftool", fake)
    records, summary = ingest.build(str(tmp_path))
    assert summary["pairs"] == 2 and summary["usable"] == 1
    assert summary["usable_by_stop"] == {35: 1}
    assert summary["rejections"] == {"picture_style:Fine Detail": 1}
    assert all("body_serial" not in r["shot"] for r in records)

    out = tmp_path / "m.jsonl"
    assert ingest.main([str(tmp_path), "--out", str(out)]) == 0
    assert len(read_jsonl(str(out))) == 2


@pytest.mark.skipif(not (shutil.which("exiftool") and os.environ.get("REDLINE_CAL_SAMPLE_CR3")),
                    reason="set REDLINE_CAL_SAMPLE_CR3 and install exiftool for the real-file check")
def test_real_cr3_roundtrip():
    from redline_cal.metadata import run_exiftool

    s = Shot.from_exif(run_exiftool([os.environ["REDLINE_CAL_SAMPLE_CR3"]])[0])
    assert s.model == "EOS R5 Mark II" and s.picture_style is not None


@pytest.mark.skipif(not os.environ.get("REDLINE_CAL_SAMPLE_CR3"), reason="set REDLINE_CAL_SAMPLE_CR3 for the decode check")
def test_real_cr3_decode():
    rawpy = pytest.importorskip("rawpy")
    assert rawpy is not None
    from redline_cal.raw import decode_linear_xyz, raw_info

    info = raw_info(os.environ["REDLINE_CAL_SAMPLE_CR3"])
    assert info.white_level > max(info.black_levels)
    xyz = decode_linear_xyz(os.environ["REDLINE_CAL_SAMPLE_CR3"])
    assert xyz.ndim == 3 and xyz.shape[2] == 3 and 0 < float(xyz.mean()) < 1


def test_allow_dlo():
    s = Shot.from_exif(exif(**{"Canon:HighlightTonePriority": "Off", "Canon:DigitalLensOptimizerSetting": "On"}))
    assert StandardPolicy().evaluate(s) == ["dlo:On"]
    assert StandardPolicy(require_dlo_off=False).evaluate(s) == []
