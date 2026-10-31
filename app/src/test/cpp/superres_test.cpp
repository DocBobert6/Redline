// B23 super-resolution merge: invariants (CTest). Python parity is in calibration/tests/test_superres.py.
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <string>
#include <vector>

#include "superres.h"

namespace {

int failures = 0;

void check(bool ok, const std::string& what) {
    if (!ok) {
        std::fprintf(stderr, "FAIL: %s\n", what.c_str());
        ++failures;
    }
}

std::vector<std::uint16_t> frame(int w, int h, int seed) {
    std::vector<std::uint16_t> f(static_cast<std::size_t>(w) * h);
    std::uint32_t s = 1234u + static_cast<std::uint32_t>(seed);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            s = s * 1664525u + 1013904223u;
            const double v = 400.0 + 200.0 * std::sin(0.11 * (x + seed) + 0.07 * y) + 100.0 * std::cos(0.05 * x - 0.13 * (y - seed)) +
                             static_cast<double>(s >> 26);
            f[static_cast<std::size_t>(y) * w + x] = static_cast<std::uint16_t>(v);
        }
    }
    return f;
}

redline::SuperResParams params(int w, int h) {
    redline::SuperResParams p;
    p.width = w;
    p.height = h;
    p.cfa = 0;
    for (float& b : p.black) b = 64.0f;
    p.white = 1023.0f;
    p.noiseS = 2e-4;
    p.noiseO = 2e-6;
    p.crop = redline::CropRect{64, 48, 128, 96};
    p.outWidth = 256;
    p.outHeight = 192;
    p.threads = 2;
    return p;
}

}  // namespace

int main() {
    // Region: crop + 64 px, clamped and even.
    {
        int r[4];
        redline::superResRegion(redline::CropRect{100, 50, 400, 300}, 640, 480, r);
        check(r[0] == 36 && r[1] == 0 && r[2] == 528 && r[3] == 414, "region clamps and stays even");
    }
    // Validation.
    {
        redline::SuperResMerger m;
        auto p = params(256, 192);
        p.crop.left = 63;
        check(!m.begin(p).empty(), "odd crop rejected");
        p = params(256, 192);
        p.outWidth = 0;
        check(!m.begin(p).empty(), "empty output rejected");
        p = params(256, 192);
        p.crop.width = 400;
        check(!m.begin(p).empty(), "crop outside the frame rejected");
    }
    // Streaming: every frame is released as soon as it is added (the region is copied); finish gathers.
    {
        const int w = 256, h = 192;
        std::vector<std::vector<std::uint16_t>> frames;
        for (int k = 0; k < 5; ++k) frames.push_back(frame(w, h, k));
        redline::SuperResMerger m;
        auto p = params(w, h);
        check(m.begin(p).empty(), "begin");
        for (int k = 0; k < 5; ++k) {
            std::vector<int> released;
            check(m.add(redline::BurstFrame{frames[k].data(), w}, released).empty(), "add");
            check(released.size() == 1 && released[0] == k, "frame released on add");
            // The buffer may be reused at once: scribble over it.
            for (auto& v : frames[k]) v = 0;
        }
        check(m.reference() >= 0 && m.reference() < 3, "reference among the first three");
        const std::size_t plane = static_cast<std::size_t>(p.outWidth) * p.outHeight;
        std::vector<float> out(plane * 3);
        redline::SuperResResult r;
        check(m.finish(out.data(), static_cast<std::size_t>(p.outWidth), plane, r).empty(), "finish");
        bool finite = true;
        for (float v : out) finite = finite && std::isfinite(v) && v > -0.5f && v < 2.0f;
        check(finite, "output finite and in range (scribbled buffers were not read)");
        check(r.framesAdded == 5 && r.frames.size() == 4, "stats per alternate frame");
        check(r.region[2] == 128 + 128 && r.region[3] == 96 + 96, "region size");
        check(r.sigmaG >= 0.25 && r.sigmaG <= 0.65, "kernel width in range");
        std::vector<int> released;
        check(!m.add(redline::BurstFrame{frames[0].data(), w}, released).empty(), "add after finish rejected");
    }
    // One frame: a wide-kernel reconstruction, no alternates.
    {
        const int w = 256, h = 192;
        auto f = frame(w, h, 3);
        auto p = params(w, h);
        const std::size_t plane = static_cast<std::size_t>(p.outWidth) * p.outHeight;
        std::vector<float> out(plane * 3);
        redline::SuperResResult r;
        const std::string err = redline::superResMerge({redline::BurstFrame{f.data(), w}}, p, out.data(),
                                                       static_cast<std::size_t>(p.outWidth), plane, r);
        check(err.empty(), "single frame merge: " + err);
        check(r.coverage == 0.0 && std::abs(r.sigmaG - 0.65) < 1e-9, "single frame uses the wide kernel");
    }
    // The on-device self-check.
    {
        redline::SuperResSelfCheck c;
        const std::string err = redline::superResSelfCheck(2, c);
        check(err.empty(), "self-check ran: " + err);
        std::printf("self-check: flow error %.3f px, coverage %.2f, rmse %.4f vs %.4f (gain %.2f), %.0f ms\n", c.flowErrorPx, c.coverage,
                    c.rmseMerge, c.rmseSingle, c.gain, c.ms);
        check(c.passed(), "self-check passes");
    }
    if (failures == 0) std::printf("superres_test: all passed\n");
    return failures == 0 ? 0 : 1;
}
