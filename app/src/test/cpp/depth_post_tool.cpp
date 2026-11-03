// Host front end of the B30 depth pre/post-processing for the Python parity test (calibration/tests/test_depth_post.py).
//
//   depth_post_tool prep <W> <H> <rot> <in.rgba> <canvas.f32> <seg.f32>        canvas 420x280x3, seg 256x144x3
//     prints "rect AX AY AW AH"
//   depth_post_tool finish <W> <H> <rot> <depth.f32> <segout.f32> <canvas.f32> <out.u16>
//     depth 420x280, segout 256x144; prints "out OW OH conf C usable U p2 P p98 P spread S edge_agree E person_cov C
//     person_nearer N dmin D dmax D"
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <string>
#include <vector>

#include "depth_post.h"

namespace {

constexpr int kCw = 420, kCh = 280, kSw = 256, kSh = 144;

int fail(const std::string& m) {
    std::fprintf(stderr, "depth_post_tool: %s\n", m.c_str());
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

template <typename T>
void writeAll(const std::string& path, const std::vector<T>& v) {
    std::ofstream f(path, std::ios::binary);
    f.write(reinterpret_cast<const char*>(v.data()), static_cast<std::streamsize>(v.size() * sizeof(T)));
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 7) return fail("usage: depth_post_tool prep|finish W H rot ...");
    const std::string mode = argv[1];
    const int w = std::stoi(argv[2]), h = std::stoi(argv[3]), rot = std::stoi(argv[4]);
    const redline::DepthCanvas c = redline::depthCanvas(w, h, rot, kCw, kCh);
    if (mode == "prep") {
        if (argc < 8) return fail("prep needs in, canvas, seg");
        std::vector<std::uint8_t> rgba;
        if (!readAll(argv[5], rgba, static_cast<std::size_t>(w) * h * 4)) return fail("cannot read rgba");
        std::vector<float> canvas(kCw * kCh * 3), seg(kSw * kSh * 3);
        const std::string err = redline::depthPrep(rgba.data(), w, h, static_cast<std::size_t>(w) * 4, c, canvas.data(), seg.data(), kSw, kSh);
        if (!err.empty()) return fail(err);
        writeAll(argv[6], canvas);
        writeAll(argv[7], seg);
        std::printf("rect %d %d %d %d\n", c.ax, c.ay, c.aw, c.ah);
        return 0;
    }
    if (mode == "finish") {
        if (argc < 9) return fail("finish needs depth, segout, canvas, out");
        std::vector<float> depth, seg, canvas;
        if (!readAll(argv[5], depth, kCw * kCh) || !readAll(argv[6], seg, kSw * kSh) || !readAll(argv[7], canvas, kCw * kCh * 3)) {
            return fail("cannot read inputs");
        }
        std::vector<std::uint16_t> out;
        int ow = 0, oh = 0;
        redline::DepthAnalysis a;
        redline::DetailSpec spec;
        const std::string err = redline::depthFinish(depth.data(), seg.data(), kSw, kSh, canvas.data(), c, w, h, spec, out, ow, oh, a);
        if (!err.empty()) return fail(err);
        writeAll(argv[8], out);
        std::printf("out %d %d conf %.6f usable %d p2 %.6f p98 %.6f spread %.6f edge_agree %.6f person_cov %.6f person_nearer %.6f dmin %.6f dmax %.6f\n",
                    ow, oh, a.confidence, a.usable ? 1 : 0, a.p2, a.p98, a.spread, a.edgeAgree, a.personCoverage, a.personNearer, a.dmin, a.dmax);
        return 0;
    }
    return fail("unknown mode " + mode);
}
