// Redline native module (B23): handheld multi-frame super-resolution at the 50 and 70 mm stops.
// Pure C++17 (no Android or JNI), tested on the CI host (app/src/test/cpp) and checked against the Python reference,
// calibration/redline_cal/superres.py, whose docstring is the contract:
// region (crop + margin) -> reference = sharpest of the first 3 -> B22 integer tile alignment -> Lucas-Kanade
// sub-pixel refinement -> per-frame affine motion model (tiles that disagree take it) -> per-quad robustness ->
// kernel gather of every frame's Bayer samples straight onto the output grid (no demosaic) -> normalised camera RGB.
//
// Streaming like the B22a merge: each frame's region is copied on add and the frame handed back at once, so the
// camera's buffers are never held; alignment and robustness run per frame as it arrives (once the reference is
// known), the gather runs at finish over all frames. Work runs on the B22a pool.
#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "burst_merge.h"
#include "raw_pipeline.h"

namespace redline {

struct SuperResParams {
    int width = 0;   // RAW frame, even
    int height = 0;  // even
    int cfa = 0;     // Camera2 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR
    float black[4] = {0, 0, 0, 0};
    float white = 1.0f;
    double noiseS = 0.0;  // variance = S x + O at normalised signal x
    double noiseO = 0.0;
    // Optional lens shading map, Camera2 layout rows x cols x 4, spanning the frame (copied by begin; or set later with
    // setShading, since only the gather at finish needs it).
    const float* shading = nullptr;
    int shadingRows = 0;
    int shadingCols = 0;
    CropRect crop;  // the stop's 3:2 crop (left/top/width/height even)
    int outWidth = 0;
    int outHeight = 0;
    int threads = 1;
    int reference = -1;  // -1: the sharpest of the first 3 frames
    bool keepFlows = false;
};

struct SuperResFrameStats {
    int index = 0;
    double meanDispPx = 0;      // RAW px, mean over tiles of max(|dy|, |dx|) of the integer displacement
    double meanSubpixPx = 0;    // RAW px, mean |flow - 2 d| (both axes)
    double meanRobustness = 0;  // mean quad robustness (1 = fully used)
    double rejectedFraction = 0;  // quads with robustness < 0.5
    double modelResolved = 0;   // tiles that took the frame's motion model
    double motionPx[2] = {0, 0};  // the model's flow at the region centre (dy, dx) RAW px; 0 without a model
    bool model = false;
    double alignMs = 0;   // pyramid + integer alignment + LK + model
    double robustMs = 0;
};

struct SuperResResult {
    int reference = 0;
    int framesAdded = 0;
    int region[4] = {0, 0, 0, 0};  // left, top, width, height
    int tilesX = 0;
    int tilesY = 0;
    double coverage = 0;  // 0 (tripod / one frame) .. 1
    double sigmaG = 0;
    double sigmaRB = 0;
    double copyMs = 0;
    double referenceMs = 0;
    double alignMs = 0;
    double robustMs = 0;
    double gatherMs = 0;
    int threads = 0;
    int maxRegionsHeld = 0;
    double peakNativeMb = 0;  // the merger's own peak (region copies, pyramids, maps), not the output
    std::vector<SuperResFrameStats> frames;  // one per alternate frame, in frame order
    std::vector<std::vector<int>> displacements;  // keepFlows: per frame index, tilesY*tilesX*(dy, dx) grey px
    std::vector<std::vector<float>> flows;        // keepFlows: per frame index, tilesY*tilesX*(fy, fx) RAW px
};

// Planar float camera RGB on the output grid: plane c at data + c * planeStride, rows rowStride floats apart.
struct LinearImage {
    const float* data = nullptr;
    int width = 0;
    int height = 0;
    std::size_t rowStride = 0;
    std::size_t planeStride = 0;
};

class SuperResMerger {
public:
    SuperResMerger();
    ~SuperResMerger();
    SuperResMerger(const SuperResMerger&) = delete;
    SuperResMerger& operator=(const SuperResMerger&) = delete;

    std::string begin(const SuperResParams& p);
    // Copies the frame's region; the frame's buffer is free again when add returns (its index is in `released`).
    std::string add(const BurstFrame& frame, std::vector<int>& released);
    // Sets (or replaces) the lens shading map before finish; only the gather uses it (copied; null clears it).
    std::string setShading(const float* map, int rows, int cols);
    // Gathers all frames into `out` (3 planes of outHeight rows of outWidth floats; see LinearImage).
    std::string finish(float* out, std::size_t rowStride, std::size_t planeStride, SuperResResult& result);
    int added() const;
    int reference() const;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

// One-shot merge (a merger fed in order): the host tool, the tests and the self-check.
std::string superResMerge(const std::vector<BurstFrame>& frames, const SuperResParams& p, float* out, std::size_t rowStride,
                          std::size_t planeStride, SuperResResult& result);

// The region (crop + margin, clamped, even) of a crop in a width x height frame: left, top, width, height.
void superResRegion(const CropRect& crop, int width, int height, int out[4]);

// Synthetic self-check (no inputs): a textured 256 x 192 crop with a grating blended in, 8 frames with known
// real-valued handheld shifts and noise, upscaled 2x. Fills the median sub-pixel flow error (RAW px), the coverage,
// the error against the true scene of the merge and of the reference frame's own kernel reconstruction (the gain
// is their ratio, > 1 when the burst added detail) and the time.
struct SuperResSelfCheck {
    double flowErrorPx = 0;
    double coverage = 0;
    double rmseMerge = 0;
    double rmseSingle = 0;
    double gain = 0;
    double ms = 0;
    bool passed() const { return gain >= 1.15 && flowErrorPx <= 0.15 && coverage >= 0.4; }
};
std::string superResSelfCheck(int threads, SuperResSelfCheck& out);

}  // namespace redline
