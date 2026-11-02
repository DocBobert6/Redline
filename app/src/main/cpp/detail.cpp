// B24 detail post-pass. See detail.h and calibration/redline_cal/detail.py (the contract).
#include "detail.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <functional>
#include <thread>
#include <vector>

namespace redline {

const float kNoiseBinEdges[kNoiseBins + 1] = {0.0f, 0.1f, 0.2f, 0.35f, 0.5f, 0.65f, 0.8f, 0.92f};

namespace {

constexpr int kMaxSide = 16384;
constexpr int kMaxThreads = 16;
constexpr int kDistortTable = 2048;
constexpr int kBorderSamples = 1024;
constexpr int kNoiseBlock = 16;
constexpr int kNoiseBlockStep = 2;
constexpr double kNoiseQuantile = 0.10;
constexpr int kNoiseMinBlocks = 8;
constexpr float kLr = 0.2126f, kLg = 0.7152f, kLb = 0.0722f;
const float kIrwinHallScale = static_cast<float>(1.0 / std::sqrt(4.0 * (256.0 * 256.0 - 1.0) / 12.0));

using Clock = std::chrono::steady_clock;
double msSince(Clock::time_point t0) { return std::chrono::duration<double, std::milli>(Clock::now() - t0).count(); }

// A view of the image as float values in 0..1, either RGBA8 or float RGB.
struct Image {
    std::uint8_t* rgba = nullptr;
    float* rgb = nullptr;
    int width = 0;
    int height = 0;
    std::size_t stride = 0;  // bytes (rgba) or floats (rgb)

    void get(int x, int y, float o[3]) const {
        if (rgba != nullptr) {
            const std::uint8_t* p = rgba + static_cast<std::size_t>(y) * stride + static_cast<std::size_t>(x) * 4;
            o[0] = p[0] * (1.0f / 255.0f);
            o[1] = p[1] * (1.0f / 255.0f);
            o[2] = p[2] * (1.0f / 255.0f);
        } else {
            const float* p = rgb + static_cast<std::size_t>(y) * stride + static_cast<std::size_t>(x) * 3;
            o[0] = p[0];
            o[1] = p[1];
            o[2] = p[2];
        }
    }

    void put(int x, int y, const float v[3]) const {
        if (rgba != nullptr) {
            std::uint8_t* p = rgba + static_cast<std::size_t>(y) * stride + static_cast<std::size_t>(x) * 4;
            for (int c = 0; c < 3; ++c) {
                const float s = std::min(std::max(v[c], 0.0f), 1.0f) * 255.0f + 0.5f;
                p[c] = static_cast<std::uint8_t>(s);
            }
        } else {
            float* p = rgb + static_cast<std::size_t>(y) * stride + static_cast<std::size_t>(x) * 3;
            for (int c = 0; c < 3; ++c) p[c] = std::min(std::max(v[c], 0.0f), 1.0f);
        }
    }

    float luma(int x, int y) const {
        float v[3];
        get(x, y, v);
        return kLr * v[0] + kLg * v[1] + kLb * v[2];
    }
};

void parallelRows(int rows, int threads, const std::function<void(int, int)>& job) {
    threads = std::min(std::max(threads, 1), std::min(kMaxThreads, std::max(rows, 1)));
    if (threads == 1) {
        job(0, rows);
        return;
    }
    std::vector<std::thread> pool;
    pool.reserve(threads);
    for (int t = 0; t < threads; ++t) {
        const int a = static_cast<int>(static_cast<long long>(rows) * t / threads);
        const int b = static_cast<int>(static_cast<long long>(rows) * (t + 1) / threads);
        pool.emplace_back([&job, a, b] { job(a, b); });
    }
    for (auto& th : pool) th.join();
}

float lookup(const std::vector<float>& table, float u) {  // u in 0..1 over the table, clamped
    const int n = static_cast<int>(table.size());
    float f = std::min(std::max(u, 0.0f), 1.0f) * static_cast<float>(n - 1);
    int i = std::min(static_cast<int>(f), n - 2);
    const float w = f - static_cast<float>(i);
    return table[i] + (table[i + 1] - table[i]) * w;
}

// ----------------------------------------------------------------------------------------------- 1. distortion

void distortion(const Image& im, const DetailSpec& s, int threads, DetailStats& st) {
    const int w = im.width, h = im.height;
    const double a = s.distA, b = s.distB, c = s.distC, d = 1.0 - a - b - c;
    const double half = std::min(w, h) / 2.0;
    const double rMax = std::hypot(w / 2.0, h / 2.0) / half;
    std::vector<float> ratio(kDistortTable);
    ratio[0] = static_cast<float>(1.0 / d);
    for (int i = 1; i < kDistortTable; ++i) {
        const double rd = rMax * i / (kDistortTable - 1);
        double r = rd;
        for (int k = 0; k < 30; ++k) {
            const double f = r * (a * r * r * r + b * r * r + c * r + d) - rd;
            const double fp = 4 * a * r * r * r + 3 * b * r * r + 2 * c * r + d;
            r -= f / fp;
        }
        ratio[i] = static_cast<float>(r / rd);
    }
    float kMax = 0.0f;
    for (int i = 0; i < kBorderSamples; ++i) {
        const double t = static_cast<double>(i) / (kBorderSamples - 1);
        const double xs[2] = {t * w / 2.0 / half, w / 2.0 / half};
        const double ys[2] = {h / 2.0 / half, t * h / 2.0 / half};
        for (int e = 0; e < 2; ++e) {
            const double rd = std::sqrt(xs[e] * xs[e] + ys[e] * ys[e]);
            kMax = std::max(kMax, lookup(ratio, static_cast<float>(rd / rMax)));
        }
    }
    const float scale = std::min(1.0f, 1.0f / kMax);
    st.distortScale = scale;
    // Source copy in the image's own format (RGBA8 bytes on the phone: no float copy of a 10 MP frame).
    std::vector<std::uint8_t> src8;
    std::vector<float> srcF;
    if (im.rgba != nullptr) {
        src8.resize(static_cast<std::size_t>(w) * h * 4);
        for (int y = 0; y < h; ++y) {
            std::copy(im.rgba + static_cast<std::size_t>(y) * im.stride, im.rgba + static_cast<std::size_t>(y) * im.stride + w * 4,
                      src8.begin() + static_cast<std::ptrdiff_t>(static_cast<std::size_t>(y) * w * 4));
        }
    } else {
        srcF.resize(static_cast<std::size_t>(w) * h * 3);
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) im.get(x, y, &srcF[(static_cast<std::size_t>(y) * w + x) * 3]);
        }
    }
    auto px = [&](int x, int y, int ch) -> float {
        const std::size_t i = static_cast<std::size_t>(y) * w + x;
        return im.rgba != nullptr ? src8[i * 4 + ch] * (1.0f / 255.0f) : srcF[i * 3 + ch];
    };
    const float invHalf = static_cast<float>(1.0 / half);
    const float invRMax = static_cast<float>(1.0 / rMax);
    parallelRows(h, threads, [&](int y0, int y1) {
        for (int y = y0; y < y1; ++y) {
            const float yy = static_cast<float>(y) + 0.5f - h / 2.0f;
            for (int x = 0; x < w; ++x) {
                const float xx = static_cast<float>(x) + 0.5f - w / 2.0f;
                const float rd = std::sqrt(xx * xx + yy * yy) * invHalf;
                const float k = lookup(ratio, rd * invRMax) * scale;
                const float sx = std::min(std::max(xx * k + w / 2.0f - 0.5f, 0.0f), static_cast<float>(w - 1));
                const float sy = std::min(std::max(yy * k + h / 2.0f - 0.5f, 0.0f), static_cast<float>(h - 1));
                const int x0 = std::min(static_cast<int>(sx), w - 2);
                const int yi = std::min(static_cast<int>(sy), h - 2);
                const float fx = sx - static_cast<float>(x0), fy = sy - static_cast<float>(yi);
                float o[3];
                for (int ch = 0; ch < 3; ++ch) {
                    o[ch] = (px(x0, yi, ch) * (1 - fx) + px(x0 + 1, yi, ch) * fx) * (1 - fy) +
                            (px(x0, yi + 1, ch) * (1 - fx) + px(x0 + 1, yi + 1, ch) * fx) * fy;
                }
                im.put(x, y, o);
            }
        }
    });
}

// ----------------------------------------------------------------------------------------------- 2. sharpening

void sharpen(const Image& im, const DetailSpec& s, int threads) {
    const int w = im.width, h = im.height;
    const int rad = static_cast<int>(3.0f * s.sharpenSigma + 0.5f);
    std::vector<float> k(2 * rad + 1);
    double sum = 0.0;
    for (int i = -rad; i <= rad; ++i) {
        const double v = std::exp(-0.5 * (i / static_cast<double>(s.sharpenSigma)) * (i / static_cast<double>(s.sharpenSigma)));
        k[i + rad] = static_cast<float>(v);
        sum += v;
    }
    for (float& v : k) v = static_cast<float>(v / sum);
    // Horizontal pass of the luma for every row (read-only on the image).
    std::vector<float> t(static_cast<std::size_t>(w) * h);
    parallelRows(h, threads, [&](int y0, int y1) {
        std::vector<float> row(static_cast<std::size_t>(w) + 2 * rad);
        for (int y = y0; y < y1; ++y) {
            for (int x = 0; x < w; ++x) row[x + rad] = im.luma(x, y);
            for (int i = 0; i < rad; ++i) {
                row[i] = row[rad];
                row[w + rad + i] = row[w + rad - 1];
            }
            float* o = &t[static_cast<std::size_t>(y) * w];
            for (int x = 0; x < w; ++x) {
                float acc = 0.0f;
                for (int i = 0; i <= 2 * rad; ++i) acc += k[i] * row[x + i];
                o[x] = acc;
            }
        }
    });
    const float thr2 = s.sharpenThreshold * s.sharpenThreshold;
    const float amount = s.sharpenAmount;
    // Vertical pass (row accumulation, vectorisable) + apply: each thread writes only its own rows and reads only its
    // own pixels.
    parallelRows(h, threads, [&](int y0, int y1) {
        std::vector<float> acc(static_cast<std::size_t>(w));
        for (int y = y0; y < y1; ++y) {
            std::fill(acc.begin(), acc.end(), 0.0f);
            for (int i = -rad; i <= rad; ++i) {
                const int yy = std::min(std::max(y + i, 0), h - 1);
                const float ki = k[i + rad];
                const float* tr = &t[static_cast<std::size_t>(yy) * w];
                float* a = acc.data();
                for (int x = 0; x < w; ++x) a[x] += ki * tr[x];
            }
            for (int x = 0; x < w; ++x) {
                float v[3];
                im.get(x, y, v);
                const float yl = kLr * v[0] + kLg * v[1] + kLb * v[2];
                float dd = yl - acc[x];
                if (thr2 > 0.0f) {
                    const float d2 = dd * dd;
                    dd = d2 + thr2 > 0.0f ? dd * d2 / (d2 + thr2) : 0.0f;
                }
                const float add = amount * dd;
                const float o[3] = {v[0] + add, v[1] + add, v[2] + add};
                im.put(x, y, o);
            }
        }
    });
}

// ----------------------------------------------------------------------------------------------- 3. noise + grain

void estimateNoise(const Image& im, float measured[kNoiseBins]) {
    const int w = im.width, h = im.height;
    const double c = std::sqrt(3.14159265358979323846 / 2.0) / 6.0;
    std::vector<float> sig[kNoiseBins];
    std::vector<float> ly(static_cast<std::size_t>(kNoiseBlock + 2) * (kNoiseBlock + 2));
    for (int by = 0; by < h / kNoiseBlock; by += kNoiseBlockStep) {
        for (int bx = 0; bx < w / kNoiseBlock; bx += kNoiseBlockStep) {
            const int y0 = by * kNoiseBlock, x0 = bx * kNoiseBlock;
            // luma of the block plus a one-pixel ring (clamped; ring values only feed interior pixels)
            const int W2 = kNoiseBlock + 2;
            for (int j = 0; j < W2; ++j) {
                const int yy = std::min(std::max(y0 - 1 + j, 0), h - 1);
                for (int i = 0; i < W2; ++i) {
                    const int xx = std::min(std::max(x0 - 1 + i, 0), w - 1);
                    ly[static_cast<std::size_t>(j) * W2 + i] = im.luma(xx, yy);
                }
            }
            double lap = 0.0, mean = 0.0;
            int n = 0;
            for (int j = 0; j < kNoiseBlock; ++j) {
                const int y = y0 + j;
                for (int i = 0; i < kNoiseBlock; ++i) {
                    const int x = x0 + i;
                    const float* p = &ly[static_cast<std::size_t>(j + 1) * W2 + (i + 1)];
                    mean += p[0];
                    if (y < 1 || y >= h - 1 || x < 1 || x >= w - 1) continue;
                    const float v = p[-W2 - 1] + p[-W2 + 1] + p[W2 - 1] + p[W2 + 1] -
                                    2.0f * (p[-W2] + p[W2] + p[-1] + p[1]) + 4.0f * p[0];
                    lap += std::fabs(v);
                    ++n;
                }
            }
            mean /= kNoiseBlock * kNoiseBlock;
            if (n == 0) continue;
            const float sigma = static_cast<float>(c * lap / n);
            for (int b = 0; b < kNoiseBins; ++b) {
                if (mean >= kNoiseBinEdges[b] && mean < kNoiseBinEdges[b + 1]) {
                    sig[b].push_back(sigma);
                    break;
                }
            }
        }
    }
    for (int b = 0; b < kNoiseBins; ++b) {
        auto& v = sig[b];
        if (static_cast<int>(v.size()) < kNoiseMinBlocks) {
            measured[b] = -1.0f;
            continue;
        }
        const std::size_t q = static_cast<std::size_t>(std::floor(kNoiseQuantile * static_cast<double>(v.size() - 1)));
        std::nth_element(v.begin(), v.begin() + static_cast<std::ptrdiff_t>(q), v.end());
        measured[b] = v[q];
    }
}

void addGrain(const Image& im, const float add[kNoiseBins], const DetailSpec& s, int threads) {
    float centre[kNoiseBins];
    for (int b = 0; b < kNoiseBins; ++b) centre[b] = 0.5f * (kNoiseBinEdges[b] + kNoiseBinEdges[b + 1]);
    const int w = im.width, h = im.height;
    const float k0 = s.grainKernelW, k1 = 1.0f - 2.0f * s.grainKernelW;
    const float invKappa = 1.0f / s.grainKappa;
    parallelRows(h, threads, [&](int y0, int y1) {
        // Horizontally filtered noise rows in a ring keyed by (clamped row) % 3: rows y - 1, y, y + 1 never share a slot.
        std::vector<float> raw(static_cast<std::size_t>(w)), hr[3];
        for (auto& r : hr) r.resize(static_cast<std::size_t>(w));
        int have[3] = {-1, -1, -1};
        auto hrow = [&](int yy) -> const float* {
            yy = std::min(std::max(yy, 0), h - 1);
            const int slot = yy % 3;
            float* o = hr[slot].data();
            if (have[slot] == yy) return o;
            for (int x = 0; x < w; ++x) raw[x] = grainNormal(static_cast<std::uint32_t>(x), static_cast<std::uint32_t>(yy), s.seed);
            for (int x = 0; x < w; ++x) {
                const float l = raw[std::max(x - 1, 0)], r = raw[std::min(x + 1, w - 1)];
                o[x] = k0 * l + k1 * raw[x] + k0 * r;
            }
            have[slot] = yy;
            return o;
        };
        for (int y = y0; y < y1; ++y) {
            const float* up = hrow(y - 1);
            const float* mid = hrow(y);
            const float* dn = hrow(y + 1);
            for (int x = 0; x < w; ++x) {
                float v[3];
                im.get(x, y, v);
                const float yl = kLr * v[0] + kLg * v[1] + kLb * v[2];
                float amp;
                if (yl <= centre[0]) {
                    amp = add[0];
                } else if (yl >= centre[kNoiseBins - 1]) {
                    amp = add[kNoiseBins - 1];
                } else {
                    int b = 0;
                    while (b < kNoiseBins - 2 && yl > centre[b + 1]) ++b;
                    const float u = (yl - centre[b]) / (centre[b + 1] - centre[b]);
                    amp = add[b] + (add[b + 1] - add[b]) * u;
                }
                if (amp <= 0.0f) continue;
                const float field = (k0 * up[x] + k1 * mid[x] + k0 * dn[x]) * invKappa;
                const float n = amp * field;
                const float o[3] = {v[0] + n, v[1] + n, v[2] + n};
                im.put(x, y, o);
            }
        }
    });
}

std::string validate(int width, int height, const DetailSpec& s) {
    if (width < 4 || height < 4 || width > kMaxSide || height > kMaxSide) return "image size out of range";
    if (s.sharpenAmount < 0.0f || !std::isfinite(s.sharpenAmount)) return "bad sharpening amount";
    if (s.sharpenAmount > 0.0f && (!(s.sharpenSigma >= 0.2f) || s.sharpenSigma > 8.0f)) return "bad sharpening sigma";
    if (s.sharpenThreshold < 0.0f || !std::isfinite(s.sharpenThreshold)) return "bad sharpening threshold";
    if (s.distort) {
        const double d = 1.0 - s.distA - s.distB - s.distC;
        if (!(d > 0.5) || !std::isfinite(d)) return "bad distortion coefficients";
    }
    for (float v : s.grainTarget) {
        if (v < 0.0f || !std::isfinite(v)) return "bad grain target";
    }
    if (s.grain && (!(s.grainKappa > 0.0f) || !(s.grainKernelW >= 0.0f) || s.grainKernelW > 0.33f)) return "bad grain filter";
    return "";
}

std::string run(const Image& im, const DetailSpec& s, DetailStats& st) {
    const std::string err = validate(im.width, im.height, s);
    if (!err.empty()) return err;
    const auto t0 = Clock::now();
    auto t = t0;
    if (s.distort) {
        distortion(im, s, s.threads, st);
        st.distortMs = msSince(t);
    }
    t = Clock::now();
    if (s.sharpenAmount > 0.0f) {
        sharpen(im, s, s.threads);
        st.sharpenMs = msSince(t);
    }
    if (s.grain) {
        t = Clock::now();
        estimateNoise(im, st.measured);
        st.noiseMs = msSince(t);
        for (int b = 0; b < kNoiseBins; ++b) {
            const float m = st.measured[b];
            const float tg = s.grainTarget[b];
            st.added[b] = m < 0.0f ? 0.0f : std::sqrt(std::max(0.0f, tg * tg - m * m));
        }
        bool any = false;
        for (float a : st.added) any = any || a > 0.0f;
        if (any) {  // nothing to add (the frame is already as noisy as the camera JPEG): skip the noise field
            t = Clock::now();
            addGrain(im, st.added, s, s.threads);
            st.grainMs = msSince(t);
        }
    }
    st.totalMs = msSince(t0);
    return "";
}

}  // namespace

float grainNormal(std::uint32_t x, std::uint32_t y, std::uint32_t seed) {
    std::uint32_t v = (x * 0x8DA6B343u) ^ (y * 0xD8163841u) ^ (seed * 0xCB1AB31Fu);
    v ^= v >> 16;
    v *= 0x85EBCA6Bu;
    v ^= v >> 13;
    v *= 0xC2B2AE35u;
    v ^= v >> 16;
    const int s = static_cast<int>(v & 0xFFu) + static_cast<int>((v >> 8) & 0xFFu) + static_cast<int>((v >> 16) & 0xFFu) +
                  static_cast<int>(v >> 24);
    return static_cast<float>(s - 510) * kIrwinHallScale;
}

std::string applyDetail(std::uint8_t* rgba, int width, int height, std::size_t strideBytes, const DetailSpec& spec,
                        DetailStats& stats) {
    if (rgba == nullptr || strideBytes < static_cast<std::size_t>(std::max(width, 0)) * 4) return "bad image buffer";
    Image im;
    im.rgba = rgba;
    im.width = width;
    im.height = height;
    im.stride = strideBytes;
    return run(im, spec, stats);
}

std::string applyDetailFloat(float* rgb, int width, int height, std::size_t strideFloats, const DetailSpec& spec,
                             DetailStats& stats) {
    if (rgb == nullptr || strideFloats < static_cast<std::size_t>(std::max(width, 0)) * 3) return "bad image buffer";
    Image im;
    im.rgb = rgb;
    im.width = width;
    im.height = height;
    im.stride = strideFloats;
    return run(im, spec, stats);
}

}  // namespace redline
