// Redline native module (B21): RAW colour pipeline, the per-pixel part.
// Pure C++17 (no Android or JNI), so the same source is tested on the CI host (app/src/test/cpp) and checked
// against the Python reference, calibration/redline_cal/raw_pipeline.py, whose docstring is the contract:
// normalise (black/white, lens shading map) -> Malvar-He-Cutler demosaic (mirror borders) -> crop ->
// white balance + clip -> 3x3 to linear sRGB -> area downscale -> tone curve with highlight shoulder -> 3D LUT.
// B23: the same stages 4-8 on linear camera RGB that the super-resolution merge produced at the output size.
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace redline {

struct RawInput {
    const std::uint16_t* data = nullptr;
    int width = 0;
    int height = 0;
    int rowStride = 0;  // in uint16 elements
    int cfa = 0;        // Camera2 SENSOR_INFO_COLOR_FILTER_ARRANGEMENT 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR
    float black[4] = {0, 0, 0, 0};  // per CFA position (y & 1) * 2 + (x & 1)
    float white = 1.0f;
    // Optional lens shading map, Camera2 layout: rows x cols x 4 (R, G_even, G_odd, B), spanning the frame.
    const float* shading = nullptr;
    int shadingRows = 0;
    int shadingCols = 0;
};

struct CropRect {
    int left = 0;
    int top = 0;
    int width = 0;
    int height = 0;
};

struct ColourSpec {
    float matrix[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};  // row-major, white-balanced camera RGB -> linear sRGB
    float neutral[3] = {1, 1, 1};                    // camera neutral, max = 1
};

// B25: Ultra HDR gain map accumulation during the render. The HDR rendition continues the tone curve past the
// highlight knee (curve input x = gain * vignette * luma(linear sRGB), knee x_k = 2^kneeEv) with the curve's own
// log-log slope p just below the knee: L_hdr(x) = L_k * (x / x_k)^p, L = sRGB-decoded curve output; below the knee the
// two renditions are equal (gain 1). Per 4x4 cell of output pixels the sum of log2(L_hdr / L_sdr(x)) is accumulated
// (L_sdr(x) = decoded C(x), clamped at >= 0), so the map is the mean log2 gain of the cell. The SDR pixels are not touched:
// the render writes exactly the same output with or without a gain map (raw_pipeline_test checks bit for bit).
struct GainMapAccum {
    float kneeEv = -1.5f;
    // Output: cellsW x cellsH sums (cellsW = ceil(outWidth / 4), cellsH = ceil(outHeight / 4)); the render zeroes them.
    float* sums = nullptr;
    int cellsW = 0;
    int cellsH = 0;
    // Output: the slope p the render measured on the curve (log2 of decoded output per EV, 0.25 EV below the knee,
    // clamped to [kMinSlope, kMaxSlope]).
    float slope = 0.0f;
    static constexpr int kCell = 4;
    static constexpr float kSlopeDeltaEv = 0.25f;
    static constexpr float kMinSlope = 0.2f;
    static constexpr float kMaxSlope = 1.0f;
};

struct RenderSpec {
    CropRect crop;
    int outWidth = 0;
    int outHeight = 0;
    ColourSpec colour;
    float gain = 1.0f;
    // Curve on gained linear values, sampled uniformly in log2 from curveLo to curveHi (inclusive).
    // Below 2^curveLo: linear toe through the origin to curve[0]; at or above 2^curveHi: curve[last].
    const float* curve = nullptr;
    int curvePoints = 0;
    float curveLo = -14.0f;
    float curveHi = 0.0f;
    // 3D LUT, .cube order (red fastest), on the unit cube.
    const float* lut = nullptr;
    int lutSize = 0;
    int threads = 1;
    // B24: residual vignette, gain at r = 0 .. 1 in vignettePoints uniform steps (linear interpolation, clamped),
    // r = distance of the output pixel centre from the frame centre / half-diagonal. Multiplies the linear values
    // after the matrix (and the area downscale), before the curve. nullptr = off.
    const float* vignette = nullptr;
    int vignettePoints = 0;
    // B25: optional gain map accumulation (nullptr = none). With it the row bands of the threads start on cell rows.
    GainMapAccum* gainMap = nullptr;
};

struct RawStats {
    float medianY = 0.0f;
    float clippedFraction = 0.0f;
    int cells = 0;
    float p98Y = 0.0f;  // B23c: order statistic at floor(0.98 * (n - 1)), for the R5 II metering fit
    float p25Y = 0.0f;  // B23c.1: order statistic at floor(0.25 * (n - 1)), for the metering headroom lift
};

// Renders the crop to outWidth x outHeight RGBA8 (alpha 255) into `rgba` (row stride in bytes).
// Returns an empty string on success, else a short error (nothing useful is written then).
std::string renderRaw(const RawInput& in, const RenderSpec& spec, std::uint8_t* rgba, std::size_t strideBytes);

// Same render as full-precision float RGB (sRGB-encoded, 0..1), row stride in floats. Used by the host parity
// tool so the comparison with the Python reference is not limited by 8-bit rounding.
std::string renderRawFloat(const RawInput& in, const RenderSpec& spec, float* rgb, std::size_t strideFloats);

// Exposure statistics from CFA cells sampled every `step` cells inside the crop (no demosaic).
std::string rawStats(const RawInput& in, const CropRect& crop, const ColourSpec& colour, int step, RawStats& out);

std::string validate(const RawInput& in);

// B23: camera RGB already on the output grid (the super-resolution merge's normalised, shading-corrected values),
// planar: plane c at data + c * planeStride, rows rowStride floats apart.
struct LinearRgb {
    const float* data = nullptr;
    int width = 0;
    int height = 0;
    std::size_t rowStride = 0;
    std::size_t planeStride = 0;
};

// B23: stages 4-8 of the pipeline on linear camera RGB at the output size (balance + clip, matrix, tone curve, LUT;
// nothing is resampled; spec.crop is ignored and spec.outWidth/outHeight must equal the image size).
std::string renderLinear(const LinearRgb& in, const RenderSpec& spec, std::uint8_t* rgba, std::size_t strideBytes);
std::string renderLinearFloat(const LinearRgb& in, const RenderSpec& spec, float* rgb, std::size_t strideFloats);

// B23: rawStats for linear camera RGB: every `step`-th pixel in each direction from (0, 0).
std::string linearStats(const LinearRgb& in, const ColourSpec& colour, int step, RawStats& out);

// B23b.1: the nice value the last render worker thread read back after taking its spawning thread's (-1000: none yet).
int renderThreadNiceSeen();

}  // namespace redline
