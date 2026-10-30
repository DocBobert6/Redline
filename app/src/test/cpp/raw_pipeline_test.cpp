// Host-side unit test of the native RAW pipeline (B21). Built and run by CI with CTest next to the parser test.
// Pixel parity against the Python reference is checked separately (calibration/tests/test_raw_pipeline.py).
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "raw_pipeline.h"

namespace {

int failures = 0;

void check(bool ok, const char* what) {
    if (!ok) {
        std::printf("FAIL: %s\n", what);
        ++failures;
    }
}

struct Fixture {
    int w = 48, h = 32;
    std::vector<std::uint16_t> raw;
    std::vector<float> curve;
    std::vector<float> lut;
    redline::RawInput in;
    redline::RenderSpec spec;

    explicit Fixture(int cfa = 0) {
        raw.assign(static_cast<std::size_t>(w) * h, 0);
        in.width = w;
        in.height = h;
        in.rowStride = w;
        in.cfa = cfa;
        for (float& b : in.black) b = 64.0f;
        in.white = 1023.0f;
        // Identity curve on the encoded scale is enough for structural checks: y = x, sampled in log2.
        const int n = 256;
        curve.resize(n);
        for (int i = 0; i < n; ++i) curve[i] = std::exp2(-14.0f + 14.0f * i / (n - 1));
        spec.curve = curve.data();
        spec.curvePoints = n;
        spec.curveLo = -14.0f;
        spec.curveHi = 0.0f;
        const int s = 9;
        lut.resize(static_cast<std::size_t>(s) * s * s * 3);
        std::size_t k = 0;
        for (int b = 0; b < s; ++b)
            for (int g = 0; g < s; ++g)
                for (int r = 0; r < s; ++r) {
                    lut[k++] = r / (s - 1.0f);
                    lut[k++] = g / (s - 1.0f);
                    lut[k++] = b / (s - 1.0f);
                }
        spec.lut = lut.data();
        spec.lutSize = s;
        spec.crop = {0, 0, w, h};
        spec.outWidth = w;
        spec.outHeight = h;
    }

    // Fills a mosaic whose per-colour normalised value is v[colour].
    void fill(const float v[3]) {
        static const int layouts[4][4] = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};
        for (int y = 0; y < h; ++y)
            for (int x = 0; x < w; ++x) {
                const int colour = layouts[in.cfa][((y & 1) << 1) | (x & 1)];
                raw[static_cast<std::size_t>(y) * w + x] = static_cast<std::uint16_t>(std::lround(64 + v[colour] * (1023 - 64)));
            }
        in.data = raw.data();
    }

    std::vector<std::uint8_t> render(std::string& err) {
        std::vector<std::uint8_t> out(static_cast<std::size_t>(spec.outWidth) * spec.outHeight * 4);
        err = redline::renderRaw(in, spec, out.data(), static_cast<std::size_t>(spec.outWidth) * 4);
        return out;
    }
};

void testValidate() {
    Fixture f;
    check(!redline::validate(f.in).empty(), "null data refused");
    const float v[3] = {0.2f, 0.2f, 0.2f};
    f.fill(v);
    check(redline::validate(f.in).empty(), "valid input accepted");
    f.in.white = 10.0f;
    check(!redline::validate(f.in).empty(), "white <= black refused");
    f.in.white = 1023.0f;
    f.in.cfa = 5;
    check(!redline::validate(f.in).empty(), "bad CFA refused");
    f.in.cfa = 0;
    std::string err;
    f.spec.crop = {0, 0, f.w + 2, f.h};
    f.render(err);
    check(!err.empty(), "crop outside refused");
    f.spec.crop = {0, 0, f.w, f.h};
    f.spec.outWidth = f.w * 2;
    f.render(err);
    check(!err.empty(), "upscale refused");
}

// A flat neutral field must render flat and neutral for every CFA layout, at full size and downscaled.
void testFlatNeutral() {
    for (int cfa = 0; cfa < 4; ++cfa) {
        Fixture f(cfa);
        // Camera neutral (0.5, 1, 0.7): the balanced value is the same in every channel.
        const float neutral[3] = {0.5f, 1.0f, 0.7f};
        const float v[3] = {0.18f * 0.5f, 0.18f, 0.18f * 0.7f};
        f.fill(v);
        for (int i = 0; i < 3; ++i) f.spec.colour.neutral[i] = neutral[i];
        for (int threads : {1, 3}) {
            for (int down : {1, 2}) {
                f.spec.threads = threads;
                f.spec.outWidth = f.w / down;
                f.spec.outHeight = f.h / down;
                std::string err;
                const auto out = f.render(err);
                check(err.empty(), "flat render ok");
                int worst = 0, spread = 0;
                for (std::size_t i = 0; i + 3 < out.size(); i += 4) {
                    worst = std::max({worst, std::abs(out[i] - out[i + 1]), std::abs(out[i + 1] - out[i + 2])});
                    spread = std::max(spread, std::abs(out[i + 1] - out[1]));
                }
                check(worst <= 1, "flat field stays neutral");
                check(spread <= 1, "flat field stays flat");
                check(std::abs(out[1] - 46) <= 1, "0.18 renders to 0.18 * 255 with the identity curve and LUT");
            }
        }
    }
}

// Threading must not change a single byte.
void testThreadsDeterministic() {
    Fixture f(1);
    for (std::size_t i = 0; i < f.raw.size(); ++i) f.raw[i] = static_cast<std::uint16_t>(64 + (i * 7919) % 900);
    f.in.data = f.raw.data();
    const float m[9] = {1.6f, -0.4f, -0.2f, -0.3f, 1.5f, -0.2f, 0.0f, -0.5f, 1.5f};
    for (int i = 0; i < 9; ++i) f.spec.colour.matrix[i] = m[i];
    f.spec.crop = {4, 2, 40, 26};
    f.spec.outWidth = 27;
    f.spec.outHeight = 17;
    f.spec.gain = 1.7f;
    std::string err;
    f.spec.threads = 1;
    const auto a = f.render(err);
    check(err.empty(), "textured render ok");
    f.spec.threads = 4;
    const auto b = f.render(err);
    check(a == b, "1 and 4 threads identical");
}

void testStats() {
    Fixture f;
    const float v[3] = {0.1f, 0.2f, 0.3f};
    f.fill(v);
    redline::RawStats st;
    const std::string err = redline::rawStats(f.in, {0, 0, f.w, f.h}, f.spec.colour, 2, st);
    check(err.empty(), "stats ok");
    const float y = 0.2126729f * 0.1f + 0.7151522f * 0.2f + 0.0721750f * 0.3f;
    check(std::fabs(st.medianY - y) < 2e-3f, "stats median Y");
    check(st.clippedFraction == 0.0f, "nothing clipped");
    check(st.cells == (f.w / 4) * (f.h / 4), "cell count");
    f.spec.colour.neutral[0] = 0.05f;
    redline::rawStats(f.in, {0, 0, f.w, f.h}, f.spec.colour, 2, st);
    check(st.clippedFraction == 1.0f, "red over the neutral counts as clipped");
}

// Lens shading: a uniform gain of 2 doubles the normalised signal.
void testShading() {
    Fixture f;
    const float v[3] = {0.1f, 0.1f, 0.1f};
    f.fill(v);
    std::vector<float> map(3 * 4 * 4, 2.0f);
    f.in.shading = map.data();
    f.in.shadingRows = 3;
    f.in.shadingCols = 4;
    redline::RawStats st;
    redline::rawStats(f.in, {0, 0, f.w, f.h}, f.spec.colour, 1, st);
    check(std::fabs(st.medianY - 0.2f) < 2e-3f, "shading gain applied");
}

}  // namespace

int main() {
    testValidate();
    testFlatNeutral();
    testThreadsDeterministic();
    testStats();
    testShading();
    if (failures == 0) std::printf("raw_pipeline_test: all passed\n");
    return failures == 0 ? 0 : 1;
}
