// B23 handheld multi-frame super-resolution. See superres.h; the contract is calibration/redline_cal/superres.py.
//
// Speed: the integer alignment is the B22 code (NEON, shared through burst_internal.h); the reference's gradients,
// tile matrices and box means are computed once; Lucas-Kanade, the robustness maps and the gather run on the B22a pool
// in row chunks. The gather accumulates one output row at a time over all frames (6 floats per output pixel), with
// the separable kernel weights looked up per pixel and a fast path for windows away from the region's border.
#include "superres.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <numeric>

#include "burst_internal.h"

#if (defined(__ARM_NEON) || defined(__ARM_NEON__)) && defined(__aarch64__) && !defined(REDLINE_NO_NEON)
#include <arm_neon.h>
#define REDLINE_SR_NEON 1
#else
#define REDLINE_SR_NEON 0
#endif

namespace redline {
namespace {

using detail::Grid;
using detail::Image;
using detail::Pyramid;

constexpr int kMargin = 64;
constexpr int kReferenceCandidates = 3;
constexpr double kSubpixClamp = 1.0;
constexpr int kLkIters = 3;
constexpr double kLkReg = 0.01;
constexpr double kModelScale = 1000.0;
constexpr double kModelMinStrength = 0.05;
constexpr int kModelMinTiles = 12;
constexpr double kModelBands[3] = {3.0, 1.5, 1.0};
constexpr double kModelTol = 0.5;
constexpr int kKernelQ = 64;
constexpr double kSigmaG = 0.25;
constexpr double kSigmaRB = 0.35;
constexpr double kSigmaWideG = 0.65;
constexpr double kSigmaWideRB = 0.75;
constexpr double kCovLo = 0.2;
constexpr double kCovHi = 0.45;
constexpr double kRobKn = 3.0;
constexpr double kRobKl = 1.0;
constexpr int kCoverageMargin = 16;
constexpr int kMaxThreads = 16;
constexpr int kCfaLayouts[4][4] = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};

using Clock = std::chrono::steady_clock;
double msSince(Clock::time_point t0) { return std::chrono::duration<double, std::milli>(Clock::now() - t0).count(); }

inline int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
inline double clampd(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }

// Reflect-101 into [0, n) (numpy: period 2 (n - 1), |i| mod period, folded).
inline int mirror(int i, int n) {
    if (n == 1) return 0;
    const int period = 2 * (n - 1);
    i = std::abs(i) % period;
    return i >= n ? period - i : i;
}

// Bilinear interpolation of the tile flows (tile centres at RAW 16 i, 16 j) along one axis.
struct FlowAxis {
    int i0 = 0;
    int i1 = 0;
    double t = 0;
};

inline FlowAxis flowAxis(double v, int n) {
    FlowAxis a;
    const double f = v / 16.0;
    a.i0 = clampi(static_cast<int>(std::floor(f)), 0, std::max(n - 2, 0));
    a.t = clampd(f - a.i0, 0.0, 1.0);
    a.i1 = std::min(a.i0 + 1, n - 1);
    return a;
}

// Per tile flows (fy, fx), RAW px, row-major tiles.
struct Flow {
    int nx = 0;
    int ny = 0;
    std::vector<double> f;
    void at(const FlowAxis& ay, const FlowAxis& ax, double& fy, double& fx) const {
        const double* a = &f[(static_cast<std::size_t>(ay.i0) * nx + ax.i0) * 2];
        const double* b = &f[(static_cast<std::size_t>(ay.i0) * nx + ax.i1) * 2];
        const double* c = &f[(static_cast<std::size_t>(ay.i1) * nx + ax.i0) * 2];
        const double* d = &f[(static_cast<std::size_t>(ay.i1) * nx + ax.i1) * 2];
        const double w00 = (1 - ay.t) * (1 - ax.t), w01 = (1 - ay.t) * ax.t, w10 = ay.t * (1 - ax.t), w11 = ay.t * ax.t;
        fy = a[0] * w00 + b[0] * w01 + c[0] * w10 + d[0] * w11;
        fx = a[1] * w00 + b[1] * w01 + c[1] * w10 + d[1] * w11;
    }
};

// Shading grid axis (as raw_pipeline.cpp): per full-frame pixel, the two nodes and the weight.
void shadingAxis(int pixels, int nodes, int first, int count, std::vector<int>& i0, std::vector<int>& i1, std::vector<float>& t) {
    i0.resize(count);
    i1.resize(count);
    t.resize(count);
    for (int k = 0; k < count; ++k) {
        const int i = first + k;
        const double f = pixels > 1 ? static_cast<double>(i) * (nodes - 1) / (pixels - 1) : 0.0;
        int lo = nodes > 1 ? std::min(static_cast<int>(std::floor(f)), nodes - 2) : 0;
        lo = std::max(lo, 0);
        i0[k] = lo;
        i1[k] = std::min(lo + 1, nodes - 1);
        t[k] = static_cast<float>(f - lo);
    }
}

// 3x3 box mean with replicated edges (scipy uniform_filter size 3, mode nearest), separable.
void box3(const std::vector<double>& in, int h, int w, std::vector<double>& out, int threads) {
    std::vector<double> tmp(in.size());
    detail::runParallel(h, threads, [&](int y0, int y1) {
        for (int y = y0; y < y1; ++y) {
            const double* r = &in[static_cast<std::size_t>(y) * w];
            double* o = &tmp[static_cast<std::size_t>(y) * w];
            for (int x = 0; x < w; ++x) o[x] = r[std::max(x - 1, 0)] + r[x] + r[std::min(x + 1, w - 1)];
        }
    });
    out.resize(in.size());
    detail::runParallel(h, threads, [&](int y0, int y1) {
        for (int y = y0; y < y1; ++y) {
            const double* a = &tmp[static_cast<std::size_t>(std::max(y - 1, 0)) * w];
            const double* b = &tmp[static_cast<std::size_t>(y) * w];
            const double* c = &tmp[static_cast<std::size_t>(std::min(y + 1, h - 1)) * w];
            double* o = &out[static_cast<std::size_t>(y) * w];
            for (int x = 0; x < w; ++x) o[x] = (a[x] + b[x] + c[x]) / 9.0;
        }
    });
}

std::string checkParams(const SuperResParams& p) {
    if (p.width < 8 || p.height < 8 || (p.width & 1) || (p.height & 1)) return "frame dimensions must be even and >= 8";
    if (p.cfa < 0 || p.cfa > 3) return "unsupported colour filter arrangement";
    for (float b : p.black) {
        if (!std::isfinite(b) || b < 0.0f || !(p.white > b)) return "white level must exceed black";
    }
    if (!(p.noiseS >= 0 && p.noiseO >= 0) || !std::isfinite(p.noiseS) || !std::isfinite(p.noiseO)) return "bad noise model";
    const CropRect& c = p.crop;
    if ((c.left & 1) || (c.top & 1) || (c.width & 1) || (c.height & 1)) return "crop must be even";
    if (c.width < 16 || c.height < 16 || c.left < 0 || c.top < 0 || c.left + c.width > p.width || c.top + c.height > p.height) {
        return "crop outside the frame";
    }
    if (p.outWidth < 1 || p.outHeight < 1 || p.outWidth > 4 * c.width || p.outHeight > 4 * c.height) return "bad output size";
    if (p.shading != nullptr && (p.shadingRows < 1 || p.shadingCols < 1)) return "bad lens shading map size";
    return "";
}

}  // namespace

void superResRegion(const CropRect& crop, int width, int height, int out[4]) {
    const int rl = std::max(0, crop.left - kMargin), rt = std::max(0, crop.top - kMargin);
    int rr = std::min(width, crop.left + crop.width + kMargin), rb = std::min(height, crop.top + crop.height + kMargin);
    rr -= (rr - rl) % 2;
    rb -= (rb - rt) % 2;
    out[0] = rl;
    out[1] = rt;
    out[2] = rr - rl;
    out[3] = rb - rt;
}

namespace {

// ------------------------------------------------------------------------------------------------ the gather

struct Combo {
    int cA[2], cB[2], clA[2], clB[2];
    float bA[2], bB[2];
    float b4[2][4];  // (bA, bB, bA, bB) per row parity
};

struct GatherFrame {
    const std::uint16_t* raw = nullptr;
    const std::uint8_t* rob = nullptr;
    const double* flow = nullptr;
    bool isRef = false;
};

struct GatherCtx {
    int rw = 0, rh = 0, wo = 0, w0 = 0, tileStride = 0;
    const float* scale = nullptr;
    float* out = nullptr;
    std::size_t rowStride = 0, planeStride = 0;
    float table[2][kKernelQ + 1][4];
    // NEON lanes (A, B, A, B): kx4[clA][clB][px] and wy4[clA][clB][py][ky] mix the two colour classes of a window row.
    float kx4[2][2][kKernelQ + 1][4];
    float wy4[2][2][kKernelQ + 1][4][4];
    Combo combos[4];
    int colourAt[4];
    float black[4];
    std::vector<double> rx, ry, ftx, fty;
    std::vector<int> qx, qy, fx0, fx1, fy0, fy1;
    std::vector<GatherFrame> frames;
};

// One frame's 4 x 4 window at RAW position (ay, ax) into the pixel's accumulator a (num R, G, B, den R, G, B).
inline void gatherTaps(const GatherCtx& g, const std::uint16_t* raw, double ay, double ax, float rw8, float* a) {
    // floor() of values > -4096 (positions stay within the region's mirror range).
    const int iyf = static_cast<int>(ay + 4096.0) - 4096, ixf = static_cast<int>(ax + 4096.0) - 4096;
    const int py = static_cast<int>((ay - iyf) * kKernelQ + 0.5);
    const int px = static_cast<int>((ax - ixf) * kKernelQ + 0.5);
    const int iy0 = iyf - 1, ix0 = ixf - 1;
    const int rw = g.rw, rh = g.rh;
    if (iy0 >= 0 && ix0 >= 0 && iy0 + 3 < rh && ix0 + 3 < rw) {
        // Columns ix0..ix0+3 alternate between two CFA columns (taps 0, 2 one colour; 1, 3 the other), and rows ky and
        // ky + 2 share their colours: four pairs per row parity, accumulated in registers.
        const Combo& cb = g.combos[((iy0 & 1) << 1) | (ix0 & 1)];
#if REDLINE_SR_NEON
        {
            const float32x4_t kx[2] = {vld1q_f32(g.kx4[cb.clA[0]][cb.clB[0]][px]), vld1q_f32(g.kx4[cb.clA[1]][cb.clB[1]][px])};
            const float* wy[2] = {g.wy4[cb.clA[0]][cb.clB[0]][py][0], g.wy4[cb.clA[1]][cb.clB[1]][py][0]};
            const float32x4_t bl[2] = {vld1q_f32(cb.b4[0]), vld1q_f32(cb.b4[1])};
            float32x4_t n[2] = {vdupq_n_f32(0), vdupq_n_f32(0)}, d[2] = {vdupq_n_f32(0), vdupq_n_f32(0)};
            std::size_t off = static_cast<std::size_t>(iy0) * rw + ix0;
            for (int ky = 0; ky < 4; ++ky, off += rw) {
                const int r = ky & 1;
                const float32x4_t v = vmulq_f32(vsubq_f32(vcvtq_f32_u32(vmovl_u16(vld1_u16(raw + off))), bl[r]), vld1q_f32(g.scale + off));
                const float32x4_t w = vmulq_f32(kx[r], vld1q_f32(wy[r] + 4 * ky));
                n[r] = vfmaq_f32(n[r], w, v);
                d[r] = vaddq_f32(d[r], w);
            }
            for (int r = 0; r < 2; ++r) {
                // (nA, nB, dA, dB) = lanes 0 + 2 and 1 + 3 of n and d.
                const float32x4_t t = vaddq_f32(vcombine_f32(vget_low_f32(n[r]), vget_low_f32(d[r])), vcombine_f32(vget_high_f32(n[r]), vget_high_f32(d[r])));
                const float32x4_t u = vmulq_n_f32(t, rw8);
                a[cb.cA[r]] += vgetq_lane_f32(u, 0);
                a[cb.cB[r]] += vgetq_lane_f32(u, 1);
                a[3 + cb.cA[r]] += vgetq_lane_f32(u, 2);
                a[3 + cb.cB[r]] += vgetq_lane_f32(u, 3);
            }
            return;
        }
#endif
        float nA[2] = {0, 0}, dA[2] = {0, 0}, nB[2] = {0, 0}, dB[2] = {0, 0};
        for (int ky = 0; ky < 4; ++ky) {
            const int r = ky & 1;
            const std::size_t off = static_cast<std::size_t>(iy0 + ky) * rw + ix0;
            const std::uint16_t* rr = raw + off;
            const float* sr = g.scale + off;
            const float* kA = g.table[cb.clA[r]][px];
            const float* kB = g.table[cb.clB[r]][px];
            const float wyA = g.table[cb.clA[r]][py][ky], wyB = g.table[cb.clB[r]][py][ky];
            const float v0 = (static_cast<float>(rr[0]) - cb.bA[r]) * sr[0];
            const float v1 = (static_cast<float>(rr[1]) - cb.bB[r]) * sr[1];
            const float v2 = (static_cast<float>(rr[2]) - cb.bA[r]) * sr[2];
            const float v3 = (static_cast<float>(rr[3]) - cb.bB[r]) * sr[3];
            nA[r] += wyA * (kA[0] * v0 + kA[2] * v2);
            dA[r] += wyA * (kA[0] + kA[2]);
            nB[r] += wyB * (kB[1] * v1 + kB[3] * v3);
            dB[r] += wyB * (kB[1] + kB[3]);
        }
        for (int r = 0; r < 2; ++r) {
            a[cb.cA[r]] += rw8 * nA[r];
            a[3 + cb.cA[r]] += rw8 * dA[r];
            a[cb.cB[r]] += rw8 * nB[r];
            a[3 + cb.cB[r]] += rw8 * dB[r];
        }
        return;
    }
    for (int ky = 0; ky < 4; ++ky) {
        const int iy = mirror(iy0 + ky, rh);
        const std::uint16_t* rr = raw + static_cast<std::size_t>(iy) * rw;
        const float* sr = g.scale + static_cast<std::size_t>(iy) * rw;
        const int qrow = (iy & 1) << 1;
        for (int kx = 0; kx < 4; ++kx) {
            const int ix = mirror(ix0 + kx, rw);
            const int q = qrow | (ix & 1);
            const int col = g.colourAt[q];
            const int cls = col == 1 ? 0 : 1;
            const float w = g.table[cls][py][ky] * g.table[cls][px][kx] * rw8;
            const float v = (static_cast<float>(rr[ix]) - g.black[q]) * sr[ix];
            a[col] += w * v;
            a[3 + col] += w;
        }
    }
}

void gatherRows(const GatherCtx& g, int ya, int yb) {
    const int wo = g.wo;
    std::vector<float> acc(static_cast<std::size_t>(wo) * 6);
    for (int oy = ya; oy < yb; ++oy) {
        std::fill(acc.begin(), acc.end(), 0.0f);
        const double ry = g.ry[oy];
        const double ty = g.fty[oy];
        for (const GatherFrame& f : g.frames) {
            if (f.isRef) {
                for (int ox = 0; ox < wo; ++ox) gatherTaps(g, f.raw, ry, g.rx[ox], 1.0f, &acc[static_cast<std::size_t>(ox) * 6]);
                continue;
            }
            const std::uint8_t* rob = f.rob + static_cast<std::size_t>(g.qy[oy]) * g.w0;
            const double* f0 = f.flow + static_cast<std::size_t>(g.fy0[oy]) * g.tileStride;
            const double* f1 = f.flow + static_cast<std::size_t>(g.fy1[oy]) * g.tileStride;
            for (int ox = 0; ox < wo; ++ox) {
                const std::uint8_t q = rob[g.qx[ox]];
                if (q == 0) continue;
                const double tx = g.ftx[ox];
                const double w00 = (1 - ty) * (1 - tx), w01 = (1 - ty) * tx, w10 = ty * (1 - tx), w11 = ty * tx;
                const double* a0 = f0 + g.fx0[ox];
                const double* a1 = f0 + g.fx1[ox];
                const double* b0 = f1 + g.fx0[ox];
                const double* b1 = f1 + g.fx1[ox];
                const double ay = ry + (a0[0] * w00 + a1[0] * w01 + b0[0] * w10 + b1[0] * w11);
                const double ax = g.rx[ox] + (a0[1] * w00 + a1[1] * w01 + b0[1] * w10 + b1[1] * w11);
                gatherTaps(g, f.raw, ay, ax, q / 255.0f, &acc[static_cast<std::size_t>(ox) * 6]);
            }
        }
        for (int ch = 0; ch < 3; ++ch) {
            float* o = g.out + ch * g.planeStride + static_cast<std::size_t>(oy) * g.rowStride;
            for (int ox = 0; ox < wo; ++ox) {
                const float* a = &acc[static_cast<std::size_t>(ox) * 6];
                o[ox] = a[3 + ch] > 0.0f ? a[ch] / a[3 + ch] : 0.0f;
            }
        }
    }
}

}  // namespace

// ------------------------------------------------------------------------------------------------ the merger

struct SuperResMerger::Impl {
    SuperResParams p;
    std::vector<float> shading;
    bool begun = false;
    bool finished = false;
    int threads = 1;
    int rl = 0, rt = 0, rw = 0, rh = 0;  // region
    int w0 = 0, h0 = 0;                  // grey level
    Grid g0;
    std::size_t nt = 0;
    int added = 0;
    double blackMean = 0;
    double range = 1;

    struct Frame {
        int index = 0;
        std::vector<std::uint16_t> raw;  // the region, compact (row stride rw)
        Pyramid pyr;                     // until processed (the reference keeps its own)
        std::int64_t sharp = 0;
        Flow flow;                       // alternates only
        std::vector<int> disp;
        std::vector<std::uint8_t> rob;   // h0 x w0, round(255 R)
        bool processed = false;
    };
    std::vector<Frame> frames;
    int ref = -1;  // index into frames (== frame index)

    // Reference precomputations.
    std::vector<double> gy, gx;           // grey gradients
    std::vector<double> hyy, hxx, hxy;    // per tile, regularised
    std::vector<double> strength;         // per tile
    std::vector<double> refMean, refVar;  // 3x3 box mean and local variance of the normalised reference grey

    SuperResResult res;
    std::size_t peakBytes = 0;

    void track() {
        std::size_t s = shading.size() * sizeof(float) + (gy.size() + gx.size() + refMean.size() + refVar.size()) * sizeof(double);
        for (const auto& f : frames) {
            s += f.raw.size() * sizeof(std::uint16_t) + detail::pyramidSizeBytes(f.pyr) + f.rob.size() + f.flow.f.size() * sizeof(double);
        }
        peakBytes = std::max(peakBytes, s);
        res.peakNativeMb = static_cast<double>(peakBytes) / (1024.0 * 1024.0);
    }

    void setupReference(int index) {
        ref = index;
        res.reference = index;
        const Image& r0 = frames[static_cast<std::size_t>(index)].pyr[0];
        const std::size_t n = static_cast<std::size_t>(w0) * h0;
        gy.assign(n, 0.0);
        gx.assign(n, 0.0);
        detail::runParallel(h0, threads, [&](int ya, int yb) {
            for (int y = ya; y < yb; ++y) {
                for (int x = 0; x < w0; ++x) {
                    const std::size_t k = static_cast<std::size_t>(y) * w0 + x;
                    if (y >= 1 && y <= h0 - 2) gy[k] = (static_cast<double>(r0.at(y + 1, x)) - r0.at(y - 1, x)) * 0.5;
                    if (x >= 1 && x <= w0 - 2) gx[k] = (static_cast<double>(r0.at(y, x + 1)) - r0.at(y, x - 1)) * 0.5;
                }
            }
        });
        hyy.assign(nt, 0.0);
        hxx.assign(nt, 0.0);
        hxy.assign(nt, 0.0);
        strength.assign(nt, 0.0);
        detail::runParallel(g0.ny, threads, [&](int ia, int ib) {
            for (int i = ia; i < ib; ++i) {
                for (int j = 0; j < g0.nx; ++j) {
                    double a = 0, b = 0, c = 0;
                    const int y0 = std::max(g0.oy(i), 1), y1 = std::min(g0.oy(i) + detail::kFineTile, h0 - 1);
                    const int x0 = std::max(g0.ox(j), 1), x1 = std::min(g0.ox(j) + detail::kFineTile, w0 - 1);
                    for (int y = y0; y < y1; ++y) {
                        for (int x = x0; x < x1; ++x) {
                            const std::size_t k = static_cast<std::size_t>(y) * w0 + x;
                            a += gy[k] * gy[k];
                            b += gx[k] * gx[k];
                            c += gy[k] * gx[k];
                        }
                    }
                    const std::size_t t = static_cast<std::size_t>(i) * g0.nx + j;
                    strength[t] = 0.5 * (a + b - std::sqrt((a - b) * (a - b) + 4.0 * c * c));
                    const double reg = kLkReg * (a + b);
                    hyy[t] = a + reg;
                    hxx[t] = b + reg;
                    hxy[t] = c;
                }
            }
        });
        std::vector<double> g(n), g2(n);
        for (int y = 0; y < h0; ++y) {
            for (int x = 0; x < w0; ++x) {
                const std::size_t k = static_cast<std::size_t>(y) * w0 + x;
                const double v = (static_cast<double>(r0.at(y, x)) / 4.0 - blackMean) / range;
                g[k] = v;
                g2[k] = v * v;
            }
        }
        std::vector<double> m2;
        box3(g, h0, w0, refMean, threads);
        box3(g2, h0, w0, m2, threads);
        refVar.resize(n);
        for (std::size_t k = 0; k < n; ++k) refVar[k] = std::max(m2[k] - refMean[k] * refMean[k], 0.0);
        frames[static_cast<std::size_t>(index)].processed = true;
    }

    // LK refinement from `start` (grey px per tile); writes the refined displacement.
    void lucasKanade(const Image& alt, const std::vector<double>& start, std::vector<double>& out) {
        const Image& r0 = frames[static_cast<std::size_t>(ref)].pyr[0];
        out = start;
        detail::runParallel(g0.ny, threads, [&](int ia, int ib) {
            for (int i = ia; i < ib; ++i) {
                for (int j = 0; j < g0.nx; ++j) {
                    const std::size_t t = static_cast<std::size_t>(i) * g0.nx + j;
                    const double det = hyy[t] * hxx[t] - hxy[t] * hxy[t];
                    if (!(det > 0.0)) continue;
                    const double sy = start[2 * t], sx = start[2 * t + 1];
                    double dy = sy, dx = sx;
                    const int y0 = std::max(g0.oy(i), 1), y1 = std::min(g0.oy(i) + detail::kFineTile, h0 - 1);
                    const int x0 = std::max(g0.ox(j), 1), x1 = std::min(g0.ox(j) + detail::kFineTile, w0 - 1);
                    for (int it = 0; it < kLkIters; ++it) {
                        double by = 0, bx = 0;
                        for (int y = y0; y < y1; ++y) {
                            // Bilinear (clamped) sample of the alternate at (y + dy, x + dx).
                            const double py = clampd(y + dy, 0.0, h0 - 1.0);
                            const int iy = std::min(static_cast<int>(std::floor(py)), h0 - 2);
                            const double ty = py - iy;
                            const std::uint16_t* a0 = alt.row(iy);
                            const std::uint16_t* a1 = alt.row(iy + 1);
                            const std::uint16_t* rr = r0.row(y);
                            const double* jy = &gy[static_cast<std::size_t>(y) * w0];
                            const double* jx = &gx[static_cast<std::size_t>(y) * w0];
                            for (int x = x0; x < x1; ++x) {
                                const double px = clampd(x + dx, 0.0, w0 - 1.0);
                                const int ix = std::min(static_cast<int>(std::floor(px)), w0 - 2);
                                const double tx = px - ix;
                                const double v = a0[ix] * (1 - ty) * (1 - tx) + a0[ix + 1] * (1 - ty) * tx + a1[ix] * ty * (1 - tx) +
                                                 a1[ix + 1] * ty * tx;
                                const double e = v - rr[x];
                                by += jy[x] * e;
                                bx += jx[x] * e;
                            }
                        }
                        dy -= (hxx[t] * by - hxy[t] * bx) / det;
                        dx -= (hyy[t] * bx - hxy[t] * by) / det;
                        dy = clampd(dy, sy - kSubpixClamp, sy + kSubpixClamp);
                        dx = clampd(dx, sx - kSubpixClamp, sx + kSubpixClamp);
                    }
                    out[2 * t] = dy;
                    out[2 * t + 1] = dx;
                }
            }
        });
    }

    // The frame's affine motion model (3 x 2, row-major [1, y, x] x (fy, fx)); false when there is none.
    bool fitMotion(const std::vector<double>& flow, double P[6]) const {
        std::vector<double> wt(nt, 0.0);
        double wmax = 0;
        for (int i = 0; i < g0.ny; ++i) {
            if (!(g0.oy(i) >= 0 && g0.oy(i) + detail::kFineTile <= h0)) continue;
            for (int j = 0; j < g0.nx; ++j) {
                if (!(g0.ox(j) >= 0 && g0.ox(j) + detail::kFineTile <= w0)) continue;
                const std::size_t t = static_cast<std::size_t>(i) * g0.nx + j;
                wt[t] = strength[t];
                wmax = std::max(wmax, wt[t]);
            }
        }
        if (!(wmax > 0)) return false;
        for (auto& v : wt) v = v >= kModelMinStrength * wmax ? v : 0.0;
        // Weighted median translation per axis.
        double pred0[2];
        std::vector<std::size_t> order(nt);
        for (int c = 0; c < 2; ++c) {
            std::iota(order.begin(), order.end(), 0);
            std::stable_sort(order.begin(), order.end(), [&](std::size_t a, std::size_t b) { return flow[2 * a + c] < flow[2 * b + c]; });
            double total = 0;
            for (double v : wt) total += v;
            double cw = 0;
            pred0[c] = flow[2 * order[0] + c];
            for (std::size_t k = 0; k < nt; ++k) {
                cw += wt[order[k]];
                if (cw >= 0.5 * total) {
                    pred0[c] = flow[2 * order[k] + c];
                    break;
                }
            }
        }
        std::vector<double> pred(2 * nt);
        for (std::size_t t = 0; t < nt; ++t) {
            pred[2 * t] = pred0[0];
            pred[2 * t + 1] = pred0[1];
        }
        bool have = false;
        for (double band : kModelBands) {
            double A[3][3] = {{0, 0, 0}, {0, 0, 0}, {0, 0, 0}};
            double B[3][2] = {{0, 0}, {0, 0}, {0, 0}};
            int count = 0;
            for (int i = 0; i < g0.ny; ++i) {
                const double y = 2.0 * g0.cy(i) / kModelScale;
                for (int j = 0; j < g0.nx; ++j) {
                    const std::size_t t = static_cast<std::size_t>(i) * g0.nx + j;
                    const double w = wt[t];
                    if (!(w > 0)) continue;
                    if (std::max(std::abs(flow[2 * t] - pred[2 * t]), std::abs(flow[2 * t + 1] - pred[2 * t + 1])) > band) continue;
                    ++count;
                    const double a[3] = {1.0, y, 2.0 * g0.cx(j) / kModelScale};
                    for (int r = 0; r < 3; ++r) {
                        for (int c = 0; c < 3; ++c) A[r][c] += w * a[r] * a[c];
                        B[r][0] += w * a[r] * flow[2 * t];
                        B[r][1] += w * a[r] * flow[2 * t + 1];
                    }
                }
            }
            if (count < kModelMinTiles) return false;
            // Solve A P = B (3x3, symmetric positive semi-definite) by Gaussian elimination with partial pivoting.
            double M[3][5];
            for (int r = 0; r < 3; ++r) {
                for (int c = 0; c < 3; ++c) M[r][c] = A[r][c];
                M[r][3] = B[r][0];
                M[r][4] = B[r][1];
            }
            for (int c = 0; c < 3; ++c) {
                int piv = c;
                for (int r = c + 1; r < 3; ++r)
                    if (std::abs(M[r][c]) > std::abs(M[piv][c])) piv = r;
                if (!(std::abs(M[piv][c]) > 1e-300)) return false;
                if (piv != c)
                    for (int k = 0; k < 5; ++k) std::swap(M[c][k], M[piv][k]);
                for (int r = 0; r < 3; ++r) {
                    if (r == c) continue;
                    const double f = M[r][c] / M[c][c];
                    for (int k = c; k < 5; ++k) M[r][k] -= f * M[c][k];
                }
            }
            for (int r = 0; r < 3; ++r) {
                P[r * 2] = M[r][3] / M[r][r];
                P[r * 2 + 1] = M[r][4] / M[r][r];
            }
            have = true;
            for (int i = 0; i < g0.ny; ++i) {
                const double y = 2.0 * g0.cy(i) / kModelScale;
                for (int j = 0; j < g0.nx; ++j) {
                    const double x = 2.0 * g0.cx(j) / kModelScale;
                    const std::size_t t = static_cast<std::size_t>(i) * g0.nx + j;
                    pred[2 * t] = P[0] + P[2] * y + P[4] * x;
                    pred[2 * t + 1] = P[1] + P[3] * y + P[5] * x;
                }
            }
        }
        return have;
    }

    void process(Frame& f) {
        SuperResFrameStats st;
        st.index = f.index;
        auto t0 = Clock::now();
        const Frame& rf = frames[static_cast<std::size_t>(ref)];
        f.disp = detail::alignTiles(rf.pyr, f.pyr, threads);
        std::vector<double> start(2 * nt), d1;
        for (std::size_t k = 0; k < 2 * nt; ++k) start[k] = f.disp[k];
        lucasKanade(f.pyr[0], start, d1);
        f.flow.nx = g0.nx;
        f.flow.ny = g0.ny;
        f.flow.f.resize(2 * nt);
        for (std::size_t k = 0; k < 2 * nt; ++k) f.flow.f[k] = 2.0 * d1[k];
        double P[6];
        if (fitMotion(f.flow.f, P)) {
            st.model = true;
            int off = 0;
            for (int i = 0; i < g0.ny; ++i) {
                const double y = 2.0 * g0.cy(i) / kModelScale;
                for (int j = 0; j < g0.nx; ++j) {
                    const double x = 2.0 * g0.cx(j) / kModelScale;
                    const std::size_t t = static_cast<std::size_t>(i) * g0.nx + j;
                    const double my = P[0] + P[2] * y + P[4] * x, mx = P[1] + P[3] * y + P[5] * x;
                    if (std::max(std::abs(f.flow.f[2 * t] - my), std::abs(f.flow.f[2 * t + 1] - mx)) > kModelTol) {
                        f.flow.f[2 * t] = my;
                        f.flow.f[2 * t + 1] = mx;
                        ++off;
                    }
                }
            }
            st.modelResolved = static_cast<double>(off) / static_cast<double>(nt);
            const double cy = rh / 2.0 / kModelScale, cx = rw / 2.0 / kModelScale;
            st.motionPx[0] = P[0] + P[2] * cy + P[4] * cx;
            st.motionPx[1] = P[1] + P[3] * cy + P[5] * cx;
        }
        st.alignMs = msSince(t0);
        res.alignMs += st.alignMs;
        t0 = Clock::now();
        robustness(f);
        st.robustMs = msSince(t0);
        res.robustMs += st.robustMs;
        double sumMag = 0, sumSub = 0;
        for (std::size_t t = 0; t < nt; ++t) {
            sumMag += 2.0 * std::max(std::abs(f.disp[2 * t]), std::abs(f.disp[2 * t + 1]));
            sumSub += std::abs(f.flow.f[2 * t] - 2.0 * f.disp[2 * t]) + std::abs(f.flow.f[2 * t + 1] - 2.0 * f.disp[2 * t + 1]);
        }
        st.meanDispPx = sumMag / static_cast<double>(nt);
        st.meanSubpixPx = sumSub / static_cast<double>(2 * nt);
        double sumR = 0;
        std::size_t rej = 0;
        for (std::uint8_t q : f.rob) {
            const double r = q / 255.0;
            sumR += r;
            if (r < 0.5) ++rej;
        }
        st.meanRobustness = sumR / static_cast<double>(f.rob.size());
        st.rejectedFraction = static_cast<double>(rej) / static_cast<double>(f.rob.size());
        res.frames.push_back(st);
        if (p.keepFlows) {
            if (static_cast<int>(res.displacements.size()) <= f.index) {
                res.displacements.resize(static_cast<std::size_t>(f.index) + 1);
                res.flows.resize(static_cast<std::size_t>(f.index) + 1);
            }
            res.displacements[static_cast<std::size_t>(f.index)] = f.disp;
            std::vector<float> fl(2 * nt);
            for (std::size_t k = 0; k < 2 * nt; ++k) fl[k] = static_cast<float>(f.flow.f[k]);
            res.flows[static_cast<std::size_t>(f.index)] = fl;
        }
        Pyramid().swap(f.pyr);
        f.processed = true;
        track();
    }

    // Step 6: per-quad robustness of an alternate frame (uint8, round(255 R)).
    void robustness(Frame& f) {
        const Image& a0 = f.pyr[0];
        const std::size_t n = static_cast<std::size_t>(w0) * h0;
        std::vector<double> warped(n);
        std::vector<FlowAxis> axX(static_cast<std::size_t>(w0));
        for (int x = 0; x < w0; ++x) axX[static_cast<std::size_t>(x)] = flowAxis(2.0 * x + 0.5, g0.nx);
        detail::runParallel(h0, threads, [&](int ya, int yb) {
            for (int y = ya; y < yb; ++y) {
                const FlowAxis ay = flowAxis(2.0 * y + 0.5, g0.ny);
                for (int x = 0; x < w0; ++x) {
                    double fy, fx;
                    f.flow.at(ay, axX[static_cast<std::size_t>(x)], fy, fx);
                    const double py = clampd(y + fy / 2.0, 0.0, h0 - 1.0);
                    const double px = clampd(x + fx / 2.0, 0.0, w0 - 1.0);
                    const int y0 = std::min(static_cast<int>(std::floor(py)), h0 - 1);
                    const int x0 = std::min(static_cast<int>(std::floor(px)), w0 - 1);
                    const double ty = py - y0, tx = px - x0;
                    const int y1 = std::min(y0 + 1, h0 - 1), x1 = std::min(x0 + 1, w0 - 1);
                    auto g = [&](int yy, int xx) { return (static_cast<double>(a0.at(yy, xx)) / 4.0 - blackMean) / range; };
                    warped[static_cast<std::size_t>(y) * w0 + x] =
                        g(y0, x0) * (1 - ty) * (1 - tx) + g(y0, x1) * (1 - ty) * tx + g(y1, x0) * ty * (1 - tx) + g(y1, x1) * ty * tx;
                }
            }
        });
        std::vector<double> ma;
        box3(warped, h0, w0, ma, threads);
        std::vector<double> r(n);
        for (std::size_t k = 0; k < n; ++k) {
            const double mr = refMean[k];
            const double sd2 = 2.0 * (p.noiseS * clampd(mr, 0.0, 1.0) + p.noiseO) / 36.0;
            const double d = mr - ma[k];
            const double d2 = d * d;
            const double lim = kRobKn * kRobKn * sd2 + kRobKl * kRobKl * refVar[k];
            r[k] = d2 <= lim ? 1.0 : lim / std::max(d2, 1e-300);
        }
        // 3x3 minimum (edges clamped), then 8 bits.
        f.rob.resize(n);
        detail::runParallel(h0, threads, [&](int ya, int yb) {
            for (int y = ya; y < yb; ++y) {
                for (int x = 0; x < w0; ++x) {
                    double m = 1e300;
                    for (int dy = -1; dy <= 1; ++dy) {
                        const double* row = &r[static_cast<std::size_t>(clampi(y + dy, 0, h0 - 1)) * w0];
                        for (int dx = -1; dx <= 1; ++dx) m = std::min(m, row[clampi(x + dx, 0, w0 - 1)]);
                    }
                    f.rob[static_cast<std::size_t>(y) * w0 + x] = static_cast<std::uint8_t>(std::floor(m * 255.0 + 0.5));
                }
            }
        });
    }

    void decide() {
        int pick = -1;
        if (p.reference >= 0) {
            pick = p.reference;
        } else {
            std::int64_t best = -1;
            const int n = std::min(kReferenceCandidates, static_cast<int>(frames.size()));
            for (int c = 0; c < n; ++c) {
                if (frames[static_cast<std::size_t>(c)].sharp > best) {
                    best = frames[static_cast<std::size_t>(c)].sharp;
                    pick = c;
                }
            }
        }
        setupReference(pick);
        for (auto& f : frames)
            if (!f.processed) process(f);
    }

    double coverage() const {
        const int n = static_cast<int>(frames.size());
        if (n < 2) return 0.0;
        std::vector<int> rows, cols;
        for (int i = 0; i < g0.ny; ++i)
            if (g0.oy(i) >= kCoverageMargin && g0.oy(i) + detail::kFineTile <= h0 - kCoverageMargin) rows.push_back(i);
        for (int j = 0; j < g0.nx; ++j)
            if (g0.ox(j) >= kCoverageMargin && g0.ox(j) + detail::kFineTile <= w0 - kCoverageMargin) cols.push_back(j);
        if (rows.empty() || cols.empty()) {
            rows.resize(static_cast<std::size_t>(g0.ny));
            cols.resize(static_cast<std::size_t>(g0.nx));
            std::iota(rows.begin(), rows.end(), 0);
            std::iota(cols.begin(), cols.end(), 0);
        }
        auto bin = [](double v) {
            double m = std::fmod(v + 0.25, 2.0);
            if (m < 0) m += 2.0;
            return static_cast<int>(std::floor(m * 2.0)) % 4;
        };
        double sum = 0;
        for (int i : rows) {
            for (int j : cols) {
                const std::size_t t = static_cast<std::size_t>(i) * g0.nx + j;
                std::uint32_t hits = 1u;  // the reference, phase (0, 0)
                for (const auto& f : frames) {
                    if (f.index == ref) continue;
                    hits |= 1u << (bin(f.flow.f[2 * t]) * 4 + bin(f.flow.f[2 * t + 1]));
                }
                int c = 0;
                for (std::uint32_t h = hits; h; h &= h - 1) ++c;
                sum += c;
            }
        }
        const double mean = sum / static_cast<double>(rows.size() * cols.size());
        return (mean - 1.0) / (std::min(16, n) - 1);
    }

    // Step 7: the gather, one output row at a time.
    void gather(float* out, std::size_t rowStride, std::size_t planeStride) {
        const int ho = p.outHeight, wo = p.outWidth;
        const double cov = coverage();
        res.coverage = cov;
        const double s = clampd((cov - kCovLo) / (kCovHi - kCovLo), 0.0, 1.0);
        const double sg = kSigmaWideG + s * (kSigmaG - kSigmaWideG);
        const double srb = kSigmaWideRB + s * (kSigmaRB - kSigmaWideRB);
        res.sigmaG = sg;
        res.sigmaRB = srb;
        float table[2][kKernelQ + 1][4];
        for (int q = 0; q <= kKernelQ; ++q) {
            for (int k = 0; k < 4; ++k) {
                const double t = k - 1 - static_cast<double>(q) / kKernelQ;
                table[0][q][k] = static_cast<float>(std::exp(-(t * t) / (2.0 * sg * sg)));
                table[1][q][k] = static_cast<float>(std::exp(-(t * t) / (2.0 * srb * srb)));
            }
        }
        // Normalisation map of the region: shade / (white - black[p]), and black per CFA position.
        std::vector<float> scale(static_cast<std::size_t>(rw) * rh);
        {
            const bool hasShading = !shading.empty();
            std::vector<int> ax0, ax1, ay0, ay1;
            std::vector<float> axt, ayt;
            int chan[4];
            for (int q = 0; q < 4; ++q) {
                const int colour = kCfaLayouts[p.cfa][q];
                chan[q] = colour == 0 ? 0 : colour == 2 ? 3 : ((q >> 1) == 0 ? 1 : 2);
            }
            if (hasShading) {
                shadingAxis(p.width, p.shadingCols, rl, rw, ax0, ax1, axt);
                shadingAxis(p.height, p.shadingRows, rt, rh, ay0, ay1, ayt);
            }
            detail::runParallel(rh, threads, [&](int ya, int yb) {
                for (int y = ya; y < yb; ++y) {
                    for (int x = 0; x < rw; ++x) {
                        const int q = ((y & 1) << 1) | (x & 1);
                        float g = 1.0f;
                        if (hasShading) {
                            const int c = chan[q];
                            const int cols = p.shadingCols;
                            auto m = [&](int r, int cc) { return shading[(static_cast<std::size_t>(r) * cols + cc) * 4 + c]; };
                            const float ty = ayt[y], tx = axt[x];
                            g = m(ay0[y], ax0[x]) * (1 - ty) * (1 - tx) + m(ay0[y], ax1[x]) * (1 - ty) * tx + m(ay1[y], ax0[x]) * ty * (1 - tx) +
                                m(ay1[y], ax1[x]) * ty * tx;
                        }
                        scale[static_cast<std::size_t>(y) * rw + x] = g / (p.white - p.black[q]);
                    }
                }
            });
        }
        GatherCtx g;
        g.rw = rw;
        g.rh = rh;
        g.wo = wo;
        g.scale = scale.data();
        g.out = out;
        g.rowStride = rowStride;
        g.planeStride = planeStride;
        std::memcpy(g.table, table, sizeof(table));
        for (int k = 0; k < 4; ++k) {
            // k = (iy0 & 1) << 1 | (ix0 & 1): the colours of the window's columns A (even taps) and B (odd taps) per row parity.
            for (int r = 0; r < 2; ++r) {
                const int qA = ((((k >> 1) + r) & 1) << 1) | (k & 1), qB = qA ^ 1;
                Combo& cb = g.combos[k];
                cb.cA[r] = kCfaLayouts[p.cfa][qA];
                cb.cB[r] = kCfaLayouts[p.cfa][qB];
                cb.bA[r] = p.black[qA];
                cb.bB[r] = p.black[qB];
                cb.clA[r] = cb.cA[r] == 1 ? 0 : 1;
                cb.clB[r] = cb.cB[r] == 1 ? 0 : 1;
                cb.b4[r][0] = cb.b4[r][2] = cb.bA[r];
                cb.b4[r][1] = cb.b4[r][3] = cb.bB[r];
            }
        }
        for (int ca = 0; ca < 2; ++ca) {
            for (int cbl = 0; cbl < 2; ++cbl) {
                for (int q = 0; q <= kKernelQ; ++q) {
                    for (int k = 0; k < 4; ++k) {
                        g.kx4[ca][cbl][q][k] = table[(k & 1) ? cbl : ca][q][k];
                        for (int l = 0; l < 4; ++l) g.wy4[ca][cbl][q][k][l] = table[(l & 1) ? cbl : ca][q][k];
                    }
                }
            }
        }
        for (int q = 0; q < 4; ++q) {
            g.colourAt[q] = kCfaLayouts[p.cfa][q];
            g.black[q] = p.black[q];
        }
        const CropRect& c = p.crop;
        g.rx.resize(static_cast<std::size_t>(wo));
        g.qx.resize(static_cast<std::size_t>(wo));
        g.fx0.resize(static_cast<std::size_t>(wo));
        g.fx1.resize(static_cast<std::size_t>(wo));
        g.ftx.resize(static_cast<std::size_t>(wo));
        for (int ox = 0; ox < wo; ++ox) {
            const double rx = (c.left - rl) + (ox + 0.5) * c.width / wo - 0.5;
            g.rx[ox] = rx;
            g.qx[ox] = clampi(static_cast<int>(std::floor((rx + 0.5) / 2.0)), 0, w0 - 1);
            const FlowAxis ax = flowAxis(rx, g0.nx);
            g.fx0[ox] = 2 * ax.i0;
            g.fx1[ox] = 2 * ax.i1;
            g.ftx[ox] = ax.t;
        }
        g.ry.resize(static_cast<std::size_t>(ho));
        g.qy.resize(static_cast<std::size_t>(ho));
        g.fy0.resize(static_cast<std::size_t>(ho));
        g.fy1.resize(static_cast<std::size_t>(ho));
        g.fty.resize(static_cast<std::size_t>(ho));
        for (int oy = 0; oy < ho; ++oy) {
            const double ry = (c.top - rt) + (oy + 0.5) * c.height / ho - 0.5;
            g.ry[oy] = ry;
            g.qy[oy] = clampi(static_cast<int>(std::floor((ry + 0.5) / 2.0)), 0, h0 - 1);
            const FlowAxis ay = flowAxis(ry, g0.ny);
            g.fy0[oy] = ay.i0;
            g.fy1[oy] = ay.i1;
            g.fty[oy] = ay.t;
        }
        for (const auto& f : frames) {
            GatherFrame gf;
            gf.raw = f.raw.data();
            gf.isRef = f.index == ref;
            if (!gf.isRef) {
                gf.rob = f.rob.data();
                gf.flow = f.flow.f.data();
            }
            g.frames.push_back(gf);
        }
        g.w0 = w0;
        g.tileStride = 2 * g0.nx;
        detail::runParallel(ho, threads, [&g](int ya, int yb) { gatherRows(g, ya, yb); });
    }
};

SuperResMerger::SuperResMerger() : impl_(new Impl()) {}
SuperResMerger::~SuperResMerger() = default;

std::string SuperResMerger::begin(const SuperResParams& p) {
    const std::string err = checkParams(p);
    if (!err.empty()) return err;
    impl_.reset(new Impl());
    Impl& m = *impl_;
    m.p = p;
    if (p.shading != nullptr) {
        m.shading.assign(p.shading, p.shading + static_cast<std::size_t>(p.shadingRows) * p.shadingCols * 4);
    }
    m.p.shading = nullptr;
    m.begun = true;
    m.threads = std::min(std::max(p.threads, 1), kMaxThreads);
    int r[4];
    superResRegion(p.crop, p.width, p.height, r);
    m.rl = r[0];
    m.rt = r[1];
    m.rw = r[2];
    m.rh = r[3];
    m.w0 = m.rw / 2;
    m.h0 = m.rh / 2;
    m.g0 = detail::fineTileGrid(m.h0, m.w0);
    m.nt = static_cast<std::size_t>(m.g0.nx) * m.g0.ny;
    m.blackMean = (p.black[0] + p.black[1] + p.black[2] + p.black[3]) / 4.0;
    m.range = p.white - m.blackMean;
    for (int k = 0; k < 4; ++k) m.res.region[k] = r[k];
    m.res.tilesX = m.g0.nx;
    m.res.tilesY = m.g0.ny;
    m.res.threads = m.threads;
    return "";
}

std::string SuperResMerger::add(const BurstFrame& frame, std::vector<int>& released) {
    Impl& m = *impl_;
    if (!m.begun) return "merger not started";
    if (m.finished) return "merger already finished";
    if (frame.data == nullptr) return "missing frame data";
    if (frame.rowStride < m.p.width) return "row stride smaller than width";
    const int index = m.added++;
    m.frames.emplace_back();
    Impl::Frame& f = m.frames.back();
    f.index = index;
    auto t0 = Clock::now();
    f.raw.resize(static_cast<std::size_t>(m.rw) * m.rh);
    for (int y = 0; y < m.rh; ++y) {
        std::memcpy(&f.raw[static_cast<std::size_t>(y) * m.rw], frame.data + static_cast<std::size_t>(m.rt + y) * frame.rowStride + m.rl,
                    static_cast<std::size_t>(m.rw) * sizeof(std::uint16_t));
    }
    m.res.copyMs += msSince(t0);
    released.push_back(index);  // the region is ours now
    t0 = Clock::now();
    f.pyr = detail::buildPyramid(BurstFrame{f.raw.data(), m.rw}, m.rw, m.rh, m.threads);
    m.res.maxRegionsHeld = static_cast<int>(m.frames.size());
    if (m.ref >= 0) {
        m.res.alignMs += msSince(t0);
        m.process(f);
        return "";
    }
    const bool fixed = m.p.reference >= 0;
    f.sharp = fixed ? 0 : detail::pyramidSharpness(f.pyr);
    m.res.referenceMs += msSince(t0);
    m.track();
    const bool ready = fixed ? index == m.p.reference : static_cast<int>(m.frames.size()) == kReferenceCandidates;
    if (ready) m.decide();
    return "";
}

std::string SuperResMerger::finish(float* out, std::size_t rowStride, std::size_t planeStride, SuperResResult& result) {
    Impl& m = *impl_;
    if (!m.begun) return "merger not started";
    if (m.finished) return "merger already finished";
    if (m.added == 0) return "no frames";
    if (out == nullptr || rowStride < static_cast<std::size_t>(m.p.outWidth) ||
        planeStride < rowStride * static_cast<std::size_t>(m.p.outHeight)) {
        return "bad output";
    }
    if (m.ref < 0) {
        if (m.p.reference >= m.added) return "reference index out of range";
        m.decide();
    }
    m.finished = true;
    const auto t0 = Clock::now();
    m.gather(out, rowStride, planeStride);
    m.res.gatherMs = msSince(t0);
    std::sort(m.res.frames.begin(), m.res.frames.end(), [](const SuperResFrameStats& a, const SuperResFrameStats& b) { return a.index < b.index; });
    m.res.framesAdded = m.added;
    if (m.p.keepFlows) {
        m.res.displacements.resize(static_cast<std::size_t>(m.added));
        m.res.flows.resize(static_cast<std::size_t>(m.added));
    }
    result = m.res;
    std::vector<Impl::Frame>().swap(m.frames);
    return "";
}

std::string SuperResMerger::setShading(const float* map, int rows, int cols) {
    Impl& m = *impl_;
    if (!m.begun) return "merger not started";
    if (m.finished) return "merger already finished";
    if (map == nullptr) {
        m.shading.clear();
        return "";
    }
    if (rows < 1 || cols < 1) return "bad lens shading map size";
    m.shading.assign(map, map + static_cast<std::size_t>(rows) * cols * 4);
    m.p.shadingRows = rows;
    m.p.shadingCols = cols;
    return "";
}

int SuperResMerger::added() const { return impl_->added; }
int SuperResMerger::reference() const { return impl_->ref; }

std::string superResMerge(const std::vector<BurstFrame>& frames, const SuperResParams& p, float* out, std::size_t rowStride,
                          std::size_t planeStride, SuperResResult& result) {
    if (frames.empty()) return "no frames";
    if (p.reference >= static_cast<int>(frames.size())) return "reference index out of range";
    SuperResMerger m;
    std::string err = m.begin(p);
    if (!err.empty()) return err;
    std::vector<int> released;
    for (const auto& f : frames) {
        err = m.add(f, released);
        if (!err.empty()) return err;
    }
    return m.finish(out, rowStride, planeStride, result);
}

// ------------------------------------------------------------------------------------------------ self-check

namespace {

constexpr double kPi = 3.14159265358979323846;

// A continuous grey-ish scene: random cosines (texture for alignment) plus a grating above the sensor's Nyquist in the
// middle; box-integrated over one RAW pixel analytically (each cosine scaled by sinc in both axes).
struct Scene {
    struct Wave {
        double ky, kx, ph, amp[3];
    };
    std::vector<Wave> waves;
    double gk[2] = {0, 0};
    double cy0 = 0, cy1 = 0, cx0 = 0, cx1 = 0;

    static double sinc(double v) { return v == 0.0 ? 1.0 : std::sin(kPi * v) / (kPi * v); }

    explicit Scene(int h, int w) {
        std::uint32_t s = 12345u;
        auto rnd = [&]() {
            s = s * 1664525u + 1013904223u;
            return (s >> 8) / 16777216.0;
        };
        for (int i = 0; i < 40; ++i) {
            const double f = 0.01 + 0.3 * rnd() * rnd();
            const double th = 2 * kPi * rnd();
            Wave wv{f * std::sin(th), f * std::cos(th), 2 * kPi * rnd(), {0, 0, 0}};
            const double a = 0.04 * rnd() + 0.01;
            for (int c = 0; c < 3; ++c) wv.amp[c] = a * (0.8 + 0.4 * rnd());
            waves.push_back(wv);
        }
        gk[0] = 0.62 * std::sin(0.12);
        gk[1] = 0.62 * std::cos(0.12);
        cy0 = h * 0.3;
        cy1 = h * 0.7;
        cx0 = w * 0.3;
        cx1 = w * 0.7;
    }

    // Values of all three channels on the grid ys x xs (separable per wave: cos(a + b) = cos a cos b - sin a sin b).
    void grid(const std::vector<double>& ys, const std::vector<double>& xs, std::vector<double> out[3]) const {
        const std::size_t ny = ys.size(), nx = xs.size();
        for (int c = 0; c < 3; ++c) out[c].assign(ny * nx, 0.4);
        std::vector<double> cy(ny), sy(ny), cx(nx), sx(nx);
        auto add = [&](double ky, double kx, double ph, const double amp[3], bool patch) {
            for (std::size_t i = 0; i < ny; ++i) {
                cy[i] = std::cos(2 * kPi * ky * ys[i] + ph);
                sy[i] = std::sin(2 * kPi * ky * ys[i] + ph);
            }
            for (std::size_t j = 0; j < nx; ++j) {
                cx[j] = std::cos(2 * kPi * kx * xs[j]);
                sx[j] = std::sin(2 * kPi * kx * xs[j]);
            }
            for (std::size_t i = 0; i < ny; ++i) {
                if (patch && !(ys[i] > cy0 && ys[i] < cy1)) continue;
                for (std::size_t j = 0; j < nx; ++j) {
                    if (patch && !(xs[j] > cx0 && xs[j] < cx1)) continue;
                    const double v = cy[i] * cx[j] - sy[i] * sx[j];
                    const std::size_t k = i * nx + j;
                    out[0][k] += amp[0] * v;
                    out[1][k] += amp[1] * v;
                    out[2][k] += amp[2] * v;
                }
            }
        };
        for (const auto& wv : waves) {
            const double s = sinc(wv.ky) * sinc(wv.kx);
            const double amp[3] = {wv.amp[0] * s, wv.amp[1] * s, wv.amp[2] * s};
            add(wv.ky, wv.kx, wv.ph, amp, false);
        }
        const double g = 0.12 * sinc(gk[0]) * sinc(gk[1]);
        const double amp[3] = {g, g, g};
        add(gk[0], gk[1], 0.0, amp, true);
    }
};

}  // namespace

std::string superResSelfCheck(int threads, SuperResSelfCheck& out) {
    const auto t0 = Clock::now();
    constexpr int kW = 256 + 2 * kMargin + 32, kH = 192 + 2 * kMargin + 32, kN = 8;
    const double shifts[kN][2] = {{0, 0}, {1.3, -0.7}, {-2.1, 0.45}, {0.8, 2.6}, {-0.35, -1.9}, {2.4, 1.15}, {-1.6, -2.7}, {0.55, 1.4}};
    const Scene scene(kH + 8, kW + 8);
    const float black = 64.0f, white = 1023.0f;
    const double noiseS = 2e-4, noiseO = 2e-6;
    std::vector<std::vector<std::uint16_t>> data(kN, std::vector<std::uint16_t>(static_cast<std::size_t>(kW) * kH));
    std::uint32_t s = 777u;
    auto gauss = [&]() {
        double u = 0;
        for (int k = 0; k < 12; ++k) {
            s = s * 1664525u + 1013904223u;
            u += (s >> 8) / 16777216.0;
        }
        return u - 6.0;
    };
    for (int k = 0; k < kN; ++k) {
        std::vector<double> ys(kH), xs(kW), v[3];
        for (int y = 0; y < kH; ++y) ys[y] = y + shifts[k][0];
        for (int x = 0; x < kW; ++x) xs[x] = x + shifts[k][1];
        scene.grid(ys, xs, v);
        for (int y = 0; y < kH; ++y) {
            for (int x = 0; x < kW; ++x) {
                const int c = kCfaLayouts[0][((y & 1) << 1) | (x & 1)];
                const double m = v[c][static_cast<std::size_t>(y) * kW + x];
                const double noisy = m + gauss() * std::sqrt(noiseS * m + noiseO);
                const double raw = std::round(noisy * (white - black) + black);
                data[k][static_cast<std::size_t>(y) * kW + x] = static_cast<std::uint16_t>(clampd(raw, 0.0, white));
            }
        }
    }
    SuperResParams p;
    p.width = kW;
    p.height = kH;
    p.cfa = 0;
    for (float& b : p.black) b = black;
    p.white = white;
    p.noiseS = noiseS;
    p.noiseO = noiseO;
    p.crop = CropRect{kMargin + 16, kMargin + 16, 256, 192};
    p.outWidth = 512;
    p.outHeight = 384;
    p.threads = threads;
    p.reference = 0;
    p.keepFlows = true;
    std::vector<BurstFrame> frames;
    for (int k = 0; k < kN; ++k) frames.push_back({data[k].data(), kW});
    const std::size_t plane = static_cast<std::size_t>(p.outWidth) * p.outHeight;
    std::vector<float> merged(plane * 3), single(plane * 3);
    SuperResResult r, r1;
    std::string err = superResMerge(frames, p, merged.data(), static_cast<std::size_t>(p.outWidth), plane, r);
    if (!err.empty()) return err;
    std::vector<BurstFrame> one(frames.begin(), frames.begin() + 1);
    err = superResMerge(one, p, single.data(), static_cast<std::size_t>(p.outWidth), plane, r1);
    if (!err.empty()) return err;
    // Flow error against the known shifts (flow = s_ref - s_k), interior tiles.
    std::vector<double> errs;
    for (int k = 1; k < kN; ++k) {
        const auto& fl = r.flows[static_cast<std::size_t>(k)];
        for (int i = 4; i < r.tilesY - 4; ++i) {
            for (int j = 4; j < r.tilesX - 4; ++j) {
                const std::size_t t = static_cast<std::size_t>(i) * r.tilesX + j;
                errs.push_back(std::abs(fl[2 * t] - (shifts[0][0] - shifts[k][0])));
                errs.push_back(std::abs(fl[2 * t + 1] - (shifts[0][1] - shifts[k][1])));
            }
        }
    }
    std::nth_element(errs.begin(), errs.begin() + errs.size() / 2, errs.end());
    out.flowErrorPx = errs[errs.size() / 2];
    out.coverage = r.coverage;
    // Error against the true (box-integrated) scene at the output positions, normalised units, inner area.
    double e2m = 0, e2s = 0;
    std::size_t n = 0;
    const int b = 16;
    std::vector<double> ys, xs, truth[3];
    for (int oy = b; oy < p.outHeight - b; ++oy) ys.push_back(p.crop.top + (oy + 0.5) * p.crop.height / p.outHeight - 0.5);
    for (int ox = b; ox < p.outWidth - b; ++ox) xs.push_back(p.crop.left + (ox + 0.5) * p.crop.width / p.outWidth - 0.5);
    scene.grid(ys, xs, truth);
    for (int oy = b; oy < p.outHeight - b; ++oy) {
        for (int ox = b; ox < p.outWidth - b; ++ox) {
            for (int c = 0; c < 3; ++c) {
                const double t = truth[c][static_cast<std::size_t>(oy - b) * xs.size() + (ox - b)];
                const std::size_t k = c * plane + static_cast<std::size_t>(oy) * p.outWidth + ox;
                e2m += (merged[k] - t) * (merged[k] - t);
                e2s += (single[k] - t) * (single[k] - t);
                ++n;
            }
        }
    }
    out.rmseMerge = std::sqrt(e2m / static_cast<double>(n));
    out.rmseSingle = std::sqrt(e2s / static_cast<double>(n));
    out.gain = out.rmseMerge > 0 ? out.rmseSingle / out.rmseMerge : 0.0;
    out.ms = msSince(t0);
    return "";
}

}  // namespace redline
