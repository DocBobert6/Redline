// B30 depth pre/post-processing. See depth_post.h and calibration/redline_cal/depth_post.py (the contract).
#include "depth_post.h"

#include <algorithm>
#include <cmath>

namespace redline {

namespace {

constexpr float kRangeLo = 0.15f, kRangeHi = 0.5f;
constexpr float kEdgeQ = 0.95f, kEdgeMin = 0.02f, kEdgeGradLimit = 0.08f;
constexpr int kRing = 8;
constexpr float kLetterbox = 0.5f;
// B31.1 matte path (see analyseDepth)
constexpr float kMaskSupport = 0.05f;
constexpr float kMatteCovLo = 0.05f, kMatteCovHi = 0.25f;
constexpr float kClarityLo = 0.4f, kClarityHi = 0.7f;
constexpr float kMatteNearLo = 0.1f, kMatteNearHi = 0.3f;

float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

float smoothstep(float a, float b, float x) {
    const float t = clamp01((x - a) / (b - a));
    return t * t * (3 - 2 * t);
}

// Nearest-rank percentile: sorted[floor(q * (n - 1) + 0.5)].
float pct(std::vector<float> v, float q) {
    if (v.empty()) return 0;
    const std::size_t k = static_cast<std::size_t>(std::floor(q * static_cast<double>(v.size() - 1) + 0.5));
    std::nth_element(v.begin(), v.begin() + static_cast<long>(k), v.end());
    return v[k];
}

void gradMag(const float* f, int w, int h, std::vector<float>& g) {
    g.assign(static_cast<std::size_t>(w) * h, 0);
    for (int y = 0; y < h; ++y) {
        const int ym = std::max(y - 1, 0), yp = std::min(y + 1, h - 1);
        for (int x = 0; x < w; ++x) {
            const int xm = std::max(x - 1, 0), xp = std::min(x + 1, w - 1);
            const double gx = (static_cast<double>(f[y * w + xp]) - f[y * w + xm]) * 0.5;
            const double gy = (static_cast<double>(f[yp * w + x]) - f[ym * w + x]) * 0.5;
            g[static_cast<std::size_t>(y) * w + x] = static_cast<float>(std::sqrt(gx * gx + gy * gy));
        }
    }
}

// (2r+1)^2 square maximum, clamped edges, separable.
void maxFilter(const std::vector<float>& f, int w, int h, int r, std::vector<float>& out) {
    std::vector<float> tmp(f.size());
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            float m = f[static_cast<std::size_t>(y) * w + x];
            for (int k = -r; k <= r; ++k) m = std::max(m, f[static_cast<std::size_t>(y) * w + std::min(std::max(x + k, 0), w - 1)]);
            tmp[static_cast<std::size_t>(y) * w + x] = m;
        }
    }
    out.assign(f.size(), 0);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            float m = tmp[static_cast<std::size_t>(y) * w + x];
            for (int k = -r; k <= r; ++k) m = std::max(m, tmp[static_cast<std::size_t>(std::min(std::max(y + k, 0), h - 1)) * w + x]);
            out[static_cast<std::size_t>(y) * w + x] = m;
        }
    }
}

// Upright (u, v) of a rot-rotated still (width x height pixels) -> the still's pixel (x, y).
inline void uprightToSensor(int u, int v, int width, int height, int rot, int& x, int& y) {
    switch (rot) {
        case 90:
            x = v;
            y = height - 1 - u;
            break;
        case 180:
            x = width - 1 - u;
            y = height - 1 - v;
            break;
        case 270:
            x = width - 1 - v;
            y = u;
            break;
        default:
            x = u;
            y = v;
    }
}

}  // namespace

DepthCanvas depthCanvas(int width, int height, int rot, int canvasW, int canvasH) {
    DepthCanvas c;
    c.canvasW = canvasW;
    c.canvasH = canvasH;
    c.rot = ((rot % 360) + 360) % 360;
    const bool swap = c.rot == 90 || c.rot == 270;
    const double uw = swap ? height : width, uh = swap ? width : height;
    const double s = std::min(canvasW / uw, canvasH / uh);
    c.aw = std::max(1, std::min(canvasW, static_cast<int>(std::floor(uw * s + 0.5))));
    c.ah = std::max(1, std::min(canvasH, static_cast<int>(std::floor(uh * s + 0.5))));
    c.ax = (canvasW - c.aw) / 2;
    c.ay = (canvasH - c.ah) / 2;
    return c;
}

void resizeBilinear(const float* src, int sw, int sh, int ch, float* dst, int dw, int dh) {
    for (int y = 0; y < dh; ++y) {
        const float fy = std::min(std::max((y + 0.5f) * sh / dh - 0.5f, 0.0f), static_cast<float>(sh - 1));
        const int y0 = std::min(static_cast<int>(fy), sh - 1), y1 = std::min(y0 + 1, sh - 1);
        const float wy = fy - y0;
        for (int x = 0; x < dw; ++x) {
            const float fx = std::min(std::max((x + 0.5f) * sw / dw - 0.5f, 0.0f), static_cast<float>(sw - 1));
            const int x0 = std::min(static_cast<int>(fx), sw - 1), x1 = std::min(x0 + 1, sw - 1);
            const float wx = fx - x0;
            for (int k = 0; k < ch; ++k) {
                const float a = src[(y0 * sw + x0) * ch + k] * (1 - wx) + src[(y0 * sw + x1) * ch + k] * wx;
                const float b = src[(y1 * sw + x0) * ch + k] * (1 - wx) + src[(y1 * sw + x1) * ch + k] * wx;
                dst[(y * dw + x) * ch + k] = a * (1 - wy) + b * wy;
            }
        }
    }
}

std::string depthPrep(const std::uint8_t* rgba, int width, int height, std::size_t stride, const DepthCanvas& c, float* rgbCanvas,
                      float* segIn, int segW, int segH) {
    if (rgba == nullptr || width < 2 || height < 2 || stride < static_cast<std::size_t>(width) * 4) return "bad still";
    if (rgbCanvas == nullptr || c.canvasW < 2 || c.canvasH < 2 || c.aw < 1 || c.ah < 1) return "bad canvas";
    const bool swap = c.rot == 90 || c.rot == 270;
    const int uw = swap ? height : width, uh = swap ? width : height;
    for (std::size_t i = 0; i < static_cast<std::size_t>(c.canvasW) * c.canvasH * 3; ++i) rgbCanvas[i] = kLetterbox;
    // Integer source boxes in upright coordinates: [floor(o * u / a), max(floor((o + 1) * u / a), start + 1)).
    std::vector<int> xs(c.aw + 1), ys(c.ah + 1);
    for (int i = 0; i <= c.aw; ++i) xs[i] = static_cast<int>(static_cast<long long>(i) * uw / c.aw);
    for (int i = 0; i <= c.ah; ++i) ys[i] = static_cast<int>(static_cast<long long>(i) * uh / c.ah);
    for (int oy = 0; oy < c.ah; ++oy) {
        const int v0 = ys[oy], v1 = std::max(ys[oy + 1], v0 + 1);
        for (int ox = 0; ox < c.aw; ++ox) {
            const int u0 = xs[ox], u1 = std::max(xs[ox + 1], u0 + 1);
            std::uint32_t r = 0, g = 0, b = 0, n = 0;
            for (int v = v0; v < v1; ++v) {
                for (int u = u0; u < u1; ++u) {
                    int x, y;
                    uprightToSensor(u, v, width, height, c.rot, x, y);
                    const std::uint8_t* p = rgba + static_cast<std::size_t>(y) * stride + static_cast<std::size_t>(x) * 4;
                    r += p[0];
                    g += p[1];
                    b += p[2];
                    ++n;
                }
            }
            float* o = rgbCanvas + (static_cast<std::size_t>(oy + c.ay) * c.canvasW + ox + c.ax) * 3;
            const float inv = 1.0f / (255.0f * static_cast<float>(n));
            o[0] = r * inv;
            o[1] = g * inv;
            o[2] = b * inv;
        }
    }
    if (segIn != nullptr && segW > 1 && segH > 1) resizeBilinear(rgbCanvas, c.canvasW, c.canvasH, 3, segIn, segW, segH);
    return "";
}

DepthAnalysis analyseDepth(const float* depth, const float* mask, const float* rgb, int w, int h, float* confMap) {
    DepthAnalysis a;
    const std::size_t n = static_cast<std::size_t>(w) * h;
    std::vector<float> d(depth, depth + n);
    a.dmin = *std::min_element(d.begin(), d.end());
    a.dmax = *std::max_element(d.begin(), d.end());
    a.p2 = pct(d, 0.02f);
    a.p98 = pct(d, 0.98f);
    a.spread = (a.p98 - a.p2) / std::max(a.p98, 1e-6f);
    a.rangeScore = smoothstep(kRangeLo, kRangeHi, a.spread);
    const float span = std::max(a.p98 - a.p2, 1e-6f);
    std::vector<float> dn(n), luma(n);
    for (std::size_t i = 0; i < n; ++i) {
        dn[i] = clamp01((depth[i] - a.p2) / span);
        luma[i] = rgb[i * 3] * 0.299f + rgb[i * 3 + 1] * 0.587f + rgb[i * 3 + 2] * 0.114f;
    }
    std::vector<float> gd, gl;
    gradMag(dn.data(), w, h, gd);
    gradMag(luma.data(), w, h, gl);
    const float edgeT = std::max(kEdgeMin, pct(gd, kEdgeQ));
    std::size_t strong = 0;
    for (float v : gd) strong += v > edgeT ? 1 : 0;
    if (static_cast<double>(strong) < 0.002 * static_cast<double>(n)) {
        a.edgeAgree = -1;
        a.edgeScore = 0.5f;
    } else {
        const float tl = std::max(0.01f, 2 * pct(gl, 0.5f));
        std::vector<float> near;
        maxFilter(gl, w, h, 1, near);
        std::size_t agree = 0;
        for (std::size_t i = 0; i < n; ++i) agree += (gd[i] > edgeT && near[i] > tl) ? 1 : 0;
        a.edgeAgree = static_cast<float>(static_cast<double>(agree) / static_cast<double>(strong));
        a.edgeScore = clamp01((a.edgeAgree - 0.3f) / 0.5f);
    }
    std::vector<float> person(n);
    std::size_t pc = 0;
    for (std::size_t i = 0; i < n; ++i) {
        person[i] = mask[i] > 0.5f ? 1.0f : 0.0f;
        pc += mask[i] > 0.5f ? 1 : 0;
    }
    a.personCoverage = static_cast<float>(static_cast<double>(pc) / static_cast<double>(n));
    if (a.personCoverage >= 0.01f && a.personCoverage <= 0.9f) {
        std::vector<float> dil;
        maxFilter(person, w, h, kRing, dil);
        std::vector<float> ring;
        for (std::size_t i = 0; i < n; ++i) {
            if (dil[i] > 0.5f && person[i] == 0) ring.push_back(dn[i]);
        }
        if (ring.size() >= 50) {
            const float bg = pct(ring, 0.5f);
            std::size_t nearer = 0;
            for (std::size_t i = 0; i < n; ++i) nearer += (person[i] > 0 && dn[i] > bg) ? 1 : 0;
            a.personNearer = static_cast<float>(static_cast<double>(nearer) / static_cast<double>(pc));
            a.personScore = clamp01((a.personNearer - 0.5f) / 0.4f);
        }
    }
    // B31.1: the matte path. A person-dominant frame with a clear mask is usable even when the depth term collapses
    // (person filling most of the frame, a near floor or wall around them: the ring is about as near as the person),
    // because the mask itself keeps the subject sharp; a person clearly behind their surroundings still fails.
    {
        double sum = 0;
        std::size_t sup = 0;
        for (std::size_t i = 0; i < n; ++i) {
            if (mask[i] > kMaskSupport) {
                sum += 4.0 * mask[i] * (1.0 - mask[i]);
                ++sup;
            }
        }
        a.maskClarity = sup > 0 ? static_cast<float>(1.0 - sum / static_cast<double>(sup)) : 0.0f;
        const float nearOk = a.personNearer < 0 ? 1.0f : smoothstep(kMatteNearLo, kMatteNearHi, a.personNearer);
        a.matteScore = smoothstep(kMatteCovLo, kMatteCovHi, a.personCoverage) * smoothstep(kClarityLo, kClarityHi, a.maskClarity) * nearOk;
    }
    a.depthConfidence = a.rangeScore * (0.4f + 0.6f * a.edgeScore) * a.personScore;
    a.matteConfidence = a.matteScore * (0.5f + 0.5f * std::max(a.rangeScore, a.edgeScore));
    a.confidence = std::max(a.depthConfidence, a.matteConfidence);
    a.usable = a.confidence >= kDepthUsable;
    if (confMap != nullptr) {
        for (std::size_t i = 0; i < n; ++i) {
            const float m = mask[i];
            confMap[i] = std::min(1 - clamp01(gd[i] / kEdgeGradLimit), 1 - 4 * m * (1 - m));
        }
    }
    return a;
}

std::string depthFinish(const float* depthOut, const float* segOut, int segW, int segH, const float* rgbCanvas, const DepthCanvas& c,
                        int imageW, int imageH, const DetailSpec& spec, std::vector<std::uint16_t>& out16, int& outW, int& outH,
                        DepthAnalysis& a) {
    if (depthOut == nullptr || segOut == nullptr || rgbCanvas == nullptr || segW < 2 || segH < 2) return "missing model output";
    if (c.aw < 8 || c.ah < 8 || c.ax + c.aw > c.canvasW || c.ay + c.ah > c.canvasH) return "bad active rect";
    const std::size_t cn = static_cast<std::size_t>(c.canvasW) * c.canvasH;
    std::vector<float> maskCanvas(cn);
    resizeBilinear(segOut, segW, segH, 1, maskCanvas.data(), c.canvasW, c.canvasH);
    const std::size_t an = static_cast<std::size_t>(c.aw) * c.ah;
    std::vector<float> d(an), m(an), rgb(an * 3), conf(an);
    for (int y = 0; y < c.ah; ++y) {
        for (int x = 0; x < c.aw; ++x) {
            const std::size_t s = static_cast<std::size_t>(y + c.ay) * c.canvasW + x + c.ax;
            const std::size_t t = static_cast<std::size_t>(y) * c.aw + x;
            d[t] = depthOut[s];
            m[t] = clamp01(maskCanvas[s]);
            rgb[t * 3] = rgbCanvas[s * 3];
            rgb[t * 3 + 1] = rgbCanvas[s * 3 + 1];
            rgb[t * 3 + 2] = rgbCanvas[s * 3 + 2];
        }
    }
    a = analyseDepth(d.data(), m.data(), rgb.data(), c.aw, c.ah, conf.data());
    // Back to the still's pixel orientation: sensor (x, y) of size outW x outH reads upright (u, v).
    const bool swap = c.rot == 90 || c.rot == 270;
    outW = swap ? c.ah : c.aw;
    outH = swap ? c.aw : c.ah;
    std::vector<float> sd(an), sm(an), sc(an);
    for (int v = 0; v < c.ah; ++v) {
        for (int u = 0; u < c.aw; ++u) {
            int x, y;
            uprightToSensor(u, v, outW, outH, c.rot, x, y);
            const std::size_t t = static_cast<std::size_t>(v) * c.aw + u;
            const std::size_t s = static_cast<std::size_t>(y) * outW + x;
            sd[s] = d[t];
            sm[s] = m[t];
            sc[s] = conf[t];
        }
    }
    if (spec.distort) {
        for (std::vector<float>* p : {&sd, &sm, &sc}) {
            const std::string err = warpMapFloat(p->data(), outW, outH, static_cast<std::size_t>(outW), imageW, imageH, spec);
            if (!err.empty()) return "warp: " + err;
        }
    }
    const float span = std::max(a.dmax - a.dmin, 1e-6f);
    out16.assign(an * 3, 0);
    for (std::size_t i = 0; i < an; ++i) {
        out16[i * 3] = static_cast<std::uint16_t>(clamp01((sd[i] - a.dmin) / span) * 65535.0f + 0.5f);
        out16[i * 3 + 1] = static_cast<std::uint16_t>(clamp01(sm[i]) * 65535.0f + 0.5f);
        out16[i * 3 + 2] = static_cast<std::uint16_t>(clamp01(sc[i]) * 65535.0f + 0.5f);
    }
    return "";
}

}  // namespace redline
