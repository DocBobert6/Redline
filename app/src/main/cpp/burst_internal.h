// Redline native module: the B22 burst merge internals that the B23 super-resolution merge (superres.cpp) shares:
// the grey pyramid, the reference pick, the coarse-to-fine integer tile alignment and the work pool. Internal to the
// library (not part of the JNI surface); the definitions live in burst_merge.cpp.
#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>
#include <vector>

#include "burst_merge.h"

namespace redline {
namespace detail {

constexpr int kFineTile = 16;   // grey px
constexpr int kFineStride = 8;  // grey px; fine tile i starts at grey 8 i - 8 (centre 8 i, RAW 16 i)

struct Image {
    int w = 0;
    int h = 0;
    std::vector<std::uint16_t> px;
    std::uint16_t maxv = 65535;  // largest value (picks the narrow-accumulator kernels when they cannot overflow)
    std::uint16_t at(int y, int x) const { return px[static_cast<std::size_t>(y) * w + x]; }
    const std::uint16_t* row(int y) const { return px.data() + static_cast<std::size_t>(y) * w; }
    std::size_t bytes() const { return px.size() * sizeof(std::uint16_t); }
};

using Pyramid = std::vector<Image>;

struct Grid {
    int size = 0;
    int stride = 0;
    int offset = 0;  // origin of tile i = i * stride + offset
    int nx = 0;
    int ny = 0;
    int ox(int j) const { return j * stride + offset; }
    int oy(int i) const { return i * stride + offset; }
    int cx(int j) const { return ox(j) + size / 2; }
    int cy(int i) const { return oy(i) + size / 2; }
};

// Runs body(begin, end) over [0, n) in chunks on the shared persistent pool (the caller works too).
void runParallel(int n, int threads, const std::function<void(int, int)>& body);
// Grey quads (uint16 sums) and the coarser levels of a width x height mosaic.
Pyramid buildPyramid(const BurstFrame& f, int width, int height, int threads);
std::size_t pyramidSizeBytes(const Pyramid& p);
// The B22 sharpness measure (sum of squared neighbour differences on level 1).
std::int64_t pyramidSharpness(const Pyramid& p);
// The fine tile grid of an h x w grey level.
Grid fineTileGrid(int h, int w);
// B22 coarse-to-fine alignment: per fine tile (row-major) the integer (dy, dx) in grey px, alt(q + d) ~ ref(q).
std::vector<int> alignTiles(const Pyramid& ref, const Pyramid& alt, int threads);

}  // namespace detail
}  // namespace redline
