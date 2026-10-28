"""Pair RAW files with their in-camera JPEGs."""
from __future__ import annotations

import os
from collections import defaultdict
from dataclasses import dataclass

RAW_EXT = {".cr3", ".cr2"}
JPEG_EXT = {".jpg", ".jpeg"}


@dataclass(frozen=True)
class Pair:
    raw: str
    jpeg: str


def scan(root: str) -> tuple[list[str], list[str]]:
    raws, jpegs = [], []
    for d, _, files in os.walk(root):
        for f in sorted(files):
            ext = os.path.splitext(f)[1].lower()
            p = os.path.join(d, f)
            if ext in RAW_EXT:
                raws.append(p)
            elif ext in JPEG_EXT:
                jpegs.append(p)
    return sorted(raws), sorted(jpegs)


def pair_by_stem(raws: list[str], jpegs: list[str]) -> tuple[list[Pair], list[str], list[str]]:
    """Pair files sharing a directory and filename stem (case-insensitive)."""
    idx: dict[tuple[str, str], list[str]] = defaultdict(list)
    for j in jpegs:
        idx[(os.path.dirname(j), os.path.splitext(os.path.basename(j))[0].lower())].append(j)
    pairs, lone_raw, used = [], [], set()
    for r in raws:
        key = (os.path.dirname(r), os.path.splitext(os.path.basename(r))[0].lower())
        cands = [j for j in idx.get(key, []) if j not in used]
        if cands:
            pairs.append(Pair(r, cands[0]))
            used.add(cands[0])
        else:
            lone_raw.append(r)
    lone_jpeg = [j for j in jpegs if j not in used]
    return pairs, lone_raw, lone_jpeg


def pair_by_capture_time(
    lone_raw: list[str], lone_jpeg: list[str], times: dict[str, tuple[str | None, str | None, str | None]]
) -> list[Pair]:
    """Fallback for renamed downloads: match on (DateTimeOriginal, SubSec, body serial)."""
    by_key: dict[tuple, list[str]] = defaultdict(list)
    for j in lone_jpeg:
        k = times.get(j)
        if k and k[0]:
            by_key[k].append(j)
    out = []
    for r in lone_raw:
        k = times.get(r)
        if k and k[0] and len(by_key.get(k, [])) == 1:
            out.append(Pair(r, by_key[k].pop()))
    return out
