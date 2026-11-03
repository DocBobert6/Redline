// Redline native module (B30): everything around the depth and person-segmentation models, pure C++17 (no Android, no
// JNI; tested on the CI host). calibration/redline_cal/depth_post.py is the contract:
//   prep:    the rendered still (RGBA8, sensor orientation) -> upright (the shot's rotation), fitted inside the model
//            canvas (aspect kept; a portrait frame is letterboxed with mid grey), box-averaged, float RGB 0..1; plus the
//            segmenter's input (the canvas resized bilinearly to its size);
//   finish:  the person mask back to the canvas, the confidence analysis on the active rect, the maps rotated back to
//            the still's pixel orientation, warped with its RAW-feel distortion, packed as 16-bit RGB
//            (R = depth normalised to the active rect's min..max, G = person mask, B = per-pixel confidence).
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "detail.h"

namespace redline {

/** The model canvas and where the photo sits on it (upright coordinates). */
struct DepthCanvas {
    int canvasW = 0, canvasH = 0;
    int ax = 0, ay = 0, aw = 0, ah = 0;  // active rect
    int rot = 0;                         // clockwise degrees from the still's pixels to upright (0, 90, 180, 270)
};

/** The confidence analysis (see depth_post.py). Optional values are -1 when absent. */
struct DepthAnalysis {
    float confidence = 0, p2 = 0, p98 = 0, spread = 0, rangeScore = 0;
    float edgeAgree = -1, edgeScore = 0.5f;
    float personCoverage = 0, personNearer = -1, personScore = 1;
    float dmin = 0, dmax = 0;
    bool usable = false;
};

constexpr float kDepthUsable = 0.35f;

/** Active rect for a still of width x height at rot on the canvas (aspect fitted, centred). */
DepthCanvas depthCanvas(int width, int height, int rot, int canvasW, int canvasH);

/**
 * The model inputs: rgbCanvas (canvasW * canvasH * 3 floats) and segIn (segW * segH * 3), from an RGBA8 still.
 * Returns an empty string or the error.
 */
std::string depthPrep(const std::uint8_t* rgba, int width, int height, std::size_t stride, const DepthCanvas& c, float* rgbCanvas,
                      float* segIn, int segW, int segH);

/** Bilinear resize of a float image with ch channels: half-pixel centres, clamped edges. */
void resizeBilinear(const float* src, int sw, int sh, int ch, float* dst, int dw, int dh);

/** The confidence analysis on maps of w x h (depth, mask 0..1, rgb 0..1 interleaved); fills confMap (w * h). */
DepthAnalysis analyseDepth(const float* depth, const float* mask, const float* rgb, int w, int h, float* confMap);

/**
 * Finish: depthOut (canvas), segOut (segW x segH), rgbCanvas, the canvas/rect, the still's size and distortion
 * (spec.distort false = none) -> out16 (outW * outH * 3, RGB16 interleaved, the still's pixel orientation) and the
 * analysis. outW x outH = the active rect rotated back (aw x ah for rot 0/180, ah x aw for 90/270).
 */
std::string depthFinish(const float* depthOut, const float* segOut, int segW, int segH, const float* rgbCanvas, const DepthCanvas& c,
                        int imageW, int imageH, const DetailSpec& spec, std::vector<std::uint16_t>& out16, int& outW, int& outH,
                        DepthAnalysis& a);

}  // namespace redline
