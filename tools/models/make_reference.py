#!/usr/bin/env python3
"""B30: the torch reference for the export parity check (run once, by hand; the result is committed).

Runs depth-anything/Depth-Anything-V2-Small-hf at the pinned revision on the self-check scene (420x280, ImageNet
normalisation, no resize) and writes tools/models/selfcheck_reference_depth.npy (float16 [280, 420]) and the 4x4 block
means the app's self-check compares against, app/src/main/assets/depth/selfcheck_expected_105x70.f32 (little-endian float32).
Needs torch + transformers (not needed by the build).
"""
import numpy as np
import torch
from PIL import Image
from transformers import AutoModelForDepthEstimation

REVISION = "5426e4f0f36572d16453bbda7a8389317b1bef99"

m = AutoModelForDepthEstimation.from_pretrained("depth-anything/Depth-Anything-V2-Small-hf", revision=REVISION).eval()
x = np.asarray(Image.open("app/src/main/assets/depth/selfcheck_420x280.png").convert("RGB")).astype(np.float32) / 255.0
mean = np.array([0.485, 0.456, 0.406], np.float32)
std = np.array([0.229, 0.224, 0.225], np.float32)
t = torch.from_numpy(((x - mean) / std).transpose(2, 0, 1)[None])
with torch.no_grad():
    d = m(pixel_values=t).predicted_depth[0].numpy()
np.save("tools/models/selfcheck_reference_depth.npy", d.astype(np.float16))
d.reshape(70, 4, 105, 4).mean(axis=(1, 3)).astype("<f4").tofile("app/src/main/assets/depth/selfcheck_expected_105x70.f32")
print("depth range", float(d.min()), float(d.max()))
