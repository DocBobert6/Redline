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

// ---- B24.1: the strip / in-place stages against the B24 full-copy algorithm (kept here as the reference) ----

float refLookup(const std::vector<float>& t, float u) {
    const int n = static_cast<int>(t.size());
    float f = std::min(std::max(u, 0.0f), 1.0f) * static_cast<float>(n - 1);
    int i = std::min(static_cast<int>(f), n - 2);
    const float w = f - static_cast<float>(i);
    return t[i] + (t[i + 1] - t[i]) * w;
}

// B24 distortion with a full copy of the frame (RGBA8, one thread).
void refDistort(std::vector<std::uint8_t>& img, int w, int h, float A, float B, float C) {
    const double a = A, b = B, c = C, d = 1.0 - a - b - c;
    const double half = std::min(w, h) / 2.0;
    const double rMax = std::hypot(w / 2.0, h / 2.0) / half;
    std::vector<float> ratio(2048);
    ratio[0] = static_cast<float>(1.0 / d);
    for (int i = 1; i < 2048; ++i) {
        const double rd = rMax * i / 2047;
        double r = rd;
        for (int k = 0; k < 30; ++k) {
            const double f = r * (a * r * r * r + b * r * r + c * r + d) - rd;
            const double fp = 4 * a * r * r * r + 3 * b * r * r + 2 * c * r + d;
            r -= f / fp;
        }
        ratio[i] = static_cast<float>(r / rd);
    }
    float kMax = 0.0f;
    for (int i = 0; i < 1024; ++i) {
        const double t = static_cast<double>(i) / 1023;
        const double xs[2] = {t * w / 2.0 / half, w / 2.0 / half};
        const double ys[2] = {h / 2.0 / half, t * h / 2.0 / half};
        for (int e = 0; e < 2; ++e) kMax = std::max(kMax, refLookup(ratio, static_cast<float>(std::sqrt(xs[e] * xs[e] + ys[e] * ys[e]) / rMax)));
    }
    const float scale = std::min(1.0f, 1.0f / kMax);
    const std::vector<std::uint8_t> src = img;
    const float invHalf = static_cast<float>(1.0 / half), invRMax = static_cast<float>(1.0 / rMax);
    auto px = [&](int x, int y, int ch) { return src[(static_cast<std::size_t>(y) * w + x) * 4 + ch] * (1.0f / 255.0f); };
    for (int y = 0; y < h; ++y) {
        const float yy = static_cast<float>(y) + 0.5f - h / 2.0f;
        for (int x = 0; x < w; ++x) {
            const float xx = static_cast<float>(x) + 0.5f - w / 2.0f;
            const float rd = std::sqrt(xx * xx + yy * yy) * invHalf;
            const float k = refLookup(ratio, rd * invRMax) * scale;
            const float sx = std::min(std::max(xx * k + w / 2.0f - 0.5f, 0.0f), static_cast<float>(w - 1));
            const float sy = std::min(std::max(yy * k + h / 2.0f - 0.5f, 0.0f), static_cast<float>(h - 1));
            const int x0 = std::min(static_cast<int>(sx), w - 2);
            const int yi = std::min(static_cast<int>(sy), h - 2);
            const float fx = sx - static_cast<float>(x0), fy = sy - static_cast<float>(yi);
            for (int ch = 0; ch < 3; ++ch) {
                const float o = (px(x0, yi, ch) * (1 - fx) + px(x0 + 1, yi, ch) * fx) * (1 - fy) +
                                (px(x0, yi + 1, ch) * (1 - fx) + px(x0 + 1, yi + 1, ch) * fx) * fy;
                img[(static_cast<std::size_t>(y) * w + x) * 4 + ch] =
                    static_cast<std::uint8_t>(std::min(std::max(o, 0.0f), 1.0f) * 255.0f + 0.5f);
            }
        }
    }
}

std::vector<std::uint8_t> noisy(int w, int h, unsigned seed) {
    std::vector<std::uint8_t> img(static_cast<std::size_t>(w) * h * 4);
    unsigned v = seed;
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            for (int c = 0; c < 4; ++c) {
                v = v * 1664525u + 1013904223u;
                img[(static_cast<std::size_t>(y) * w + x) * 4 + c] =
                    static_cast<std::uint8_t>(((x * 3 + y * 2 + c * 40) & 255) ^ ((v >> 24) & 15));
            }
        }
    }
    return img;
}

void testStripsMatchFullCopy() {
    struct Case {
        int w, h;
        float a, b, c;
        int threads;
    };
    const Case cases[] = {{640, 427, 0.0f, -0.02f, 0.0f, 1}, {641, 431, 0.01f, -0.05f, 0.02f, 3}, {333, 500, -0.01f, 0.04f, -0.02f, 5},
                          {97, 64, 0.0f, -0.1f, 0.05f, 4},   {301, 201, 0.002f, -0.012f, 0.0f, 2}, {7, 5, 0.0f, -0.05f, 0.0f, 3}};
    for (const Case& k : cases) {
        auto img = noisy(k.w, k.h, 7u + static_cast<unsigned>(k.w));
        auto ref = img;
        redline::DetailSpec s;
        s.distort = true;
        s.distA = k.a;
        s.distB = k.b;
        s.distC = k.c;
        s.threads = k.threads;
        redline::DetailStats st;
        check(redline::applyDetail(img.data(), k.w, k.h, static_cast<std::size_t>(k.w) * 4, s, st).empty(), "strip distortion ok");
        refDistort(ref, k.w, k.h, k.a, k.b, k.c);
        check(img == ref, "strip distortion is bit-identical to the full-copy version");
        check(st.peakBytes < 0.25 * k.w * k.h * 4 + 64 * 1024, "strip distortion holds far less than a frame copy");
    }
    // Sharpening: the result does not depend on the band count (bands meet at halo rows) and holds no full plane.
    auto base = noisy(500, 333, 3u);
    std::vector<std::uint8_t> out1;
    for (int th : {1, 2, 3, 7}) {
        auto img = base;
        redline::DetailSpec s;
        s.sharpenSigma = 1.217f;
        s.sharpenAmount = 0.707f;
        s.sharpenThreshold = 8.0f / 255.0f;
        s.threads = th;
        redline::DetailStats st;
        check(redline::applyDetail(img.data(), 500, 333, 500 * 4, s, st).empty(), "strip sharpen ok");
        if (out1.empty()) {
            out1 = img;
        } else {
            check(img == out1, "strip sharpen: identical for any band count");
        }
        check(st.peakBytes < 0.1 * 500 * 333 * 4 * th + 64 * 1024, "strip sharpen holds no full-size plane");
    }
}

}  // namespace

int main() {
    testOffIsIdentity();
    testSharpenFlatAndEdges();
    testGrainDeterministicAndThreadSafe();
    testDistortion();
    testGrainNormal();
    testStripsMatchFullCopy();
    if (failures == 0) std::printf("detail_test: all passed\n");
    return failures == 0 ? 0 : 1;
}
