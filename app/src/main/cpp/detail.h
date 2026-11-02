// Redline native module (B24): the detail post-pass on the final 8-bit still (after the 50/70 upscale).
// Pure C++17 (no Android or JNI), tested on the CI host (app/src/test/cpp) against the Python reference,
// calibration/redline_cal/detail.py, whose docstring is the contract:
//   1. distortion (optional, "RAW feel"): lensfun ptlens forward model, each output pixel inverse-mapped, bilinear;
//   2. sharpen: luma unsharp mask (separable Gaussian, clamped edges) with soft coring d * d^2 / (d^2 + t^2);
//   3. grain: noise estimate per luma bin (Immerkaer, flattest decile of 16 px blocks), then luma noise of
//      sigma_add(Y) = sqrt(max(0, target^2 - measured^2)): counter-hash normal deviates filtered by [w, 1 - 2w, w]
//      in x and y (the camera JPEG's grain correlation) and divided by kappa.
// The vignette is not here: it is applied in linear light inside the RAW render (raw_pipeline.h, RenderSpec).
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace redline {

constexpr int kNoiseBins = 7;

struct DetailSpec {
    // 1. distortion
    bool distort = false;
    float distA = 0.0f, distB = 0.0f, distC = 0.0f;
    // 2. sharpening (sigma in output pixels; threshold in encoded 0..1 units); amount 0 = off
    float sharpenSigma = 1.0f;
    float sharpenAmount = 0.0f;
    float sharpenThreshold = 0.0f;
    // 3. grain: target sigma per luma bin (encoded 0..1 units); grain off when grain == false
    bool grain = false;
    float grainTarget[kNoiseBins] = {0, 0, 0, 0, 0, 0, 0};
    float grainKernelW = 0.0f;  // [w, 1 - 2w, w] in x and y on the unit hash noise
    float grainKappa = 1.0f;    // Immerkaer sigma of that filtered unit field
    std::uint32_t seed = 0;
    int threads = 1;
};

struct DetailStats {
    float measured[kNoiseBins] = {-1, -1, -1, -1, -1, -1, -1};  // -1 = too few blocks in the bin
    float added[kNoiseBins] = {0, 0, 0, 0, 0, 0, 0};
    float distortScale = 1.0f;
    double distortMs = 0.0, sharpenMs = 0.0, noiseMs = 0.0, grainMs = 0.0, totalMs = 0.0;
};

// In place on RGBA8 (alpha kept). Returns an empty string on success, else a short error (the image is untouched
// only when the error comes from validation).
std::string applyDetail(std::uint8_t* rgba, int width, int height, std::size_t strideBytes, const DetailSpec& spec,
                        DetailStats& stats);

// The same on float RGB (encoded 0..1, row stride in floats; the host parity tool). Rounding: none.
std::string applyDetailFloat(float* rgb, int width, int height, std::size_t strideFloats, const DetailSpec& spec,
                             DetailStats& stats);

// The encoded luma bin edges of the noise estimate (kNoiseBins + 1 values) and the bin centres.
extern const float kNoiseBinEdges[kNoiseBins + 1];

// The deterministic normal deviate of the grain at (x, y) (Irwin-Hall of four hash bytes, unit variance).
float grainNormal(std::uint32_t x, std::uint32_t y, std::uint32_t seed);

}  // namespace redline
