#!/usr/bin/env python3
"""B30: Depth Anything V2 Small -> LiteRT (.tflite) at Redline's input size, from the official checkpoint.

Why not the litert-community export: it is fixed at 518x686 (1813 patches) and measures ~3.5x slower than this one, which
keeps the 3:2 frame at 280x420 (20x30 = 600 patches). The network is rebuilt here with plain TensorFlow ops (DINOv2-S/14
backbone + DPT head, as in transformers' DepthAnythingForDepthEstimation) and loaded from the checkpoint's safetensors,
so the build needs only numpy + safetensors + TensorFlow (no torch). Input normalisation is inside the graph.

    input  "image"  float32 [1, 280, 420, 3]  RGB in 0..1 (sRGB-encoded, as the processor expects)
    output "depth"  float32 [1, 280, 420, 1]  relative inverse depth (larger = nearer), ReLU'd, not metric

Only the Small model is Apache-2.0; Base/Large are CC-BY-NC-4.0 and must not be used.

Usage: export_depth_anything_v2_small.py --weights model.safetensors --out depth.tflite [--height 280 --width 420]
       [--fp16] [--reference-image scene.png --reference-depth depth.npy]
       (reference: the torch model's output for that image, saved by tools/models/make_reference.py; the export fails when
        the converted model does not reproduce it)
"""
import argparse
import hashlib
import math
import sys

import numpy as np

CHECKPOINT = "depth-anything/Depth-Anything-V2-Small-hf"
REVISION = "5426e4f0f36572d16453bbda7a8389317b1bef99"
SAFETENSORS_SHA256 = "3152477ce0d8d6978d76b995120de97cb5b928701fd0f817769f59e249a16b70"
MEAN = (0.485, 0.456, 0.406)
STD = (0.229, 0.224, 0.225)
PATCH = 14
DIM = 384
HEADS = 6
LAYERS = 12
OUT_INDICES = (3, 6, 9, 12)  # hidden states after these blocks (1-based), then the final LayerNorm
NECK = (48, 96, 192, 384)
FUSION = 64
HEAD_HIDDEN = 32
LN_EPS = 1e-6


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def cubic_weights(t, a=-0.75):
    """torch upsample_bicubic2d coefficients for the 4 taps around a fractional offset t."""
    def c1(x):  # |x| <= 1
        return ((a + 2) * x - (a + 3)) * x * x + 1

    def c2(x):  # 1 < |x| < 2
        return ((a * x - 5 * a) * x + 8 * a) * x - 4 * a

    return np.array([c2(t + 1), c1(t), c1(1 - t), c2(2 - t)])


def bicubic_resize(img, out_h, out_w):
    """img [C, H, W] -> [C, out_h, out_w]; matches torch.nn.functional.interpolate(mode='bicubic', align_corners=False)."""
    c, h, w = img.shape

    def axis_matrix(n_in, n_out):
        m = np.zeros((n_out, n_in))
        scale = n_in / n_out
        for o in range(n_out):
            src = (o + 0.5) * scale - 0.5
            i0 = math.floor(src)
            t = src - i0
            wts = cubic_weights(t)
            for k in range(4):
                idx = min(max(i0 - 1 + k, 0), n_in - 1)
                m[o, idx] += wts[k]
        return m

    my = axis_matrix(h, out_h)
    mx = axis_matrix(w, out_w)
    return np.einsum("oh,chw,pw->cop", my, img.astype(np.float64), mx).astype(np.float32)


def position_embeddings(pos, gh, gw):
    """[1, 1 + 37*37, D] -> [1, 1 + gh*gw, D] (DINOv2 interpolate_pos_encoding)."""
    cls = pos[:, :1]
    patch = pos[0, 1:]
    n = int(round(math.sqrt(patch.shape[0])))
    grid = patch.reshape(n, n, -1).transpose(2, 0, 1)  # [D, n, n]
    res = bicubic_resize(grid, gh, gw).transpose(1, 2, 0).reshape(1, gh * gw, -1)
    return np.concatenate([cls, res], axis=1).astype(np.float32)


def build(weights, height, width):
    import tensorflow as tf

    gh, gw = height // PATCH, width // PATCH
    assert gh * PATCH == height and gw * PATCH == width, "size must be a multiple of 14"
    W = weights

    def const(name):
        return tf.constant(W[name])

    def conv_w(name):  # torch [out, in, kh, kw] -> TF [kh, kw, in, out]
        return tf.constant(np.transpose(W[name], (2, 3, 1, 0)))

    def conv(x, name, stride=1, pad=0, bias=True):
        if pad:
            x = tf.pad(x, [[0, 0], [pad, pad], [pad, pad], [0, 0]])
        y = tf.nn.conv2d(x, conv_w(name + ".weight"), strides=stride, padding="VALID")
        return y + const(name + ".bias") if bias else y

    def conv_transpose(x, name, factor):  # torch ConvTranspose2d(k=s=factor, p=0); weight [in, out, kh, kw]
        wt = np.transpose(W[name + ".weight"], (2, 3, 1, 0))  # [kh, kw, out, in]
        n, h, w_, _ = x.shape
        out_c = wt.shape[2]
        y = tf.nn.conv2d_transpose(x, tf.constant(wt), [n, h * factor, w_ * factor, out_c], strides=factor, padding="VALID")
        return y + const(name + ".bias")

    def dense(x, name):
        return tf.matmul(x, tf.constant(W[name + ".weight"].T)) + const(name + ".bias")

    def layer_norm(x, name):
        mean = tf.reduce_mean(x, axis=-1, keepdims=True)
        d = x - mean
        var = tf.reduce_mean(d * d, axis=-1, keepdims=True)
        return d * tf.math.rsqrt(var + LN_EPS) * const(name + ".weight") + const(name + ".bias")

    def gelu(x):  # tanh form: MUL/ADD/TANH only (every LiteRT GPU backend has them); parity checked against exact GELU
        return 0.5 * x * (1.0 + tf.tanh(0.7978845608028654 * (x + 0.044715 * x * x * x)))

    def resize_ac(x, h, w):  # bilinear, align_corners=True
        return tf.compat.v1.image.resize_bilinear(x, [h, w], align_corners=True, half_pixel_centers=False)

    pos = tf.constant(position_embeddings(W["backbone.embeddings.position_embeddings"], gh, gw))
    cls = const("backbone.embeddings.cls_token")
    n_tok = 1 + gh * gw
    hd = DIM // HEADS

    @tf.function(input_signature=[tf.TensorSpec([1, height, width, 3], tf.float32, name="image")])
    def model(image):
        x = (image - tf.constant(MEAN, tf.float32)) / tf.constant(STD, tf.float32)
        x = conv(x, "backbone.embeddings.patch_embeddings.projection", stride=PATCH)  # [1, gh, gw, D]
        x = tf.reshape(x, [1, gh * gw, DIM])
        x = tf.concat([cls, x], axis=1) + pos  # [1, n_tok, D]
        feats = []
        for i in range(LAYERS):
            p = f"backbone.encoder.layer.{i}."
            h = layer_norm(x, p + "norm1")
            q = tf.transpose(tf.reshape(dense(h, p + "attention.attention.query"), [n_tok, HEADS, hd]), [1, 0, 2])
            k = tf.transpose(tf.reshape(dense(h, p + "attention.attention.key"), [n_tok, HEADS, hd]), [1, 2, 0])
            v = tf.transpose(tf.reshape(dense(h, p + "attention.attention.value"), [n_tok, HEADS, hd]), [1, 0, 2])
            a = tf.nn.softmax(tf.matmul(q * (1.0 / math.sqrt(hd)), k), axis=-1)  # [H, n, n]
            o = tf.reshape(tf.transpose(tf.matmul(a, v), [1, 0, 2]), [1, n_tok, DIM])
            x = x + dense(o, p + "attention.output.dense") * const(p + "layer_scale1.lambda1")
            h = layer_norm(x, p + "norm2")
            h = dense(gelu(dense(h, p + "mlp.fc1")), p + "mlp.fc2")
            x = x + h * const(p + "layer_scale2.lambda1")
            if (i + 1) in OUT_INDICES:
                feats.append(layer_norm(x, "backbone.layernorm"))
        # neck: reassemble
        maps = []
        factors = (4, 2, 1, 0.5)
        for i, f in enumerate(feats):
            t = tf.reshape(f[:, 1:, :], [1, gh, gw, DIM])
            p = f"neck.reassemble_stage.layers.{i}."
            t = conv(t, p + "projection")
            if factors[i] > 1:
                t = conv_transpose(t, p + "resize", int(factors[i]))
            elif factors[i] < 1:
                t = conv(t, p + "resize", stride=2, pad=1)
            maps.append(conv(t, f"neck.convs.{i}", pad=1, bias=False))

        def residual(t, name):
            r = t
            t = conv(tf.nn.relu(t), name + ".convolution1", pad=1)
            t = conv(tf.nn.relu(t), name + ".convolution2", pad=1)
            return t + r

        maps = maps[::-1]
        fused = None
        for idx, t in enumerate(maps):
            p = f"neck.fusion_stage.layers.{idx}."
            if fused is None:
                h = t
            else:
                h = fused + residual(t, p + "residual_layer1")
            h = residual(h, p + "residual_layer2")
            if idx != len(maps) - 1:
                size = maps[idx + 1].shape[1:3]
            else:
                size = (h.shape[1] * 2, h.shape[2] * 2)
            h = resize_ac(h, int(size[0]), int(size[1]))
            fused = conv(h, p + "projection")
        d = conv(fused, "head.conv1", pad=1)
        d = resize_ac(d, height, width)
        d = tf.nn.relu(conv(d, "head.conv2", pad=1))
        d = tf.nn.relu(conv(d, "head.conv3"))
        return {"depth": tf.identity(d, name="depth")}

    return model


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--weights", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--height", type=int, default=280)
    ap.add_argument("--width", type=int, default=420)
    ap.add_argument("--fp16", action="store_true", help="store weights as float16 (half the size; GPU computes in fp16 anyway)")
    ap.add_argument("--reference-image", help="RGB PNG at the model size")
    ap.add_argument("--reference-depth", help="npy [H, W]: the torch model's depth for --reference-image")
    ap.add_argument("--skip-checksum", action="store_true")
    a = ap.parse_args()
    if not a.skip_checksum:
        got = sha256(a.weights)
        if got != SAFETENSORS_SHA256:
            sys.exit(f"checkpoint sha256 {got} != {SAFETENSORS_SHA256} ({CHECKPOINT}@{REVISION})")
    from safetensors.numpy import load_file
    import tensorflow as tf

    weights = load_file(a.weights)
    model = build(weights, a.height, a.width)
    conc = model.get_concrete_function()
    conv = tf.lite.TFLiteConverter.from_concrete_functions([conc], model)
    conv.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    if a.fp16:
        conv.optimizations = [tf.lite.Optimize.DEFAULT]
        conv.target_spec.supported_types = [tf.float16]
    blob = conv.convert()
    with open(a.out, "wb") as f:
        f.write(blob)
    print(f"wrote {a.out}: {len(blob)} bytes, sha256 {hashlib.sha256(blob).hexdigest()}")
    if a.reference_image and a.reference_depth:
        from PIL import Image

        image = np.asarray(Image.open(a.reference_image).convert("RGB")).astype(np.float32)[None] / 255.0
        ref = np.load(a.reference_depth).astype(np.float32)
        it = tf.lite.Interpreter(model_content=blob, num_threads=2)
        it.allocate_tensors()
        it.set_tensor(it.get_input_details()[0]["index"], image)
        it.invoke()
        out = it.get_tensor(it.get_output_details()[0]["index"])[0, :, :, 0]
        rel = np.abs(out - ref).max() / max(1e-6, np.abs(ref).max())
        corr = np.corrcoef(out.ravel(), ref.ravel())[0, 1]
        print(f"parity vs torch reference: max |diff| / max {rel:.5f}, correlation {corr:.6f}")
        if rel > 0.02 or corr < 0.999:
            sys.exit("parity check failed")

if __name__ == "__main__":
    main()
