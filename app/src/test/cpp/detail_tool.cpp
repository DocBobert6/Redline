// Host command-line front end of the B24 detail post-pass, used by the Python parity test
// (calibration/tests/test_detail.py).
//
//   detail_tool applyf <params.txt> <in.f32> <out.f32>   float RGB (encoded 0..1), no rounding
//   detail_tool apply8 <params.txt> <in.rgb> <out.rgb>   RGB8 through the app's RGBA8 path (8-bit after each stage)
// Prints "detail_ms TOTAL distort_scale S measured M0..M6 added A0..A6".
//
// params.txt: width W  height H  distort A B C | distort none  sharpen SIGMA AMOUNT THRESHOLD
//             grain T0..T6 KERNEL_W KAPPA SEED | grain none  threads T
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <string>
#include <vector>

#include "detail.h"

namespace {

int fail(const std::string& m) {
    std::fprintf(stderr, "detail_tool: %s\n", m.c_str());
    return 2;
}

template <typename T>
bool readAll(const std::string& path, std::vector<T>& out, std::size_t count) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    out.resize(count);
    f.read(reinterpret_cast<char*>(out.data()), static_cast<std::streamsize>(count * sizeof(T)));
    return static_cast<std::size_t>(f.gcount()) == count * sizeof(T);
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 5) return fail("usage: detail_tool applyf|apply8 params.txt in out");
    const std::string mode = argv[1];
    std::ifstream pf(argv[2]);
    if (!pf) return fail("cannot read params");
    int w = 0, h = 0;
    redline::DetailSpec s;
    std::string key;
    while (pf >> key) {
        if (key == "width") pf >> w;
        else if (key == "height") pf >> h;
        else if (key == "distort") {
            std::string a;
            pf >> a;
            if (a != "none") {
                s.distort = true;
                s.distA = std::stof(a);
                pf >> s.distB >> s.distC;
            }
        } else if (key == "sharpen") pf >> s.sharpenSigma >> s.sharpenAmount >> s.sharpenThreshold;
        else if (key == "grain") {
            std::string a;
            pf >> a;
            if (a != "none") {
                s.grain = true;
                s.grainTarget[0] = std::stof(a);
                for (int i = 1; i < redline::kNoiseBins; ++i) pf >> s.grainTarget[i];
                pf >> s.grainKernelW >> s.grainKappa >> s.seed;
            }
        } else if (key == "threads") pf >> s.threads;
        else return fail("unknown key " + key);
    }
    const std::size_t px = static_cast<std::size_t>(w) * h;
    redline::DetailStats st;
    std::string err;
    std::ofstream of(argv[4], std::ios::binary);
    if (mode == "applyf") {
        std::vector<float> img;
        if (!readAll(argv[3], img, px * 3)) return fail("cannot read input");
        err = redline::applyDetailFloat(img.data(), w, h, static_cast<std::size_t>(w) * 3, s, st);
        if (!err.empty()) return fail(err);
        of.write(reinterpret_cast<const char*>(img.data()), static_cast<std::streamsize>(img.size() * sizeof(float)));
    } else if (mode == "apply8") {
        std::vector<std::uint8_t> rgb;
        if (!readAll(argv[3], rgb, px * 3)) return fail("cannot read input");
        std::vector<std::uint8_t> rgba(px * 4);
        for (std::size_t i = 0; i < px; ++i) {
            for (int c = 0; c < 3; ++c) rgba[i * 4 + c] = rgb[i * 3 + c];
            rgba[i * 4 + 3] = 255;
        }
        err = redline::applyDetail(rgba.data(), w, h, static_cast<std::size_t>(w) * 4, s, st);
        if (!err.empty()) return fail(err);
        for (std::size_t i = 0; i < px; ++i) {
            for (int c = 0; c < 3; ++c) rgb[i * 3 + c] = rgba[i * 4 + c];
            if (rgba[i * 4 + 3] != 255) return fail("alpha changed");
        }
        of.write(reinterpret_cast<const char*>(rgb.data()), static_cast<std::streamsize>(rgb.size()));
    } else {
        return fail("unknown mode " + mode);
    }
    std::printf("detail_ms %.3f distort_scale %.9g measured", st.totalMs, st.distortScale);
    for (float v : st.measured) std::printf(" %.9g", v);
    std::printf(" added");
    for (float v : st.added) std::printf(" %.9g", v);
    std::printf(" sharpen_ms %.3f noise_ms %.3f grain_ms %.3f distort_ms %.3f\n", st.sharpenMs, st.noiseMs, st.grainMs, st.distortMs);
    return 0;
}
