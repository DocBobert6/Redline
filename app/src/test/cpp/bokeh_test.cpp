// B31 simulated depth of field, host tests: the PLAN 19.5 blur table (model and rendered discs), kernel shape
// (circular wide open, 9 blades stopped down, cat's-eye widths toward the corners), longitudinal CA only at 70 mm f/2.8,
// a synthetic scene (sharp subject edge without halo, background blurred, highlights brighter than a gamma-space blur,
// HDR continuation), guided-filter edge alignment, gating, RGBA vs float, band/thread independence, and timings.
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <cstdlib>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include "bokeh.h"

namespace {

int failures = 0;

void check(bool ok, const std::string& what) {
    if (!ok) {
        ++failures;
        std::printf("FAIL %s\n", what.c_str());
    }
}

// The 19.5 table: background-at-infinity disc, % of frame width at f/2.8.
const int kFocals[4] = {24, 35, 50, 70};
const double kDist[3] = {1, 2, 4};
const double kTable[4][3] = {{0.59, 0.29, 0.14}, {1.26, 0.62, 0.31}, {2.61, 1.27, 0.63}, {5.23, 2.52, 1.24}};

// sRGB-like curve with a shoulder to 1 at 2^hi (the native table layout).
std::vector<float> toneTable(int n, float lo, float hi) {
    std::vector<float> t(static_cast<std::size_t>(n));
    for (int i = 0; i < n; ++i) {
        const double e = lo + (hi - lo) * i / (n - 1);
        const double x = std::exp2(e - hi);
        double y = x <= 0.0031308 ? 12.92 * x : 1.055 * std::pow(x, 1 / 2.4) - 0.055;
        t[static_cast<std::size_t>(i)] = static_cast<float>(std::min(1.0, y * 1.02));
    }
    t.back() = 1.0f;
    return t;
}

// Identity in the encoded domain (C(x) = x / 2^hi): the "gamma-space" blur reference and a linear readout.
std::vector<float> identityTable(int n, float lo, float hi) {
    std::vector<float> t(static_cast<std::size_t>(n));
    for (int i = 0; i < n; ++i) t[static_cast<std::size_t>(i)] = static_cast<float>(std::exp2(lo + (hi - lo) * i / (n - 1) - hi));
    return t;
}

redline::BokehTone tone(const std::vector<float>& t, float lo, float hi) {
    redline::BokehTone o;
    o.curve = t.data();
    o.curvePoints = static_cast<int>(t.size());
    o.curveLo = lo;
    o.curveHi = hi;
    return o;
}

// Equivalent disc diameter of a blurred point (channel c of a w x h float RGB image, point at (px, py)): a uniform
// disc of diameter D has E[r^2] = D^2 / 8, so D = sqrt(8 E[r^2]) with E the intensity-weighted mean.
double rmsDiameter(const std::vector<float>& img, int w, int h, int px, int py, int c) {
    double s = 0, s2 = 0;
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const double v = img[(static_cast<std::size_t>(y) * w + x) * 3 + c];
            if (v <= 0) continue;
            const double r2 = (x - px) * static_cast<double>(x - px) + (y - py) * static_cast<double>(y - py);
            s += v;
            s2 += v * r2;
        }
    return std::sqrt(8.0 * s2 / s);
}

// Disc diameter (px) of a point at infinity, per channel average, at the frame centre.
double renderedDisc(int focal, double n, double s, int w, double* chans = nullptr) {
    const int h = 360;
    const float lo = -14, hi = 0;
    const std::vector<float> idt = identityTable(256, lo, hi);
    const redline::BokehTone t = tone(idt, lo, hi);
    std::vector<float> img(static_cast<std::size_t>(w) * h * 3, 0.0f);
    const int px = w / 2, py = h / 2;
    for (int c = 0; c < 3; ++c) img[(static_cast<std::size_t>(py) * w + px) * 3 + c] = 1.0f;
    const int dw = 120, dh = 9;
    std::vector<float> depth(static_cast<std::size_t>(dw) * dh, 0.0f);
    for (int y = 1; y < 8; ++y)
        for (int x = 6; x < 18; ++x) depth[static_cast<std::size_t>(y) * dw + x] = 1.0f;  // the subject at s
    redline::BokehDepth d;
    d.depth = depth.data();
    d.width = dw;
    d.height = dh;
    d.confidence = 0.9f;
    redline::BokehParams p;
    p.focalMm = static_cast<float>(focal);
    p.aperture = static_cast<float>(n);
    p.focusM = static_cast<float>(s);
    p.focusX = 11.5f / dw;
    p.focusY = 4.5f / dh;
    p.phoneN = 1e6f;
    p.threads = 2;
    redline::BokehStats st;
    const std::string err = redline::bokehRenderFloat(img.data(), w, h, static_cast<std::size_t>(w) * 3, t, d, p, st);
    if (!err.empty() || !st.applied) return -1;
    double sum = 0;
    for (int c = 0; c < 3; ++c) {
        const double f = rmsDiameter(img, w, h, px, py, c);
        if (chans != nullptr) chans[c] = f;
        sum += f;
    }
    return sum / 3.0;
}

void testTable() {
    double worst = 0;
    for (int i = 0; i < 4; ++i) {
        for (int j = 0; j < 3; ++j) {
            const double got = redline::bokehCocMm(kFocals[i], 2.8, kDist[j], 0) / 36.0 * 100.0;
            const double err = std::fabs(got - kTable[i][j]) / kTable[i][j];
            worst = std::max(worst, err);
            check(err < 0.10, "model table " + std::to_string(kFocals[i]) + "mm s=" + std::to_string(kDist[j]));
            const double r4 = redline::bokehCocMm(kFocals[i], 4.0, kDist[j], 0) / redline::bokehCocMm(kFocals[i], 2.8, kDist[j], 0);
            check(std::fabs(r4 - 0.7) < 0.01, "model f/4 ~0.7x");
        }
    }
    std::printf("model vs PLAN 19.5 table: worst error %.2f %%\n", worst * 100);
    // an object at 2s behind focus has half the infinity disc (|d - s| / d)
    check(std::fabs(redline::bokehCocMm(50, 2.8, 2, 4) / redline::bokehCocMm(50, 2.8, 2, 0) - 0.5) < 1e-9, "model finite distance");
    check(std::fabs(redline::bokehPhoneFactor(2.8, 6.0) - std::sqrt(1 - (2.8 / 6) * (2.8 / 6))) < 1e-12, "phone factor");
    check(redline::bokehPhoneFactor(8.0, 6.0) == 0.0, "phone factor stopped down");

    // rendered discs at 6000 px wide (the 24 mm / 4 m disc is 8.6 px there)
    const int w = 6000;
    double worstR = 0;
    for (int i = 0; i < 4; ++i) {
        for (int j = 0; j < 3; ++j) {
            const double want = kTable[i][j] / 100.0 * w;
            const double got = renderedDisc(kFocals[i], 2.8, kDist[j], w);
            const double err = std::fabs(got - want) / want;
            worstR = std::max(worstR, err);
            std::printf("  rendered disc %2dmm f/2.8 s=%gm: %.1f px (table %.1f px, %+.1f %%)\n", kFocals[i], kDist[j], got, want,
                        (got / want - 1) * 100);
            check(err < 0.10, "rendered table " + std::to_string(kFocals[i]) + "mm s=" + std::to_string(kDist[j]));
        }
    }
    const double r28 = renderedDisc(50, 2.8, 1, w), r4 = renderedDisc(50, 4.0, 1, w);
    std::printf("rendered vs table: worst %.1f %%; f/4 / f/2.8 at 50 mm 1 m = %.3f\n", worstR * 100, r4 / r28);
    check(std::fabs(r4 / r28 - 0.7) < 0.07, "rendered f/4 ~0.7x");
}

struct Extent {
    double radial, tangential;
};

Extent extent(const std::vector<double>& k, int K, double ux, double uy) {
    double mx = 0;
    for (double v : k) mx = std::max(mx, v);
    double amin = 1e9, amax = -1e9, bmin = 1e9, bmax = -1e9;
    const int n = 2 * K + 1;
    for (int y = 0; y < n; ++y)
        for (int x = 0; x < n; ++x) {
            if (k[static_cast<std::size_t>(y) * n + x] <= 0.02 * mx) continue;
            const double dx = x - K, dy = y - K;
            const double a = dx * ux + dy * uy, b = -dx * uy + dy * ux;
            amin = std::min(amin, a);
            amax = std::max(amax, a);
            bmin = std::min(bmin, b);
            bmax = std::max(bmax, b);
        }
    return {amax - amin + 1, bmax - bmin + 1};
}

void testKernel() {
    const double r = 30;
    std::vector<double> k;
    int K = 0;
    redline::bokehKernelDense(r, redline::bokehBladeBeta(2.8), 0, 1, 0, k, K);
    double sum = 0;
    for (double v : k) sum += v;
    check(std::fabs(sum - 1) < 1e-6, "kernel sums to 1");
    double wmin = 1e9, wmax = 0;
    for (int i = 0; i < 12; ++i) {
        const double a = i * 3.14159265358979 / 12;
        const double e = extent(k, K, std::cos(a), std::sin(a)).radial;
        wmin = std::min(wmin, e);
        wmax = std::max(wmax, e);
    }
    std::printf("kernel f/2.8 circularity (min/max width over 12 directions): %.3f\n", wmin / wmax);
    check(wmin / wmax > 0.96, "f/2.8 near circular");
    // faint rim, flat centre, no rings: centre / rim ratio and monotone plateau
    const int n = 2 * K + 1;
    const double centre = k[static_cast<std::size_t>(K) * n + K];
    const double rim = k[static_cast<std::size_t>(K) * n + K + static_cast<int>(r * 0.9)];
    std::printf("kernel rim / centre: %.3f\n", rim / centre);
    check(rim / centre > 1.05 && rim / centre < 1.25, "faint brighter rim");
    // stopped down: the 9-blade polygon shows slightly (radius to the support edge per direction)
    auto radial = [&](const std::vector<double>& kk, int KK, double a) {
        const int nn = 2 * KK + 1;
        double mx = 0;
        for (double v : kk) mx = std::max(mx, v);
        double t = 0;
        for (; t < KK; t += 0.05) {
            const double fx = KK + t * std::cos(a), fy = KK + t * std::sin(a);
            const int x = static_cast<int>(std::floor(fx)), y = static_cast<int>(std::floor(fy));
            const double ux = fx - x, uy = fy - y;
            auto at = [&](int xx, int yy) { return kk[static_cast<std::size_t>(yy) * nn + xx]; };
            const double v = (at(x, y) * (1 - ux) + at(x + 1, y) * ux) * (1 - uy) + (at(x, y + 1) * (1 - ux) + at(x + 1, y + 1) * ux) * uy;
            if (v < 0.5 * centre) break;
        }
        (void)mx;
        return t;
    };
    double cmin = 1e9, cmax = 0;
    for (int i = 0; i < 72; ++i) {
        const double t = radial(k, K, i * 3.14159265358979 / 36);
        cmin = std::min(cmin, t);
        cmax = std::max(cmax, t);
    }
    redline::bokehKernelDense(r, redline::bokehBladeBeta(11.0), 0, 1, 0, k, K);
    double pmin = 1e9, pmax = 0;
    for (int i = 0; i < 72; ++i) {
        const double t = radial(k, K, i * 3.14159265358979 / 36);
        pmin = std::min(pmin, t);
        pmax = std::max(pmax, t);
    }
    std::printf("kernel edge radius min/max: f/2.8 %.3f, f/11 %.3f\n", cmin / cmax, pmin / pmax);
    check(cmin / cmax > 0.97, "f/2.8 edge round");
    check(pmin / pmax < cmin / cmax && pmin / pmax > 0.93, "f/11 polygon slightly visible");
    // cat's-eye at the corner (|v| = 1, diagonal)
    const double v = 1 / std::sqrt(2.0);
    struct Cell {
        int f;
        double n, lo, hi;
    } cells[] = {{24, 2.8, 0.60, 0.70}, {70, 2.8, 0.75, 0.85}, {24, 5.6, 0.97, 1.0}, {70, 5.6, 0.97, 1.0}};
    for (const Cell& c : cells) {
        double h, ux, uy;
        redline::bokehCatEye(r, c.f, c.n, v, v, h, ux, uy);
        redline::bokehKernelDense(r, redline::bokehBladeBeta(c.n), h, ux, uy, k, K);
        const Extent e = extent(k, K, ux, uy);
        const double frac = e.radial / (2 * r + 1);
        std::printf("cat's-eye %dmm f/%.1f corner: radial width %.2f of the disc, tangential %.2f\n", c.f, c.n, frac, e.tangential / (2 * r + 1));
        check(frac >= c.lo && frac <= c.hi, "cat's-eye width " + std::to_string(c.f) + " f/" + std::to_string(c.n));
    }
    double h, ux, uy;
    redline::bokehCatEye(r, 24, 4.0, v, v, h, ux, uy);
    redline::bokehKernelDense(r, redline::bokehBladeBeta(4.0), h, ux, uy, k, K);
    const double f4 = extent(k, K, ux, uy).radial / (2 * r + 1);
    check(f4 > 0.70 && f4 < 0.90, "cat's-eye f/4 between");
    redline::bokehCatEye(r, 24, 2.8, 0, 0, h, ux, uy);
    check(h == 0, "no cat's-eye at the centre");
}

// ------------------------------------------------------------------------------------------------ synthetic scene

struct Scene {
    int w = 600, h = 400;
    std::vector<float> img;  // encoded RGB
    std::vector<float> depth, mask;
    int dw = 150, dh = 100;
    int sx0, sx1, sy0, sy1;  // subject rect (full res)
};

Scene makeScene(int w, int h, bool highlights) {
    Scene s;
    s.w = w;
    s.h = h;
    s.img.assign(static_cast<std::size_t>(w) * h * 3, 0.0f);
    s.sx0 = w * 4 / 10;
    s.sx1 = w * 6 / 10;
    s.sy0 = h * 3 / 10;
    s.sy1 = h * 9 / 10;
    std::uint32_t seed = 12345;
    auto rnd = [&]() {
        seed = seed * 1664525u + 1013904223u;
        return (seed >> 8) * (1.0f / 16777216.0f);
    };
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            float* p = &s.img[(static_cast<std::size_t>(y) * w + x) * 3];
            const bool subj = x >= s.sx0 && x < s.sx1 && y >= s.sy0 && y < s.sy1;
            if (subj) {
                p[0] = 0.75f + 0.05f * rnd();
                p[1] = 0.2f;
                p[2] = 0.15f;
            } else {
                const float t = 0.5f + 0.5f * std::sin(x * 0.4f) * std::sin(y * 0.3f);
                p[0] = 0.08f;
                p[1] = 0.25f + 0.15f * t;
                p[2] = 0.35f + 0.1f * t;
            }
        }
    if (highlights) {
        for (int i = 0; i < 6; ++i) {
            const int hx = w / 12 + i * (w / 40), hy = h / 6 + (i % 2) * (h / 10);
            for (int y = hy; y < hy + 2; ++y)
                for (int x = hx; x < hx + 2; ++x)
                    for (int c = 0; c < 3; ++c) s.img[(static_cast<std::size_t>(y) * w + x) * 3 + c] = 1.0f;
        }
    }
    s.depth.assign(static_cast<std::size_t>(s.dw) * s.dh, 0.0f);
    s.mask.assign(static_cast<std::size_t>(s.dw) * s.dh, 0.0f);
    for (int y = 0; y < s.dh; ++y)
        for (int x = 0; x < s.dw; ++x) {
            const double fx = (x + 0.5) * w / s.dw, fy = (y + 0.5) * h / s.dh;
            float d = static_cast<float>(0.05 + 0.25 * std::max(0.0, (fy / h - 0.6) / 0.4));  // background + floor coming closer
            if (fx >= s.sx0 && fx < s.sx1 && fy >= s.sy0 && fy < s.sy1) {
                d = 0.8f;
                s.mask[static_cast<std::size_t>(y) * s.dw + x] = 1.0f;
            }
            s.depth[static_cast<std::size_t>(y) * s.dw + x] = d;
        }
    return s;
}

redline::BokehDepth depthOf(const Scene& s) {
    redline::BokehDepth d;
    d.depth = s.depth.data();
    d.mask = s.mask.data();
    d.width = s.dw;
    d.height = s.dh;
    d.confidence = 0.8f;
    return d;
}

redline::BokehParams portrait(const Scene& s) {
    redline::BokehParams p;
    p.focalMm = 50;
    p.aperture = 2.8f;
    p.focusM = 1.5f;
    p.focusX = 0.5f * (s.sx0 + s.sx1) / s.w;
    p.focusY = 0.5f * (s.sy0 + s.sy1) / s.h;
    p.phoneN = 6.0f;
    p.threads = 2;
    return p;
}

void testScene() {
    const float lo = -14, hi = 1;
    const std::vector<float> tt = toneTable(512, lo, hi);
    const redline::BokehTone t = tone(tt, lo, hi);
    Scene s = makeScene(600, 400, true);
    std::vector<float> out = s.img;
    const redline::BokehDepth d = depthOf(s);
    redline::BokehParams p = portrait(s);
    redline::BokehStats st;
    std::string err = redline::bokehRenderFloat(out.data(), s.w, s.h, static_cast<std::size_t>(s.w) * 3, t, d, p, st);
    check(err.empty() && st.applied, "scene renders: " + err + st.reason);
    std::printf("scene 600x400 50mm f/2.8 1.5 m: max radius %.1f px, layers %d, on person %d, %.1f ms\n", st.maxRadius, st.layers,
                st.onPerson ? 1 : 0, st.totalMs);
    // subject interior: untouched beyond N px of its edge
    const int N = 3;
    double inner = 0;
    for (int y = s.sy0 + N; y < s.sy1 - N; ++y)
        for (int x = s.sx0 + N; x < s.sx1 - N; ++x)
            for (int c = 0; c < 3; ++c)
                inner = std::max(inner, static_cast<double>(std::fabs(out[(static_cast<std::size_t>(y) * s.w + x) * 3 + c] -
                                                                       s.img[(static_cast<std::size_t>(y) * s.w + x) * 3 + c])));
    std::printf("subject interior (>= %d px from the edge) max change: %.5f\n", N, inner);
    check(inner < 1e-3, "subject stays sharp");
    // subject edge stays sharp: the step across the left edge spans <= N px
    {
        const int y = (s.sy0 + s.sy1) / 2;
        const float in = out[(static_cast<std::size_t>(y) * s.w + s.sx0 + N) * 3];
        const float outside = out[(static_cast<std::size_t>(y) * s.w + s.sx0 - N - 1) * 3];
        check(in > 0.7f && outside < 0.2f, "edge step within N px");
    }
    // no halo: red from the subject does not bleed into the background beyond N px
    double bleed = 0;
    for (int y = s.sy0; y < s.sy1; ++y)
        for (int x = 0; x < s.w; ++x) {
            const int dx = x < s.sx0 ? s.sx0 - x : (x >= s.sx1 ? x - s.sx1 + 1 : 0);
            if (dx <= N) continue;
            bleed = std::max(bleed, static_cast<double>(out[(static_cast<std::size_t>(y) * s.w + x) * 3] - 0.08f));
        }
    std::printf("subject red bleed into the background beyond %d px: %.4f\n", N, bleed);
    check(bleed < 0.02, "no halo around the subject");
    // background blurred: texture variance well down
    auto var = [&](const std::vector<float>& im) {
        double m = 0, m2 = 0;
        int n = 0;
        for (int y = 10; y < s.sy0 - 10; ++y)
            for (int x = 10; x < s.sx0 - 30; ++x) {
                const double v = im[(static_cast<std::size_t>(y) * s.w + x) * 3 + 1];
                m += v;
                m2 += v * v;
                ++n;
            }
        m /= n;
        return m2 / n - m * m;
    };
    std::printf("background texture variance: before %.5f after %.5f\n", var(s.img), var(out));
    check(var(out) < 0.5 * var(s.img), "background blurred");

    // highlights: linear-light discs stand out more than the same blur in gamma space. Increment of the disc over the
    // same render without highlights, at 70 mm f/2.8 1 m (radius ~13 px here).
    const std::vector<float> idt = identityTable(512, lo, hi);
    const redline::BokehTone ti = tone(idt, lo, hi);
    redline::BokehParams ph = p;
    ph.focalMm = 70;
    ph.focusM = 1.0f;
    const Scene plain = makeScene(600, 400, false);
    const int hx = s.w / 12 + 1, hy = s.h / 6 + 1;
    auto discInc = [&](const redline::BokehTone& tc, const redline::BokehTone* hdrTone) {
        std::vector<float> a = s.img, b = plain.img;
        redline::bokehRenderFloat(a.data(), s.w, s.h, static_cast<std::size_t>(s.w) * 3, hdrTone ? *hdrTone : tc, d, ph, st);
        redline::bokehRenderFloat(b.data(), s.w, s.h, static_cast<std::size_t>(s.w) * 3, hdrTone ? *hdrTone : tc, d, ph, st);
        double inc = 0;
        for (int y = hy - 4; y <= hy + 4; ++y)
            for (int x = hx - 4; x <= hx + 4; ++x)
                inc += a[(static_cast<std::size_t>(y) * s.w + x) * 3 + 1] - b[(static_cast<std::size_t>(y) * s.w + x) * 3 + 1];
        return inc / 81;
    };
    const double incLin = discInc(t, nullptr), incGam = discInc(ti, nullptr);
    std::printf("highlight disc increment (G, encoded, radius %.1f px): linear light %.4f vs gamma space %.4f\n", st.maxRadius, incLin, incGam);
    check(incLin > 1.5 * incGam, "highlights bloom brighter in linear light");
    // HDR continuation: a gain map over the highlights makes the discs brighter still
    {
        std::vector<float> gain(static_cast<std::size_t>(150) * 100, 0.0f);
        for (int y = 0; y < 100; ++y)
            for (int x = 0; x < 150; ++x) {
                const double fy = (y + 0.5) * s.h / 100, fx = (x + 0.5) * s.w / 150;
                if (fy < s.sy0 && fx < s.sx0) gain[static_cast<std::size_t>(y) * 150 + x] = 2.0f;
            }
        redline::BokehTone th = t;
        th.slope = 0.5f;
        th.kneeEv = -1.5f;
        th.gainLog2 = gain.data();
        th.gainW = 150;
        th.gainH = 100;
        const double incHdr = discInc(t, &th);
        std::printf("highlight disc increment with the HDR gain map: %.4f\n", incHdr);
        check(incHdr > 1.2 * incLin, "HDR continuation brightens highlight discs");

        // B31 gain out: the gain map recomputed from the blurred linear result. Cells inside the sharp subject keep their
        // input value exactly; the dark blurred background (below the knee) drops to 0; the result does not depend on
        // the thread count or the band height.
        std::vector<float> gin = gain;
        const int sc0 = s.sx0 * 150 / s.w + 2, sc1 = s.sx1 * 150 / s.w - 2, sr0 = s.sy0 * 100 / s.h + 2, sr1 = s.sy1 * 100 / s.h - 2;
        for (int y = sr0; y < sr1; ++y)
            for (int x = sc0; x < sc1; ++x) gin[static_cast<std::size_t>(y) * 150 + x] = 1.25f;
        auto gainRun = [&](int threads, int band) {
            std::vector<float> a = s.img, go(gin.size(), -1.0f);
            redline::BokehTone tg = th;
            tg.gainLog2 = gin.data();
            tg.gainOut = go.data();
            redline::BokehParams pg = ph;
            pg.threads = threads;
            pg.band = band;
            redline::BokehStats sg;
            const std::string e = redline::bokehRenderFloat(a.data(), s.w, s.h, static_cast<std::size_t>(s.w) * 3, tg, d, pg, sg);
            check(e.empty() && sg.applied, "gain-out render: " + e + sg.reason);
            return go;
        };
        const std::vector<float> g1 = gainRun(1, 64), g3 = gainRun(3, 32);
        bool finite = true, same = true;
        for (std::size_t i = 0; i < g1.size(); ++i) {
            finite = finite && std::isfinite(g1[i]) && g1[i] >= 0.0f;
            same = same && std::fabs(g1[i] - g3[i]) < 1e-5f;
        }
        check(finite, "gain out finite and >= 0");
        check(same, "gain out independent of threads and bands");
        bool kept = true;
        for (int y = sr0; y < sr1; ++y)
            for (int x = sc0; x < sc1; ++x) kept = kept && g1[static_cast<std::size_t>(y) * 150 + x] == 1.25f;
        check(kept, "gain out keeps the sharp subject's cells");
        // a dark background cell far from the highlights (they sit in the top sixth to quarter of the frame)
        const int by = (s.sy0 * 100 / s.h) - 3, bx = 3;
        std::printf("gain out: dark background cell %.3f (input %.3f), highlight cell max %.3f\n", g1[static_cast<std::size_t>(by) * 150 + bx],
                    gin[static_cast<std::size_t>(by) * 150 + bx], *std::max_element(g1.begin(), g1.end()));
        check(gin[static_cast<std::size_t>(by) * 150 + bx] > 1.0f && g1[static_cast<std::size_t>(by) * 150 + bx] == 0.0f,
              "gain out recomputed below the knee");
    }

    // RGBA path == float path within one code value
    {
        std::vector<std::uint8_t> rgba(static_cast<std::size_t>(s.w) * s.h * 4);
        std::vector<float> q(s.img.size());
        for (std::size_t i = 0; i < static_cast<std::size_t>(s.w) * s.h; ++i) {
            for (int c = 0; c < 3; ++c) {
                const std::uint8_t v = static_cast<std::uint8_t>(s.img[i * 3 + c] * 255.0f + 0.5f);
                rgba[i * 4 + c] = v;
                q[i * 3 + c] = v / 255.0f;
            }
            rgba[i * 4 + 3] = 255;
        }
        redline::bokehRenderFloat(q.data(), s.w, s.h, static_cast<std::size_t>(s.w) * 3, t, d, p, st);
        redline::bokehRender(rgba.data(), s.w, s.h, static_cast<std::size_t>(s.w) * 4, t, d, p, st);
        int worst = 0;
        for (std::size_t i = 0; i < static_cast<std::size_t>(s.w) * s.h; ++i)
            for (int c = 0; c < 3; ++c)
                worst = std::max(worst, std::abs(static_cast<int>(rgba[i * 4 + c]) - static_cast<int>(q[i * 3 + c] * 255.0f + 0.5f)));
        check(worst <= 1, "RGBA matches float within 1 code value (" + std::to_string(worst) + ")");
    }
    // bands and threads do not change the result
    {
        std::vector<float> a = s.img, b = s.img;
        redline::BokehParams pa = p, pb = p;
        pa.threads = 1;
        pa.band = 4096;
        pb.threads = 4;
        pb.band = 24;
        pa.aperture = pb.aperture = 2.0f;  // larger discs: level 2 and 4 in play
        redline::bokehRenderFloat(a.data(), s.w, s.h, static_cast<std::size_t>(s.w) * 3, t, d, pa, st);
        redline::bokehRenderFloat(b.data(), s.w, s.h, static_cast<std::size_t>(s.w) * 3, t, d, pb, st);
        double m = 0;
        for (std::size_t i = 0; i < a.size(); ++i) m = std::max(m, static_cast<double>(std::fabs(a[i] - b[i])));
        check(m < 1e-5, "band / thread independent (" + std::to_string(m) + ")");
    }
    // gating
    {
        std::vector<float> a = s.img;
        redline::BokehDepth dl = d;
        dl.confidence = 0.3f;
        err = redline::bokehRenderFloat(a.data(), s.w, s.h, static_cast<std::size_t>(s.w) * 3, t, dl, p, st);
        check(err.empty() && !st.applied && st.reason.find("confidence") != std::string::npos && a == s.img, "low confidence: not applied");
        redline::BokehParams ps = p;
        ps.aperture = 8.0f;
        err = redline::bokehRenderFloat(a.data(), s.w, s.h, static_cast<std::size_t>(s.w) * 3, t, d, ps, st);
        check(err.empty() && !st.applied && st.reason.find("phone") != std::string::npos && a == s.img, "phone deeper: not applied");
    }
}

// Longitudinal CA: behind focus G spreads wider (green fringe), in front R/B (magenta), only at 70 mm f/2.8.
void testCa() {
    double ch[3];
    const double d70 = renderedDisc(70, 2.8, 2, 3000, ch);
    std::printf("70mm f/2.8 background disc equivalent diameter R %.1f G %.1f B %.1f (mean %.1f)\n", ch[0], ch[1], ch[2], d70);
    check(ch[1] > ch[0] * 1.04 && ch[1] > ch[2] * 1.04, "green wider behind focus at 70 f/2.8");
    renderedDisc(50, 2.8, 1, 3000, ch);
    check(std::fabs(ch[1] - ch[0]) < 0.5, "no CA at 50 mm");
    renderedDisc(70, 4.0, 1, 3000, ch);
    check(std::fabs(ch[1] - ch[0]) < 0.5, "no CA at 70 f/4");
    // in front of focus: focus on the background, a bright point at the near plane
    const int w = 3000, h = 400;
    const float lo = -14, hi = 0;
    const std::vector<float> idt = identityTable(256, lo, hi);
    const redline::BokehTone t = tone(idt, lo, hi);
    std::vector<float> img(static_cast<std::size_t>(w) * h * 3, 0.0f);
    for (int c = 0; c < 3; ++c) img[(static_cast<std::size_t>(h / 2) * w + w / 2) * 3 + c] = 1.0f;
    const int dw = 60, dh = 20;
    std::vector<float> depth(static_cast<std::size_t>(dw) * dh, 0.1f);  // near everywhere (twice the focus inverse depth)
    for (int y = 0; y < 6; ++y)
        for (int x = 0; x < 10; ++x) depth[static_cast<std::size_t>(y) * dw + x] = 0.0f;  // far corner, the focus
    redline::BokehDepth d;
    d.depth = depth.data();
    d.width = dw;
    d.height = dh;
    d.confidence = 0.9f;
    redline::BokehParams p;
    p.focalMm = 70;
    p.aperture = 2.8f;
    p.focusM = 3.0f;
    p.focusX = 1.5f / dw;
    p.focusY = 1.5f / dh;
    p.phoneN = 1e6f;
    p.threads = 2;
    redline::BokehStats st;
    redline::bokehRenderFloat(img.data(), w, h, static_cast<std::size_t>(w) * 3, t, d, p, st);
    double f[3];
    for (int c = 0; c < 3; ++c) f[c] = rmsDiameter(img, w, h, w / 2, h / 2, c);
    std::printf("70mm f/2.8 foreground disc equivalent diameter R %.1f G %.1f B %.1f\n", f[0], f[1], f[2]);
    check(f[0] > f[1] * 1.04 && f[2] > f[1] * 1.04, "magenta (R/B wider) in front of focus");
}

// Guided filter: the radius map's transition follows the image edge, not the soft low-res depth edge.
void testGuided() {
    const int w = 400, h = 200;
    std::vector<float> img(static_cast<std::size_t>(w) * h * 3);
    const int edge = 203;  // image edge (subject left, background right)
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x)
            for (int c = 0; c < 3; ++c) img[(static_cast<std::size_t>(y) * w + x) * 3 + c] = x < edge ? 0.8f : 0.2f;
    const int dw = 25, dh = 12;  // 16x coarser: the depth edge is a 16 px ramp, off by a few px
    std::vector<float> depth(static_cast<std::size_t>(dw) * dh);
    for (int y = 0; y < dh; ++y)
        for (int x = 0; x < dw; ++x) depth[static_cast<std::size_t>(y) * dw + x] = (x + 0.5) * 16 < 200 ? 0.9f : 0.1f;
    redline::BokehDepth d;
    d.depth = depth.data();
    d.width = dw;
    d.height = dh;
    d.confidence = 0.9f;
    redline::BokehParams p;
    p.focalMm = 70;
    p.aperture = 2.8f;
    p.focusM = 1.0f;
    p.focusX = 0.25f;
    p.focusY = 0.5f;
    p.phoneN = 6.0f;
    p.workMax = 400;
    std::vector<float> on, off;
    int mw, mh;
    redline::BokehStats st;
    redline::bokehRadiusMap(img.data(), w, h, static_cast<std::size_t>(w) * 3, d, p, on, mw, mh, st);
    p.refine = false;
    redline::bokehRadiusMap(img.data(), w, h, static_cast<std::size_t>(w) * 3, d, p, off, mw, mh, st);
    auto span = [&](const std::vector<float>& m) {
        const float* r = m.data() + static_cast<std::size_t>(h / 2) * w;
        float mx = 0;
        for (int x = 0; x < w; ++x) mx = std::max(mx, std::fabs(r[x]));
        int a = 0, b = 0;
        for (int x = 0; x < w; ++x) {
            if (std::fabs(r[x]) < 0.1f * mx) a = x;
            if (std::fabs(r[x]) < 0.9f * mx) b = x;
        }
        return std::make_pair(a, b);  // last x below 10 % and below 90 %
    };
    const auto s1 = span(on), s0 = span(off);
    std::printf("radius 10-90 %% transition: refined x %d..%d (%d px), bilinear depth x %d..%d (%d px); image edge at %d\n", s1.first,
                s1.second, s1.second - s1.first, s0.first, s0.second, s0.second - s0.first, edge);
    check(s1.second - s1.first < s0.second - s0.first, "guided filter sharpens the transition");
    check(std::abs((s1.first + s1.second) / 2 - edge) <= std::abs((s0.first + s0.second) / 2 - edge), "transition moves toward the image edge");
    check(std::fabs(on[static_cast<std::size_t>(h / 2) * w + edge - 4]) < 0.2f * st.capRadius, "subject side stays sharp next to the edge");
}

void timing(int w, int h) {
    const float lo = -14, hi = 1;
    const std::vector<float> tt = toneTable(1024, lo, hi);
    const redline::BokehTone t = tone(tt, lo, hi);
    Scene s = makeScene(w, h, true);
    std::vector<std::uint8_t> rgba(static_cast<std::size_t>(w) * h * 4);
    for (std::size_t i = 0; i < static_cast<std::size_t>(w) * h; ++i) {
        for (int c = 0; c < 3; ++c) rgba[i * 4 + c] = static_cast<std::uint8_t>(s.img[i * 3 + c] * 255.0f + 0.5f);
        rgba[i * 4 + 3] = 255;
    }
    s.img.clear();
    s.img.shrink_to_fit();
    const redline::BokehDepth d = depthOf(s);
    struct Case {
        int focal;
        float n, s;
    } cases[] = {{50, 2.8f, 1.5f}, {24, 2.8f, 1.0f}, {70, 2.8f, 2.0f}};
    const unsigned hw = std::thread::hardware_concurrency();
    for (const Case& c : cases) {
        for (int threads : {1, 4}) {
            if (std::getenv("BOKEH_T1") != nullptr && threads != 1) continue;
            std::vector<std::uint8_t> im = rgba;
            redline::BokehParams p = portrait(s);
            p.focalMm = static_cast<float>(c.focal);
            p.aperture = c.n;
            p.focusM = c.s;
            p.threads = threads;
            redline::BokehStats st;
            const std::string err = redline::bokehRender(im.data(), w, h, static_cast<std::size_t>(w) * 4, t, d, p, st);
            check(err.empty() && st.applied, "timing render");
            std::printf("timing %dx%d %dmm f/%.1f s=%.1fm threads %d (host cores %u): total %.0f ms (map %.0f, whole levels %.0f, bands %.0f), "
                        "fill %.0f gather %.0f composite %.0f, max radius %.0f px, %d gathers, peak %.0f MB\n",
                        w, h, c.focal, static_cast<double>(c.n), static_cast<double>(c.s), threads, hw, st.totalMs, st.mapMs, st.levelsMs,
                        st.bandsMs, st.fillMs, st.gatherMs, st.compositeMs, static_cast<double>(st.maxRadius), st.layers, st.peakBytes / 1e6);
            check(st.totalMs < 60000, "timing sane");
        }
    }
}

}  // namespace

int main(int argc, char** argv) {
    const bool quick = argc > 1 && std::string(argv[1]) == "quick";
    testTable();
    testKernel();
    testScene();
    testCa();
    testGuided();
    if (!quick) {
        timing(4000, 2667);
        timing(3000, 2000);
    }
    if (failures > 0) {
        std::printf("%d failure(s)\n", failures);
        return 1;
    }
    std::printf("bokeh_test: all passed\n");
    return 0;
}
