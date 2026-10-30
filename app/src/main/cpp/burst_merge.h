// Redline native module (B22): RAW burst tile alignment + robust merge in the Bayer domain.
// Pure C++17 (no Android or JNI), so the same source is tested on the CI host (app/src/test/cpp) and checked
// against the Python reference, calibration/redline_cal/burst_merge.py, whose docstring is the contract:
// grey quads -> pyramid (x2, x4, x4) -> reference = sharpest of the first 3 -> coarse-to-fine tile alignment
// (L2 on coarse levels, L1 on the finest, whole-quad displacements) -> per-tile noise-model weights and per-quad
// outlier rejection -> raised-cosine blended merge of the CFA samples. Frames are processed one at a time, so only
// one alternate frame's pyramid is alive at once.
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace redline {

struct BurstFrame {
    const std::uint16_t* data = nullptr;
    int rowStride = 0;  // in uint16 elements
};

struct BurstParams {
    int width = 0;   // RAW samples, even
    int height = 0;  // even
    float black[4] = {0, 0, 0, 0};
    float white = 1.0f;
    double noiseS = 0.0;  // variance = S * x + O at normalised signal x
    double noiseO = 0.0;
    int threads = 1;
    int reference = -1;  // -1: the sharpest of the first 3 frames
    bool keepDisplacements = false;
};

struct BurstFrameStats {
    int index = 0;
    double meanDispPx = 0;  // RAW pixels (2 per grey pixel), mean over L0 tiles of max(|dx|, |dy|)
    double maxDispPx = 0;
    double meanWeight = 0;        // mean tile weight
    double rejectedFraction = 0;  // tiles with weight < 0.5
    double alignMs = 0;
    double mergeMs = 0;
};

struct BurstResult {
    int reference = 0;
    int tilesX = 0;
    int tilesY = 0;
    double pyramidMs = 0;  // all frames' grey + pyramid builds
    double referenceMs = 0;
    double alignMs = 0;
    double mergeMs = 0;
    double finishMs = 0;
    std::vector<BurstFrameStats> frames;      // one per alternate frame, in frame order
    std::vector<std::vector<int>> displacements;  // per frame (empty for the reference): tilesY*tilesX*(dy, dx)
};

// Merges the frames into `out` as float RAW values (row stride in floats). Returns "" or an error.
std::string mergeBurstFloat(const std::vector<BurstFrame>& frames, const BurstParams& p, float* out, std::size_t strideFloats,
                            BurstResult& result);

// Same merge written as uint16 RAW scaled by `scale` (so black and white levels scale too), rounded and clamped to
// [0, white * scale]. The app uses scale 16 to keep 4 extra bits of the merged precision in a RAW_SENSOR layout.
std::string mergeBurstU16(const std::vector<BurstFrame>& frames, const BurstParams& p, std::uint16_t* out, std::size_t strideElems,
                          float scale, BurstResult& result);

// Synthetic self-check (no inputs): a 512x384 RGGB burst of 6 frames with known whole-quad shifts and
// Poisson-Gaussian noise. Fills the fraction of interior tiles aligned exactly, the noise reduction (single-frame
// RMS error over merged RMS error, against the clean frame) and the time. Returns "" or an error.
struct BurstSelfCheck {
    double alignedFraction = 0;
    double noiseRatio = 0;
    double ms = 0;
};
std::string burstSelfCheck(int threads, BurstSelfCheck& out);

}  // namespace redline
