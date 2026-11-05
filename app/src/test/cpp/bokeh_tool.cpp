// Host front end of the B31 bokeh render for the Python parity test (calibration/tests/test_bokeh.py).
//
//   bokeh_tool <W> <H> <rgb.f32> <dw> <dh> <depth.f32> <mask.f32|-> <curve.f32> <curveLo> <curveHi>
//              <gain.f32|-> <gw> <gh> <kneeEv> <slope> <focalMm> <N> <focusM> <focusX> <focusY> <phoneN> <farM>
//              <maxCoc> <workMax> <confidence> <threads> <out.f32> <radius.f32>
//   rgb: W x H x 3 encoded floats (the result is written to out.f32 the same way); radius.f32: the working-grid map.
//   Prints "applied A kmax K work WW HH r_f R r_bg R scale S cap C person P layers L ms T reason <text>".
#include <cstdio>
#include <fstream>
#include <string>
#include <vector>

#include "bokeh.h"

namespace {

int fail(const std::string& m) {
    std::fprintf(stderr, "bokeh_tool: %s\n", m.c_str());
    return 2;
}

bool readAll(const std::string& path, std::vector<float>& out, std::size_t count) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    out.resize(count);
    f.read(reinterpret_cast<char*>(out.data()), static_cast<std::streamsize>(count * sizeof(float)));
    return static_cast<std::size_t>(f.gcount()) == count * sizeof(float);
}

void writeAll(const std::string& path, const std::vector<float>& v) {
    std::ofstream f(path, std::ios::binary);
    f.write(reinterpret_cast<const char*>(v.data()), static_cast<std::streamsize>(v.size() * sizeof(float)));
}

}  // namespace

int main(int argc, char** argv) {
    if (argc != 29) return fail("expected 28 arguments (see the header)");
    int a = 1;
    const int w = std::stoi(argv[a++]), h = std::stoi(argv[a++]);
    std::vector<float> rgb, depth, mask, curve, gain;
    if (!readAll(argv[a++], rgb, static_cast<std::size_t>(w) * h * 3)) return fail("cannot read rgb");
    const int dw = std::stoi(argv[a++]), dh = std::stoi(argv[a++]);
    if (!readAll(argv[a++], depth, static_cast<std::size_t>(dw) * dh)) return fail("cannot read depth");
    const std::string maskPath = argv[a++];
    if (maskPath != "-" && !readAll(maskPath, mask, static_cast<std::size_t>(dw) * dh)) return fail("cannot read mask");
    const std::string curvePath = argv[a++];
    {
        std::ifstream f(curvePath, std::ios::binary | std::ios::ate);
        if (!f) return fail("cannot read curve");
        const std::size_t n = static_cast<std::size_t>(f.tellg()) / sizeof(float);
        if (!readAll(curvePath, curve, n)) return fail("cannot read curve");
    }
    redline::BokehTone tone;
    tone.curve = curve.data();
    tone.curvePoints = static_cast<int>(curve.size());
    tone.curveLo = std::stof(argv[a++]);
    tone.curveHi = std::stof(argv[a++]);
    const std::string gainPath = argv[a++];
    const int gw = std::stoi(argv[a++]), gh = std::stoi(argv[a++]);
    tone.kneeEv = std::stof(argv[a++]);
    tone.slope = std::stof(argv[a++]);
    if (gainPath != "-") {
        if (!readAll(gainPath, gain, static_cast<std::size_t>(gw) * gh)) return fail("cannot read gain");
        tone.gainLog2 = gain.data();
        tone.gainW = gw;
        tone.gainH = gh;
    }
    redline::BokehParams p;
    p.focalMm = std::stof(argv[a++]);
    p.aperture = std::stof(argv[a++]);
    p.focusM = std::stof(argv[a++]);
    p.focusX = std::stof(argv[a++]);
    p.focusY = std::stof(argv[a++]);
    p.phoneN = std::stof(argv[a++]);
    p.farM = std::stof(argv[a++]);
    p.maxCoc = std::stof(argv[a++]);
    p.workMax = std::stoi(argv[a++]);
    redline::BokehDepth d;
    d.depth = depth.data();
    d.mask = mask.empty() ? nullptr : mask.data();
    d.width = dw;
    d.height = dh;
    d.confidence = std::stof(argv[a++]);
    p.threads = std::stoi(argv[a++]);
    const std::string outPath = argv[a++], radiusPath = argv[a++];

    std::vector<float> map;
    int mw = 0, mh = 0;
    redline::BokehStats ms;
    std::string err = redline::bokehRadiusMap(rgb.data(), w, h, static_cast<std::size_t>(w) * 3, d, p, map, mw, mh, ms);
    if (!err.empty()) return fail(err);
    writeAll(radiusPath, map);
    redline::BokehStats st;
    err = redline::bokehRenderFloat(rgb.data(), w, h, static_cast<std::size_t>(w) * 3, tone, d, p, st);
    if (!err.empty()) return fail(err);
    writeAll(outPath, rgb);
    std::printf("applied %d kmax %d work %d %d r_f %.6f r_bg %.6f scale %.6f cap %.4f person %d layers %d ms %.2f reason %s\n",
                st.applied ? 1 : 0, st.kmax, ms.workW, ms.workH, static_cast<double>(ms.rFocus), static_cast<double>(ms.rBg),
                static_cast<double>(ms.scale), static_cast<double>(ms.capRadius), ms.onPerson ? 1 : 0, st.layers, st.totalMs, st.reason.c_str());
    return 0;
}
