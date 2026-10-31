// Redline native module (B20): Adobe/Resolve .cube 3D LUT parser.
// Pure C++17, no Android or JNI dependencies, so the same source is unit-tested on the CI host (app/src/test/cpp).
#pragma once

#include <cstddef>
#include <string>
#include <vector>

#define REDLINE_NATIVE_VERSION "redline_native 0.2.3-alpha.1 (B20 .cube parser, B21 RAW colour pipeline, B22b streaming burst merge, B23 super-resolution)"

namespace redline {

struct CubeLut {
    int size = 0;
    float domainMin[3] = {0.0f, 0.0f, 0.0f};
    float domainMax[3] = {1.0f, 1.0f, 1.0f};
    std::string title;
    // size^3 RGB triples in file order: red varies fastest, then green, then blue.
    std::vector<float> data;
};

constexpr int kMinCubeSize = 2;
constexpr int kMaxCubeSize = 256;

// Parses .cube text. Supports TITLE, LUT_3D_SIZE, DOMAIN_MIN / DOMAIN_MAX, LUT_3D_INPUT_RANGE, '#' comments,
// blank lines and CRLF line ends; unknown keywords are ignored. 1D LUTs are refused. Returns an empty string on
// success, otherwise a short error message (and leaves `out` unspecified).
std::string parseCube(const char* text, std::size_t length, CubeLut& out);

const char* nativeVersion();

}  // namespace redline
