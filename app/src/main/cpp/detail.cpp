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
constexpr int kDistortChunk = 32;  // rows per sweep step (B24.1)
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

// B24.1: in place without a copy of the frame. Each output pixel reads a 2x2 neighbourhood at a radially scaled
// position; k = ratio(r) * scale stays in [kLo, kHi], so output row y reads only source rows within readRows(y). Rows are
// produced in two sweeps from the top and bottom edges towards the centre, in chunks, into a small set of pending row
// buffers; a pending row is written back once no row still to be produced (in either sweep) can read it. Every output
// value is the same expression on the same source values as the B24 full-copy version (bit-identical; detail_test).
struct DistortMap {
    std::vector<float> ratio;
    float scale = 1.0f;
    float invHalf = 1.0f;
    float invRMax = 1.0f;
    float kLo = 1.0f, kHi = 1.0f;
};

DistortMap distortMap(int w, int h, const DetailSpec& s) {
    DistortMap m;
    const double a = s.distA, b = s.distB, c = s.distC, d = 1.0 - a - b - c;
    const double half = std::min(w, h) / 2.0;
    const double rMax = std::hypot(w / 2.0, h / 2.0) / half;
    m.ratio.resize(kDistortTable);
    m.ratio[0] = static_cast<float>(1.0 / d);
    for (int i = 1; i < kDistortTable; ++i) {
        const double rd = rMax * i / (kDistortTable - 1);
        double r = rd;
        for (int k = 0; k < 30; ++k) {
            const double f = r * (a * r * r * r + b * r * r + c * r + d) - rd;
            const double fp = 4 * a * r * r * r + 3 * b * r * r + 2 * c * r + d;
            r -= f / fp;
        }
        m.ratio[i] = static_cast<float>(r / rd);
    }
    float kMax = 0.0f;
    for (int i = 0; i < kBorderSamples; ++i) {
        const double t = static_cast<double>(i) / (kBorderSamples - 1);
        const double xs[2] = {t * w / 2.0 / half, w / 2.0 / half};
        const double ys[2] = {h / 2.0 / half, t * h / 2.0 / half};
        for (int e = 0; e < 2; ++e) {
            const double rd = std::sqrt(xs[e] * xs[e] + ys[e] * ys[e]);
            kMax = std::max(kMax, lookup(m.ratio, static_cast<float>(rd / rMax)));
        }
    }
    m.scale = std::min(1.0f, 1.0f / kMax);
    m.invHalf = static_cast<float>(1.0 / half);
    m.invRMax = static_cast<float>(1.0 / rMax);
    const auto mm = std::minmax_element(m.ratio.begin(), m.ratio.end());
    // lookup() interpolates between table entries, so k lies between the extremes (widened for rounding).
    m.kLo = *mm.first * m.scale * (1.0f - 1e-4f);
    m.kHi = *mm.second * m.scale * (1.0f + 1e-4f);
    return m;
}

// The source rows output row y can read (bilinear rows yi and yi + 1), conservatively, clamped to the image.
void readRows(const DistortMap& m, int y, int h, int& lo, int& hi) {
    const float yy = static_cast<float>(y) + 0.5f - h / 2.0f;
    const float s0 = yy * m.kLo + h / 2.0f - 0.5f, s1 = yy * m.kHi + h / 2.0f - 0.5f;
    const float a = std::min(s0, s1), b = std::max(s0, s1);
    lo = std::max(0, static_cast<int>(std::floor(a)) - 2);
    hi = std::min(h - 1, static_cast<int>(std::floor(b)) + 3);
}

void distortion(const Image& im, const DetailSpec& s, int threads, DetailStats& st) {
    const int w = im.width, h = im.height;
    const DistortMap m = distortMap(w, h, s);
    const float scale = m.scale;
    st.distortScale = scale;
    const std::vector<float>& ratio = m.ratio;
    const float invHalf = m.invHalf;
    const float invRMax = m.invRMax;
    const bool rgba = im.rgba != nullptr;
    const std::size_t rowElems = rgba ? static_cast<std::size_t>(w) * 4 : static_cast<std::size_t>(w) * 3;
    const std::size_t rowBytes = rowElems * (rgba ? 1 : sizeof(float));
    // Pending output rows: row index -> buffer (bytes for RGBA8, floats for RGB), recycled through a free list.
    std::vector<std::vector<std::uint8_t>> bufs8;
    std::vector<std::vector<float>> bufsF;
    std::vector<int> freeSlots;
    std::vector<int> slotOf(static_cast<std::size_t>(h), -1);
    std::vector<int> pending;
    std::size_t peakRows = 0;
    auto takeSlot = [&]() -> int {
        if (!freeSlots.empty()) {
            const int k = freeSlots.back();
            freeSlots.pop_back();
            return k;
        }
        if (rgba) {
            bufs8.emplace_back(rowElems);
            return static_cast<int>(bufs8.size()) - 1;
        }
        bufsF.emplace_back(rowElems);
        return static_cast<int>(bufsF.size()) - 1;
    };
    auto px = [&](int x, int y, int ch) -> float {
        if (rgba) return im.rgba[static_cast<std::size_t>(y) * im.stride + static_cast<std::size_t>(x) * 4 + ch] * (1.0f / 255.0f);
        return im.rgb[static_cast<std::size_t>(y) * im.stride + static_cast<std::size_t>(x) * 3 + ch];
    };
    // One output row into its pending buffer, with k for the row given (k is symmetric in x and in y about the centre).
    auto produce = [&](int y, const float* kRow) {
        const int slot = slotOf[static_cast<std::size_t>(y)];
        std::uint8_t* d8 = rgba ? bufs8[static_cast<std::size_t>(slot)].data() : nullptr;
        float* dF = rgba ? nullptr : bufsF[static_cast<std::size_t>(slot)].data();
        if (rgba) std::copy(im.rgba + static_cast<std::size_t>(y) * im.stride, im.rgba + static_cast<std::size_t>(y) * im.stride + rowElems, d8);
        const float yy = static_cast<float>(y) + 0.5f - h / 2.0f;
        for (int x = 0; x < w; ++x) {
            const float xx = static_cast<float>(x) + 0.5f - w / 2.0f;
            const float k = kRow[x];
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
            if (rgba) {
                std::uint8_t* p = d8 + static_cast<std::size_t>(x) * 4;
                for (int c = 0; c < 3; ++c) {
                    const float v = std::min(std::max(o[c], 0.0f), 1.0f) * 255.0f + 0.5f;
                    p[c] = static_cast<std::uint8_t>(v);
                }
            } else {
                float* p = dF + static_cast<std::size_t>(x) * 3;
                for (int c = 0; c < 3; ++c) p[c] = std::min(std::max(o[c], 0.0f), 1.0f);
            }
        }
    };
    auto kRowFor = [&](int y, float* kRow) {
        const float yy = static_cast<float>(y) + 0.5f - h / 2.0f;
        const int halfW = (w + 1) / 2;
        for (int x = 0; x < halfW; ++x) {
            const float xx = static_cast<float>(x) + 0.5f - w / 2.0f;
            const float rd = std::sqrt(xx * xx + yy * yy) * invHalf;
            kRow[x] = lookup(ratio, rd * invRMax) * scale;
        }
        for (int x = halfW; x < w; ++x) kRow[x] = kRow[w - 1 - x];  // xx(w - 1 - x) == -xx(x) exactly
    };
    auto commit = [&](int y) {
        const int slot = slotOf[static_cast<std::size_t>(y)];
        if (rgba) {
            std::copy(bufs8[static_cast<std::size_t>(slot)].begin(), bufs8[static_cast<std::size_t>(slot)].end(),
                      im.rgba + static_cast<std::size_t>(y) * im.stride);
        } else {
            std::copy(bufsF[static_cast<std::size_t>(slot)].begin(), bufsF[static_cast<std::size_t>(slot)].end(),
                      im.rgb + static_cast<std::size_t>(y) * im.stride);
        }
        freeSlots.push_back(slot);
        slotOf[static_cast<std::size_t>(y)] = -1;
    };
    const int mid = h / 2;  // top sweep rows [0, mid), bottom sweep rows [mid, h), bottom from h - 1 down
    const int chunk = std::max(8, std::min(kDistortChunk, mid));
    int top = 0, bot = h - 1;
    std::vector<std::vector<float>> kRows(static_cast<std::size_t>(std::max(1, std::min(kMaxThreads, std::max(threads, 1)))),
                                          std::vector<float>(static_cast<std::size_t>(w)));
    while (top < mid || bot >= mid) {
        // This step: top rows [top, tEnd) and bottom rows (bEnd, bot]; row y pairs with h - 1 - y (same k row).
        const int tEnd = std::min(mid, top + chunk);
        const int bEnd = std::max(mid - 1, bot - chunk);
        std::vector<int> rows;
        for (int y = top; y < tEnd; ++y) rows.push_back(y);
        for (int y = bot; y > bEnd; --y) rows.push_back(y);
        for (int y : rows) {
            slotOf[static_cast<std::size_t>(y)] = takeSlot();
            pending.push_back(y);
        }
        peakRows = std::max(peakRows, pending.size());
        // Jobs: a top row with its mirror (when the mirror is in this step), and any unpaired row.
        std::vector<std::pair<int, int>> jobs;
        std::vector<char> done(rows.size(), 0);
        for (std::size_t i = 0; i < rows.size(); ++i) {
            if (done[i]) continue;
            const int y = rows[i];
            const int mirror = h - 1 - y;
            int partner = -1;
            for (std::size_t j = i + 1; j < rows.size(); ++j) {
                if (!done[j] && rows[j] == mirror && mirror != y) {
                    partner = mirror;
                    done[j] = 1;
                    break;
                }
            }
            done[i] = 1;
            jobs.emplace_back(y, partner);
        }
        const int nt = static_cast<int>(kRows.size());
        const int nj = static_cast<int>(jobs.size());
        parallelRows(std::min(nt, nj), nt, [&](int t0, int t1) {
            for (int t = t0; t < t1; ++t) {
                float* kRow = kRows[static_cast<std::size_t>(t)].data();
                const int parts = std::min(nt, nj);
                const int a = static_cast<int>(static_cast<long long>(nj) * t / parts);
                const int b = static_cast<int>(static_cast<long long>(nj) * (t + 1) / parts);
                for (int j = a; j < b; ++j) {
                    kRowFor(jobs[static_cast<std::size_t>(j)].first, kRow);
                    produce(jobs[static_cast<std::size_t>(j)].first, kRow);
                    if (jobs[static_cast<std::size_t>(j)].second >= 0) produce(jobs[static_cast<std::size_t>(j)].second, kRow);
                }
            }
        });
        top = tEnd;
        bot = bEnd;
        // Source rows any row still to be produced may read (read ranges grow monotonically along each sweep).
        int fLoT = h, fHiT = -1, fLoB = h, fHiB = -1;
        if (top < mid) {
            int lo, hi;
            readRows(m, top, h, fLoT, hi);
            readRows(m, mid - 1, h, lo, fHiT);
            fLoT = std::min(fLoT, lo);
            fHiT = std::max(fHiT, hi);
        }
        if (bot >= mid) {
            int lo, hi;
            readRows(m, mid, h, fLoB, hi);
            readRows(m, bot, h, lo, fHiB);
            fLoB = std::min(fLoB, lo);
            fHiB = std::max(fHiB, hi);
        }
        std::vector<int> keep;
        for (int y : pending) {
            const bool readT = y >= fLoT && y <= fHiT;
            const bool readB = y >= fLoB && y <= fHiB;
            if (readT || readB) {
                keep.push_back(y);
            } else {
                commit(y);
            }
        }
        pending.swap(keep);
    }
    for (int y : pending) commit(y);
    const std::size_t slots = rgba ? bufs8.size() : bufsF.size();
    st.peakBytes = std::max(st.peakBytes, static_cast<double>(slots * rowBytes + kRows.size() * w * sizeof(float) +
                                                              ratio.size() * sizeof(float) + h * sizeof(int)));
    (void)peakRows;
}

// ----------------------------------------------------------------------------------------------- 2. sharpening

// B24.1: in place without a full-size plane. The horizontal Gaussian of the luma is kept for a ring of 2r + 1 rows per
// band; the rows just outside each band (which the neighbouring band rewrites) are blurred first, before any band
// writes. Same arithmetic, in the same order, as the B24 full-plane version (bit-identical; detail_test).
void sharpen(const Image& im, const DetailSpec& s, int threads, DetailStats& st) {
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
    const int taps = 2 * rad + 1;
    // Horizontal pass of one row's luma into o (row: scratch of w + 2 rad).
    auto hpass = [&](int y, float* row, float* o) {
        for (int x = 0; x < w; ++x) row[x + rad] = im.luma(x, y);
        for (int i = 0; i < rad; ++i) {
            row[i] = row[rad];
            row[w + rad + i] = row[w + rad - 1];
        }
        for (int x = 0; x < w; ++x) {
            float acc = 0.0f;
            for (int i = 0; i < taps; ++i) acc += k[i] * row[x + i];
            o[x] = acc;
        }
    };
    const int bands = std::min(std::max(threads, 1), std::min(kMaxThreads, std::max(h, 1)));
    std::vector<int> a(bands), b(bands);
    for (int t = 0; t < bands; ++t) {
        a[t] = static_cast<int>(static_cast<long long>(h) * t / bands);
        b[t] = static_cast<int>(static_cast<long long>(h) * (t + 1) / bands);
    }
    const std::size_t W = static_cast<std::size_t>(w);
    // Per band: halo rows (outside the band, inside the image) blurred from the untouched image.
    std::vector<std::vector<int>> haloRows(bands);
    std::vector<std::vector<float>> halo(bands);
    for (int t = 0; t < bands; ++t) {
        const int lo = std::max(0, a[t] - rad), hi = std::min(h - 1, b[t] - 1 + rad);
        for (int y = lo; y <= hi; ++y) {
            if (y < a[t] || y >= b[t]) haloRows[t].push_back(y);
        }
        halo[t].resize(haloRows[t].size() * W);
    }
    std::vector<std::vector<float>> scratch(bands, std::vector<float>(W + 2 * rad));
    parallelRows(bands, bands, [&](int t0, int t1) {
        for (int t = t0; t < t1; ++t) {
            for (std::size_t i = 0; i < haloRows[t].size(); ++i) hpass(haloRows[t][i], scratch[t].data(), &halo[t][i * W]);
        }
    });
    const float thr2 = s.sharpenThreshold * s.sharpenThreshold;
    const float amount = s.sharpenAmount;
    std::vector<std::vector<float>> ring(bands, std::vector<float>(static_cast<std::size_t>(taps) * W));
    std::vector<std::vector<float>> accs(bands, std::vector<float>(W));
    parallelRows(bands, bands, [&](int t0, int t1) {
        for (int t = t0; t < t1; ++t) {
            const int ya = a[t], yb = b[t];
            float* rg = ring[t].data();
            float* acc = accs[t].data();
            float* row = scratch[t].data();
            auto rowPtr = [&](int yy) -> const float* {
                if (yy >= ya && yy < yb) return rg + static_cast<std::size_t>(yy % taps) * W;
                const auto& hr = haloRows[t];
                const std::size_t i = static_cast<std::size_t>(std::lower_bound(hr.begin(), hr.end(), yy) - hr.begin());
                return &halo[t][i * W];
            };
            int next = ya;  // next band row whose blur is not in the ring yet
            for (int y = ya; y < yb; ++y) {
                // Rows up to y + rad are still untouched (this band writes row y only now).
                const int need = std::min(yb - 1, y + rad);
                for (; next <= need; ++next) hpass(next, row, rg + static_cast<std::size_t>(next % taps) * W);
                std::fill(acc, acc + w, 0.0f);
                for (int i = -rad; i <= rad; ++i) {
                    const int yy = std::min(std::max(y + i, 0), h - 1);
                    const float ki = k[i + rad];
                    const float* tr = rowPtr(yy);
                    for (int x = 0; x < w; ++x) acc[x] += ki * tr[x];
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
        }
    });
    std::size_t floats = 0;
    for (int t = 0; t < bands; ++t) floats += halo[t].size() + ring[t].size() + accs[t].size() + scratch[t].size();
    st.peakBytes = std::max(st.peakBytes, static_cast<double>(floats * sizeof(float)));
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
        sharpen(im, s, s.threads, st);
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

// B25: the gain map follows the still through the same lens distortion (RAW feel). Each map value is inverse-mapped
// with the image's own k(r) (distortMap at the image size), the map's cell centres taken in image coordinates; bilinear.
std::string warpGainMap(std::uint8_t* map, int mapWidth, int mapHeight, std::size_t mapStride, int imageWidth, int imageHeight,
                        const DetailSpec& spec) {
    if (!spec.distort) return "";
    if (map == nullptr || mapWidth < 2 || mapHeight < 2 || mapStride < static_cast<std::size_t>(mapWidth)) return "bad gain map";
    const std::string err = validate(imageWidth, imageHeight, spec);
    if (!err.empty()) return err;
    const DistortMap m = distortMap(imageWidth, imageHeight, spec);
    const std::vector<std::uint8_t> src(map, map + mapStride * static_cast<std::size_t>(mapHeight));
    const float sx = static_cast<float>(imageWidth) / static_cast<float>(mapWidth);
    const float sy = static_cast<float>(imageHeight) / static_cast<float>(mapHeight);
    const float cx = imageWidth / 2.0f, cy = imageHeight / 2.0f;
    for (int y = 0; y < mapHeight; ++y) {
        const float yy = (static_cast<float>(y) + 0.5f) * sy - cy;
        std::uint8_t* row = map + static_cast<std::size_t>(y) * mapStride;
        for (int x = 0; x < mapWidth; ++x) {
            const float xx = (static_cast<float>(x) + 0.5f) * sx - cx;
            const float rd = std::sqrt(xx * xx + yy * yy) * m.invHalf;
            const float k = lookup(m.ratio, rd * m.invRMax) * m.scale;
            const float fx = std::min(std::max((xx * k + cx) / sx - 0.5f, 0.0f), static_cast<float>(mapWidth - 1));
            const float fy = std::min(std::max((yy * k + cy) / sy - 0.5f, 0.0f), static_cast<float>(mapHeight - 1));
            const int x0 = std::min(static_cast<int>(fx), mapWidth - 2);
            const int y0 = std::min(static_cast<int>(fy), mapHeight - 2);
            const float ux = fx - static_cast<float>(x0), uy = fy - static_cast<float>(y0);
            const std::uint8_t* a = src.data() + static_cast<std::size_t>(y0) * mapStride;
            const std::uint8_t* b = a + mapStride;
            const float v = (a[x0] * (1 - ux) + a[x0 + 1] * ux) * (1 - uy) + (b[x0] * (1 - ux) + b[x0 + 1] * ux) * uy;
            row[x] = static_cast<std::uint8_t>(std::min(std::max(v, 0.0f), 255.0f) + 0.5f);
        }
    }
    return "";
}

}  // namespace redline
