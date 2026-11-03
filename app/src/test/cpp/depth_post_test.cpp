// B30: depth pre/post-processing invariants (depth_post.cpp). Parity with the Python reference is in
// calibration/tests/test_depth_post.py (through depth_post_tool).
#include <chrono>
#include <cmath>
#include <cstdio>
#include <vector>

#include "depth_post.h"

namespace {

int failures = 0;

void expect(bool ok, const char* what) {
    if (!ok) {
        std::printf("FAIL: %s\n", what);
        ++failures;
    }
}

constexpr int kCw = 420, kCh = 280, kSw = 256, kSh = 144;

void canvasFits() {
    redline::DepthCanvas c = redline::depthCanvas(2910, 1939, 0, kCw, kCh);
    expect(c.ax == 0 && c.ay == 0 && c.aw == 420 && c.ah == 280, "landscape 3:2 fills the canvas");
    c = redline::depthCanvas(2910, 1939, 90, kCw, kCh);
    expect(c.aw == 187 && c.ah == 280 && c.ax == 116 && c.ay == 0, "portrait is letterboxed 187x280 at x 116");
    c = redline::depthCanvas(4000, 3000, 270, kCw, kCh);
    expect(c.aw == 210 && c.ah == 280 && c.ax == 105, "4:3 portrait 210x280");
}

// A still whose pixel (x, y) encodes x in R and y in G (scaled), with a white marker block in the still's top-left corner.
std::vector<std::uint8_t> markerStill(int w, int h) {
    std::vector<std::uint8_t> px(static_cast<std::size_t>(w) * h * 4);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            std::uint8_t* p = &px[(static_cast<std::size_t>(y) * w + x) * 4];
            const bool mark = x < w / 8 && y < h / 8;
            p[0] = mark ? 255 : static_cast<std::uint8_t>(x * 200 / w);
            p[1] = mark ? 255 : static_cast<std::uint8_t>(y * 200 / h);
            p[2] = mark ? 255 : 30;
            p[3] = 255;
        }
    }
    return px;
}

void prepRotates() {
    const int w = 600, h = 400;
    std::vector<std::uint8_t> px = markerStill(w, h);
    std::vector<float> canvas(kCw * kCh * 3), seg(kSw * kSh * 3);
    for (int rot : {0, 90, 180, 270}) {
        const redline::DepthCanvas c = redline::depthCanvas(w, h, rot, kCw, kCh);
        const std::string err = redline::depthPrep(px.data(), w, h, static_cast<std::size_t>(w) * 4, c, canvas.data(), seg.data(), kSw, kSh);
        expect(err.empty(), "prep ok");
        // Where does the still's top-left marker land upright? rot 0: top-left; 90 (clockwise): top-right;
        // 180: bottom-right; 270: bottom-left.
        const int cx = rot == 0 || rot == 270 ? c.ax + 2 : c.ax + c.aw - 3;
        const int cy = rot == 0 || rot == 90 ? c.ay + 2 : c.ay + c.ah - 3;
        const float* p = &canvas[(static_cast<std::size_t>(cy) * kCw + cx) * 3];
        expect(p[2] > 0.99f, "marker at the rotated corner");
        if (c.ax > 0) expect(std::fabs(canvas[(140 * kCw + 1) * 3] - 0.5f) < 1e-6f, "letterbox is mid grey");
    }
}

void analysisCues() {
    const int w = 200, h = 120;
    std::vector<float> depth(w * h), mask(w * h, 0.0f), rgb(w * h * 3), conf(w * h);
    // Flat depth: nothing to separate.
    for (int i = 0; i < w * h; ++i) {
        depth[i] = 2.0f;
        rgb[i * 3] = rgb[i * 3 + 1] = rgb[i * 3 + 2] = 0.4f;
    }
    redline::DepthAnalysis a = redline::analyseDepth(depth.data(), mask.data(), rgb.data(), w, h, conf.data());
    expect(a.spread < 1e-6f && !a.usable && a.confidence == 0.0f, "flat depth is not usable");
    // A near block (the person) on a far ramp, with the image edge where the depth edge is.
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const bool in = x >= 60 && x < 120 && y >= 20;
            depth[y * w + x] = in ? 5.0f : 0.5f + 1.5f * static_cast<float>(h - y) / h;
            mask[y * w + x] = in ? 1.0f : 0.0f;
            const float v = in ? 0.8f : 0.2f;
            rgb[(y * w + x) * 3] = rgb[(y * w + x) * 3 + 1] = rgb[(y * w + x) * 3 + 2] = v;
        }
    }
    a = redline::analyseDepth(depth.data(), mask.data(), rgb.data(), w, h, conf.data());
    std::printf("depth_post: block scene conf %.3f spread %.3f edge %.3f person %.3f nearer %.3f\n", a.confidence, a.spread, a.edgeAgree,
                a.personCoverage, a.personNearer);
    expect(a.usable && a.confidence > 0.8f, "near person, aligned edges: usable");
    expect(a.personNearer > 0.95f, "the person is nearer than the ring");
    expect(conf[30 * w + 60] < 0.2f && conf[60 * w + 90] > 0.9f, "per-pixel confidence low on the depth edge, high inside");
    // The same person behind its background: the depth is wrong where it matters.
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            if (mask[y * w + x] > 0.5f) depth[y * w + x] = 0.3f;
        }
    }
    a = redline::analyseDepth(depth.data(), mask.data(), rgb.data(), w, h, conf.data());
    expect(a.personNearer < 0.05f && !a.usable, "person behind the background: not usable");
}

void finishOrients() {
    const int w = 600, h = 400;
    for (int rot : {0, 90, 180, 270}) {
        const redline::DepthCanvas c = redline::depthCanvas(w, h, rot, kCw, kCh);
        std::vector<float> depth(kCw * kCh, 1.0f), seg(kSw * kSh, 0.0f), canvas(kCw * kCh * 3, 0.3f);
        // Mark the upright position of the still's top-left corner as the nearest point.
        const int ux = rot == 0 || rot == 270 ? c.ax : c.ax + c.aw - 4;
        const int uy = rot == 0 || rot == 90 ? c.ay : c.ay + c.ah - 4;
        for (int y = uy; y < uy + 4; ++y) {
            for (int x = ux; x < ux + 4; ++x) depth[y * kCw + x] = 9.0f;
        }
        std::vector<std::uint16_t> out;
        int ow = 0, oh = 0;
        redline::DepthAnalysis a;
        redline::DetailSpec spec;
        const std::string err = redline::depthFinish(depth.data(), seg.data(), kSw, kSh, canvas.data(), c, w, h, spec, out, ow, oh, a);
        expect(err.empty(), "finish ok");
        const bool swap = rot == 90 || rot == 270;
        expect(ow == (swap ? c.ah : c.aw) && oh == (swap ? c.aw : c.ah), "output in the still's orientation");
        expect(out[(1 * ow + 1) * 3] == 65535, "the still's top-left corner is the nearest point");
        expect(out[((oh - 2) * ow + ow - 2) * 3] == 0, "the opposite corner is not");
    }
}

void warpKeepsCentre() {
    // A float map warped with a barrel distortion keeps its centre value and stays within the input range.
    const int w = 64, h = 42;
    std::vector<float> m(w * h);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) m[y * w + x] = static_cast<float>(x) / (w - 1);
    }
    redline::DetailSpec s;
    s.distort = true;
    s.distA = 0.0f;
    s.distB = -0.02f;
    s.distC = 0.0f;
    const float centre = m[(h / 2) * w + w / 2];
    const std::string err = redline::warpMapFloat(m.data(), w, h, w, 2910, 1939, s);
    expect(err.empty(), "warp ok");
    expect(std::fabs(m[(h / 2) * w + w / 2] - centre) < 0.03f, "centre kept");
    float lo = 1, hi = 0;
    for (float v : m) {
        lo = std::min(lo, v);
        hi = std::max(hi, v);
    }
    expect(lo >= 0.0f && hi <= 1.0f, "range kept");
}

void timing() {
    const int w = 2910, h = 1939;
    std::vector<std::uint8_t> px = markerStill(w, h);
    std::vector<float> canvas(kCw * kCh * 3), seg(kSw * kSh * 3), depth(kCw * kCh), segOut(kSw * kSh, 0.0f);
    const redline::DepthCanvas c = redline::depthCanvas(w, h, 0, kCw, kCh);
    auto t0 = std::chrono::steady_clock::now();
    redline::depthPrep(px.data(), w, h, static_cast<std::size_t>(w) * 4, c, canvas.data(), seg.data(), kSw, kSh);
    auto t1 = std::chrono::steady_clock::now();
    for (int i = 0; i < kCw * kCh; ++i) depth[i] = canvas[i * 3] + 0.1f;
    std::vector<std::uint16_t> out;
    int ow = 0, oh = 0;
    redline::DepthAnalysis a;
    redline::DetailSpec spec;
    spec.distort = true;
    spec.distB = -0.01f;
    redline::depthFinish(depth.data(), segOut.data(), kSw, kSh, canvas.data(), c, w, h, spec, out, ow, oh, a);
    auto t2 = std::chrono::steady_clock::now();
    std::printf("depth_post: prep %.1f ms, finish %.1f ms (2910x1939, one thread)\n",
                std::chrono::duration<double, std::milli>(t1 - t0).count(), std::chrono::duration<double, std::milli>(t2 - t1).count());
}

}  // namespace

int main() {
    canvasFits();
    prepRotates();
    analysisCues();
    finishOrients();
    warpKeepsCentre();
    timing();
    if (failures == 0) std::printf("depth_post_test: all passed\n");
    return failures == 0 ? 0 : 1;
}
