// B22 burst merge invariants on the host (CTest). Pixel parity with the Python reference is in
// calibration/tests/test_burst_merge.py (through burst_merge_tool).
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "burst_merge.h"

namespace {

int failures = 0;

void expect(bool ok, const std::string& what) {
    if (!ok) {
        std::fprintf(stderr, "FAIL: %s\n", what.c_str());
        ++failures;
    }
}

redline::BurstParams params(int w, int h) {
    redline::BurstParams p;
    p.width = w;
    p.height = h;
    for (float& b : p.black) b = 64.0f;
    p.white = 1023.0f;
    p.noiseS = 2e-3;
    p.noiseO = 2e-5;
    p.threads = 3;
    return p;
}

}  // namespace

int main() {
    // Self-check: exact alignment on interior tiles and close to sqrt(6) noise reduction.
    redline::BurstSelfCheck sc;
    std::string err = redline::burstSelfCheck(4, sc);
    expect(err.empty(), "self-check error: " + err);
    std::printf("self-check aligned %.3f noise ratio %.3f (%.0f ms)\n", sc.alignedFraction, sc.noiseRatio, sc.ms);
    expect(sc.alignedFraction >= 0.95, "aligned fraction >= 0.95");
    expect(sc.noiseRatio >= 2.0, "noise ratio >= 2.0 for 6 frames");

    // A single frame merges to itself.
    const int W = 64, H = 48;
    std::vector<std::uint16_t> a(static_cast<std::size_t>(W) * H);
    for (std::size_t i = 0; i < a.size(); ++i) a[i] = static_cast<std::uint16_t>(64 + (i * 37) % 900);
    std::vector<float> out(a.size());
    redline::BurstResult r;
    err = redline::mergeBurstFloat({{a.data(), W}}, params(W, H), out.data(), W, r);
    expect(err.empty(), "single frame: " + err);
    bool same = true;
    for (std::size_t i = 0; i < a.size(); ++i) same = same && out[i] == static_cast<float>(a[i]);
    expect(same && r.reference == 0 && r.frames.empty(), "single frame is the identity");

    // Identical frames: identity, zero displacement, full weight.
    err = redline::mergeBurstFloat({{a.data(), W}, {a.data(), W}, {a.data(), W}}, params(W, H), out.data(), W, r);
    expect(err.empty(), "identical frames: " + err);
    double maxErr = 0;
    for (std::size_t i = 0; i < a.size(); ++i) maxErr = std::fmax(maxErr, std::fabs(out[i] - a[i]));
    expect(maxErr < 1e-3, "identical frames merge to themselves");
    expect(r.frames.size() == 2 && r.frames[0].maxDispPx == 0 && r.frames[0].meanWeight > 0.999, "identical frames: no motion");

    // Scaled uint16 output keeps the levels' scale.
    std::vector<std::uint16_t> o16(a.size());
    err = redline::mergeBurstU16({{a.data(), W}, {a.data(), W}}, params(W, H), o16.data(), W, 16.0f, r);
    expect(err.empty() && o16[5] == a[5] * 16, "uint16 output scaled by 16");

    // Bad input is refused, never crashes.
    expect(!redline::mergeBurstFloat({}, params(W, H), out.data(), W, r).empty(), "no frames refused");
    expect(!redline::mergeBurstFloat({{a.data(), W}}, params(W - 1, H), out.data(), W, r).empty(), "odd width refused");
    redline::BurstParams bad = params(W, H);
    bad.reference = 3;
    expect(!redline::mergeBurstFloat({{a.data(), W}}, bad, out.data(), W, r).empty(), "reference out of range refused");
    expect(!redline::mergeBurstU16({{a.data(), W}}, params(W, H), o16.data(), W, 80.0f, r).empty(), "16-bit overflow refused");

    if (failures == 0) std::printf("burst_merge_test: all passed\n");
    return failures == 0 ? 0 : 1;
}
