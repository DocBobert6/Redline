#!/usr/bin/env bash
# B30: puts the on-device models into app/src/main/assets/depth/ (they are not committed: ~49 MB).
#
#  - Depth Anything V2 Small (Apache-2.0; only the Small variant is permissive) is converted here from the official
#    checkpoint at a pinned revision, checksum-verified, to a 280x420 LiteRT model with float16 weights
#    (export_depth_anything_v2_small.py), and must reproduce the torch reference on the self-check scene.
#  - MediaPipe selfie segmenter, landscape 144x256 (Apache-2.0), downloaded at its versioned URL, checksum-verified.
#
# Results are cached in $REDLINE_MODEL_CACHE (default ~/.cache/redline-models); CI caches that folder keyed on this
# script, the exporter and requirements.txt. Python 3.10-3.12 is needed only on a cache miss.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT="$ROOT/app/src/main/assets/depth"
CACHE="${REDLINE_MODEL_CACHE:-$HOME/.cache/redline-models}"
mkdir -p "$OUT" "$CACHE"

DEPTH=depth_anything_v2_small_280x420_fp16.tflite
SEG=selfie_segmenter_landscape.tflite
CKPT_URL=https://huggingface.co/depth-anything/Depth-Anything-V2-Small-hf/resolve/5426e4f0f36572d16453bbda7a8389317b1bef99/model.safetensors
CKPT_SHA=3152477ce0d8d6978d76b995120de97cb5b928701fd0f817769f59e249a16b70
# The versioned URL (.../float16/1/...) intermittently answers 403; "latest" is the same file today. Either way the
# checksum pins the bytes, and a changed "latest" fails the build loudly instead of shipping a different model.
SEG_URL="https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_segmenter_landscape/float16/latest/selfie_segmenter_landscape.tflite https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_segmenter_landscape/float16/1/selfie_segmenter_landscape.tflite"
SEG_SHA=490e9ea734313e0de10fa0cd9e3c6133e36ea4db2b7a49bde9ef019f72796b8e

sha() { sha256sum "$1" | cut -d' ' -f1; }

fetch() {  # "url [mirror ...]" sha256 dest
  if [ -f "$3" ] && [ "$(sha "$3")" = "$2" ]; then return 0; fi
  local url got
  for url in $1; do
    for attempt in 1 2 3; do
      if curl -fsSL --retry 3 --retry-all-errors -o "$3.part" "$url"; then
        got=$(sha "$3.part")
        if [ "$got" = "$2" ]; then mv "$3.part" "$3"; return 0; fi
        echo "checksum mismatch for $url: $got (expected $2)" >&2
        rm -f "$3.part"
        break
      fi
      sleep $((attempt * 2))
    done
  done
  echo "could not fetch $3 with sha256 $2" >&2
  exit 1
}

fetch "$SEG_URL" "$SEG_SHA" "$CACHE/$SEG"

if [ ! -f "$CACHE/$DEPTH" ] || [ ! -f "$CACHE/$DEPTH.parity-ok" ]; then
  fetch "$CKPT_URL" "$CKPT_SHA" "$CACHE/model.safetensors"
  PY="${PYTHON:-python3}"
  if [ ! -x "$CACHE/venv/bin/python" ]; then "$PY" -m venv "$CACHE/venv"; fi
  "$CACHE/venv/bin/python" -m pip install -q --disable-pip-version-check -r "$ROOT/tools/models/requirements.txt"
  TF_CPP_MIN_LOG_LEVEL=2 "$CACHE/venv/bin/python" "$ROOT/tools/models/export_depth_anything_v2_small.py" \
    --weights "$CACHE/model.safetensors" --out "$CACHE/$DEPTH" --fp16 \
    --reference-image "$OUT/selfcheck_420x280.png" --reference-depth "$ROOT/tools/models/selfcheck_reference_depth.npy"
  touch "$CACHE/$DEPTH.parity-ok"
  # keep the cache small: only the two model files are needed later
  rm -rf "$CACHE/model.safetensors" "$CACHE/venv"
fi

cp "$CACHE/$DEPTH" "$CACHE/$SEG" "$OUT/"
echo "models in $OUT:"
for f in "$OUT/$DEPTH" "$OUT/$SEG"; do echo "  $(basename "$f") $(stat -c %s "$f") bytes sha256 $(sha "$f")"; done
