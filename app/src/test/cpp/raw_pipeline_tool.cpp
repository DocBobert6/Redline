// Host command-line front end of the native RAW pipeline (B21), used by the Python parity test
// (calibration/tests/test_raw_pipeline.py) and the local parity run on real DNGs.
//
//   raw_pipeline_tool render <params.txt> <out.rgb>   writes outWidth x outHeight RGB8, row-major, no padding
//   raw_pipeline_tool renderf <params.txt> <out.f32>  the same render as float32 RGB (parity before 8-bit rounding)
//   raw_pipeline_tool stats  <params.txt>             prints "median_y clipped_fraction cells"
//
// params.txt is whitespace-separated "key value..." records:
//   width W  height H  cfa C  black B0 B1 B2 B3  white WL  raw PATH (uint16 little-endian, W*H, no padding)
//   shading ROWS COLS PATH (float32 ROWS*COLS*4, Camera2 order) | shading none
//   crop L T W H  out OW OH  matrix M0..M8  neutral N0 N1 N2  gain G
//   curve N LO HI PATH (float32 N)  cube PATH (.cube file)  threads T  step S
#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "cube_parser.h"
#include "raw_pipeline.h"

namespace {

template <typename T>
bool readBinary(const std::string& path, std::vector<T>& out, std::size_t count) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    out.resize(count);
    f.read(reinterpret_cast<char*>(out.data()), static_cast<std::streamsize>(count * sizeof(T)));
    return static_cast<std::size_t>(f.gcount()) == count * sizeof(T);
}

int fail(const std::string& message) {
    std::fprintf(stderr, "raw_pipeline_tool: %s\n", message.c_str());
    return 2;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 3) return fail("usage: raw_pipeline_tool render|stats params.txt [out.rgb]");
    const std::string mode = argv[1];
    std::ifstream pf(argv[2]);
    if (!pf) return fail("cannot read params");
    redline::RawInput in;
    redline::RenderSpec spec;
    std::vector<std::uint16_t> raw;
    std::vector<float> shading, curve;
    redline::CubeLut lut;
    std::string rawPath, shadingPath, curvePath, cubePath;
    int step = 8;
    std::string key;
    while (pf >> key) {
        if (key == "width") pf >> in.width;
        else if (key == "height") pf >> in.height;
        else if (key == "cfa") pf >> in.cfa;
        else if (key == "black") pf >> in.black[0] >> in.black[1] >> in.black[2] >> in.black[3];
        else if (key == "white") pf >> in.white;
        else if (key == "raw") pf >> rawPath;
        else if (key == "shading") {
            pf >> shadingPath;
            if (shadingPath != "none") {
                in.shadingRows = std::stoi(shadingPath);
                pf >> in.shadingCols >> shadingPath;
            }
        } else if (key == "crop") pf >> spec.crop.left >> spec.crop.top >> spec.crop.width >> spec.crop.height;
        else if (key == "out") pf >> spec.outWidth >> spec.outHeight;
        else if (key == "matrix") for (float& v : spec.colour.matrix) pf >> v;
        else if (key == "neutral") for (float& v : spec.colour.neutral) pf >> v;
        else if (key == "gain") pf >> spec.gain;
        else if (key == "curve") pf >> spec.curvePoints >> spec.curveLo >> spec.curveHi >> curvePath;
        else if (key == "cube") pf >> cubePath;
        else if (key == "threads") pf >> spec.threads;
        else if (key == "step") pf >> step;
        else return fail("unknown key " + key);
    }
    in.rowStride = in.width;
    if (!readBinary(rawPath, raw, static_cast<std::size_t>(in.width) * in.height)) return fail("cannot read raw");
    in.data = raw.data();
    if (in.shadingRows > 0) {
        if (!readBinary(shadingPath, shading, static_cast<std::size_t>(in.shadingRows) * in.shadingCols * 4)) {
            return fail("cannot read shading");
        }
        in.shading = shading.data();
    }
    if (mode == "stats") {
        redline::RawStats st;
        const std::string err = redline::rawStats(in, spec.crop, spec.colour, step, st);
        if (!err.empty()) return fail(err);
        std::printf("%.9g %.9g %d\n", st.medianY, st.clippedFraction, st.cells);
        return 0;
    }
    if ((mode != "render" && mode != "renderf") || argc < 4) return fail("render needs an output path");
    if (!readBinary(curvePath, curve, static_cast<std::size_t>(spec.curvePoints))) return fail("cannot read curve");
    spec.curve = curve.data();
    std::ifstream cf(cubePath, std::ios::binary);
    if (!cf) return fail("cannot read cube");
    std::stringstream ss;
    ss << cf.rdbuf();
    const std::string text = ss.str();
    const std::string perr = redline::parseCube(text.data(), text.size(), lut);
    if (!perr.empty()) return fail("cube: " + perr);
    spec.lut = lut.data.data();
    spec.lutSize = lut.size;
    const std::size_t pixels = static_cast<std::size_t>(spec.outWidth) * spec.outHeight;
    std::ofstream of(argv[3], std::ios::binary);
    double ms = 0.0;
    if (mode == "renderf") {
        std::vector<float> rgb(pixels * 3);
        const auto t0 = std::chrono::steady_clock::now();
        const std::string err = redline::renderRawFloat(in, spec, rgb.data(), static_cast<std::size_t>(spec.outWidth) * 3);
        ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        if (!err.empty()) return fail(err);
        of.write(reinterpret_cast<const char*>(rgb.data()), static_cast<std::streamsize>(rgb.size() * sizeof(float)));
    } else {
        std::vector<std::uint8_t> rgba(pixels * 4);
        const auto t0 = std::chrono::steady_clock::now();
        const std::string err = redline::renderRaw(in, spec, rgba.data(), static_cast<std::size_t>(spec.outWidth) * 4);
        ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        if (!err.empty()) return fail(err);
        std::vector<std::uint8_t> rgb(pixels * 3);
        for (std::size_t i = 0; i < pixels; ++i) {
            rgb[i * 3] = rgba[i * 4];
            rgb[i * 3 + 1] = rgba[i * 4 + 1];
            rgb[i * 3 + 2] = rgba[i * 4 + 2];
        }
        of.write(reinterpret_cast<const char*>(rgb.data()), static_cast<std::streamsize>(rgb.size()));
    }
    if (!of) return fail("cannot write output");
    std::printf("render_ms %.1f\n", ms);
    return 0;
}
