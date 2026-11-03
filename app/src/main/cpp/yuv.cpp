#include "yuv.h"

#include <algorithm>

namespace redline {

namespace {

inline int clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

// JFIF full-range BT.601 in 16-bit fixed point (x65536): Y = .299 R + .587 G + .114 B, Cb/Cr centred on 128.
inline int lumaOf(int r, int g, int b) { return (19595 * r + 38470 * g + 7471 * b + 32768) >> 16; }
// Offset by 128 << 16 before the shift so the value is never negative (no implementation-defined shifts).
inline int cbOf(int r, int g, int b) { return clamp255((-11059 * r - 21709 * g + 32768 * b + (128 << 16) + 32768) >> 16); }
inline int crOf(int r, int g, int b) { return clamp255((32768 * r - 27439 * g - 5329 * b + (128 << 16) + 32768) >> 16); }

}  // namespace

void rgbToYcc(int r, int g, int b, int& y, int& cb, int& cr) {
    y = lumaOf(r, g, b);
    cb = cbOf(r, g, b);
    cr = crOf(r, g, b);
}

const char* rgbaToYuv420Tile(const std::uint8_t* rgba, int width, int height, int rowStride, int x0, int y0, int tileW,
                             int tileH, const Yuv420Dst& dst) {
    if (rgba == nullptr || width <= 0 || height <= 0 || rowStride < width * 4) return "bad source image";
    if (tileW <= 0 || tileH <= 0 || (tileW & 1) != 0 || (tileH & 1) != 0) return "tile size must be even and positive";
    if (x0 < 0 || y0 < 0 || x0 >= width || y0 >= height) return "tile origin outside the image";
    if (dst.y == nullptr || dst.u == nullptr || dst.v == nullptr || dst.yPixelStride < 1 || dst.uvPixelStride < 1 ||
        dst.yRowStride < (tileW - 1) * dst.yPixelStride + 1 || dst.uvRowStride < (tileW / 2 - 1) * dst.uvPixelStride + 1) {
        return "bad destination planes";
    }
    // Source column of each tile column (edge repeat), computed once.
    int colsBuf[4096];
    if (tileW > 4096) return "tile wider than 4096";
    for (int i = 0; i < tileW; ++i) colsBuf[i] = std::min(x0 + i, width - 1) * 4;
    for (int ty = 0; ty < tileH; ty += 2) {
        const int sy0 = std::min(y0 + ty, height - 1);
        const int sy1 = std::min(y0 + ty + 1, height - 1);
        const std::uint8_t* r0 = rgba + static_cast<long>(sy0) * rowStride;
        const std::uint8_t* r1 = rgba + static_cast<long>(sy1) * rowStride;
        std::uint8_t* yd0 = dst.y + static_cast<long>(ty) * dst.yRowStride;
        std::uint8_t* yd1 = yd0 + dst.yRowStride;
        std::uint8_t* ud = dst.u + static_cast<long>(ty / 2) * dst.uvRowStride;
        std::uint8_t* vd = dst.v + static_cast<long>(ty / 2) * dst.uvRowStride;
        for (int tx = 0; tx < tileW; tx += 2) {
            const int c0 = colsBuf[tx];
            const int c1 = colsBuf[tx + 1];
            const int ra = r0[c0], ga = r0[c0 + 1], ba = r0[c0 + 2];
            const int rb = r0[c1], gb = r0[c1 + 1], bb = r0[c1 + 2];
            const int rc = r1[c0], gc = r1[c0 + 1], bc = r1[c0 + 2];
            const int rd = r1[c1], gd = r1[c1 + 1], bd = r1[c1 + 2];
            yd0[tx * dst.yPixelStride] = static_cast<std::uint8_t>(lumaOf(ra, ga, ba));
            yd0[(tx + 1) * dst.yPixelStride] = static_cast<std::uint8_t>(lumaOf(rb, gb, bb));
            yd1[tx * dst.yPixelStride] = static_cast<std::uint8_t>(lumaOf(rc, gc, bc));
            yd1[(tx + 1) * dst.yPixelStride] = static_cast<std::uint8_t>(lumaOf(rd, gd, bd));
            // 2x2 mean (rounded) then the chroma matrix.
            const int r = (ra + rb + rc + rd + 2) >> 2;
            const int g = (ga + gb + gc + gd + 2) >> 2;
            const int b = (ba + bb + bc + bd + 2) >> 2;
            const long o = static_cast<long>(tx / 2) * dst.uvPixelStride;
            ud[o] = static_cast<std::uint8_t>(cbOf(r, g, b));
            vd[o] = static_cast<std::uint8_t>(crOf(r, g, b));
        }
    }
    return "";
}

}  // namespace redline
