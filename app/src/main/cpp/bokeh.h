// Redline native module (B31): simulated depth of field on the rendered still. Pure C++17 (no Android or JNI), tested on
// the CI host (app/src/test/cpp/bokeh_test.cpp) and checked against the Python reference,
// calibration/redline_cal/bokeh.py, whose docstring is the contract:
//   gate (depth confidence >= 0.35, the phone's own depth of field shallower than the chosen aperture) ->
//   thin-lens CoC on a 36 mm full-frame sensor from the affine inverse depth anchored at the focus point and the
//   background percentile, minus the phone's own blur in quadrature, capped -> person matte + guided filter on a working
//   grid -> tone curve inverted (plus the B25 HDR continuation for highlights) -> layered gather (9 rounded blades, faint
//   rim, cat's-eye toward the corners wide open, 70 mm f/2.8 longitudinal CA) at reduced resolution for large radii ->
//   back-to-front composite -> tone curve re-applied.
// Memory: levels down-sampled 4x and more are held whole (1/16 of the frame and less); full and half resolution are
// processed in row bands with their halos, so no full-size float copy of the still exists.
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace redline {

constexpr float kBokehUsable = 0.35f;

/** The tone curve the still was rendered with (RenderSpec's table: log2 gained linear input curveLo..curveHi). */
struct BokehTone {
    const float* curve = nullptr;
    int curvePoints = 0;
    float curveLo = -14.0f;
    float curveHi = 0.0f;
    // Optional B25 HDR continuation: slope p > 0 and a map of the mean log2 HDR gain (any resolution, bilinear).
    float kneeEv = -1.5f;
    float slope = 0.0f;
    const float* gainLog2 = nullptr;
    int gainW = 0, gainH = 0;
};

/** The B30 maps at their own resolution (still orientation): inverse depth 0..1 (1 near), person mask (nullable). */
struct BokehDepth {
    const float* depth = nullptr;
    const float* mask = nullptr;
    const float* confMap = nullptr;  // accepted, not used yet (the scalar gates)
    int width = 0, height = 0;
    float confidence = 0.0f;
};

struct BokehParams {
    float focalMm = 50.0f;
    float aperture = 2.8f;
    float focusM = 2.0f;           // focus distance from AF or tap, metres
    float focusX = 0.5f, focusY = 0.5f;  // focus point, 0..1 of the still
    float phoneN = 6.0f;           // the phone's full-frame-equivalent f-number for the crop being rendered
    float farM = 0.0f;             // distance of the background percentile; <= 0 or <= focusM: infinity
    float maxCoc = 0.08f;          // cap on the disc diameter, fraction of the width
    int workMax = 1024;            // long side of the matte/guided-filter grid
    int threads = 1;
    bool refine = true;            // guided-filter edge refinement (off only for tests and diagnostics)
    int band = 64;                 // row band height of the full/half resolution levels (memory vs halo overhead)
};

struct BokehStats {
    bool applied = false;
    std::string reason;            // why not applied
    float rFocus = 0, rBg = 0, scale = 0, capRadius = 0, maxRadius = 0, phoneFactor = 0, beta = 0;
    int kmax = 0, workW = 0, workH = 0, gfRadius = 0, layers = 0;
    bool ca = false, onPerson = false;
    double mapMs = 0, levelsMs = 0, bandsMs = 0, totalMs = 0, peakBytes = 0;
    double fillMs = 0, gatherMs = 0, compositeMs = 0;  // summed over levels and bands
};

/** In place on RGBA8 (alpha kept). Empty string = success (applied or gated; see stats), else an error. */
std::string bokehRender(std::uint8_t* rgba, int width, int height, std::size_t strideBytes, const BokehTone& tone, const BokehDepth& depth,
                        const BokehParams& p, BokehStats& stats);

/** The same on float encoded RGB 0..1 (row stride in floats; the host parity tool). No rounding. */
std::string bokehRenderFloat(float* rgb, int width, int height, std::size_t strideFloats, const BokehTone& tone, const BokehDepth& depth,
                             const BokehParams& p, BokehStats& stats);

// ---- the model, exposed for the host tests
/** Thin-lens blur disc diameter in mm on the full-frame sensor (dM <= 0: infinity). */
double bokehCocMm(double focalMm, double n, double focusM, double dM);
/** Share of the full-frame disc added after the phone's own blur: sqrt(max(0, 1 - (n / phoneN)^2)). */
double bokehPhoneFactor(double n, double phoneN);
double bokehBladeBeta(double n);
/** Cat's-eye offset h (level px) and radial unit vector for a kernel of radius r at normalised frame position (vx, vy). */
void bokehCatEye(double r, double focalMm, double n, double vx, double vy, double& h, double& ux, double& uy);
/** Dense kernel ((2K+1)^2, row-major, sums to 1) at radius r. */
void bokehKernelDense(double r, double beta, double h, double ux, double uy, std::vector<double>& k, int& kHalf);
/** The signed radius map (output px) on the working grid (radius_map in the reference). */
std::string bokehRadiusMap(const float* rgb, int width, int height, std::size_t strideFloats, const BokehDepth& depth, const BokehParams& p,
                           std::vector<float>& map, int& mapW, int& mapH, BokehStats& stats);

}  // namespace redline
