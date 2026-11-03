// B30: RGBA -> YUV 4:2:0 tile conversion for the HEIF encoder (yuv.cpp).
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#include "yuv.h"

namespace {

int failures = 0;

void expect(bool ok, const char* what) {
    if (!ok) {
        std::printf("FAIL: %s\n", what);
        ++failures;
    }
}

std::vector<std::uint8_t> image(int w, int h) {
    std::vector<std::uint8_t> px(static_cast<size_t>(w) * h * 4);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            std::uint8_t* p = &px[(static_cast<size_t>(y) * w + x) * 4];
            p[0] = static_cast<std::uint8_t>((x * 255) / (w - 1));
            p[1] = static_cast<std::uint8_t>((y * 255) / (h - 1));
            p[2] = static_cast<std::uint8_t>(128 + 100 * std::sin(x * 0.05) * std::cos(y * 0.07));
            p[3] = 255;
        }
    }
    return px;
}

void knownColours() {
    int y, cb, cr;
    redline::rgbToYcc(255, 255, 255, y, cb, cr);
    expect(y == 255 && cb == 128 && cr == 128, "white -> 255,128,128");
    redline::rgbToYcc(0, 0, 0, y, cb, cr);
    expect(y == 0 && cb == 128 && cr == 128, "black -> 0,128,128");
    redline::rgbToYcc(128, 128, 128, y, cb, cr);
    expect(y == 128 && cb == 128 && cr == 128, "grey stays neutral");
    redline::rgbToYcc(255, 0, 0, y, cb, cr);
    // JFIF: Y 76.2, Cb 85.0, Cr 255.5 -> 255
    expect(std::abs(y - 76) <= 1 && std::abs(cb - 85) <= 1 && cr == 255, "red");
    redline::rgbToYcc(0, 0, 255, y, cb, cr);
    expect(std::abs(y - 29) <= 1 && cb == 255 && std::abs(cr - 107) <= 1, "blue");
}

// Inverse JFIF for the round trip.
void yccToRgb(int y, int cb, int cr, double& r, double& g, double& b) {
    r = y + 1.402 * (cr - 128);
    g = y - 0.344136 * (cb - 128) - 0.714136 * (cr - 128);
    b = y + 1.772 * (cb - 128);
}

void tilesI420AndNv12() {
    const int w = 301, h = 203, tile = 64;  // odd size: the last tiles repeat the edge
    std::vector<std::uint8_t> px = image(w, h);
    const int cols = (w + tile - 1) / tile, rows = (h + tile - 1) / tile;
    double maxErrY = 0, sumErrRgb = 0;
    long n = 0;
    bool edgeOk = true, nvSame = true;
    for (int ty = 0; ty < rows; ++ty) {
        for (int tx = 0; tx < cols; ++tx) {
            // I420 with padded strides
            std::vector<std::uint8_t> Y(72 * tile), U(40 * tile / 2), V(40 * tile / 2);
            redline::Yuv420Dst d;
            d.y = Y.data();
            d.yRowStride = 72;
            d.u = U.data();
            d.v = V.data();
            d.uvRowStride = 40;
            const char* e = redline::rgbaToYuv420Tile(px.data(), w, h, w * 4, tx * tile, ty * tile, tile, tile, d);
            expect(e[0] == 0, e);
            // NV12: interleaved UV
            std::vector<std::uint8_t> Y2(tile * tile), UV(tile * tile / 2 + 1);
            redline::Yuv420Dst d2;
            d2.y = Y2.data();
            d2.yRowStride = tile;
            d2.u = UV.data();
            d2.v = UV.data() + 1;
            d2.uvRowStride = tile;
            d2.uvPixelStride = 2;
            e = redline::rgbaToYuv420Tile(px.data(), w, h, w * 4, tx * tile, ty * tile, tile, tile, d2);
            expect(e[0] == 0, e);
            for (int y = 0; y < tile; ++y) {
                for (int x = 0; x < tile; ++x) {
                    const int sx = std::min(tx * tile + x, w - 1), sy = std::min(ty * tile + y, h - 1);
                    const std::uint8_t* p = &px[(static_cast<size_t>(sy) * w + sx) * 4];
                    int ey, ecb, ecr;
                    redline::rgbToYcc(p[0], p[1], p[2], ey, ecb, ecr);
                    const int gotY = Y[y * 72 + x];
                    if (gotY != ey) edgeOk = false;
                    if (Y2[y * tile + x] != gotY) nvSame = false;
                    const int cu = U[(y / 2) * 40 + x / 2], cv = V[(y / 2) * 40 + x / 2];
                    if (UV[(y / 2) * tile + (x / 2) * 2] != cu || UV[(y / 2) * tile + (x / 2) * 2 + 1] != cv) nvSame = false;
                    if (tx * tile + x < w && ty * tile + y < h) {
                        double r, g, b;
                        yccToRgb(gotY, cu, cv, r, g, b);
                        sumErrRgb += std::fabs(r - p[0]) + std::fabs(g - p[1]) + std::fabs(b - p[2]);
                        maxErrY = std::max(maxErrY, std::fabs(gotY - (0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2])));
                        n += 3;
                    }
                }
            }
        }
    }
    std::printf("yuv: max luma error %.2f, mean RGB round-trip error %.2f /255\n", maxErrY, sumErrRgb / n);
    expect(edgeOk, "luma per pixel matches rgbToYcc (edge repeat included)");
    expect(nvSame, "NV12 output equals I420 output");
    expect(maxErrY <= 0.75, "luma within rounding");
    expect(sumErrRgb / n < 2.5, "4:2:0 round trip on a smooth image");
}

void rejectsBadInput() {
    std::vector<std::uint8_t> px = image(16, 16);
    std::vector<std::uint8_t> Y(64), U(16), V(16);
    redline::Yuv420Dst d;
    d.y = Y.data();
    d.yRowStride = 8;
    d.u = U.data();
    d.v = V.data();
    d.uvRowStride = 4;
    expect(redline::rgbaToYuv420Tile(px.data(), 16, 16, 64, 0, 0, 7, 8, d)[0] != 0, "odd tile refused");
    expect(redline::rgbaToYuv420Tile(px.data(), 16, 16, 64, 16, 0, 8, 8, d)[0] != 0, "origin outside refused");
    d.yRowStride = 4;
    expect(redline::rgbaToYuv420Tile(px.data(), 16, 16, 64, 0, 0, 8, 8, d)[0] != 0, "short row stride refused");
}

// A 2910x1939 still (the B25 device shot) as 6x4 tiles of 512: the per-still conversion cost the HEIF save pays.
void timing() {
    const int w = 2910, h = 1939, tile = 512;
    std::vector<std::uint8_t> px = image(w, h);
    std::vector<std::uint8_t> Y(tile * tile), U(tile * tile / 4), V(tile * tile / 4);
    redline::Yuv420Dst d;
    d.y = Y.data();
    d.yRowStride = tile;
    d.u = U.data();
    d.v = V.data();
    d.uvRowStride = tile / 2;
    const auto t0 = std::chrono::steady_clock::now();
    for (int ty = 0; ty < (h + tile - 1) / tile; ++ty) {
        for (int tx = 0; tx < (w + tile - 1) / tile; ++tx) {
            redline::rgbaToYuv420Tile(px.data(), w, h, w * 4, tx * tile, ty * tile, tile, tile, d);
        }
    }
    const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    std::printf("yuv: 2910x1939 in 24 tiles: %.1f ms (one thread)\n", ms);
}

}  // namespace

int main() {
    knownColours();
    tilesI420AndNv12();
    rejectsBadInput();
    timing();
    if (failures == 0) std::printf("yuv_test: all passed\n");
    return failures == 0 ? 0 : 1;
}
