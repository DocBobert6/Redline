// B22 RAW burst align + merge. See burst_merge.h; the contract is calibration/redline_cal/burst_merge.py.
#include "burst_merge.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <functional>
#include <thread>

namespace redline {
namespace {

constexpr int kFineTile = 16;
constexpr int kFineStride = 8;
constexpr int kCoarseTile = 8;
constexpr int kCoarseRadius = 4;
constexpr int kFineRadius = 2;  // 1 loses odd displacements (L1 cannot represent them)
constexpr int kLevelFactors[3] = {2, 4, 4};
constexpr int kReferenceCandidates = 3;
constexpr double kTileC = 2.0;
constexpr double kPixK = 3.0;
constexpr int kMaxThreads = 16;
constexpr double kPi = 3.14159265358979323846;

using Clock = std::chrono::steady_clock;

double msSince(Clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
}

inline int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

// Python floor division / modulo for a positive divisor.
inline int floorDiv(int a, int b) { return a >= 0 ? a / b : -((-a + b - 1) / b); }
inline int floorMod(int a, int b) { return a - floorDiv(a, b) * b; }

void parallelFor(int n, int threads, const std::function<void(int, int)>& body) {
    threads = std::min(std::max(threads, 1), std::min(kMaxThreads, std::max(n, 1)));
    if (threads <= 1 || n <= 1) {
        body(0, n);
        return;
    }
    std::vector<std::thread> pool;
    pool.reserve(static_cast<std::size_t>(threads));
    for (int t = 0; t < threads; ++t) {
        const int a = static_cast<int>(static_cast<long long>(n) * t / threads);
        const int b = static_cast<int>(static_cast<long long>(n) * (t + 1) / threads);
        pool.emplace_back([&body, a, b] { body(a, b); });
    }
    for (auto& th : pool) th.join();
}

struct Image {
    int w = 0;
    int h = 0;
    std::vector<std::int32_t> px;
    std::int32_t at(int y, int x) const { return px[static_cast<std::size_t>(y) * w + x]; }
    const std::int32_t* row(int y) const { return px.data() + static_cast<std::size_t>(y) * w; }
};

using Pyramid = std::vector<Image>;

Image greyOf(const BurstFrame& f, int width, int height, int threads) {
    Image g;
    g.w = width / 2;
    g.h = height / 2;
    g.px.resize(static_cast<std::size_t>(g.w) * g.h);
    parallelFor(g.h, threads, [&](int y0, int y1) {
        for (int y = y0; y < y1; ++y) {
            const std::uint16_t* r0 = f.data + static_cast<std::size_t>(2 * y) * f.rowStride;
            const std::uint16_t* r1 = r0 + f.rowStride;
            std::int32_t* o = g.px.data() + static_cast<std::size_t>(y) * g.w;
            for (int x = 0; x < g.w; ++x) {
                o[x] = static_cast<std::int32_t>(r0[2 * x]) + r0[2 * x + 1] + r1[2 * x] + r1[2 * x + 1];
            }
        }
    });
    return g;
}

Image downsample(const Image& in, int f) {
    Image o;
    o.w = in.w / f;
    o.h = in.h / f;
    o.px.resize(static_cast<std::size_t>(o.w) * o.h);
    const std::int64_t n = static_cast<std::int64_t>(f) * f;
    for (int y = 0; y < o.h; ++y) {
        for (int x = 0; x < o.w; ++x) {
            std::int64_t s = 0;
            for (int dy = 0; dy < f; ++dy) {
                const std::int32_t* r = in.row(y * f + dy) + x * f;
                for (int dx = 0; dx < f; ++dx) s += r[dx];
            }
            o.px[static_cast<std::size_t>(y) * o.w + x] = static_cast<std::int32_t>(s / n);
        }
    }
    return o;
}

Pyramid pyramidOf(const BurstFrame& f, int width, int height, int threads) {
    Pyramid p;
    p.push_back(greyOf(f, width, height, threads));
    for (int factor : kLevelFactors) {
        Image next = downsample(p.back(), factor);
        if (next.w < kCoarseTile || next.h < kCoarseTile) break;
        p.push_back(std::move(next));
    }
    return p;
}

std::int64_t sharpness(const Pyramid& p) {
    const Image& img = p.size() > 1 ? p[1] : p[0];
    std::int64_t s = 0;
    for (int y = 0; y < img.h; ++y) {
        for (int x = 0; x < img.w; ++x) {
            const std::int64_t v = img.at(y, x);
            if (x + 1 < img.w) {
                const std::int64_t d = img.at(y, x + 1) - v;
                s += d * d;
            }
            if (y + 1 < img.h) {
                const std::int64_t d = img.at(y + 1, x) - v;
                s += d * d;
            }
        }
    }
    return s;
}

struct Grid {
    int size = 0;
    int stride = 0;
    int offset = 0;  // origin of tile i = i * stride + offset
    int nx = 0;
    int ny = 0;
    int ox(int j) const { return j * stride + offset; }
    int oy(int i) const { return i * stride + offset; }
    int cx(int j) const { return ox(j) + size / 2; }
    int cy(int i) const { return oy(i) + size / 2; }
};

Grid coarseGrid(int h, int w) {
    Grid g;
    g.size = kCoarseTile;
    g.stride = kCoarseTile;
    g.offset = 0;
    g.ny = (h + kCoarseTile - 1) / kCoarseTile;
    g.nx = (w + kCoarseTile - 1) / kCoarseTile;
    return g;
}

Grid fineGrid(int h, int w) {
    Grid g;
    g.size = kFineTile;
    g.stride = kFineStride;
    g.offset = -kFineStride;
    g.ny = (h - 1) / kFineStride + 2;
    g.nx = (w - 1) / kFineStride + 2;
    return g;
}

// Distance of the tile at (oy, ox) between ref and alt displaced by (dy, dx); inside-image ref pixels only,
// alt sampled with clamped coordinates. Exact integer maths (the parity test compares the argmin).
std::int64_t tileCost(const Image& ref, const Image& alt, int oy, int ox, int size, int dy, int dx, bool l1) {
    const int w = ref.w, h = ref.h;
    std::int64_t cost = 0;
    const bool fast = ox >= 0 && oy >= 0 && ox + size <= w && oy + size <= h && ox + dx >= 0 && oy + dy >= 0 &&
                      ox + dx + size <= w && oy + dy + size <= h;
    if (fast) {
        for (int y = 0; y < size; ++y) {
            const std::int32_t* r = ref.row(oy + y) + ox;
            const std::int32_t* a = alt.row(oy + y + dy) + ox + dx;
            if (l1) {
                std::int32_t s = 0;  // <= 16 * 262140: fits
                for (int x = 0; x < size; ++x) s += std::abs(r[x] - a[x]);
                cost += s;
            } else {
                std::int64_t s = 0;
                for (int x = 0; x < size; ++x) {
                    const std::int64_t d = r[x] - a[x];
                    s += d * d;
                }
                cost += s;
            }
        }
        return cost;
    }
    const int y0 = std::max(oy, 0), y1 = std::min(oy + size, h);
    const int x0 = std::max(ox, 0), x1 = std::min(ox + size, w);
    for (int y = y0; y < y1; ++y) {
        const std::int32_t* r = ref.row(y);
        const std::int32_t* a = alt.row(clampi(y + dy, 0, h - 1));
        for (int x = x0; x < x1; ++x) {
            const std::int64_t d = static_cast<std::int64_t>(r[x]) - a[clampi(x + dx, 0, w - 1)];
            cost += l1 ? (d < 0 ? -d : d) : d * d;
        }
    }
    return cost;
}

// Displacements of one level: (dy, dx) per tile, row-major.
using Disp = std::vector<int>;

Disp alignLevel(const Image& ref, const Image& alt, const Grid& g, const Disp* coarse, const Grid* cg, int factor, int radius,
                bool l1, int threads) {
    Disp d(static_cast<std::size_t>(g.nx) * g.ny * 2, 0);
    parallelFor(g.ny, threads, [&](int i0, int i1) {
        for (int i = i0; i < i1; ++i) {
            for (int j = 0; j < g.nx; ++j) {
                int cand[3][2] = {{0, 0}, {0, 0}, {0, 0}};
                int nc = 1;
                if (coarse != nullptr) {
                    const int span = kCoarseTile * factor;
                    const int cy = g.cy(i), cx = g.cx(j);
                    const int iy = clampi(floorDiv(cy, span), 0, cg->ny - 1);
                    const int ix = clampi(floorDiv(cx, span), 0, cg->nx - 1);
                    const int iy2 = clampi(floorMod(cy, span) >= span / 2 ? iy + 1 : iy - 1, 0, cg->ny - 1);
                    const int ix2 = clampi(floorMod(cx, span) >= span / 2 ? ix + 1 : ix - 1, 0, cg->nx - 1);
                    const int idx[3][2] = {{iy, ix}, {iy, ix2}, {iy2, ix}};
                    for (int c = 0; c < 3; ++c) {
                        const std::size_t k = (static_cast<std::size_t>(idx[c][0]) * cg->nx + idx[c][1]) * 2;
                        cand[c][0] = (*coarse)[k] * factor;
                        cand[c][1] = (*coarse)[k + 1] * factor;
                    }
                    nc = 3;
                }
                const int oy = g.oy(i), ox = g.ox(j);
                int by = cand[0][0], bx = cand[0][1];
                if (nc > 1) {
                    std::int64_t best = tileCost(ref, alt, oy, ox, g.size, by, bx, l1);
                    for (int c = 1; c < nc; ++c) {
                        const std::int64_t v = tileCost(ref, alt, oy, ox, g.size, cand[c][0], cand[c][1], l1);
                        if (v < best) {
                            best = v;
                            by = cand[c][0];
                            bx = cand[c][1];
                        }
                    }
                }
                std::int64_t best = 0;
                int ry = by, rx = bx;
                bool first = true;
                for (int dy = -radius; dy <= radius; ++dy) {
                    for (int dx = -radius; dx <= radius; ++dx) {
                        const std::int64_t v = tileCost(ref, alt, oy, ox, g.size, by + dy, bx + dx, l1);
                        if (first || v < best) {
                            first = false;
                            best = v;
                            ry = by + dy;
                            rx = bx + dx;
                        }
                    }
                }
                const std::size_t k = (static_cast<std::size_t>(i) * g.nx + j) * 2;
                d[k] = ry;
                d[k + 1] = rx;
            }
        }
    });
    return d;
}

Disp align(const Pyramid& ref, const Pyramid& alt, int threads) {
    Disp disp;
    Grid grid;
    bool have = false;
    for (int lvl = static_cast<int>(ref.size()) - 1; lvl >= 1; --lvl) {
        const Grid g = coarseGrid(ref[lvl].h, ref[lvl].w);
        Disp next = alignLevel(ref[lvl], alt[lvl], g, have ? &disp : nullptr, have ? &grid : nullptr, kLevelFactors[lvl],
                               kCoarseRadius, false, threads);
        disp = std::move(next);
        grid = g;
        have = true;
    }
    const Grid g0 = fineGrid(ref[0].h, ref[0].w);
    return alignLevel(ref[0], alt[0], g0, have ? &disp : nullptr, have ? &grid : nullptr, kLevelFactors[0], kFineRadius, true,
                      threads);
}

std::string check(const std::vector<BurstFrame>& frames, const BurstParams& p) {
    if (frames.empty()) return "no frames";
    if (p.width < 4 || p.height < 4 || (p.width & 1) || (p.height & 1)) return "frame dimensions must be even and >= 4";
    for (const auto& f : frames) {
        if (f.data == nullptr) return "missing frame data";
        if (f.rowStride < p.width) return "row stride smaller than width";
    }
    if (!(p.white > p.black[0])) return "white level must exceed black";
    if (p.reference >= static_cast<int>(frames.size())) return "reference index out of range";
    if (!(p.noiseS >= 0 && p.noiseO >= 0) || !std::isfinite(p.noiseS) || !std::isfinite(p.noiseO)) return "bad noise model";
    return "";
}

// Core: fills num (W*H floats) and den (W/2*H/2 floats); the reference is frames[result.reference].
std::string mergeCore(const std::vector<BurstFrame>& frames, const BurstParams& p, std::vector<float>& num, std::vector<float>& den,
                      BurstResult& result) {
    std::string err = check(frames, p);
    if (!err.empty()) return err;
    const int threads = std::min(std::max(p.threads, 1), kMaxThreads);
    const int W = p.width, H = p.height;
    const int w0 = W / 2, h0 = H / 2;
    result = BurstResult();

    // Reference: the sharpest of the first few (their pyramids are kept until the reference is known).
    auto t0 = Clock::now();
    const int nCand = std::min(kReferenceCandidates, static_cast<int>(frames.size()));
    std::vector<Pyramid> cand;
    int ref = p.reference;
    if (ref < 0) {
        std::int64_t best = -1;
        for (int i = 0; i < nCand; ++i) {
            cand.push_back(pyramidOf(frames[i], W, H, threads));
            const std::int64_t s = sharpness(cand.back());
            if (s > best) {
                best = s;
                ref = i;
            }
        }
    }
    result.referenceMs = msSince(t0);
    result.reference = ref;
    t0 = Clock::now();
    Pyramid refPyr = (ref < static_cast<int>(cand.size())) ? cand[ref] : pyramidOf(frames[ref], W, H, threads);
    result.pyramidMs += msSince(t0);

    const Grid g0 = fineGrid(h0, w0);
    result.tilesX = g0.nx;
    result.tilesY = g0.ny;
    const std::size_t nt = static_cast<std::size_t>(g0.nx) * g0.ny;

    // Per tile: inside-pixel count, mean level -> v2 (expected squared difference of two aligned frames).
    const double blackMean = (p.black[0] + p.black[1] + p.black[2] + p.black[3]) / 4.0;
    const double range = p.white - blackMean;
    std::vector<double> v2(nt), count(nt);
    const Image& r0 = refPyr[0];
    for (int i = 0; i < g0.ny; ++i) {
        for (int j = 0; j < g0.nx; ++j) {
            const int y0 = std::max(g0.oy(i), 0), y1 = std::min(g0.oy(i) + kFineTile, h0);
            const int x0 = std::max(g0.ox(j), 0), x1 = std::min(g0.ox(j) + kFineTile, w0);
            std::int64_t s = 0;
            for (int y = y0; y < y1; ++y)
                for (int x = x0; x < x1; ++x) s += r0.at(y, x);
            const double n = static_cast<double>((y1 - y0) * (x1 - x0));
            const double mean = static_cast<double>(s) / n;
            const double xs = std::min(std::max((mean / 4.0 - blackMean) / range, 0.0), 1.0);
            const double sigma2 = 4.0 * (p.noiseS * xs + p.noiseO) * range * range;
            const std::size_t k = static_cast<std::size_t>(i) * g0.nx + j;
            v2[k] = std::max(2.0 * sigma2, 1e-12);
            count[k] = n;
        }
    }
    float win[kFineTile];
    for (int i = 0; i < kFineTile; ++i) win[i] = static_cast<float>(0.5 - 0.5 * std::cos(2.0 * kPi * (i + 0.5) / kFineTile));

    num.assign(static_cast<std::size_t>(W) * H, 0.0f);
    den.assign(static_cast<std::size_t>(w0) * h0, 0.0f);
    result.displacements.assign(frames.size(), {});

    std::vector<double> wt(nt);
    for (int fi = 0; fi < static_cast<int>(frames.size()); ++fi) {
        if (fi == ref) continue;
        BurstFrameStats st;
        st.index = fi;
        t0 = Clock::now();
        Pyramid altPyr = (fi < static_cast<int>(cand.size())) ? std::move(cand[fi]) : pyramidOf(frames[fi], W, H, threads);
        result.pyramidMs += msSince(t0);
        t0 = Clock::now();
        const Disp d = align(refPyr, altPyr, threads);
        st.alignMs = msSince(t0);
        result.alignMs += st.alignMs;
        t0 = Clock::now();
        const Image& a0 = altPyr[0];
        // Tile weights (exact D over inside pixels with the final displacement).
        parallelFor(g0.ny, threads, [&](int i0, int i1) {
            for (int i = i0; i < i1; ++i) {
                for (int j = 0; j < g0.nx; ++j) {
                    const std::size_t k = static_cast<std::size_t>(i) * g0.nx + j;
                    const std::int64_t c = tileCost(r0, a0, g0.oy(i), g0.ox(j), kFineTile, d[2 * k], d[2 * k + 1], false);
                    const double dd = static_cast<double>(c) / count[k];
                    wt[k] = kTileC / (kTileC + std::max(0.0, dd - v2[k]) / v2[k]);
                }
            }
        });
        const BurstFrame& alt = frames[fi];
        // Tile rows of the same parity never overlap: two passes keep the accumulation race-free.
        for (int parity = 0; parity < 2; ++parity) {
            const int rows = (g0.ny - parity + 1) / 2;
            parallelFor(rows, threads, [&](int a, int b) {
                for (int rIdx = a; rIdx < b; ++rIdx) {
                    const int i = 2 * rIdx + parity;
                    const int oy = g0.oy(i);
                    for (int j = 0; j < g0.nx; ++j) {
                        const int ox = g0.ox(j);
                        const std::size_t k = static_cast<std::size_t>(i) * g0.nx + j;
                        const int dy = d[2 * k], dx = d[2 * k + 1];
                        const double tw = wt[k];
                        const double lim = kPixK * kPixK * v2[k];
                        const int y0 = std::max(oy, 0), y1 = std::min(oy + kFineTile, h0);
                        const int x0 = std::max(ox, 0), x1 = std::min(ox + kFineTile, w0);
                        for (int qy = y0; qy < y1; ++qy) {
                            const int ay = clampi(qy + dy, 0, h0 - 1);
                            const float wy = win[qy - oy];
                            const std::int32_t* rr = r0.row(qy);
                            const std::int32_t* ar = a0.row(ay);
                            const std::uint16_t* s0 = alt.data + static_cast<std::size_t>(2 * ay) * alt.rowStride;
                            const std::uint16_t* s1 = s0 + alt.rowStride;
                            float* n0 = num.data() + static_cast<std::size_t>(2 * qy) * W;
                            float* n1 = n0 + W;
                            float* dr = den.data() + static_cast<std::size_t>(qy) * w0;
                            for (int qx = x0; qx < x1; ++qx) {
                                const int ax = clampi(qx + dx, 0, w0 - 1);
                                const double dq = static_cast<double>(rr[qx] - ar[ax]);
                                const double dq2 = dq * dq;
                                const double wq = (dq2 <= lim ? 1.0 : lim / dq2) * tw;
                                const float wgt = static_cast<float>(wq * wy * win[qx - ox]);
                                dr[qx] += wgt;
                                n0[2 * qx] += wgt * s0[2 * ax];
                                n0[2 * qx + 1] += wgt * s0[2 * ax + 1];
                                n1[2 * qx] += wgt * s1[2 * ax];
                                n1[2 * qx + 1] += wgt * s1[2 * ax + 1];
                            }
                        }
                    }
                }
            });
        }
        st.mergeMs = msSince(t0);
        result.mergeMs += st.mergeMs;
        double sumMag = 0, maxMag = 0, sumW = 0;
        int rejected = 0;
        for (std::size_t k = 0; k < nt; ++k) {
            const double mag = 2.0 * std::max(std::abs(d[2 * k]), std::abs(d[2 * k + 1]));
            sumMag += mag;
            maxMag = std::max(maxMag, mag);
            sumW += wt[k];
            if (wt[k] < 0.5) ++rejected;
        }
        st.meanDispPx = sumMag / static_cast<double>(nt);
        st.maxDispPx = maxMag;
        st.meanWeight = sumW / static_cast<double>(nt);
        st.rejectedFraction = static_cast<double>(rejected) / static_cast<double>(nt);
        result.frames.push_back(st);
        if (p.keepDisplacements) result.displacements[fi] = d;
    }
    return "";
}

}  // namespace

std::string mergeBurstFloat(const std::vector<BurstFrame>& frames, const BurstParams& p, float* out, std::size_t strideFloats,
                            BurstResult& result) {
    if (out == nullptr || strideFloats < static_cast<std::size_t>(p.width)) return "bad output";
    std::vector<float> num, den;
    std::string err = mergeCore(frames, p, num, den, result);
    if (!err.empty()) return err;
    const auto t0 = Clock::now();
    const BurstFrame& ref = frames[result.reference];
    const int W = p.width, w0 = p.width / 2;
    parallelFor(p.height, p.threads, [&](int y0, int y1) {
        for (int y = y0; y < y1; ++y) {
            const std::uint16_t* r = ref.data + static_cast<std::size_t>(y) * ref.rowStride;
            const float* n = num.data() + static_cast<std::size_t>(y) * W;
            const float* dr = den.data() + static_cast<std::size_t>(y / 2) * w0;
            float* o = out + static_cast<std::size_t>(y) * strideFloats;
            for (int x = 0; x < W; ++x) o[x] = (static_cast<float>(r[x]) + n[x]) / (1.0f + dr[x / 2]);
        }
    });
    result.finishMs = msSince(t0);
    return "";
}

std::string mergeBurstU16(const std::vector<BurstFrame>& frames, const BurstParams& p, std::uint16_t* out, std::size_t strideElems,
                          float scale, BurstResult& result) {
    if (out == nullptr || strideElems < static_cast<std::size_t>(p.width)) return "bad output";
    if (!(scale > 0) || p.white * scale > 65535.0f) return "scale overflows 16 bits";
    std::vector<float> num, den;
    std::string err = mergeCore(frames, p, num, den, result);
    if (!err.empty()) return err;
    const auto t0 = Clock::now();
    const BurstFrame& ref = frames[result.reference];
    const int W = p.width, w0 = p.width / 2;
    const float hi = p.white * scale;
    parallelFor(p.height, p.threads, [&](int y0, int y1) {
        for (int y = y0; y < y1; ++y) {
            const std::uint16_t* r = ref.data + static_cast<std::size_t>(y) * ref.rowStride;
            const float* n = num.data() + static_cast<std::size_t>(y) * W;
            const float* dr = den.data() + static_cast<std::size_t>(y / 2) * w0;
            std::uint16_t* o = out + static_cast<std::size_t>(y) * strideElems;
            for (int x = 0; x < W; ++x) {
                const float v = (static_cast<float>(r[x]) + n[x]) / (1.0f + dr[x / 2]) * scale;
                o[x] = static_cast<std::uint16_t>(std::min(std::max(v, 0.0f), hi) + 0.5f);
            }
        }
    });
    result.finishMs = msSince(t0);
    return "";
}

// ------------------------------------------------------------------------------------------------ self-check

namespace {

// Deterministic hash noise in [0, 1).
inline double hash01(std::uint32_t x, std::uint32_t y, std::uint32_t seed) {
    std::uint32_t h = x * 374761393u + y * 668265263u + seed * 2246822519u;
    h = (h ^ (h >> 13)) * 1274126177u;
    h ^= h >> 16;
    return static_cast<double>(h) / 4294967296.0;
}

// Clean linear scene value of CFA channel c at scene coordinate (y, x), 0..1.
double scene(int y, int x, int c) {
    // Detail at every pyramid scale: random 40 px cells (coarse levels), waves, and per-quad texture (fine level).
    const double cells = 0.25 * hash01(static_cast<std::uint32_t>(x / 40 + 512), static_cast<std::uint32_t>(y / 40 + 512), 5);
    const double base = 0.12 + cells + 0.06 * std::sin(x / 9.3) * std::cos(y / 7.1) + 0.04 * std::sin((x + 2 * y) / 3.7);
    const double tex = 0.06 * (hash01(static_cast<std::uint32_t>(x / 2 + 4096), static_cast<std::uint32_t>(y / 2 + 4096), 11) - 0.5);
    const double gain[3] = {1.0, 0.9, 0.8};
    return std::min(std::max((base + tex) * gain[c], 0.0), 1.0);
}

}  // namespace

std::string burstSelfCheck(int threads, BurstSelfCheck& out) {
    const auto t0 = Clock::now();
    constexpr int W = 512, H = 384, N = 6;
    const int shifts[N][2] = {{0, 0}, {3, -2}, {-4, 5}, {1, 6}, {-2, -5}, {5, 1}};  // grey pixels
    const double black = 64, white = 1023, rng = white - black, S = 2e-3, O = 2e-5;
    std::vector<std::vector<std::uint16_t>> frames(N, std::vector<std::uint16_t>(static_cast<std::size_t>(W) * H));
    std::vector<double> clean(static_cast<std::size_t>(W) * H);
    std::uint64_t state = 0x9E3779B97F4A7C15ull;
    auto gauss = [&state]() {
        // Box-Muller on a 64-bit LCG.
        auto u = [&state]() {
            state = state * 6364136223846793005ull + 1442695040888963407ull;
            return (static_cast<double>(state >> 11) + 0.5) / 9007199254740992.0;
        };
        const double a = u(), b = u();
        return std::sqrt(-2.0 * std::log(a)) * std::cos(2.0 * kPi * b);
    };
    for (int k = 0; k < N; ++k) {
        for (int y = 0; y < H; ++y) {
            for (int x = 0; x < W; ++x) {
                const int c = (y & 1) == 0 ? ((x & 1) == 0 ? 0 : 1) : ((x & 1) == 0 ? 1 : 2);
                const double m = scene(y + 2 * shifts[k][0] + 64, x + 2 * shifts[k][1] + 64, c) * 0.6;
                const double v = m + gauss() * std::sqrt(S * m + O);
                frames[k][static_cast<std::size_t>(y) * W + x] =
                    static_cast<std::uint16_t>(std::min(std::max(std::lround(v * rng + black), 0L), static_cast<long>(white)));
                if (k == 0) clean[static_cast<std::size_t>(y) * W + x] = m * rng + black;
            }
        }
    }
    std::vector<BurstFrame> in;
    for (auto& f : frames) in.push_back({f.data(), W});
    BurstParams p;
    p.width = W;
    p.height = H;
    for (float& b : p.black) b = static_cast<float>(black);
    p.white = static_cast<float>(white);
    p.noiseS = S;
    p.noiseO = O;
    p.threads = threads;
    p.reference = 0;
    p.keepDisplacements = true;
    std::vector<float> merged(static_cast<std::size_t>(W) * H);
    BurstResult r;
    std::string err = mergeBurstFloat(in, p, merged.data(), W, r);
    if (!err.empty()) return err;
    // Interior tiles (away from the borders by the largest shift) aligned exactly.
    int good = 0, total = 0;
    const Grid g0 = fineGrid(H / 2, W / 2);
    for (int k = 1; k < N; ++k) {
        const auto& d = r.displacements[k];
        for (int i = 0; i < g0.ny; ++i) {
            for (int j = 0; j < g0.nx; ++j) {
                if (g0.oy(i) < 16 || g0.ox(j) < 16 || g0.oy(i) + kFineTile > H / 2 - 16 || g0.ox(j) + kFineTile > W / 2 - 16) continue;
                const std::size_t q = (static_cast<std::size_t>(i) * g0.nx + j) * 2;
                ++total;
                if (d[q] == shifts[0][0] - shifts[k][0] && d[q + 1] == shifts[0][1] - shifts[k][1]) ++good;
            }
        }
    }
    double e1 = 0, e2 = 0;
    for (int y = 32; y < H - 32; ++y) {
        for (int x = 32; x < W - 32; ++x) {
            const std::size_t q = static_cast<std::size_t>(y) * W + x;
            const double a = frames[0][q] - clean[q], b = merged[q] - clean[q];
            e1 += a * a;
            e2 += b * b;
        }
    }
    out.alignedFraction = total == 0 ? 0 : static_cast<double>(good) / total;
    out.noiseRatio = e2 > 0 ? std::sqrt(e1 / e2) : 0;
    out.ms = msSince(t0);
    return "";
}

}  // namespace redline
