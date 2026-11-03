// Host-side test of the B25 Ultra HDR gain map (raw_pipeline GainMapAccum, gainmap.h finishGainMap, detail.h
// warpGainMap). Built with -ffp-contract=off so "bit-identical" means the same IEEE result on every host compiler.
//   gainmap_test          invariants (CTest)
//   gainmap_test bench    timing of the render with and without the gain map at the baseline 24 mm still size
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "detail.h"
#include "gainmap.h"
#include "raw_pipeline.h"

namespace {

int failures = 0;

void check(bool ok, const char* what) {
    if (!ok) {
        std::printf("FAIL: %s\n", what);
        ++failures;
    }
}

double srgbEncode(double l) {
    l = std::min(std::max(l, 0.0), 1.0);
    return l <= 0.0031308 ? 12.92 * l : 1.055 * std::pow(l, 1.0 / 2.4) - 0.055;
}

// A curve with a knee-like roll-off: decoded L(x) = 1.15 x / (x + 0.15), clipped at 1 (log-log slope 0.30 at the knee).
struct Fixture {
    int w, h;
    std::vector<std::uint16_t> raw;
    std::vector<float> curve;
    std::vector<float> lut;
    std::vector<float> vignette;
    redline::RawInput in;
    redline::RenderSpec spec;

    Fixture(int width, int height) : w(width), h(height) {
        raw.assign(static_cast<std::size_t>(w) * h, 0);
        in.width = w;
        in.height = h;
        in.rowStride = w;
        in.cfa = 0;
        for (float& b : in.black) b = 64.0f;
        in.white = 1023.0f;
        const int n = 1024;
        curve.resize(n);
        for (int i = 0; i < n; ++i) {
            const double x = std::exp2(-14.0 + 14.0 * i / (n - 1));
            curve[i] = static_cast<float>(srgbEncode(1.15 * x / (x + 0.15)));
        }
        spec.curve = curve.data();
        spec.curvePoints = n;
        spec.curveLo = -14.0f;
        spec.curveHi = 0.0f;
        const int s = 17;
        lut.resize(static_cast<std::size_t>(s) * s * s * 3);
        std::size_t k = 0;
        for (int b = 0; b < s; ++b)
            for (int g = 0; g < s; ++g)
                for (int r = 0; r < s; ++r) {  // a mild non-identity look so the LUT path is exercised
                    lut[k++] = std::pow(r / (s - 1.0f), 1.05f);
                    lut[k++] = g / (s - 1.0f);
                    lut[k++] = std::pow(b / (s - 1.0f), 0.95f);
                }
        spec.lut = lut.data();
        spec.lutSize = s;
        spec.crop = {0, 0, w, h};
        spec.outWidth = w;
        spec.outHeight = h;
        spec.gain = 4.0f;  // a 2-stop under-exposed capture brought up, as the burst plans it
        vignette = {1.0f, 1.05f, 1.2f, 1.45f};
    }

    // Horizontal ramp of scene brightness (left dark, right clipped) with texture, every CFA colour alike.
    void fillRamp() {
        for (int y = 0; y < h; ++y)
            for (int x = 0; x < w; ++x) {
                const double t = static_cast<double>(x) / (w - 1);
                const double tex = 1.0 + 0.15 * std::sin(0.7 * x) * std::cos(0.45 * y);
                const double v = std::min(1.0, 0.002 * std::pow(600.0, t) * tex);
                raw[static_cast<std::size_t>(y) * w + x] = static_cast<std::uint16_t>(std::lround(64 + v * (1023 - 64)));
            }
        in.data = raw.data();
    }

    void fillFlat(double v) {
        std::fill(raw.begin(), raw.end(), static_cast<std::uint16_t>(std::lround(64 + v * (1023 - 64))));
        in.data = raw.data();
    }

    int cellsW() const { return (spec.outWidth + 3) / 4; }
    int cellsH() const { return (spec.outHeight + 3) / 4; }

    std::vector<std::uint8_t> render(redline::GainMapAccum* gm, std::vector<float>* sums, std::string& err) {
        std::vector<std::uint8_t> out(static_cast<std::size_t>(spec.outWidth) * spec.outHeight * 4);
        if (gm != nullptr) {
            sums->assign(static_cast<std::size_t>(cellsW()) * cellsH(), 123.0f);  // the render must zero them
            gm->sums = sums->data();
            gm->cellsW = cellsW();
            gm->cellsH = cellsH();
        }
        spec.gainMap = gm;
        err = redline::renderRaw(in, spec, out.data(), static_cast<std::size_t>(spec.outWidth) * 4);
        spec.gainMap = nullptr;
        return out;
    }
};

// SDR output bit-identical with and without the gain map, single and multi-threaded, full size and downscaled,
// with and without the vignette; the cell sums are identical whatever the thread count (cell-aligned bands).
void testSdrUnchanged() {
    Fixture f(200, 134);
    f.fillRamp();
    for (int vig = 0; vig < 2; ++vig) {
        f.spec.vignette = vig ? f.vignette.data() : nullptr;
        f.spec.vignettePoints = vig ? static_cast<int>(f.vignette.size()) : 0;
        for (int down : {1, 2, 3}) {
            f.spec.outWidth = f.w / down;
            f.spec.outHeight = f.h / down;
            std::vector<float> ref;
            for (int threads : {1, 3, 4, 7}) {
                f.spec.threads = threads;
                std::string e1, e2;
                const auto plain = f.render(nullptr, nullptr, e1);
                redline::GainMapAccum gm;
                std::vector<float> sums;
                const auto withMap = f.render(&gm, &sums, e2);
                check(e1.empty() && e2.empty(), "renders succeed");
                check(plain == withMap, "SDR output bit-identical with the gain map");
                if (ref.empty()) {
                    ref = sums;
                } else {
                    check(std::memcmp(ref.data(), sums.data(), ref.size() * sizeof(float)) == 0, "cell sums independent of threads");
                }
                check(gm.slope > 0.25f && gm.slope < 0.35f, "slope measured on the curve (0.30 for this curve)");
            }
        }
    }
    f.spec.vignette = nullptr;
    f.spec.vignettePoints = 0;
}

// Gains: zero on the dark side, rising monotonically (in the mean over a column of cells) across the bright side.
void testGainShape() {
    Fixture f(240, 160);
    f.fillRamp();
    f.spec.threads = 2;
    redline::GainMapAccum gm;
    std::vector<float> sums;
    std::string err;
    f.render(&gm, &sums, err);
    check(err.empty(), "ramp render");
    const int cw = f.cellsW(), ch = f.cellsH();
    std::vector<double> col(cw, 0.0);
    for (int cy = 0; cy < ch; ++cy)
        for (int cx = 0; cx < cw; ++cx) col[cx] += sums[static_cast<std::size_t>(cy) * cw + cx] / 16.0 / ch;
    check(col[0] == 0.0 && col[cw / 4] == 0.0, "no gain below the knee");
    check(col[cw - 1] > 0.5, "bright end gains more than half a stop");
    int drops = 0;
    for (int i = 1; i < cw; ++i) drops += col[i] + 0.02 < col[i - 1] ? 1 : 0;
    check(drops == 0, "gain rises with scene brightness");
    // Expected at the clip: x = gain * luma(1) = 4 (the neutral identity matrix, luma 1) -> e = 2:
    // log2 L_k + p (2 - e_k) - log2 L(clip) with L(x) = 1.15 x / (x + 0.15) clipped at 1.
    const double xk = std::exp2(-1.5), lk = 1.15 * xk / (xk + 0.15);
    const double expect = std::log2(lk) + gm.slope * (2.0 + 1.5) - 0.0;
    check(std::fabs(col[cw - 1] - expect) < 0.08, "clipped highlight gain matches the knee continuation");

    // finish: p99.9 headroom, capped, encoded
    std::vector<std::uint8_t> map(static_cast<std::size_t>(cw) * ch, 7);
    redline::GainMapResult r;
    err = redline::finishGainMap(sums.data(), cw, ch, f.spec.outWidth, f.spec.outHeight, 2.0f, 0.2f, map.data(), cw, r);
    check(err.empty() && r.written, "map written");
    check(r.headroomStops <= 2.0f && r.headroomStops > 0.5f, "headroom in range");
    check(r.p999Stops >= r.headroomStops, "headroom is the capped p99.9");
    check(map[0] == 0, "dark cell encodes 0");
    check(map[static_cast<std::size_t>(ch / 2) * cw + cw - 1] >= 250, "brightest cells encode near 255");
    redline::GainMapResult capped;
    std::vector<std::uint8_t> map2(map.size());
    redline::finishGainMap(sums.data(), cw, ch, f.spec.outWidth, f.spec.outHeight, 0.5f, 0.2f, map2.data(), cw, capped);
    check(capped.written && capped.headroomStops == 0.5f, "cap applies");
}

void testSkip() {
    Fixture f(64, 48);
    f.fillFlat(0.02);  // x = 4 * 0.02 = 0.08 < knee 0.354
    redline::GainMapAccum gm;
    std::vector<float> sums;
    std::string err;
    f.render(&gm, &sums, err);
    check(err.empty(), "flat render");
    bool allZero = true;
    for (float v : sums) allZero = allZero && v == 0.0f;
    check(allZero, "no highlights: all sums zero");
    std::vector<std::uint8_t> map(sums.size());
    redline::GainMapResult r;
    redline::finishGainMap(sums.data(), f.cellsW(), f.cellsH(), f.w, f.h, 2.0f, 0.2f, map.data(), f.cellsW(), r);
    check(!r.written && r.skip == "no highlights above the knee", "skipped: no highlights");
    // Just above the knee: a tiny gain, below 0.2 stops
    f.fillFlat(0.1);  // x = 0.4, a hair above the knee
    f.render(&gm, &sums, err);
    redline::finishGainMap(sums.data(), f.cellsW(), f.cellsH(), f.w, f.h, 2.0f, 0.2f, map.data(), f.cellsW(), r);
    check(!r.written && r.skip.rfind("headroom 0.", 0) == 0, "skipped: headroom below 0.2 stops");
    // bad inputs
    check(!redline::finishGainMap(sums.data(), f.cellsW() + 1, f.cellsH(), f.w, f.h, 2.0f, 0.2f, map.data(), f.cellsW() + 1, r).empty(),
          "mismatched cells refused");
    redline::GainMapAccum bad;
    bad.sums = sums.data();
    bad.cellsW = 1;
    bad.cellsH = 1;
    f.spec.gainMap = &bad;
    std::vector<std::uint8_t> out(static_cast<std::size_t>(f.w) * f.h * 4);
    check(!redline::renderRaw(f.in, f.spec, out.data(), static_cast<std::size_t>(f.w) * 4).empty(), "bad cells refused by the render");
    f.spec.gainMap = nullptr;
}

// The linear (super-resolution) render: same invariants.
void testLinear() {
    const int w = 150, h = 100;
    std::vector<float> planes(static_cast<std::size_t>(w) * h * 3);
    for (int c = 0; c < 3; ++c)
        for (int y = 0; y < h; ++y)
            for (int x = 0; x < w; ++x) {
                const double t = static_cast<double>(x) / (w - 1);
                planes[(static_cast<std::size_t>(c) * h + y) * w + x] =
                    static_cast<float>(std::min(1.0, 0.003 * std::pow(400.0, t) * (1.0 + 0.1 * std::sin(0.9 * y + c))));
            }
    redline::LinearRgb lin;
    lin.data = planes.data();
    lin.width = w;
    lin.height = h;
    lin.rowStride = w;
    lin.planeStride = static_cast<std::size_t>(w) * h;
    Fixture f(w, h);
    std::vector<float> ref;
    for (int threads : {1, 4, 6}) {
        f.spec.threads = threads;
        std::vector<std::uint8_t> a(static_cast<std::size_t>(w) * h * 4), b(a.size());
        check(redline::renderLinear(lin, f.spec, a.data(), static_cast<std::size_t>(w) * 4).empty(), "linear render");
        redline::GainMapAccum gm;
        std::vector<float> sums(static_cast<std::size_t>(f.cellsW()) * f.cellsH(), -5.0f);
        gm.sums = sums.data();
        gm.cellsW = f.cellsW();
        gm.cellsH = f.cellsH();
        f.spec.gainMap = &gm;
        check(redline::renderLinear(lin, f.spec, b.data(), static_cast<std::size_t>(w) * 4).empty(), "linear render with map");
        f.spec.gainMap = nullptr;
        check(a == b, "linear SDR bit-identical with the gain map");
        if (ref.empty()) {
            ref = sums;
            check(*std::max_element(sums.begin(), sums.end()) > 0.0f, "linear gains present");
        } else {
            check(std::memcmp(ref.data(), sums.data(), ref.size() * sizeof(float)) == 0, "linear sums independent of threads");
        }
    }
}

void testWarp() {
    const int gw = 100, gh = 67, iw = 400, ih = 267;
    std::vector<std::uint8_t> map(static_cast<std::size_t>(gw) * gh, 180);
    redline::DetailSpec s;
    std::vector<std::uint8_t> before = map;
    check(redline::warpGainMap(map.data(), gw, gh, gw, iw, ih, s).empty() && map == before, "no distortion: untouched");
    s.distort = true;
    s.distA = 0.0f;
    s.distB = -0.02f;
    s.distC = 0.0f;
    check(redline::warpGainMap(map.data(), gw, gh, gw, iw, ih, s).empty(), "warp runs");
    bool flat = true;
    for (auto v : map) flat = flat && v == 180;
    check(flat, "a flat map stays flat");
    // A bright spot at the centre stays at the centre; one near a corner moves (barrel/pincushion correction).
    std::fill(map.begin(), map.end(), 0);
    for (int y = gh / 2 - 1; y <= gh / 2 + 1; ++y)
        for (int x = gw / 2 - 1; x <= gw / 2 + 1; ++x) map[static_cast<std::size_t>(y) * gw + x] = 255;
    map[5 * gw + 5] = 255;
    redline::warpGainMap(map.data(), gw, gh, gw, iw, ih, s);
    check(map[static_cast<std::size_t>(gh / 2) * gw + gw / 2] == 255, "centre stays");
    check(!redline::warpGainMap(map.data(), 1, 1, 1, iw, ih, s).empty(), "tiny map refused");
}

double msSince(std::chrono::steady_clock::time_point t) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t).count();
}

// The baseline 24 mm still: 4000x3000 RAW, 3:2 crop 4000x2666 rendered at full size.
int bench(int threads, bool bright) {
    Fixture f(4000, 3000);
    if (bright) {
        f.fillFlat(0.5);  // worst case: every pixel above the knee
    } else {
        f.fillRamp();
    }
    f.spec.crop = {0, 166, 4000, 2666};
    f.spec.outWidth = 4000;
    f.spec.outHeight = 2666;
    f.spec.threads = threads;
    std::string err;
    double plain = 1e9, mapped = 1e9, finish = 1e9;
    std::vector<float> sums;
    for (int rep = 0; rep < 3; ++rep) {
        auto t = std::chrono::steady_clock::now();
        f.render(nullptr, nullptr, err);
        plain = std::min(plain, msSince(t));
        redline::GainMapAccum gm;
        t = std::chrono::steady_clock::now();
        f.render(&gm, &sums, err);
        mapped = std::min(mapped, msSince(t));
        std::vector<std::uint8_t> map(static_cast<std::size_t>(f.cellsW()) * f.cellsH());
        redline::GainMapResult r;
        t = std::chrono::steady_clock::now();
        redline::finishGainMap(sums.data(), f.cellsW(), f.cellsH(), 4000, 2666, 2.0f, 0.2f, map.data(), f.cellsW(), r);
        finish = std::min(finish, msSince(t));
    }
    std::size_t lit = 0;
    for (float v : sums) lit += v > 0.0f ? 1 : 0;
    std::printf("%s threads %d: render %.1f ms, with gain map %.1f ms (+%.1f), finish %.1f ms; cells above knee %.0f%%\n", bright ? "flat bright" : "ramp", threads, plain,
                mapped, mapped - plain, finish, 100.0 * static_cast<double>(lit) / static_cast<double>(sums.size()));
    return 0;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc > 1 && std::string(argv[1]) == "bench") {
        for (int t : {1, 4}) bench(t, false);
        bench(1, true);
        return 0;
    }
    testSdrUnchanged();
    testGainShape();
    testSkip();
    testLinear();
    testWarp();
    if (failures == 0) std::printf("gainmap_test: all passed\n");
    return failures == 0 ? 0 : 1;
}
