// Redline native module (B22, B22a): RAW burst tile alignment + robust merge in the Bayer domain.
// Pure C++17 (no Android or JNI), so the same source is tested on the CI host (app/src/test/cpp) and checked
// against the Python reference, calibration/redline_cal/burst_merge.py, whose docstring is the contract:
// grey quads -> pyramid (x2, x4, x4) -> reference = sharpest of the first 3 -> coarse-to-fine tile alignment
// (L2 on coarse levels, L1 on the finest, whole-quad displacements) -> per-tile noise-model weights and per-quad
// outlier rejection -> raised-cosine blended merge of the CFA samples.
//
// B22a: the merge streams. BurstMerger takes the frames one at a time as the camera delivers them: the first few
// are held until the reference is known (the sharpest of the first 3), then every other frame is aligned and
// accumulated as it arrives and handed back at once, so only the reference, the accumulator and the frame in flight
// stay resident. The pixel maths is unchanged (integer alignment identical to the reference; the merge within float
// rounding). Work runs on a persistent pool whose threads take small chunks dynamically, so slower cores of a
// big.LITTLE CPU never hold up a stage, at a lowered priority so the preview is not starved.
#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
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
    int framesAdded = 0;
    int tilesX = 0;
    int tilesY = 0;
    double pyramidMs = 0;    // grey + pyramid builds of the frames after the reference candidates
    double referenceMs = 0;  // the candidates' pyramids and the sharpness pick
    double alignMs = 0;
    double mergeMs = 0;
    double finishMs = 0;
    int threads = 0;            // worker threads used (including the caller)
    int maxHeldFrames = 0;      // frames the merger held at once (the candidates while the reference was undecided)
    double peakNativeMb = 0;    // the merger's own peak working set (accumulator + pyramids), not counting frames
    std::vector<BurstFrameStats> frames;          // one per alternate frame, in merge order
    std::vector<std::vector<int>> displacements;  // per frame index (empty for the reference): tilesY*tilesX*(dy, dx)
};

// Streaming merge. Frames are added in capture order (index 0, 1, 2, ...). A frame's data must stay valid until its
// index comes back in `released` (from add or finish); after that the caller may reuse the buffer. The reference
// frame is released by finish. Not thread-safe: one caller thread per merger (the work itself is parallel inside).
class BurstMerger {
public:
    BurstMerger();
    ~BurstMerger();
    BurstMerger(const BurstMerger&) = delete;
    BurstMerger& operator=(const BurstMerger&) = delete;

    std::string begin(const BurstParams& p);
    std::string add(const BurstFrame& frame, std::vector<int>& released);
    // Writes the merge as float RAW values (row stride in floats).
    std::string finishFloat(float* out, std::size_t strideFloats, BurstResult& result, std::vector<int>& released);
    // Writes the merge as uint16 RAW scaled by `scale` (so black and white scale too), rounded and clamped to
    // [0, white * scale]. `out` may be the reference frame's own buffer (same row stride): it is written in place;
    // `out` == nullptr writes in place into the reference, whichever frame that turns out to be.
    std::string finishU16(std::uint16_t* out, std::size_t strideElems, float scale, BurstResult& result, std::vector<int>& released);
    int reference() const;  // -1 until known
    int added() const;
    int held() const;  // frames currently held (candidates or the reference), not yet released

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

// One-shot merges over all frames (a BurstMerger fed in order): the host tool, the tests and the self-check.
std::string mergeBurstFloat(const std::vector<BurstFrame>& frames, const BurstParams& p, float* out, std::size_t strideFloats,
                            BurstResult& result);
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

// Worker threads to use: the CPU's performance cores (max frequency within 70% of the fastest core; on a
// big.LITTLE phone that is the prime + big cores, not the little ones), at least 2, at most 8; half the cores when
// the frequencies cannot be read.
int burstRecommendedThreads();

// Nice value of the pool's worker threads (0 = default; the app uses a mildly lowered priority so preview rendering
// wins the CPU). Applied to existing and new workers before their next job.
void burstSetWorkerNice(int nice);

}  // namespace redline
