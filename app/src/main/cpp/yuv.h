// B30 (HEIF speed fix): RGBA -> YUV 4:2:0 for the HEVC/HEIC encoder's input image, one tile at a time.
#pragma once

#include <cstdint>

namespace redline {

/** A YUV 4:2:0 destination: three planes with their strides (Android Image planes: I420 or NV12/NV21 by pixel stride). */
struct Yuv420Dst {
    std::uint8_t* y = nullptr;
    int yRowStride = 0;
    int yPixelStride = 1;
    std::uint8_t* u = nullptr;
    std::uint8_t* v = nullptr;
    int uvRowStride = 0;
    int uvPixelStride = 1;
};

/**
 * Converts the [x0, x0 + tileW) x [y0, y0 + tileH) window of an RGBA_8888 image (width x height, rowStride bytes) to
 * full-range BT.601 YCbCr (JFIF: the same matrix as a JPEG) into [dst]. Pixels outside the image repeat the edge (the
 * grid's last row/column of tiles). Chroma is the mean of each 2x2 block. tileW and tileH must be even.
 * Returns an empty string, or what was wrong.
 */
const char* rgbaToYuv420Tile(const std::uint8_t* rgba, int width, int height, int rowStride, int x0, int y0, int tileW,
                             int tileH, const Yuv420Dst& dst);

/** One pixel, for tests: full-range BT.601 (JFIF) with the converter's rounding. */
void rgbToYcc(int r, int g, int b, int& y, int& cb, int& cr);

}  // namespace redline
