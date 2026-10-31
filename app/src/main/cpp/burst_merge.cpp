// B22 RAW burst align + merge, streaming since B22a. See burst_merge.h; the contract is
// calibration/redline_cal/burst_merge.py.
//
// Speed (B22a), with the maths unchanged:
// - grey and pyramid levels are uint16 (a quad sum of four <= 14-bit samples fits; larger sums saturate, as in the
//   reference), so every distance runs on 8 lanes;
// - every tile distance is a sum of 8x8 block distances (coarse tiles are one block, fine tiles four), with NEON
//   kernels on arm64 and plain loops elsewhere; integer sums, so the result is exactly the reference's;
// - the fine search shares block distances between the four overlapping tiles that contain a block (a per-row
//   cache of each block's 5x5 window around the last centre asked for);
// - the merge blends 4 quads per NEON step in float;
// - a persistent pool hands out small chunks dynamically (no thread start per stage, slow cores take fewer chunks).
#include "burst_merge.h"
#include "burst_internal.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <functional>
#include <mutex>
#include <thread>

#if (defined(__ARM_NEON) || defined(__ARM_NEON__)) && defined(__aarch64__) && !defined(REDLINE_NO_NEON)
#include <arm_neon.h>
#define REDLINE_NEON 1
#else
#define REDLINE_NEON 0
#endif

#if defined(__linux__)
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>
#endif

namespace redline {
namespace {

using detail::Grid;
using detail::Image;
using detail::kFineStride;
using detail::kFineTile;
using detail::Pyramid;
constexpr int kBlock = 8;  // block of the distance sums: a coarse tile, or a quarter of a fine tile
constexpr int kCoarseTile = 8;
constexpr int kCoarseRadius = 4;
constexpr int kFineRadius = 2;  // 1 loses odd displacements (L1 cannot represent them)
constexpr int kFineWindow = 2 * kFineRadius + 1;
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

// ------------------------------------------------------------------------------------------------ work pool

std::atomic<int> gWorkerNice{0};

void applyNice(int nice) {
#if defined(__linux__)
    // Linux: the nice value is per thread (PRIO_PROCESS with a thread id).
    setpriority(PRIO_PROCESS, static_cast<id_t>(syscall(SYS_gettid)), nice);
#else
    (void)nice;
#endif
}

// Persistent workers; one job at a time (callers queue on jobMu_). The caller takes chunks too.
class WorkPool {
public:
    static WorkPool& get() {
        static WorkPool* pool = new WorkPool();  // never destroyed: workers simply end with the process
        return *pool;
    }

    void run(int n, int threads, const std::function<void(int, int)>& body) {
        threads = std::min(std::max(threads, 1), kMaxThreads);
        if (threads <= 1 || n <= 1) {
            if (n > 0) body(0, n);
            return;
        }
        std::lock_guard<std::mutex> job(jobMu_);
        {
            std::lock_guard<std::mutex> lk(mu_);
            while (static_cast<int>(workers_.size()) < threads - 1) {
                const int id = static_cast<int>(workers_.size());
                workers_.emplace_back([this, id] { worker(id); });
            }
            body_ = &body;
            n_ = n;
            chunk_ = std::max(1, n / (threads * 4));
            next_.store(0);
            want_ = threads - 1;
            running_ = threads - 1;
            ++gen_;
        }
        cv_.notify_all();
        drain();
        std::unique_lock<std::mutex> lk(mu_);
        done_.wait(lk, [this] { return running_ == 0; });
        body_ = nullptr;
    }

private:
    void drain() {
        for (;;) {
            const int a = next_.fetch_add(chunk_);
            if (a >= n_) break;
            (*body_)(a, std::min(n_, a + chunk_));
        }
    }

    void worker(int id) {
        int applied = 0;
        std::uint64_t seen = 0;
        for (;;) {
            std::unique_lock<std::mutex> lk(mu_);
            cv_.wait(lk, [&] { return gen_ != seen; });
            seen = gen_;
            if (id >= want_) continue;
            lk.unlock();
            const int nice = gWorkerNice.load();
            if (nice != applied) {
                applyNice(nice);
                applied = nice;
            }
            drain();
            lk.lock();
            if (--running_ == 0) done_.notify_all();
        }
    }

    std::mutex jobMu_;
    std::mutex mu_;
    std::condition_variable cv_;
    std::condition_variable done_;
    std::vector<std::thread> workers_;
    const std::function<void(int, int)>* body_ = nullptr;
    int n_ = 0;
    int chunk_ = 1;
    int want_ = 0;
    int running_ = 0;
    std::uint64_t gen_ = 0;
    std::atomic<int> next_{0};
};

void parallelFor(int n, int threads, const std::function<void(int, int)>& body) { WorkPool::get().run(n, threads, body); }

// ------------------------------------------------------------------------------------------------ images


std::size_t pyramidBytes(const Pyramid& p) {
    std::size_t s = 0;
    for (const auto& l : p) s += l.bytes();
    return s;
}

Image greyOf(const BurstFrame& f, int width, int height, int threads) {
    Image g;
    g.w = width / 2;
    g.h = height / 2;
    g.px.resize(static_cast<std::size_t>(g.w) * g.h);
    parallelFor(g.h, threads, [&](int y0, int y1) {
        for (int y = y0; y < y1; ++y) {
            const std::uint16_t* r0 = f.data + static_cast<std::size_t>(2 * y) * f.rowStride;
            const std::uint16_t* r1 = r0 + f.rowStride;
            std::uint16_t* o = g.px.data() + static_cast<std::size_t>(y) * g.w;
            int x = 0;
#if REDLINE_NEON
            for (; x + 8 <= g.w; x += 8) {
                const uint16x8x2_t a = vld2q_u16(r0 + 2 * x);
                const uint16x8x2_t b = vld2q_u16(r1 + 2 * x);
                // Saturating adds: a sum above 65535 clamps (the reference does the same).
                const uint16x8_t s = vqaddq_u16(vqaddq_u16(a.val[0], a.val[1]), vqaddq_u16(b.val[0], b.val[1]));
                vst1q_u16(o + x, s);
            }
#endif
            for (; x < g.w; ++x) {
                const std::uint32_t s = static_cast<std::uint32_t>(r0[2 * x]) + r0[2 * x + 1] + r1[2 * x] + r1[2 * x + 1];
                o[x] = static_cast<std::uint16_t>(std::min<std::uint32_t>(s, 65535u));
            }
        }
    });
    return g;
}

Image downsample(const Image& in, int f, int threads) {
    Image o;
    o.w = in.w / f;
    o.h = in.h / f;
    o.px.resize(static_cast<std::size_t>(o.w) * o.h);
    const std::uint32_t n = static_cast<std::uint32_t>(f) * f;
    parallelFor(o.h, threads, [&](int ya, int yb) {
        std::vector<std::uint32_t> acc(static_cast<std::size_t>(o.w));
        for (int y = ya; y < yb; ++y) {
            std::fill(acc.begin(), acc.end(), 0u);
            for (int dy = 0; dy < f; ++dy) {
                const std::uint16_t* r = in.row(y * f + dy);
                for (int x = 0; x < o.w; ++x) {
                    std::uint32_t s = 0;
                    for (int dx = 0; dx < f; ++dx) s += r[x * f + dx];
                    acc[x] += s;
                }
            }
            std::uint16_t* out = o.px.data() + static_cast<std::size_t>(y) * o.w;
            for (int x = 0; x < o.w; ++x) out[x] = static_cast<std::uint16_t>(acc[x] / n);
        }
    });
    return o;
}

void computeMax(Image& img) {
    std::uint16_t m = 0;
    for (std::uint16_t v : img.px) m = v > m ? v : m;
    img.maxv = m;
}

Pyramid pyramidOf(const BurstFrame& f, int width, int height, int threads) {
    Pyramid p;
    p.push_back(greyOf(f, width, height, threads));
    computeMax(p.back());
    for (int factor : kLevelFactors) {
        Image next = downsample(p.back(), factor, threads);
        if (next.w < kCoarseTile || next.h < kCoarseTile) break;
        next.maxv = p.back().maxv;  // a box mean never exceeds the level above's maximum
        p.push_back(std::move(next));
    }
    return p;
}

std::int64_t sharpness(const Pyramid& p) {
    const Image& img = p.size() > 1 ? p[1] : p[0];
    std::int64_t s = 0;
    for (int y = 0; y < img.h; ++y) {
        const std::uint16_t* r = img.row(y);
        const std::uint16_t* rn = y + 1 < img.h ? img.row(y + 1) : nullptr;
        for (int x = 0; x < img.w; ++x) {
            const std::int64_t v = r[x];
            if (x + 1 < img.w) {
                const std::int64_t d = r[x + 1] - v;
                s += d * d;
            }
            if (rn != nullptr) {
                const std::int64_t d = rn[x] - v;
                s += d * d;
            }
        }
    }
    return s;
}

// ------------------------------------------------------------------------------------------------ distances

// Distance of the 8x8 block at (oy, ox) between ref and alt displaced by (dy, dx): inside-image ref pixels only,
// alt sampled with clamped coordinates. Exact integer maths (the parity test compares the argmin).
template <bool L1>
std::int64_t blockCost(const Image& ref, const Image& alt, int oy, int ox, int dy, int dx) {
    const int w = ref.w, h = ref.h;
    if (oy >= h || ox >= w || oy + kBlock <= 0 || ox + kBlock <= 0) return 0;
    const bool fast = ox >= 0 && oy >= 0 && ox + kBlock <= w && oy + kBlock <= h && ox + dx >= 0 && oy + dy >= 0 &&
                      ox + dx + kBlock <= w && oy + dy + kBlock <= h;
    if (fast) {
#if REDLINE_NEON
        if (L1) {
            uint32x4_t acc = vdupq_n_u32(0);
            for (int y = 0; y < kBlock; ++y) {
                const uint16x8_t r = vld1q_u16(ref.row(oy + y) + ox);
                const uint16x8_t a = vld1q_u16(alt.row(oy + y + dy) + ox + dx);
                acc = vpadalq_u16(acc, vabdq_u16(r, a));
            }
            return static_cast<std::int64_t>(vaddvq_u32(acc));
        }
        uint64x2_t acc = vdupq_n_u64(0);
        for (int y = 0; y < kBlock; ++y) {
            const uint16x8_t r = vld1q_u16(ref.row(oy + y) + ox);
            const uint16x8_t a = vld1q_u16(alt.row(oy + y + dy) + ox + dx);
            const uint16x8_t d = vabdq_u16(r, a);
            acc = vpadalq_u32(acc, vmull_u16(vget_low_u16(d), vget_low_u16(d)));
            acc = vpadalq_u32(acc, vmull_high_u16(d, d));
        }
        return static_cast<std::int64_t>(vaddvq_u64(acc));
#else
        std::int64_t cost = 0;
        for (int y = 0; y < kBlock; ++y) {
            const std::uint16_t* r = ref.row(oy + y) + ox;
            const std::uint16_t* a = alt.row(oy + y + dy) + ox + dx;
            if (L1) {
                std::int32_t s = 0;
                for (int x = 0; x < kBlock; ++x) s += std::abs(static_cast<std::int32_t>(r[x]) - static_cast<std::int32_t>(a[x]));
                cost += s;
            } else {
                std::int64_t s = 0;
                for (int x = 0; x < kBlock; ++x) {
                    const std::int64_t d = static_cast<std::int64_t>(r[x]) - a[x];
                    s += d * d;
                }
                cost += s;
            }
        }
        return cost;
#endif
    }
    std::int64_t cost = 0;
    const int y0 = std::max(oy, 0), y1 = std::min(oy + kBlock, h);
    const int x0 = std::max(ox, 0), x1 = std::min(ox + kBlock, w);
    for (int y = y0; y < y1; ++y) {
        const std::uint16_t* r = ref.row(y);
        const std::uint16_t* a = alt.row(clampi(y + dy, 0, h - 1));
        for (int x = x0; x < x1; ++x) {
            const std::int64_t d = static_cast<std::int64_t>(r[x]) - a[clampi(x + dx, 0, w - 1)];
            cost += L1 ? (d < 0 ? -d : d) : d * d;
        }
    }
    return cost;
}

// Window kernels: the distances of one 8x8 block over a whole (2r+1)^2 search window at once, the reference rows
// held in registers. Only for a block whose reference and every displaced alternate block lie inside the image
// (then no clamping is involved) and when the narrow accumulators cannot overflow; otherwise the callers use
// blockCost per position. Same integers either way.
#if REDLINE_NEON
inline bool windowInside(const Image& ref, int oy, int ox, int cy, int cx, int r) {
    return oy >= 0 && ox >= 0 && oy + kBlock <= ref.h && ox + kBlock <= ref.w && oy + cy - r >= 0 && ox + cx - r >= 0 &&
           oy + cy + r + kBlock <= ref.h && ox + cx + r + kBlock <= ref.w;
}
#endif

// L1 over the 5x5 fine window; u16 lanes hold 8 rows of |d| when both images are <= 8191.
inline bool fineWindowFast(const Image& ref, const Image& alt, int oy, int ox, int cy, int cx, std::int64_t* out) {
#if REDLINE_NEON
    if (ref.maxv > 8191 || alt.maxv > 8191 || !windowInside(ref, oy, ox, cy, cx, kFineRadius)) return false;
    uint16x8_t r[kBlock];
    for (int y = 0; y < kBlock; ++y) r[y] = vld1q_u16(ref.row(oy + y) + ox);
    for (int dy = -kFineRadius; dy <= kFineRadius; ++dy) {
        uint16x8_t acc[kFineWindow];
        for (int k = 0; k < kFineWindow; ++k) acc[k] = vdupq_n_u16(0);
        for (int y = 0; y < kBlock; ++y) {
            const std::uint16_t* a = alt.row(oy + y + cy + dy) + ox + cx - kFineRadius;
            for (int k = 0; k < kFineWindow; ++k) acc[k] = vabaq_u16(acc[k], r[y], vld1q_u16(a + k));
        }
        for (int k = 0; k < kFineWindow; ++k) out[(dy + kFineRadius) * kFineWindow + k] = static_cast<std::int64_t>(vaddlvq_u16(acc[k]));
    }
    return true;
#else
    (void)ref, (void)alt, (void)oy, (void)ox, (void)cy, (void)cx, (void)out;
    return false;
#endif
}

// L2 over the 9x9 coarse window; u32 lanes hold 16 squares when both images are <= 16383.
inline bool coarseWindowFast(const Image& ref, const Image& alt, int oy, int ox, int cy, int cx, std::int64_t* out) {
#if REDLINE_NEON
    constexpr int n = 2 * kCoarseRadius + 1;
    if (ref.maxv > 16383 || alt.maxv > 16383 || !windowInside(ref, oy, ox, cy, cx, kCoarseRadius)) return false;
    uint16x8_t r[kBlock];
    for (int y = 0; y < kBlock; ++y) r[y] = vld1q_u16(ref.row(oy + y) + ox);
    for (int dy = -kCoarseRadius; dy <= kCoarseRadius; ++dy) {
        uint32x4_t acc[n];
        for (int k = 0; k < n; ++k) acc[k] = vdupq_n_u32(0);
        for (int y = 0; y < kBlock; ++y) {
            const std::uint16_t* a = alt.row(oy + y + cy + dy) + ox + cx - kCoarseRadius;
            for (int k = 0; k < n; ++k) {
                const uint16x8_t d = vabdq_u16(r[y], vld1q_u16(a + k));
                acc[k] = vmlal_u16(acc[k], vget_low_u16(d), vget_low_u16(d));
                acc[k] = vmlal_high_u16(acc[k], d, d);
            }
        }
        for (int k = 0; k < n; ++k) out[(dy + kCoarseRadius) * n + k] = static_cast<std::int64_t>(vaddlvq_u32(acc[k]));
    }
    return true;
#else
    (void)ref, (void)alt, (void)oy, (void)ox, (void)cy, (void)cx, (void)out;
    return false;
#endif
}

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

// Displacements of one level: (dy, dx) per tile, row-major.
using Disp = std::vector<int>;

// The three initial candidates of tile (i, j) from the coarser level (HDR+): the coarse tile under the centre, then
// its horizontal and vertical neighbours on the side of the centre, each times `factor`.
int candidates(const Grid& g, int i, int j, const Disp* coarse, const Grid* cg, int factor, int cand[3][2]) {
    if (coarse == nullptr) {
        cand[0][0] = cand[0][1] = 0;
        return 1;
    }
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
    return 3;
}

// Coarse level: non-overlapping 8x8 tiles (one block each), L2, +-kCoarseRadius around the best candidate.
Disp alignCoarse(const Image& ref, const Image& alt, const Grid& g, const Disp* coarse, const Grid* cg, int factor, int threads) {
    Disp d(static_cast<std::size_t>(g.nx) * g.ny * 2, 0);
    parallelFor(g.ny, threads, [&](int i0, int i1) {
        for (int i = i0; i < i1; ++i) {
            for (int j = 0; j < g.nx; ++j) {
                int cand[3][2];
                const int nc = candidates(g, i, j, coarse, cg, factor, cand);
                const int oy = g.oy(i), ox = g.ox(j);
                int by = cand[0][0], bx = cand[0][1];
                if (nc > 1) {
                    std::int64_t best = blockCost<false>(ref, alt, oy, ox, by, bx);
                    for (int c = 1; c < nc; ++c) {
                        const std::int64_t v = blockCost<false>(ref, alt, oy, ox, cand[c][0], cand[c][1]);
                        if (v < best) {
                            best = v;
                            by = cand[c][0];
                            bx = cand[c][1];
                        }
                    }
                }
                constexpr int n = 2 * kCoarseRadius + 1;
                std::int64_t costs[n * n];
                if (!coarseWindowFast(ref, alt, oy, ox, by, bx, costs)) {
                    for (int dy = -kCoarseRadius; dy <= kCoarseRadius; ++dy)
                        for (int dx = -kCoarseRadius; dx <= kCoarseRadius; ++dx)
                            costs[(dy + kCoarseRadius) * n + dx + kCoarseRadius] = blockCost<false>(ref, alt, oy, ox, by + dy, bx + dx);
                }
                std::int64_t best = 0;
                int ry = by, rx = bx;
                bool first = true;
                for (int dy = -kCoarseRadius; dy <= kCoarseRadius; ++dy) {
                    for (int dx = -kCoarseRadius; dx <= kCoarseRadius; ++dx) {
                        const std::int64_t v = costs[(dy + kCoarseRadius) * n + dx + kCoarseRadius];
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

// One 8x8 block's L1 distances over the 5x5 window around a centre displacement (fine level).
struct BlockWindow {
    bool valid = false;
    int cy = 0;
    int cx = 0;
    std::int64_t c[kFineWindow * kFineWindow];
};

struct FineBlocks {
    const Image* ref;
    const Image* alt;
    // Block origin of block (bi, bj) is (8 bi, 8 bj); bi, bj can be -1 (the fine grid starts at -8).
    std::int64_t cost(BlockWindow& win, int bi, int bj, int dy, int dx) const {
        if (win.valid && std::abs(dy - win.cy) <= kFineRadius && std::abs(dx - win.cx) <= kFineRadius) {
            return win.c[(dy - win.cy + kFineRadius) * kFineWindow + (dx - win.cx + kFineRadius)];
        }
        return blockCost<true>(*ref, *alt, bi * kBlock, bj * kBlock, dy, dx);
    }
    void centre(BlockWindow& win, int bi, int bj, int cy, int cx) const {
        if (win.valid && win.cy == cy && win.cx == cx) return;
        const int oy = bi * kBlock, ox = bj * kBlock;
        const bool outside = oy >= ref->h || ox >= ref->w || oy + kBlock <= 0 || ox + kBlock <= 0;
        if (!outside && fineWindowFast(*ref, *alt, oy, ox, cy, cx, win.c)) {
            win.valid = true;
            win.cy = cy;
            win.cx = cx;
            return;
        }
        for (int dy = -kFineRadius; dy <= kFineRadius; ++dy) {
            for (int dx = -kFineRadius; dx <= kFineRadius; ++dx) {
                win.c[(dy + kFineRadius) * kFineWindow + dx + kFineRadius] =
                    outside ? 0 : blockCost<true>(*ref, *alt, oy, ox, cy + dy, cx + dx);
            }
        }
        win.valid = true;
        win.cy = cy;
        win.cx = cx;
    }
};

// Finest level: 16x16 tiles at stride 8 (four 8x8 blocks each), L1, +-kFineRadius around the best candidate. Each
// block's window is cached per tile row pair, so neighbouring tiles that start from the same displacement share it.
Disp alignFine(const Image& ref, const Image& alt, const Grid& g, const Disp* coarse, const Grid* cg, int factor, int threads) {
    Disp d(static_cast<std::size_t>(g.nx) * g.ny * 2, 0);
    const FineBlocks fb{&ref, &alt};
    const int nbx = g.nx + 1;  // block columns -1 .. nx-1
    parallelFor(g.ny, threads, [&](int i0, int i1) {
        std::vector<BlockWindow> top(static_cast<std::size_t>(nbx)), bot(static_cast<std::size_t>(nbx));
        for (int i = i0; i < i1; ++i) {
            // Tile row i covers block rows i-1 (top) and i (bottom); the bottom row becomes the next row's top.
            if (i == i0) {
                for (auto& b : top) b.valid = false;
            } else {
                std::swap(top, bot);
            }
            for (auto& b : bot) b.valid = false;
            for (int j = 0; j < g.nx; ++j) {
                BlockWindow* blk[4] = {&top[j], &top[j + 1], &bot[j], &bot[j + 1]};
                const int bis[4] = {i - 1, i - 1, i, i};
                const int bjs[4] = {j - 1, j, j - 1, j};
                auto tileCost = [&](int dy, int dx) {
                    std::int64_t s = 0;
                    for (int b = 0; b < 4; ++b) s += fb.cost(*blk[b], bis[b], bjs[b], dy, dx);
                    return s;
                };
                int cand[3][2];
                const int nc = candidates(g, i, j, coarse, cg, factor, cand);
                int by = cand[0][0], bx = cand[0][1];
                // Blocks seen for the first time get their window around the primary candidate (nearly always the
                // start): the candidates' distances then come from it too.
                for (int b = 0; b < 4; ++b)
                    if (!blk[b]->valid) fb.centre(*blk[b], bis[b], bjs[b], by, bx);
                if (nc > 1) {
                    std::int64_t best = tileCost(by, bx);
                    for (int c = 1; c < nc; ++c) {
                        const std::int64_t v = tileCost(cand[c][0], cand[c][1]);
                        if (v < best) {
                            best = v;
                            by = cand[c][0];
                            bx = cand[c][1];
                        }
                    }
                }
                for (int b = 0; b < 4; ++b) fb.centre(*blk[b], bis[b], bjs[b], by, bx);
                std::int64_t best = 0;
                int ry = by, rx = bx;
                bool first = true;
                for (int k = 0; k < kFineWindow * kFineWindow; ++k) {
                    const std::int64_t v = blk[0]->c[k] + blk[1]->c[k] + blk[2]->c[k] + blk[3]->c[k];
                    if (first || v < best) {
                        first = false;
                        best = v;
                        ry = by + k / kFineWindow - kFineRadius;
                        rx = bx + k % kFineWindow - kFineRadius;
                    }
                }
                const std::size_t q = (static_cast<std::size_t>(i) * g.nx + j) * 2;
                d[q] = ry;
                d[q + 1] = rx;
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
        Disp next = alignCoarse(ref[lvl], alt[lvl], g, have ? &disp : nullptr, have ? &grid : nullptr, kLevelFactors[lvl], threads);
        disp = std::move(next);
        grid = g;
        have = true;
    }
    const Grid g0 = fineGrid(ref[0].h, ref[0].w);
    return alignFine(ref[0], alt[0], g0, have ? &disp : nullptr, have ? &grid : nullptr, kLevelFactors[0], threads);
}

std::string checkParams(const BurstParams& p) {
    if (p.width < 4 || p.height < 4 || (p.width & 1) || (p.height & 1)) return "frame dimensions must be even and >= 4";
    if (!(p.white > p.black[0])) return "white level must exceed black";
    if (!(p.noiseS >= 0 && p.noiseO >= 0) || !std::isfinite(p.noiseS) || !std::isfinite(p.noiseO)) return "bad noise model";
    return "";
}

}  // namespace

// ------------------------------------------------------------------------------------------------ shared with B23

namespace detail {

void runParallel(int n, int threads, const std::function<void(int, int)>& body) { redline::parallelFor(n, threads, body); }
Pyramid buildPyramid(const BurstFrame& f, int width, int height, int threads) { return redline::pyramidOf(f, width, height, threads); }
std::size_t pyramidSizeBytes(const Pyramid& p) { return redline::pyramidBytes(p); }
std::int64_t pyramidSharpness(const Pyramid& p) { return redline::sharpness(p); }
Grid fineTileGrid(int h, int w) { return redline::fineGrid(h, w); }
std::vector<int> alignTiles(const Pyramid& ref, const Pyramid& alt, int threads) { return redline::align(ref, alt, threads); }

}  // namespace detail

// ------------------------------------------------------------------------------------------------ streaming merger

struct BurstMerger::Impl {
    BurstParams p;
    bool begun = false;
    bool finished = false;
    int threads = 1;
    int W = 0, H = 0, w0 = 0, h0 = 0;
    Grid g0;
    std::size_t nt = 0;
    int added = 0;

    struct Held {
        int index;
        BurstFrame frame;
        Pyramid pyr;
        std::int64_t sharp;
    };
    std::vector<Held> held;  // candidates while the reference is undecided

    int ref = -1;
    BurstFrame refFrame;
    Pyramid refPyr;
    std::vector<double> v2, count;
    std::vector<float> num, den;
    float win[kFineTile];
    float win2[kFineTile * kFineTile];  // win(y) * win(x)
    BurstResult res;
    std::size_t nativeBytes = 0;

    void track() {
        std::size_t s = pyramidBytes(refPyr) + (num.capacity() + den.capacity()) * sizeof(float) +
                        (v2.capacity() + count.capacity()) * sizeof(double);
        for (const auto& h : held) s += pyramidBytes(h.pyr);
        nativeBytes = s;
        res.peakNativeMb = std::max(res.peakNativeMb, static_cast<double>(s) / (1024.0 * 1024.0));
    }

    void setupReference(int index, const BurstFrame& f, Pyramid pyr) {
        ref = index;
        refFrame = f;
        refPyr = std::move(pyr);
        res.reference = index;
        // Per tile: inside-pixel count, mean level -> v2 (expected squared difference of two aligned frames).
        const double blackMean = (p.black[0] + p.black[1] + p.black[2] + p.black[3]) / 4.0;
        const double range = p.white - blackMean;
        v2.assign(nt, 0.0);
        count.assign(nt, 0.0);
        const Image& r0 = refPyr[0];
        parallelFor(g0.ny, threads, [&](int ia, int ib) {
            for (int i = ia; i < ib; ++i) {
                for (int j = 0; j < g0.nx; ++j) {
                    const int y0 = std::max(g0.oy(i), 0), y1 = std::min(g0.oy(i) + kFineTile, h0);
                    const int x0 = std::max(g0.ox(j), 0), x1 = std::min(g0.ox(j) + kFineTile, w0);
                    std::int64_t s = 0;
                    for (int y = y0; y < y1; ++y) {
                        const std::uint16_t* r = r0.row(y);
                        for (int x = x0; x < x1; ++x) s += r[x];
                    }
                    const double n = static_cast<double>((y1 - y0) * (x1 - x0));
                    const double mean = static_cast<double>(s) / n;
                    const double xs = std::min(std::max((mean / 4.0 - blackMean) / range, 0.0), 1.0);
                    const double sigma2 = 4.0 * (p.noiseS * xs + p.noiseO) * range * range;
                    const std::size_t k = static_cast<std::size_t>(i) * g0.nx + j;
                    v2[k] = std::max(2.0 * sigma2, 1e-12);
                    count[k] = n;
                }
            }
        });
    }

    void ensureAccumulator() {
        if (!num.empty()) return;
        num.assign(static_cast<std::size_t>(W) * H, 0.0f);
        den.assign(static_cast<std::size_t>(w0) * h0, 0.0f);
    }

    // Aligns frame `index` to the reference and accumulates it.
    void mergeOne(int index, const BurstFrame& alt, Pyramid altPyr) {
        ensureAccumulator();
        BurstFrameStats st;
        st.index = index;
        auto t0 = Clock::now();
        const Disp d = align(refPyr, altPyr, threads);
        st.alignMs = msSince(t0);
        res.alignMs += st.alignMs;
        t0 = Clock::now();
        const Image& r0 = refPyr[0];
        const Image& a0 = altPyr[0];
        std::vector<double> wt(nt);
        std::vector<float> lim(nt);
        // Tile weights (exact D over inside pixels with the final displacement: four block L2 sums).
        parallelFor(g0.ny, threads, [&](int i0, int i1) {
            for (int i = i0; i < i1; ++i) {
                for (int j = 0; j < g0.nx; ++j) {
                    const std::size_t k = static_cast<std::size_t>(i) * g0.nx + j;
                    const int dy = d[2 * k], dx = d[2 * k + 1];
                    std::int64_t c = 0;
                    for (int a = 0; a < 2; ++a)
                        for (int b = 0; b < 2; ++b)
                            c += blockCost<false>(r0, a0, g0.oy(i) + a * kBlock, g0.ox(j) + b * kBlock, dy, dx);
                    const double dd = static_cast<double>(c) / count[k];
                    wt[k] = kTileC / (kTileC + std::max(0.0, dd - v2[k]) / v2[k]);
                    lim[k] = static_cast<float>(kPixK * kPixK * v2[k]);
                }
            }
        });
        // Cell by cell: every 8x8 quad cell lies in exactly four tiles; their contributions are summed in registers
        // and added to the accumulator once (one pass over it per frame, rows independent, no write races).
        const int cellRows = (h0 + kBlock - 1) / kBlock;
        parallelFor(cellRows, threads, [&](int a, int b) {
            for (int by = a; by < b; ++by) mergeCellRow(by, alt, r0, a0, d, wt, lim);
        });
        st.mergeMs = msSince(t0);
        res.mergeMs += st.mergeMs;
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
        res.frames.push_back(st);
        if (p.keepDisplacements) {
            if (static_cast<int>(res.displacements.size()) <= index) res.displacements.resize(static_cast<std::size_t>(index) + 1);
            res.displacements[static_cast<std::size_t>(index)] = d;
        }
        track();
    }

    void mergeCellRow(int by, const BurstFrame& alt, const Image& r0, const Image& a0, const Disp& d, const std::vector<double>& wt,
                      const std::vector<float>& lim) {
        const int qy0 = by * kBlock, qy1 = std::min(qy0 + kBlock, h0);
        const int cellCols = (w0 + kBlock - 1) / kBlock;
        for (int bx = 0; bx < cellCols; ++bx) {
            const int qx0 = bx * kBlock;
            const int nq = std::min(kBlock, w0 - qx0);
            // The four tiles over this cell: rows by (lower half of the tile) and by + 1 (upper half), same for columns.
            int tdy[4], tdx[4], toy[4], tox[4];
            float tw[4], tl[4];
            for (int t = 0; t < 4; ++t) {
                const int i = by + (t >> 1), j = bx + (t & 1);
                const std::size_t k = static_cast<std::size_t>(i) * g0.nx + j;
                tdy[t] = d[2 * k];
                tdx[t] = d[2 * k + 1];
                toy[t] = g0.oy(i);
                tox[t] = g0.ox(j);
                tw[t] = static_cast<float>(wt[k]);
                tl[t] = lim[k];
            }
            for (int qy = qy0; qy < qy1; ++qy) {
                const std::uint16_t* rr = r0.row(qy);
                float* n0 = num.data() + static_cast<std::size_t>(2 * qy) * W;
                float* n1 = n0 + W;
                float* dr = den.data() + static_cast<std::size_t>(qy) * w0;
                float accD[kBlock] = {0};
                float accN0[2 * kBlock] = {0};
                float accN1[2 * kBlock] = {0};
#if REDLINE_NEON
                float32x4_t vD[2] = {vdupq_n_f32(0), vdupq_n_f32(0)};
                float32x4x2_t vN0[2] = {{{vdupq_n_f32(0), vdupq_n_f32(0)}}, {{vdupq_n_f32(0), vdupq_n_f32(0)}}};
                float32x4x2_t vN1[2] = {{{vdupq_n_f32(0), vdupq_n_f32(0)}}, {{vdupq_n_f32(0), vdupq_n_f32(0)}}};
                bool usedV = false;
#endif
                for (int t = 0; t < 4; ++t) {
                    const int dy = tdy[t], dx = tdx[t];
                    const int ay = clampi(qy + dy, 0, h0 - 1);
                    const int xo = qx0 - tox[t];  // 0 or 8: where the cell sits in the tile's window
                    const float* w2 = win2 + (qy - toy[t]) * kFineTile + xo;
                    float cw[kBlock];
                    for (int x = 0; x < kBlock; ++x) cw[x] = tw[t] * w2[x];
                    const std::uint16_t* ar = a0.row(ay);
                    const std::uint16_t* s0 = alt.data + static_cast<std::size_t>(2 * ay) * alt.rowStride;
                    const std::uint16_t* s1 = s0 + alt.rowStride;
                    const float lm = tl[t];
#if REDLINE_NEON
                    if (nq == kBlock && qx0 + dx >= 0 && qx0 + dx + kBlock <= w0) {
                        usedV = true;
                        const float32x4_t limv = vdupq_n_f32(lm);
                        for (int h = 0; h < 2; ++h) {
                            const int qx = qx0 + 4 * h, ax = qx + dx;
                            const int32x4_t ri = vreinterpretq_s32_u32(vmovl_u16(vld1_u16(rr + qx)));
                            const int32x4_t ai = vreinterpretq_s32_u32(vmovl_u16(vld1_u16(ar + ax)));
                            const float32x4_t dq = vcvtq_f32_s32(vsubq_s32(ri, ai));
                            const float32x4_t dq2 = vmulq_f32(dq, dq);
                            // min(1, lim / dq2) == (dq2 <= lim ? 1 : lim / dq2); dq2 = 0 gives +inf -> 1.
                            const float32x4_t sel = vminq_f32(vdupq_n_f32(1.0f), vdivq_f32(limv, dq2));
                            const float32x4_t wgt = vmulq_f32(sel, vld1q_f32(cw + 4 * h));
                            vD[h] = vaddq_f32(vD[h], wgt);
                            const uint16x4x2_t a = vld2_u16(s0 + 2 * ax);
                            const uint16x4x2_t b = vld2_u16(s1 + 2 * ax);
                            vN0[h].val[0] = vfmaq_f32(vN0[h].val[0], wgt, vcvtq_f32_u32(vmovl_u16(a.val[0])));
                            vN0[h].val[1] = vfmaq_f32(vN0[h].val[1], wgt, vcvtq_f32_u32(vmovl_u16(a.val[1])));
                            vN1[h].val[0] = vfmaq_f32(vN1[h].val[0], wgt, vcvtq_f32_u32(vmovl_u16(b.val[0])));
                            vN1[h].val[1] = vfmaq_f32(vN1[h].val[1], wgt, vcvtq_f32_u32(vmovl_u16(b.val[1])));
                        }
                        continue;
                    }
#endif
                    for (int q = 0; q < nq; ++q) {
                        const int qx = qx0 + q;
                        const int ax = clampi(qx + dx, 0, w0 - 1);
                        const float dq = static_cast<float>(static_cast<int>(rr[qx]) - static_cast<int>(ar[ax]));
                        const float dq2 = dq * dq;
                        const float sel = dq2 <= lm ? 1.0f : lm / dq2;
                        const float wgt = sel * cw[q];
                        accD[q] += wgt;
                        accN0[2 * q] += wgt * s0[2 * ax];
                        accN0[2 * q + 1] += wgt * s0[2 * ax + 1];
                        accN1[2 * q] += wgt * s1[2 * ax];
                        accN1[2 * q + 1] += wgt * s1[2 * ax + 1];
                    }
                }
#if REDLINE_NEON
                if (usedV) {
                    // nq == kBlock whenever a vector step ran.
                    for (int h = 0; h < 2; ++h) {
                        const int qx = qx0 + 4 * h;
                        vst1q_f32(dr + qx, vaddq_f32(vld1q_f32(dr + qx), vaddq_f32(vD[h], vld1q_f32(accD + 4 * h))));
                        float32x4x2_t m0 = vld2q_f32(n0 + 2 * qx);
                        float32x4x2_t m1 = vld2q_f32(n1 + 2 * qx);
                        const float32x4x2_t s0v = vld2q_f32(accN0 + 8 * h);
                        const float32x4x2_t s1v = vld2q_f32(accN1 + 8 * h);
                        m0.val[0] = vaddq_f32(m0.val[0], vaddq_f32(vN0[h].val[0], s0v.val[0]));
                        m0.val[1] = vaddq_f32(m0.val[1], vaddq_f32(vN0[h].val[1], s0v.val[1]));
                        m1.val[0] = vaddq_f32(m1.val[0], vaddq_f32(vN1[h].val[0], s1v.val[0]));
                        m1.val[1] = vaddq_f32(m1.val[1], vaddq_f32(vN1[h].val[1], s1v.val[1]));
                        vst2q_f32(n0 + 2 * qx, m0);
                        vst2q_f32(n1 + 2 * qx, m1);
                    }
                    continue;
                }
#endif
                for (int q = 0; q < nq; ++q) {
                    const int qx = qx0 + q;
                    dr[qx] += accD[q];
                    n0[2 * qx] += accN0[2 * q];
                    n0[2 * qx + 1] += accN0[2 * q + 1];
                    n1[2 * qx] += accN1[2 * q];
                    n1[2 * qx + 1] += accN1[2 * q + 1];
                }
            }
        }
    }

    // Picks the reference among the held candidates (sharpest; the earliest wins ties) or the fixed one, then merges
    // the other candidates and releases them.
    std::string decide(std::vector<int>& released) {
        int pick = -1;
        if (p.reference >= 0) {
            for (std::size_t c = 0; c < held.size(); ++c)
                if (held[c].index == p.reference) pick = static_cast<int>(c);
            if (pick < 0) return "reference index out of range";
        } else {
            std::int64_t best = -1;
            for (std::size_t c = 0; c < held.size(); ++c) {
                if (held[c].sharp > best) {
                    best = held[c].sharp;
                    pick = static_cast<int>(c);
                }
            }
        }
        std::vector<Held> rest;
        for (std::size_t c = 0; c < held.size(); ++c) {
            if (static_cast<int>(c) == pick) setupReference(held[c].index, held[c].frame, std::move(held[c].pyr));
            else rest.push_back(std::move(held[c]));
        }
        held.clear();
        track();
        for (auto& h : rest) {
            mergeOne(h.index, h.frame, std::move(h.pyr));
            released.push_back(h.index);
        }
        return "";
    }

    template <typename Validate, typename Write>
    std::string finish(BurstResult& result, std::vector<int>& released, Validate validate, Write write) {
        if (!begun) return "merger not started";
        if (finished) return "merger already finished";
        if (added == 0) return "no frames";
        if (ref < 0) {
            const std::string err = decide(released);
            if (!err.empty()) return err;
        }
        {
            const std::string err = validate();
            if (!err.empty()) return err;
        }
        finished = true;
        const auto t0 = Clock::now();
        const bool any = !num.empty();
        parallelFor(H, threads, [&](int y0, int y1) {
            for (int y = y0; y < y1; ++y) {
                const std::uint16_t* r = refFrame.data + static_cast<std::size_t>(y) * refFrame.rowStride;
                const float* n = any ? num.data() + static_cast<std::size_t>(y) * W : nullptr;
                const float* dr = any ? den.data() + static_cast<std::size_t>(y / 2) * w0 : nullptr;
                write(y, r, n, dr);
            }
        });
        res.finishMs = msSince(t0);
        released.push_back(ref);
        // Stats in frame order (the merge order differs when a later candidate is the reference).
        std::sort(res.frames.begin(), res.frames.end(),
                  [](const BurstFrameStats& a, const BurstFrameStats& b) { return a.index < b.index; });
        res.framesAdded = added;
        if (p.keepDisplacements) res.displacements.resize(static_cast<std::size_t>(added));
        result = res;
        // The working set goes back now; the result keeps only the numbers.
        std::vector<float>().swap(num);
        std::vector<float>().swap(den);
        Pyramid().swap(refPyr);
        return "";
    }
};

BurstMerger::BurstMerger() : impl_(new Impl()) {}
BurstMerger::~BurstMerger() = default;

std::string BurstMerger::begin(const BurstParams& p) {
    const std::string err = checkParams(p);
    if (!err.empty()) return err;
    impl_.reset(new Impl());
    Impl& m = *impl_;
    m.p = p;
    m.begun = true;
    m.threads = std::min(std::max(p.threads, 1), kMaxThreads);
    m.W = p.width;
    m.H = p.height;
    m.w0 = p.width / 2;
    m.h0 = p.height / 2;
    m.g0 = fineGrid(m.h0, m.w0);
    m.nt = static_cast<std::size_t>(m.g0.nx) * m.g0.ny;
    m.res.tilesX = m.g0.nx;
    m.res.tilesY = m.g0.ny;
    m.res.threads = m.threads;
    for (int i = 0; i < kFineTile; ++i) m.win[i] = static_cast<float>(0.5 - 0.5 * std::cos(2.0 * kPi * (i + 0.5) / kFineTile));
    for (int y = 0; y < kFineTile; ++y)
        for (int x = 0; x < kFineTile; ++x) m.win2[y * kFineTile + x] = m.win[y] * m.win[x];
    return "";
}

std::string BurstMerger::add(const BurstFrame& f, std::vector<int>& released) {
    Impl& m = *impl_;
    if (!m.begun) return "merger not started";
    if (m.finished) return "merger already finished";
    if (f.data == nullptr) return "missing frame data";
    if (f.rowStride < m.W) return "row stride smaller than width";
    const int index = m.added++;
    m.res.frames.reserve(16);
    const auto t0 = Clock::now();
    Pyramid pyr = pyramidOf(f, m.W, m.H, m.threads);
    if (m.ref >= 0) {
        m.res.pyramidMs += msSince(t0);
        m.mergeOne(index, f, std::move(pyr));
        released.push_back(index);
        return "";
    }
    const bool fixed = m.p.reference >= 0;
    const std::int64_t sharp = fixed ? 0 : sharpness(pyr);
    if (fixed && index > m.p.reference) {
        // Unreachable: the fixed reference is decided on arrival.
        return "internal: reference not decided";
    }
    m.held.push_back({index, f, std::move(pyr), sharp});
    m.res.maxHeldFrames = std::max(m.res.maxHeldFrames, static_cast<int>(m.held.size()));
    m.res.referenceMs += msSince(t0);
    m.track();
    const bool ready = fixed ? index == m.p.reference : static_cast<int>(m.held.size()) == kReferenceCandidates;
    if (!ready) return "";
    return m.decide(released);
}

std::string BurstMerger::finishFloat(float* out, std::size_t strideFloats, BurstResult& result, std::vector<int>& released) {
    Impl& m = *impl_;
    if (out == nullptr || strideFloats < static_cast<std::size_t>(m.W)) return "bad output";
    const int W = m.W;
    return m.finish(result, released, [] { return std::string(); }, [&](int y, const std::uint16_t* r, const float* n, const float* dr) {
        float* o = out + static_cast<std::size_t>(y) * strideFloats;
        if (n == nullptr) {
            for (int x = 0; x < W; ++x) o[x] = static_cast<float>(r[x]);
            return;
        }
        for (int x = 0; x < W; ++x) o[x] = (static_cast<float>(r[x]) + n[x]) / (1.0f + dr[x / 2]);
    });
}

std::string BurstMerger::finishU16(std::uint16_t* out, std::size_t strideElems, float scale, BurstResult& result,
                                   std::vector<int>& released) {
    Impl& m = *impl_;
    const bool inPlace = out == nullptr;  // into the reference frame's own buffer (known once the reference is)
    if (!inPlace && strideElems < static_cast<std::size_t>(m.W)) return "bad output";
    if (!(scale > 0) || m.p.white * scale > 65535.0f) return "scale overflows 16 bits";
    const int W = m.W;
    const float hi = m.p.white * scale;
    // In place over the reference is fine (each sample reads its own position first) with the same row stride.
    auto validate = [&]() -> std::string {
        if (inPlace) {
            out = const_cast<std::uint16_t*>(m.refFrame.data);
            strideElems = static_cast<std::size_t>(m.refFrame.rowStride);
        }
        if (out == m.refFrame.data && strideElems != static_cast<std::size_t>(m.refFrame.rowStride)) {
            return "in-place output needs the reference's row stride";
        }
        return "";
    };
    return m.finish(result, released, validate, [&](int y, const std::uint16_t* r, const float* n, const float* dr) {
        std::uint16_t* o = out + static_cast<std::size_t>(y) * strideElems;
        for (int x = 0; x < W; ++x) {
            const float v = n == nullptr ? static_cast<float>(r[x]) * scale
                                         : (static_cast<float>(r[x]) + n[x]) / (1.0f + dr[x / 2]) * scale;
            o[x] = static_cast<std::uint16_t>(std::min(std::max(v, 0.0f), hi) + 0.5f);
        }
    });
}

int BurstMerger::reference() const { return impl_->ref; }
int BurstMerger::added() const { return impl_->added; }
int BurstMerger::held() const {
    return static_cast<int>(impl_->held.size()) + (impl_->ref >= 0 && !impl_->finished ? 1 : 0);
}

namespace {

template <typename Finish>
std::string mergeAll(const std::vector<BurstFrame>& frames, const BurstParams& p, Finish finish) {
    if (frames.empty()) return "no frames";
    if (p.reference >= static_cast<int>(frames.size())) return "reference index out of range";
    BurstMerger m;
    std::string err = m.begin(p);
    if (!err.empty()) return err;
    std::vector<int> released;
    for (const auto& f : frames) {
        err = m.add(f, released);
        if (!err.empty()) return err;
    }
    return finish(m, released);
}

}  // namespace

std::string mergeBurstFloat(const std::vector<BurstFrame>& frames, const BurstParams& p, float* out, std::size_t strideFloats,
                            BurstResult& result) {
    return mergeAll(frames, p, [&](BurstMerger& m, std::vector<int>& released) {
        return m.finishFloat(out, strideFloats, result, released);
    });
}

std::string mergeBurstU16(const std::vector<BurstFrame>& frames, const BurstParams& p, std::uint16_t* out, std::size_t strideElems,
                          float scale, BurstResult& result) {
    return mergeAll(frames, p, [&](BurstMerger& m, std::vector<int>& released) {
        return m.finishU16(out, strideElems, scale, result, released);
    });
}

int burstRecommendedThreads() {
    const int n = static_cast<int>(std::max(1u, std::thread::hardware_concurrency()));
    std::vector<long> freq;
    for (int c = 0; c < n; ++c) {
        std::ifstream f("/sys/devices/system/cpu/cpu" + std::to_string(c) + "/cpufreq/cpuinfo_max_freq");
        long v = 0;
        if (!(f >> v) || v <= 0) {
            freq.clear();
            break;
        }
        freq.push_back(v);
    }
    int t;
    if (freq.empty()) {
        t = n / 2;
    } else {
        const long top = *std::max_element(freq.begin(), freq.end());
        t = static_cast<int>(std::count_if(freq.begin(), freq.end(), [top](long v) { return v * 10 >= top * 7; }));
    }
    return std::min(std::max(t, std::min(2, n)), 8);
}

void burstSetWorkerNice(int nice) { gWorkerNice.store(nice); }

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
