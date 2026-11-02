// Host-side unit test of the B24 detail post-pass (CTest). Parity with the Python reference is checked by
// calibration/tests/test_detail.py through detail_tool.
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "detail.h"

namespace {

int failures = 0;

void check(bool ok, const char* what) {
    if (!ok) {
        std::printf("FAIL: %s\n", what);
        ++failures;
    }
}

std::vector<std::uint8_t> textured(int w, int h) {
    std::vector<std::uint8_t> img(static_cast<std::size_t>(w) * h * 4);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            std::uint8_t* p = &img[(static_cast<std::size_t>(y) * w + x) * 4];
            const int v = ((x / 6 + y / 6) % 2) ? 170 : 80;
            p[0] = static_cast<std::uint8_t>(v);
            p[1] = static_cast<std::uint8_t>(v + 10);
            p[2] = static_cast<std::uint8_t>(v - 10);
            p[3] = 255;
        }
    }
    return img;
}

void testOffIsIdentity() {
    auto img = textured(64, 40);
    const auto ref = img;
    redline::DetailSpec s;
    redline::DetailStats st;
    check(redline::applyDetail(img.data(), 64, 40, 64 * 4, s, st).empty(), "all-off ok");
    check(img == ref, "all-off leaves the image untouched");
}

void testSharpenFlatAndEdges() {
    std::vector<std::uint8_t> flat(64 * 40 * 4, 120);
    redline::DetailSpec s;
    s.sharpenAmount = 0.7f;
    s.sharpenSigma = 1.2f;
    redline::DetailStats st;
    const auto ref = flat;
    redline::applyDetail(flat.data(), 64, 40, 64 * 4, s, st);
    check(flat == ref, "a flat field is unchanged by sharpening");
    auto img = textured(64, 40);
    const auto before = img;
    redline::applyDetail(img.data(), 64, 40, 64 * 4, s, st);
    // Next to an edge the bright side gets brighter and the dark side darker.
    const std::size_t bright = (static_cast<std::size_t>(3) * 64 + 6) * 4 + 1;  // x = 6 (bright), y = 3
    const std::size_t dark = (static_cast<std::size_t>(3) * 64 + 5) * 4 + 1;    // x = 5 (dark)
    check(img[bright] > before[bright] && img[dark] < before[dark], "edges gain contrast");
    // A large coring threshold suppresses a small edge.
    s.sharpenThreshold = 1.0f;
    auto img2 = textured(64, 40);
    redline::applyDetail(img2.data(), 64, 40, 64 * 4, s, st);
    int maxd = 0;
    for (std::size_t i = 0; i < img2.size(); ++i) maxd = std::max(maxd, std::abs(img2[i] - before[i]));
    check(maxd <= 8, "coring holds back sharpening of low-contrast detail");
}

void testGrainDeterministicAndThreadSafe() {
    std::vector<std::uint8_t> a(128 * 96 * 4, 110), b;  // 110/255 = 0.43: luma bin 3
    for (std::size_t i = 3; i < a.size(); i += 4) a[i] = 255;
    b = a;
    redline::DetailSpec s;
    s.grain = true;
    for (float& t : s.grainTarget) t = 3.0f / 255.0f;
    s.grainKernelW = 0.11f;
    s.grainKappa = 0.39f;
    s.seed = 42;
    redline::DetailStats sa, sb;
    s.threads = 1;
    redline::applyDetail(a.data(), 128, 96, 128 * 4, s, sa);
    s.threads = 4;
    redline::applyDetail(b.data(), 128, 96, 128 * 4, s, sb);
    check(a == b, "grain: 1 and 4 threads identical");
    check(sa.measured[3] >= 0.0f && sa.measured[3] < 0.5f / 255.0f, "a flat 8-bit frame measures (almost) no noise");
    check(std::fabs(sa.added[3] - 3.0f / 255.0f) < 0.5f / 255.0f, "the whole target is added on a clean frame");
    // Measured afterwards, the grain hits its Immerkaer target (8-bit rounding included).
    redline::DetailSpec m;
    m.grain = true;
    redline::DetailStats sm;
    auto c = a;
    redline::applyDetail(c.data(), 128, 96, 128 * 4, m, sm);
    check(std::fabs(sm.measured[3] - 3.0f / 255.0f) < 0.8f / 255.0f, "grain measures at its target");
    check(a[0] == a[1] && a[1] == a[2], "grain is luma only");
    // A frame already as noisy as the target gets nothing.
    redline::DetailSpec s2 = s;
    for (float& t : s2.grainTarget) t = 1.0f / 255.0f;
    auto d = a;
    redline::DetailStats sd;
    redline::applyDetail(d.data(), 128, 96, 128 * 4, s2, sd);
    check(sd.added[3] == 0.0f, "no grain on top of enough noise");
}

void testDistortion() {
    auto img = textured(90, 60);
    const auto before = img;
    redline::DetailSpec s;
    s.distort = true;  // all-zero coefficients: identity map
    redline::DetailStats st;
    check(redline::applyDetail(img.data(), 90, 60, 90 * 4, s, st).empty(), "distortion ok");
    int maxd = 0;
    for (std::size_t i = 0; i < img.size(); ++i) maxd = std::max(maxd, std::abs(img[i] - before[i]));
    check(maxd <= 1, "zero coefficients leave the image as it was");
    // 24 mm barrel (PLAN 19.1): scale < 1 so corners are filled; centre pixel unchanged.
    s.distA = 0.0259091f;
    s.distB = -0.0662025f;
    s.distC = 0.0194773f;
    auto img2 = textured(90, 60);
    redline::applyDetail(img2.data(), 90, 60, 90 * 4, s, st);
    check(st.distortScale <= 1.0f && st.distortScale > 0.95f, "fill scale in range");
    s.distA = 0.0f;
    s.distB = 0.0f;
    s.distC = 1.0f;  // d = 0: refused
    check(!redline::applyDetail(img2.data(), 90, 60, 90 * 4, s, st).empty(), "degenerate distortion refused");
}

void testGrainNormal() {
    double sum = 0.0, sq = 0.0;
    const int n = 200000;
    for (int i = 0; i < n; ++i) {
        const double v = redline::grainNormal(static_cast<std::uint32_t>(i % 997), static_cast<std::uint32_t>(i / 997), 7u);
        sum += v;
        sq += v * v;
    }
    const double mean = sum / n, var = sq / n - mean * mean;
    check(std::fabs(mean) < 0.01 && std::fabs(var - 1.0) < 0.02, "hash deviates: zero mean, unit variance");
}

}  // namespace

int main() {
    testOffIsIdentity();
    testSharpenFlatAndEdges();
    testGrainDeterministicAndThreadSafe();
    testDistortion();
    testGrainNormal();
    if (failures == 0) std::printf("detail_test: all passed\n");
    return failures == 0 ? 0 : 1;
}
