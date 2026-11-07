// B31 simulated depth of field. calibration/redline_cal/bokeh.py is the contract (see bokeh.h).
#if defined(__linux__) && !defined(_GNU_SOURCE)
#define _GNU_SOURCE  // sched_getcpu, CPU_SET (glibc host builds; bionic and libc++ define it already)
#endif
#include "bokeh.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cerrno>
#include <climits>
#include <cmath>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <functional>
#include <limits>
#include <memory>
#include <mutex>
#include <thread>

#if defined(__linux__)
#include <sched.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>
#endif

namespace redline {
namespace {

constexpr double kFfWidthMm = 36.0;
constexpr double kBgPct = 0.02;
constexpr double kMinSpan = 0.1;
constexpr double kFocusWin = 0.02;
constexpr double kPersonLo = 0.1, kPersonHi = 0.2;
constexpr double kGfEps = 1e-3;
constexpr double kCertGrad = 0.08;
constexpr double kCertLo = 0.05, kCertHi = 0.2;
constexpr int kLadderFine = 8;
constexpr float kSnap = 0.25f;
constexpr double kR0 = 2.0;
constexpr int kTile = 32;
constexpr double kRim = 0.15;
constexpr double kRimInner = 0.8;
constexpr double kRimMinR = 4.0;
constexpr int kBlades = 9;
constexpr float kCaEps = 0.05f;
constexpr double kCaRampLo = 4.0, kCaRampHi = 12.0;
constexpr double kHdrCapEv = 8.0;
constexpr int kInvPoints = 1024;
constexpr int kMaxThreads = 8;
constexpr int kRearQ = 8;
constexpr double kRearEdgeLo = 0.02, kRearEdgeHi = 0.1, kRearNear = 0.25;
constexpr double kPi = 3.14159265358979323846;
constexpr float kLuma[3] = {0.2126729f, 0.7151522f, 0.0721750f};

using Clock = std::chrono::steady_clock;
double msSince(Clock::time_point t) { return std::chrono::duration<double, std::milli>(Clock::now() - t).count(); }

// ------------------------------------------------------------------------------------------------ workers

// B31.1: what one render's parallel regions run with, and what they saw.
struct Exec {
    int threads = 1;
    int nice = kBokehInheritNice;
    bool bigCores = false;
    std::atomic<std::uint64_t> cpus{0};
    std::atomic<int> niceSeen{kBokehInheritNice};
    std::atomic<int> regions{0};
};

#if defined(__linux__)
int threadId() { return static_cast<int>(syscall(SYS_gettid)); }
int niceOf(int tid) {
    errno = 0;
    const int v = getpriority(PRIO_PROCESS, static_cast<id_t>(tid));
    return errno != 0 ? kBokehInheritNice : v;
}

// The CPUs above the lowest-capacity cluster (cpu_capacity, else cpuinfo_max_freq), within the process' allowed set;
// empty when the cores are all alike or fewer than two would remain.
cpu_set_t bigCoreSet(bool& any) {
    cpu_set_t allowed;
    CPU_ZERO(&allowed);
    any = false;
    if (sched_getaffinity(0, sizeof(allowed), &allowed) != 0) return allowed;
    long cap[64];
    long lo = LONG_MAX, hi = 0;
    for (int c = 0; c < 64; ++c) {
        cap[c] = -1;
        if (!CPU_ISSET(c, &allowed)) continue;
        char path[96];
        long v = -1;
        for (const char* f : {"cpu_capacity", "cpufreq/cpuinfo_max_freq"}) {
            std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/%s", c, f);
            if (FILE* fp = std::fopen(path, "r")) {
                if (std::fscanf(fp, "%ld", &v) != 1) v = -1;
                std::fclose(fp);
            }
            if (v > 0) break;
        }
        cap[c] = v;
        if (v > 0) {
            lo = std::min(lo, v);
            hi = std::max(hi, v);
        }
    }
    cpu_set_t big;
    CPU_ZERO(&big);
    if (hi <= 0 || hi == lo) return big;
    int n = 0;
    for (int c = 0; c < 64; ++c)
        if (cap[c] > lo) {
            CPU_SET(c, &big);
            ++n;
        }
    any = n >= 2;
    return big;
}

const cpu_set_t& allowedSet() {
    static const cpu_set_t s = [] {
        cpu_set_t a;
        CPU_ZERO(&a);
        sched_getaffinity(0, sizeof(a), &a);
        return a;
    }();
    return s;
}

const cpu_set_t* bigSet() {
    static bool any = false;
    static const cpu_set_t s = bigCoreSet(any);
    return any ? &s : nullptr;
}

void pinThread(int tid, bool big) {
    const cpu_set_t* b = big ? bigSet() : nullptr;
    sched_setaffinity(tid, sizeof(cpu_set_t), b != nullptr ? b : &allowedSet());
}

void markCpu(Exec& ex) {
    const int c = sched_getcpu();
    if (c >= 0 && c < 64) ex.cpus.fetch_or(std::uint64_t{1} << c, std::memory_order_relaxed);
}
#else
int threadId() { return 0; }
int niceOf(int) { return kBokehInheritNice; }
void pinThread(int, bool) {}
void markCpu(Exec&) {}
#endif

// A persistent pool: the workers live across renders, so the scheduler sees long-running busy threads (and moves them
// to the fast cores and clocks) instead of thousands of short-lived ones; each region's nice is applied per worker
// (Linux nice is per thread), optionally pinned to the cores above the little cluster. Regions are serialised.
class Pool {
public:
    static Pool& get() {
        static Pool* p = new Pool();  // never destroyed (workers may still be parked at exit)
        return *p;
    }

    void run(int tasks, Exec& ex, const std::function<void(int)>& fn) {
        if (tasks <= 0) return;
        const int threads = std::min(std::min(std::max(ex.threads, 1), kMaxThreads), tasks);
        ex.regions.fetch_add(1, std::memory_order_relaxed);
        if (threads <= 1) {
            markCpu(ex);
            for (int i = 0; i < tasks; ++i) fn(i);
            return;
        }
        std::lock_guard<std::mutex> serial(runM_);
        ensure(threads - 1);
        const int nice = ex.nice == kBokehInheritNice ? niceOf(threadId()) : ex.nice;
        {
            std::lock_guard<std::mutex> lk(m_);
            fn_ = &fn;
            tasks_ = tasks;
            next_.store(0);
            want_ = threads - 1;
            finished_ = 0;
            nice_ = nice;
            big_ = ex.bigCores;
            ex_ = &ex;
            ++gen_;
        }
        cv_.notify_all();
        markCpu(ex);
        work(fn);
        std::unique_lock<std::mutex> lk(m_);
        done_.wait(lk, [&] { return finished_ == want_; });
        fn_ = nullptr;
        ex_ = nullptr;
    }

private:
    void work(const std::function<void(int)>& fn) {
        int i;
        while ((i = next_.fetch_add(1)) < tasks_) fn(i);
    }

    void ensure(int n) {
        while (static_cast<int>(workers_.size()) < n) {
            const int idx = static_cast<int>(workers_.size());
            workers_.emplace_back([this, idx] { loop(idx); });
            workers_.back().detach();
        }
    }

    void loop(int idx) {
        std::uint64_t seen = 0;
        int myNice = INT_MIN, myBig = -1;
        const int tid = threadId();
        for (;;) {
            std::unique_lock<std::mutex> lk(m_);
            cv_.wait(lk, [&] { return gen_ != seen && idx < want_; });
            seen = gen_;
            const std::function<void(int)>* fn = fn_;
            Exec* ex = ex_;
            const int nice = nice_;
            const int big = big_ ? 1 : 0;
            lk.unlock();
#if defined(__linux__)
            if (nice != myNice && nice != kBokehInheritNice) {
                setpriority(PRIO_PROCESS, static_cast<id_t>(tid), nice);
                myNice = nice;
            }
#endif
            if (big != myBig) {
                pinThread(tid, big != 0);
                myBig = big;
            }
            ex->niceSeen.store(niceOf(tid), std::memory_order_relaxed);
            markCpu(*ex);
            work(*fn);
            lk.lock();
            if (++finished_ == want_) done_.notify_one();
        }
    }

    std::mutex runM_, m_;
    std::condition_variable cv_, done_;
    std::vector<std::thread> workers_;
    const std::function<void(int)>* fn_ = nullptr;
    Exec* ex_ = nullptr;
    std::atomic<int> next_{0};
    int tasks_ = 0, want_ = 0, finished_ = 0, nice_ = 0;
    bool big_ = false;
    std::uint64_t gen_ = 0;
};

// Rows [0, rows) in chunks (several per thread, taken dynamically, so a slow core does not hold the region up).
void parallelRows(int rows, Exec& ex, const std::function<void(int, int)>& job) {
    if (rows <= 0) return;
    const int threads = std::min(std::max(ex.threads, 1), kMaxThreads);
    const int chunks = threads == 1 ? 1 : std::min(rows, threads * 4);
    Pool::get().run(chunks, ex, [&](int i) {
        const int a = static_cast<int>(static_cast<long long>(rows) * i / chunks);
        const int b = static_cast<int>(static_cast<long long>(rows) * (i + 1) / chunks);
        if (b > a) job(a, b);
    });
}

// ------------------------------------------------------------------------------------------------ optics / kernel

double catCornerWidth(double f) { return 0.65 + 0.15 * std::min(std::max((f - 24.0) / 46.0, 0.0), 1.0); }
double catStrength(double n) { return std::min(std::max(std::log2(5.6 / n), 0.0), 1.0); }
bool caActive(double f, double n) { return std::fabs(f - 70.0) < 0.5 && n < 2.85; }

bool bladeInside(double x, double y, double r, double beta) {
    const double rho = std::hypot(x, y);
    if (rho == 0.0) return true;
    const double sector = 2.0 * kPi / kBlades;
    const double th = std::atan2(y, x) - kPi / 2.0;
    const double phi = th - sector * std::floor(th / sector) - kPi / kBlades;
    const double edge = r * (1.0 - beta * (1.0 - std::cos(kPi / kBlades) / std::cos(phi)));
    return rho <= edge;
}

bool bladeChord(double y, double r, double beta, double& xl, double& xr) {
    if (!bladeInside(0.0, y, r, beta)) return false;
    double ends[2];
    for (int s = 0; s < 2; ++s) {
        const double sgn = s == 0 ? 1.0 : -1.0;
        double lo = 0.0, hi = r + 1.0;
        for (int i = 0; i < 40; ++i) {
            const double mid = 0.5 * (lo + hi);
            if (bladeInside(sgn * mid, y, r, beta)) lo = mid;
            else hi = mid;
        }
        ends[s] = sgn * 0.5 * (lo + hi);
    }
    xl = ends[1];
    xr = ends[0];
    return true;
}

bool discChord(double y, double cx, double cy, double r, double& xl, double& xr) {
    const double dy = y - cy;
    if (std::fabs(dy) > r) return false;
    const double half = std::sqrt(r * r - dy * dy);
    xl = cx - half;
    xr = cx + half;
    return true;
}

struct Term {
    int dy, lo, hi;
    float cl, ch, w;  // coverage of lo and hi (lo == hi: cl only), weight
};

struct Kernel {
    std::vector<Term> terms;
    int k = 0;
};

// The blade chords at every sub-row of a radius (they do not depend on the tile; the cat's-eye discs do).
struct BladeRows {
    double r = 0, beta = 0;
    int k = 0;
    int shapes = 1;
    double scale[2] = {1.0, kRimInner};
    double weight[2] = {1.0, 0.0};
    // [shape][row][sub] -> valid, xl, xr
    std::vector<unsigned char> valid;
    std::vector<double> xl, xr;

    // rim: on when the disc's output radius (rOut, level radius x level) is at least kRimMinR (B31.1: by the output
    // radius, so the rim does not come and go with the level a layer is gathered at); rOut < 0: r is the output radius
    BladeRows(double r_, double beta_, double rOut = -1.0) : r(r_), beta(beta_) {
        k = static_cast<int>(std::ceil(r)) + 1;
        if ((rOut < 0 ? r : rOut) >= kRimMinR) {
            shapes = 2;
            weight[0] = 1.0 + kRim;
            weight[1] = -kRim;
        }
        const int rows = 2 * k + 1;
        const std::size_t n = static_cast<std::size_t>(shapes) * rows * 4;
        valid.assign(n, 0);
        xl.assign(n, 0.0);
        xr.assign(n, 0.0);
        static const double subs[4] = {-0.375, -0.125, 0.125, 0.375};
        for (int s = 0; s < shapes; ++s) {
            for (int dy = -k; dy <= k; ++dy) {
                for (int j = 0; j < 4; ++j) {
                    const std::size_t i = (static_cast<std::size_t>(s) * rows + (dy + k)) * 4 + j;
                    double a = 0, b = 0;
                    if (bladeChord(dy + subs[j], r * scale[s], beta, a, b)) {
                        valid[i] = 1;
                        xl[i] = a;
                        xr[i] = b;
                    }
                }
            }
        }
    }

    void build(double h, double ux, double uy, Kernel& out) const {
        static const double subs[4] = {-0.375, -0.125, 0.125, 0.375};
        const int rows = 2 * k + 1;
        struct Raw {
            int dy;
            double xl, xr, w;
        };
        std::vector<Raw> raw;
        raw.reserve(static_cast<std::size_t>(shapes * rows));
        for (int s = 0; s < shapes; ++s) {
            const double rs = r * scale[s], hs = h * scale[s];
            for (int dy = -k; dy <= k; ++dy) {
                int n = 0;
                double sl = 0, sr = 0;
                for (int j = 0; j < 4; ++j) {
                    const std::size_t i = (static_cast<std::size_t>(s) * rows + (dy + k)) * 4 + j;
                    if (!valid[i]) continue;
                    double a = xl[i], b = xr[i];
                    if (h > 0.0) {
                        bool ok = true;
                        for (int t = 0; t < 2 && ok; ++t) {
                            const double sg = t == 0 ? 1.0 : -1.0;
                            double c, d;
                            if (!discChord(dy + subs[j], sg * hs * ux, sg * hs * uy, rs, c, d)) {
                                ok = false;
                            } else {
                                a = std::max(a, c);
                                b = std::min(b, d);
                            }
                        }
                        if (!ok || b <= a) continue;
                    }
                    ++n;
                    sl += a;
                    sr += b;
                }
                if (n > 0) raw.push_back({dy, sl / n, sr / n, weight[s] * n / 4.0});
            }
        }
        double total = 0;
        for (const Raw& t : raw) total += t.w * (t.xr - t.xl);
        out.k = k;
        out.terms.clear();
        for (const Raw& t : raw) {
            Term m;
            m.dy = t.dy;
            m.lo = static_cast<int>(std::floor(t.xl + 0.5));
            m.hi = std::max(m.lo, static_cast<int>(std::ceil(t.xr - 0.5)));
            auto cov = [&](int dx) { return std::max(0.0, std::min(dx + 0.5, t.xr) - std::max(dx - 0.5, t.xl)); };
            m.cl = static_cast<float>(cov(m.lo));
            m.ch = m.hi > m.lo ? static_cast<float>(cov(m.hi)) : 0.0f;
            m.w = static_cast<float>(t.w / total);
            out.terms.push_back(m);
        }
    }
};

// ------------------------------------------------------------------------------------------------ tone

// log2 from the exponent bits and a 2048-entry mantissa table (linear interpolation, error < 1e-7).
class FastLog2 {
public:
    FastLog2() {
        for (int i = 0; i <= kN; ++i) t_[i] = static_cast<float>(std::log2(1.0 + static_cast<double>(i) / kN));
    }
    float operator()(float x) const {
        std::uint32_t b;
        std::memcpy(&b, &x, sizeof b);
        const int e = static_cast<int>((b >> 23) & 0xff) - 127;
        const std::uint32_t m = b & 0x7fffff;
        const std::uint32_t i = m >> (23 - kBits);
        const float f = static_cast<float>(m & ((1u << (23 - kBits)) - 1)) * (1.0f / static_cast<float>(1u << (23 - kBits)));
        return static_cast<float>(e) + t_[i] + (t_[i + 1] - t_[i]) * f;
    }

private:
    static constexpr int kBits = 11, kN = 1 << kBits;
    float t_[kN + 1];
};

struct Curve {
    const float* t;
    int n;
    float lo, hi, floor_, step_;
    std::shared_ptr<FastLog2> log2_;
    explicit Curve(const BokehTone& s) : t(s.curve), n(s.curvePoints), lo(s.curveLo), hi(s.curveHi), log2_(std::make_shared<FastLog2>()) {
        floor_ = std::exp2(lo);
        step_ = (n - 1) / (hi - lo);
    }
    float operator()(float x) const {
        if (!(x > 0.0f)) return 0.0f;
        if (x < floor_) return std::min(x / floor_ * t[0], 1.0f);
        const float f = ((*log2_)(x) - lo) * step_;
        if (f >= static_cast<float>(n - 1)) return std::min(std::max(t[n - 1], 0.0f), 1.0f);
        const int i = static_cast<int>(f);
        const float u = f - static_cast<float>(i);
        return std::min(std::max(t[i] + (t[i + 1] - t[i]) * u, 0.0f), 1.0f);
    }
};

// B31.1: the curve as one table on the float's exponent and top 7 mantissa bits (32768 nodes, linear in x between
// them: relative node spacing 1/128, error far below one 8-bit step), for the per-pixel re-apply.
class ToneLut {
public:
    explicit ToneLut(const Curve& c) : t_(kN + 1) {
        for (std::uint32_t i = 0; i <= kN; ++i) {
            const std::uint32_t b = std::min<std::uint32_t>(i, kN - 1) << 16;
            float x;
            std::memcpy(&x, &b, sizeof x);
            t_[i] = i == kN ? t_[kN - 1] : (std::isfinite(x) ? c(x) : c(std::numeric_limits<float>::max()));
        }
    }
    float operator()(float x) const {
        if (!(x > 0.0f)) return 0.0f;
        std::uint32_t b;
        std::memcpy(&b, &x, sizeof b);
        const std::uint32_t i = b >> 16;
        if (i >= kN - 1) return t_[kN - 1];
        const float f = static_cast<float>(b & 0xffffu) * (1.0f / 65536.0f);
        return t_[i] + (t_[i + 1] - t_[i]) * f;
    }

private:
    static constexpr std::uint32_t kN = 1u << 15;  // sign 0, 8 exponent bits, 7 mantissa bits
    std::vector<float> t_;
};

double srgbDecode(double v) {
    v = std::min(std::max(v, 0.0), 1.0);
    return v <= 0.04045 ? v / 12.92 : std::pow((v + 0.055) / 1.055, 2.4);
}

std::vector<float> inverseTable(const BokehTone& s) {
    const int n = s.curvePoints;
    std::vector<double> env(static_cast<std::size_t>(n)), grid(static_cast<std::size_t>(n));
    double run = -std::numeric_limits<double>::infinity();
    for (int i = 0; i < n; ++i) {
        run = std::max(run, static_cast<double>(s.curve[i]));
        env[static_cast<std::size_t>(i)] = run + i * 1e-9;
        grid[static_cast<std::size_t>(i)] = s.curveLo + (static_cast<double>(s.curveHi) - s.curveLo) * i / (n - 1);
    }
    std::vector<float> out(kInvPoints + 1);
    int i = 0;
    for (int j = 0; j <= kInvPoints; ++j) {
        const double v = static_cast<double>(j) / kInvPoints;
        double x;
        if (v <= env[0]) {
            x = (env[0] > 0 ? v / env[0] : 1.0) * std::exp2(static_cast<double>(s.curveLo));
        } else if (v >= env[static_cast<std::size_t>(n - 1)]) {
            x = std::exp2(static_cast<double>(s.curveHi));
        } else {
            while (i < n - 2 && env[static_cast<std::size_t>(i + 1)] <= v) ++i;
            const double e = grid[i] + (grid[i + 1] - grid[i]) * (v - env[i]) / (env[i + 1] - env[i]);
            x = std::exp2(e);
        }
        out[static_cast<std::size_t>(j)] = static_cast<float>(x);
    }
    return out;
}

inline float invAt(const std::vector<float>& inv, float v) {
    v = std::min(std::max(v, 0.0f), 1.0f) * kInvPoints;
    const int j = std::min(static_cast<int>(v), kInvPoints - 1);
    return inv[static_cast<std::size_t>(j)] + (inv[static_cast<std::size_t>(j) + 1] - inv[static_cast<std::size_t>(j)]) * (v - j);
}

// Bilinear resize sampling plan (half-pixel centres, clamped): per output index, i0, i1, weight.
struct Axis {
    std::vector<int> i0, i1;
    std::vector<float> w;
    void plan(int src, int dst) {
        i0.resize(static_cast<std::size_t>(dst));
        i1.resize(static_cast<std::size_t>(dst));
        w.resize(static_cast<std::size_t>(dst));
        for (int o = 0; o < dst; ++o) {
            double f = (o + 0.5) * src / dst - 0.5;
            f = std::min(std::max(f, 0.0), static_cast<double>(src - 1));
            const int a = std::min(static_cast<int>(std::floor(f)), src - 1);
            i0[static_cast<std::size_t>(o)] = a;
            i1[static_cast<std::size_t>(o)] = std::min(a + 1, src - 1);
            w[static_cast<std::size_t>(o)] = static_cast<float>(f - a);
        }
    }
    // Level up-sampling: x_level = (x + 0.5) / q - 0.5
    void planUp(int lvl, int dst, int q) {
        i0.resize(static_cast<std::size_t>(dst));
        i1.resize(static_cast<std::size_t>(dst));
        w.resize(static_cast<std::size_t>(dst));
        for (int o = 0; o < dst; ++o) {
            double f = (o + 0.5) / q - 0.5;
            f = std::min(std::max(f, 0.0), static_cast<double>(lvl - 1));
            const int a = std::min(static_cast<int>(std::floor(f)), lvl - 1);
            i0[static_cast<std::size_t>(o)] = a;
            i1[static_cast<std::size_t>(o)] = std::min(a + 1, lvl - 1);
            w[static_cast<std::size_t>(o)] = static_cast<float>(f - a);
        }
    }
};

std::vector<float> resizeMap(const float* src, int sw, int sh, int dw, int dh) {
    Axis ax, ay;
    ax.plan(sw, dw);
    ay.plan(sh, dh);
    std::vector<float> out(static_cast<std::size_t>(dw) * dh);
    for (int y = 0; y < dh; ++y) {
        const float* r0 = src + static_cast<std::size_t>(ay.i0[y]) * sw;
        const float* r1 = src + static_cast<std::size_t>(ay.i1[y]) * sw;
        const float wy = ay.w[y];
        for (int x = 0; x < dw; ++x) {
            const float wx = ax.w[x];
            const float a = r0[ax.i0[x]] * (1 - wx) + r0[ax.i1[x]] * wx;
            const float b = r1[ax.i0[x]] * (1 - wx) + r1[ax.i1[x]] * wx;
            out[static_cast<std::size_t>(y) * dw + x] = a * (1 - wy) + b * wy;
        }
    }
    return out;
}

double pct(const float* a, std::size_t n, double q) {
    std::vector<float> s(a, a + n);
    const std::size_t k = static_cast<std::size_t>(std::floor(q * (n - 1) + 0.5));
    std::nth_element(s.begin(), s.begin() + static_cast<std::ptrdiff_t>(k), s.end());
    return s[k];
}

double focusSample(const float* m, int w, int h, double fx, double fy) {
    int r = std::max(1, static_cast<int>(std::lround(kFocusWin * std::max(w, h))));
    r = std::min(r, std::min((w - 1) / 2, (h - 1) / 2));
    const int cx = std::min(std::max(static_cast<int>(std::floor(fx * w)), r), w - 1 - r);
    const int cy = std::min(std::max(static_cast<int>(std::floor(fy * h)), r), h - 1 - r);
    std::vector<float> win;
    for (int y = cy - r; y <= cy + r; ++y)
        for (int x = cx - r; x <= cx + r; ++x) win.push_back(m[static_cast<std::size_t>(y) * w + x]);
    const std::size_t k = (win.size() - 1) / 2;
    std::nth_element(win.begin(), win.begin() + static_cast<std::ptrdiff_t>(k), win.end());
    return win[k];
}

double smoothstep(double a, double b, double x) {
    const double t = std::min(std::max((x - a) / (b - a), 0.0), 1.0);
    return t * t * (3 - 2 * t);
}

double ladderRadius(int k) {
    if (k <= 0) return 0.0;
    if (k <= kLadderFine) return std::exp2((k - 1) / 2.0);
    return std::exp2(3.5) * std::exp2((k - kLadderFine) / 4.0);
}

int ladderMax(double cap) {
    int k = 1;
    while (ladderRadius(k) < cap) ++k;
    return k;
}

// (2r+1)^2 mean, clamped window (divided by the pixels inside); B31.1: separable running sums in double, rows and
// columns in parallel (the integral image it replaces was serial and the radius map's largest cost); float maps.
struct BoxMean {
    int w, h, r;
    Exec& ex;
    std::vector<double> src, tmp;
    BoxMean(int w_, int h_, int r_, Exec& e)
        : w(w_), h(h_), r(r_), ex(e), src(static_cast<std::size_t>(w_) * h_), tmp(static_cast<std::size_t>(w_) * h_) {}
    template <typename F>
    std::vector<float> operator()(F f) {
        parallelRows(h, ex, [&](int a, int b) {
            for (std::size_t i = static_cast<std::size_t>(a) * w; i < static_cast<std::size_t>(b) * w; ++i) src[i] = f(i);
        });
        // horizontal window sums
        parallelRows(h, ex, [&](int a, int b) {
            for (int y = a; y < b; ++y) {
                const double* s = src.data() + static_cast<std::size_t>(y) * w;
                double* o = tmp.data() + static_cast<std::size_t>(y) * w;
                double run = 0;
                for (int x = 0; x < std::min(r, w); ++x) run += s[x];
                for (int x = 0; x < w; ++x) {
                    if (x + r < w) run += s[x + r];
                    if (x - r - 1 >= 0) run -= s[x - r - 1];
                    o[x] = run;
                }
            }
        });
        std::vector<float> out(static_cast<std::size_t>(w) * h);
        // vertical window sums over column strips, divided by the window's pixel count
        const int strips = (w + 63) / 64;
        parallelRows(strips, ex, [&](int a, int b) {
            std::vector<double> run(64);
            for (int sIdx = a; sIdx < b; ++sIdx) {
                const int x0 = sIdx * 64, x1 = std::min(x0 + 64, w);
                std::fill(run.begin(), run.end(), 0.0);
                for (int y = 0; y < std::min(r, h); ++y)
                    for (int x = x0; x < x1; ++x) run[x - x0] += tmp[static_cast<std::size_t>(y) * w + x];
                for (int y = 0; y < h; ++y) {
                    const int ya = std::max(y - r, 0), yb = std::min(y + r + 1, h);
                    if (y + r < h)
                        for (int x = x0; x < x1; ++x) run[x - x0] += tmp[static_cast<std::size_t>(y + r) * w + x];
                    if (y - r - 1 >= 0)
                        for (int x = x0; x < x1; ++x) run[x - x0] -= tmp[static_cast<std::size_t>(y - r - 1) * w + x];
                    for (int x = x0; x < x1; ++x) {
                        const int xa = std::max(x - r, 0), xb = std::min(x + r + 1, w);
                        out[static_cast<std::size_t>(y) * w + x] = static_cast<float>(run[x - x0] / ((yb - ya) * (xb - xa)));
                    }
                }
            }
        });
        return out;
    }
};

// ------------------------------------------------------------------------------------------------ the still

struct Still {
    std::uint8_t* rgba = nullptr;
    float* rgb = nullptr;
    std::size_t stride = 0;  // bytes (rgba) or floats (rgb)
    int w = 0, h = 0;
    float enc(int x, int y, int c) const {
        if (rgba != nullptr) return rgba[static_cast<std::size_t>(y) * stride + static_cast<std::size_t>(x) * 4 + c] * (1.0f / 255.0f);
        return rgb[static_cast<std::size_t>(y) * stride + static_cast<std::size_t>(x) * 3 + c];
    }
};

std::string radiusMapImpl(const Still& im, const BokehDepth& d, const BokehParams& p, std::vector<float>& map, int& ww, int& hh,
                          BokehStats& st, Exec& ex) {
    const int w = im.w, h = im.h;
    const int fw = std::max(1, (std::max(w, h) + p.workMax - 1) / p.workMax);
    ww = (w + fw - 1) / fw;
    hh = (h + fw - 1) / fw;
    // guide: the encoded RGB box-averaged to the working grid (partial edge boxes)
    std::vector<float> guide[3];
    {
        const auto tg = Clock::now();
        std::vector<double> acc(static_cast<std::size_t>(ww) * hh * 3, 0.0), cnt(static_cast<std::size_t>(ww) * hh, 0.0);
        parallelRows(hh, ex, [&](int ga, int gb) {
            for (int y = ga * fw; y < std::min(gb * fw, h); ++y) {
                const int gy = y / fw;
                for (int x = 0; x < w; ++x) {
                    const std::size_t i = static_cast<std::size_t>(gy) * ww + x / fw;
                    for (int c = 0; c < 3; ++c) acc[i * 3 + c] += im.enc(x, y, c);
                    cnt[i] += 1.0;
                }
            }
        });
        st.guideMs += msSince(tg);
        for (int c = 0; c < 3; ++c) {
            guide[c].resize(cnt.size());
            for (std::size_t i = 0; i < cnt.size(); ++i) guide[c][i] = static_cast<float>(acc[i * 3 + c] / cnt[i]);
        }
    }
    const std::size_t dn = static_cast<std::size_t>(d.width) * d.height;
    const double rf = focusSample(d.depth, d.width, d.height, p.focusX, p.focusY);
    const double rbg = pct(d.depth, dn, kBgPct);
    const double ds = 1.0 / p.focusM;
    const double dfar = (p.farM <= 0.0f || p.farM <= p.focusM) ? 0.0 : 1.0 / p.farM;
    const double a = (ds - dfar) / std::max(rf - rbg, kMinSpan);
    const std::vector<float> rw = resizeMap(d.depth, d.width, d.height, ww, hh);
    const double f = p.focalMm;
    const double base = f * f / (p.aperture * (p.focusM * 1000.0 - f));
    const double kp = bokehPhoneFactor(p.aperture, p.phoneN);
    const double cap = p.maxCoc * w / 2.0;
    std::vector<double> rho(static_cast<std::size_t>(ww) * hh);
    for (std::size_t i = 0; i < rho.size(); ++i) {
        const double dd = std::max(ds + a * (rw[i] - rf), 0.0);
        const double b = base * (ds - dd) / ds * kp;
        rho[i] = std::min(std::max(b / kFfWidthMm * w / 2.0, -cap), cap);
    }
    st.onPerson = false;
    if (d.mask != nullptr && focusSample(d.mask, d.width, d.height, p.focusX, p.focusY) >= 0.5) {
        st.onPerson = true;
        const std::vector<float> mw = resizeMap(d.mask, d.width, d.height, ww, hh);
        for (std::size_t i = 0; i < rho.size(); ++i) {
            const double m = mw[i] * (1.0 - smoothstep(kPersonLo, kPersonHi, std::fabs(rw[i] - rf)));
            rho[i] *= 1.0 - std::min(std::max(m, 0.0), 1.0);
        }
    }
    const int gr = std::max(1, static_cast<int>(std::floor(2.0 * std::max(static_cast<double>(ww) / d.width, static_cast<double>(hh) / d.height) + 0.5)));
    map.resize(rho.size());
    float mx = 0;
    if (!p.refine) {
        for (std::size_t i = 0; i < rho.size(); ++i) map[i] = static_cast<float>(rho[i]);
    } else {
        // certainty: low on depth edges and on an undecided person mask (depth grid), up-sampled to the working grid
        std::vector<float> cert(dn);
        for (int y = 0; y < d.height; ++y)
            for (int x = 0; x < d.width; ++x) {
                auto at = [&](int xx, int yy) {
                    xx = std::min(std::max(xx, 0), d.width - 1);
                    yy = std::min(std::max(yy, 0), d.height - 1);
                    return static_cast<double>(d.depth[static_cast<std::size_t>(yy) * d.width + xx]);
                };
                const double gx = (at(x + 1, y) - at(x - 1, y)) * 0.5, gy = (at(x, y + 1) - at(x, y - 1)) * 0.5;
                double c = 1.0 - std::min(std::max(std::sqrt(gx * gx + gy * gy) / kCertGrad, 0.0), 1.0);
                const std::size_t i = static_cast<std::size_t>(y) * d.width + x;
                if (st.onPerson) c = std::min(c, 1.0 - 4.0 * d.mask[i] * (1.0 - static_cast<double>(d.mask[i])));
                if (d.confMap != nullptr) c = std::min(c, static_cast<double>(d.confMap[i]));
                cert[i] = static_cast<float>(c);
            }
        std::vector<float> cw = resizeMap(cert.data(), d.width, d.height, ww, hh);
        for (float& v : cw) v = std::min(std::max(v, 0.0f), 1.0f);
        // He et al. colour guided filter on p = rho / cap, c p and c (the guide's inverse covariance shared)
        const auto tgf = Clock::now();
        BoxMean box(ww, hh, gr, ex);
        // per-pixel loops of the filter over the working rows in parallel
        const auto pfor = [&](const std::function<void(std::size_t, std::size_t)>& f) {
            parallelRows(hh, ex, [&](int ra, int rb) { f(static_cast<std::size_t>(ra) * ww, static_cast<std::size_t>(rb) * ww); });
        };
        std::vector<float> mi[3];
        for (int c = 0; c < 3; ++c) mi[c] = box([&](std::size_t i) { return static_cast<double>(guide[c][i]); });
        std::vector<float> inv[6];  // symmetric inverse of (Sigma + eps U): 00 01 02 11 12 22
        {
            std::vector<float> sg[6];
            static const int pi[6] = {0, 0, 0, 1, 1, 2}, pj[6] = {0, 1, 2, 1, 2, 2};
            for (int k = 0; k < 6; ++k)
                sg[k] = box([&](std::size_t i) { return static_cast<double>(guide[pi[k]][i]) * guide[pj[k]][i]; });
            for (auto& v : inv) v.resize(rho.size());
            pfor([&](std::size_t i0, std::size_t i1) {
            for (std::size_t i = i0; i < i1; ++i) {
                const double m0 = mi[0][i], m1 = mi[1][i], m2 = mi[2][i];
                const double a00 = sg[0][i] - m0 * m0 + kGfEps, a01 = sg[1][i] - m0 * m1, a02 = sg[2][i] - m0 * m2;
                const double a11 = sg[3][i] - m1 * m1 + kGfEps, a12 = sg[4][i] - m1 * m2, a22 = sg[5][i] - m2 * m2 + kGfEps;
                const double c00 = a11 * a22 - a12 * a12, c01 = a02 * a12 - a01 * a22, c02 = a01 * a12 - a02 * a11;
                const double c11 = a00 * a22 - a02 * a02, c12 = a01 * a02 - a00 * a12, c22 = a00 * a11 - a01 * a01;
                const double det = a00 * c00 + a01 * c01 + a02 * c02;
                inv[0][i] = static_cast<float>(c00 / det);
                inv[1][i] = static_cast<float>(c01 / det);
                inv[2][i] = static_cast<float>(c02 / det);
                inv[3][i] = static_cast<float>(c11 / det);
                inv[4][i] = static_cast<float>(c12 / det);
                inv[5][i] = static_cast<float>(c22 / det);
            }
            });
        }
        auto gf = [&](const std::function<double(std::size_t)>& pf) {
            const std::vector<float> mp = box(pf);
            std::vector<float> cv[3];
            for (int c = 0; c < 3; ++c) cv[c] = box([&](std::size_t i) { return static_cast<double>(guide[c][i]) * pf(i); });
            std::vector<float> ca[3], cb(rho.size());
            for (int c = 0; c < 3; ++c) ca[c].resize(rho.size());
            pfor([&](std::size_t i0, std::size_t i1) {
            for (std::size_t i = i0; i < i1; ++i) {
                const double m0 = mi[0][i], m1 = mi[1][i], m2 = mi[2][i], mpv = mp[i];
                const double v0 = cv[0][i] - m0 * mpv, v1 = cv[1][i] - m1 * mpv, v2 = cv[2][i] - m2 * mpv;
                const double x0 = inv[0][i] * v0 + inv[1][i] * v1 + inv[2][i] * v2;
                const double x1 = inv[1][i] * v0 + inv[3][i] * v1 + inv[4][i] * v2;
                const double x2 = inv[2][i] * v0 + inv[4][i] * v1 + inv[5][i] * v2;
                ca[0][i] = static_cast<float>(x0);
                ca[1][i] = static_cast<float>(x1);
                ca[2][i] = static_cast<float>(x2);
                cb[i] = static_cast<float>(mpv - (x0 * m0 + x1 * m1 + x2 * m2));
            }
            });
            const std::vector<float> mb = box([&](std::size_t i) { return static_cast<double>(cb[i]); });
            std::vector<double> q(rho.size());
            std::vector<float> ma[3];
            for (int c = 0; c < 3; ++c) ma[c] = box([&](std::size_t i) { return static_cast<double>(ca[c][i]); });
            pfor([&](std::size_t i0, std::size_t i1) {
                for (std::size_t i = i0; i < i1; ++i)
                    q[i] = mb[i] + static_cast<double>(ma[0][i]) * guide[0][i] + static_cast<double>(ma[1][i]) * guide[1][i] +
                           static_cast<double>(ma[2][i]) * guide[2][i];
            });
            return q;
        };
        const std::vector<double> qp = gf([&](std::size_t i) { return rho[i] / cap; });
        const std::vector<double> qcp = gf([&](std::size_t i) { return cw[i] * rho[i] / cap; });
        const std::vector<double> qc = gf([&](std::size_t i) { return static_cast<double>(cw[i]); });
        pfor([&](std::size_t i0, std::size_t i1) {
            for (std::size_t i = i0; i < i1; ++i) {
                const double t = smoothstep(kCertLo, kCertHi, qc[i]);
                const double q = t * qcp[i] / std::max(qc[i], kCertLo) + (1.0 - t) * qp[i];
                map[i] = static_cast<float>(std::min(std::max(q * cap, -cap), cap));
            }
        });
        st.guidedMs = msSince(tgf);
    }
    for (std::size_t i = 0; i < rho.size(); ++i) mx = std::max(mx, std::fabs(map[i]));
    // the guided filter's float maps (3 guide, 3 means, p, mean p, 3 + 6 moments) and the double integral
    st.peakBytes = std::max(st.peakBytes, static_cast<double>(rho.size()) * (17.0 * 4 + 8 + 8));
    st.rFocus = static_cast<float>(rf);
    st.rBg = static_cast<float>(rbg);
    st.scale = static_cast<float>(a);
    st.capRadius = static_cast<float>(cap);
    st.workW = ww;
    st.workH = hh;
    st.gfRadius = gr;
    st.maxRadius = mx;
    st.phoneFactor = static_cast<float>(kp);
    return "";
}

// ------------------------------------------------------------------------------------------------ render

struct Layer {
    int k;
    double R;
    int q;
    double r;
    std::unique_ptr<BladeRows> blade;
};

// B31.1: the level kernel's radius. The box down-sample (variance q^2 / 12) and the bilinear up-sample (q^2 / 6) widen
// a disc gathered at level q by q^2 / 4 per axis; a uniform disc of radius R has R^2 / 4, so the level disc is shrunk
// to keep the rendered disc's second moment: r = sqrt((R / q)^2 - 1) for q > 1.
double levelRadius(double rk, int q) {
    const double r = rk / q;
    return q > 1 ? std::sqrt(std::max(r * r - 1.0, 0.25 * r * r)) : r;
}

int levelOf(double rk) {
    int q = 1;
    while (rk / (2 * q) >= kR0) q *= 2;
    return q;
}

// Level-resolution rows [y0, y0 + rows) of width wl, padded by pad zero pixels each side, nc floats per pixel
// (premultiplied r, g, b, then one alpha (nc 4) or one alpha per colour lane (nc 6, the CA case)), with flags on the
// 32 x 32 tiles (global tile grid) that hold anything. Kept all-zero between uses: clear() re-zeroes only the flagged
// tiles, so a sparse layer costs what it covers.
struct TileBuf {
    int y0 = 0, rows = 0, cap = 0, wl = 0, pad = 0, tilesX = 0, ty0 = 0, tyN = 0, nc = 4;
    std::vector<float> v;
    std::vector<unsigned char> flag;
    void init(int y0_, int rows_, int wl_, int pad_, int nc_) {
        wl = wl_;
        pad = pad_;
        cap = rows_;
        nc = nc_;
        v.assign(static_cast<std::size_t>(rows_) * (wl + 2 * pad) * nc, 0.0f);
        tilesX = (wl + kTile - 1) / kTile;
        rebase(y0_, rows_);
    }
    // New row range on the same (all-zero) storage.
    void rebase(int y0_, int rows_) {
        y0 = y0_;
        rows = std::min(rows_, cap);
        ty0 = y0 / kTile;
        tyN = rows > 0 ? (y0 + rows - 1) / kTile - ty0 + 1 : 0;
        flag.assign(static_cast<std::size_t>(std::max(tyN, 0)) * tilesX, 0);
    }
    float* row(int y) { return v.data() + (static_cast<std::size_t>(y - y0) * (wl + 2 * pad) + pad) * nc; }
    const float* row(int y) const { return v.data() + (static_cast<std::size_t>(y - y0) * (wl + 2 * pad) + pad) * nc; }
    void mark(int x, int y) { __atomic_store_n(&flag[static_cast<std::size_t>(y / kTile - ty0) * tilesX + x / kTile], 1, __ATOMIC_RELAXED); }
    bool tile(int tx, int ty) const {
        if (tx < 0 || tx >= tilesX || ty < ty0 || ty >= ty0 + tyN) return false;
        return flag[static_cast<std::size_t>(ty - ty0) * tilesX + tx] != 0;
    }
    bool any() const {
        for (unsigned char f : flag)
            if (f) return true;
        return false;
    }
    template <typename F>
    void forTiles(F f) {
        for (int t = 0; t < tyN; ++t)
            for (int tx = 0; tx < tilesX; ++tx)
                if (flag[static_cast<std::size_t>(t) * tilesX + tx]) {
                    const int ya = std::max((ty0 + t) * kTile, y0), yb = std::min((ty0 + t + 1) * kTile, y0 + rows);
                    f(tx * kTile, std::min((tx + 1) * kTile, wl), ya, yb);
                }
    }
    void clear() {
        forTiles([&](int xa, int xb, int ya, int yb) {
            for (int y = ya; y < yb; ++y) std::fill(row(y) + static_cast<std::size_t>(xa) * nc, row(y) + static_cast<std::size_t>(xb) * nc, 0.0f);
        });
        std::fill(flag.begin(), flag.end(), 0);
    }
    double bytes() const { return static_cast<double>(v.size()) * sizeof(float); }
};

class Renderer {
public:
    Renderer(const Still& im, const BokehTone& tone, const BokehParams& p, BokehStats& st, Exec& ex)
        : im_(im), tone_(tone), curve_(tone), toneLut_(curve_), p_(p), st_(st), ex_(ex) {}

    std::string run(const BokehDepth& d) {
        const auto t0 = Clock::now();
        std::string err = radiusMapImpl(im_, d, p_, map_, mw_, mh_, st_, ex_);
        if (!err.empty()) return err;
        w_ = im_.w;
        h_ = im_.h;
        kmax_ = ladderMax(st_.capRadius);
        if (kmax_ > 62) return "blur cap too large";
        ca_ = caActive(p_.focalMm, p_.aperture);
        nc_ = ca_ ? 6 : 4;
        beta_ = bokehBladeBeta(p_.aperture);
        tiled_ = catStrength(p_.aperture) > 0;
        hd_ = std::hypot(static_cast<double>(w_), static_cast<double>(h_)) / 2.0;
        st_.kmax = kmax_;
        st_.ca = ca_;
        st_.beta = static_cast<float>(beta_);
        for (int k = 0; k <= kmax_ + 1; ++k) {
            rad_.push_back(ladderRadius(k));
            rad2_.push_back(rad_.back() * rad_.back());
        }
        const auto tu = Clock::now();
        // the full-resolution signed radius, 1/64 px (int16; see the reference), bilinear from the working grid
        {
            Axis mx, my;
            mx.plan(mw_, w_);
            my.plan(mh_, h_);
            radQ_.resize(static_cast<std::size_t>(w_) * h_);
            parallelRows(h_, ex_, [&](int a, int b) {
                for (int y = a; y < b; ++y) {
                    const float* r0 = map_.data() + static_cast<std::size_t>(my.i0[y]) * mw_;
                    const float* r1 = map_.data() + static_cast<std::size_t>(my.i1[y]) * mw_;
                    const float wy = my.w[y];
                    for (int x = 0; x < w_; ++x) {
                        const float wx = mx.w[x];
                        const float t = r0[mx.i0[x]] * (1 - wx) + r0[mx.i1[x]] * wx;
                        const float u = r1[mx.i0[x]] * (1 - wx) + r1[mx.i1[x]] * wx;
                        const double v = std::floor((t * (1 - wy) + u * wy) * 64.0 + 0.5);
                        radQ_[static_cast<std::size_t>(y) * w_ + x] = static_cast<std::int16_t>(std::min(std::max(v, -32767.0), 32767.0));
                    }
                }
            });
            std::vector<float>().swap(map_);
        }
        // ladder positions by quantised radius (nominal, x(1 + e), x(1 - e) for the colour lanes)
        {
            int qmax = 0;
            for (std::int16_t v : radQ_) qmax = std::max(qmax, std::abs(static_cast<int>(v)));
            for (int c = 0; c < 3; ++c) uLut_[c].resize(static_cast<std::size_t>(qmax) + 1);
            for (int i = 0; i <= qmax; ++i) {
                const double a = i / 64.0;
                const double e[3] = {0.0, kCaEps, -kCaEps};
                for (int c = 0; c < 3; ++c) {
                    double u = 0.0;
                    // B31.1: the CA fades in with the blur (none below kCaRampLo px, full from kCaRampHi), so the
                    // partly blurred pixels of an in-focus/out-of-focus seam are not split by colour
                    const double ec = e[c] * smoothstep(kCaRampLo, kCaRampHi, a);
                    if (a >= kSnap) u = a < 1.0 ? a * a : pos(c == 0 ? a : std::max(a * (1.0 + ec), 1.0));
                    uLut_[c][static_cast<std::size_t>(i)] = static_cast<float>(u);
                }
            }
        }
        // per 32 x 32 tile of the still: exactly the layers its pixels feed on any lane (floor(u) and floor(u) + 1 of
        // the lowest and highest lane), and whether it holds a pixel in front of the focus plane
        tilesW_ = (w_ + kTile - 1) / kTile;
        tilesH_ = (h_ + kTile - 1) / kTile;
        layerMask_.assign(static_cast<std::size_t>(tilesW_) * tilesH_, 0);
        std::atomic<bool> anyFront{false};
        parallelRows(tilesH_, ex_, [&](int a, int b) {
            bool fr = false;
            for (int ty = a; ty < b; ++ty)
                for (int y = ty * kTile; y < std::min((ty + 1) * kTile, h_); ++y)
                    for (int x = 0; x < w_; ++x) {
                        const int rq = radQ_[static_cast<std::size_t>(y) * w_ + x];
                        const int qi = std::abs(rq);
                        const float un = uLut_[0][static_cast<std::size_t>(qi)];
                        if (un <= 0.0f) continue;
                        float ulo = un, uhi = un;
                        if (ca_) {
                            for (int c = 1; c < 3; ++c) {
                                ulo = std::min(ulo, uLut_[c][static_cast<std::size_t>(qi)]);
                                uhi = std::max(uhi, uLut_[c][static_cast<std::size_t>(qi)]);
                            }
                        }
                        fr = fr || rq < 0;
                        std::uint64_t m = 0;
                        for (int j = std::max(static_cast<int>(ulo), 1); j <= std::min(static_cast<int>(uhi) + 1, kmax_); ++j)
                            m |= std::uint64_t{1} << j;
                        layerMask_[static_cast<std::size_t>(ty) * tilesW_ + x / kTile] |= m;
                    }
            if (fr) anyFront.store(true);
        });
        front_ = anyFront.load();
        st_.peakBytes = std::max(st_.peakBytes, static_cast<double>(radQ_.size()) * 2);
        st_.upsampleMs = msSince(tu);
        st_.mapMs = msSince(t0);
        const auto ti = Clock::now();
        inv_ = inverseTable(tone_);
        if (im_.rgba != nullptr)
            for (int i = 0; i < 256; ++i) lut_[i] = invAt(inv_, i * (1.0f / 255.0f));
        hdr_ = tone_.gainLog2 != nullptr && tone_.slope > 0 && tone_.gainW > 0 && tone_.gainH > 0;
        if (hdr_) {
            gX_.plan(tone_.gainW, w_);
            gY_.plan(tone_.gainH, h_);
            xk_ = std::exp2(tone_.kneeEv);
            lk_ = srgbDecode(curve_(static_cast<float>(xk_)));
        }
        gout_ = hdr_ && tone_.gainOut != nullptr;
        if (gout_) {
            const int gw = tone_.gainW, gh = tone_.gainH;
            const bool quarter = gw == (w_ + 3) / 4 && gh == (h_ + 3) / 4;
            cellX_.resize(static_cast<std::size_t>(w_));
            cellY_.resize(static_cast<std::size_t>(h_));
            for (int x = 0; x < w_; ++x)
                cellX_[static_cast<std::size_t>(x)] = quarter ? x / 4 : std::min(gw - 1, static_cast<int>(static_cast<long long>(x) * gw / w_));
            for (int y = 0; y < h_; ++y)
                cellY_[static_cast<std::size_t>(y)] = quarter ? y / 4 : std::min(gh - 1, static_cast<int>(static_cast<long long>(y) * gh / h_));
            const std::size_t n = static_cast<std::size_t>(gw) * static_cast<std::size_t>(gh);
            gSum_.assign(n, 0.0);
            gCnt_.assign(n, 0);
            gTouch_.assign(n, 0);
            log2Lk_ = lk_ > 0 ? std::log2(lk_) : 0.0;
        }
        st_.invertMs = msSince(ti);
        if (front_) {
            const auto tr = Clock::now();
            rearFill();
            st_.rearMs = msSince(tr);
        }
        st_.front = front_;
        for (int k = 1; k <= kmax_; ++k) {
            Layer l;
            l.k = k;
            l.R = rad_[static_cast<std::size_t>(k)];
            l.q = levelOf(l.R);
            l.r = levelRadius(l.R, l.q);
            l.blade = std::make_unique<BladeRows>(l.r, beta_, l.R);
            layers_.push_back(std::move(l));
        }
        const auto t1 = Clock::now();
        wholeLevels();
        st_.levelsMs = msSince(t1);
        const auto t2 = Clock::now();
        bands();
        st_.bandsMs = msSince(t2);
        if (gout_) {
            const auto tg = Clock::now();
            const std::size_t n = gSum_.size();
            for (std::size_t i = 0; i < n; ++i)
                tone_.gainOut[i] = gTouch_[i] && gCnt_[i] > 0 ? static_cast<float>(gSum_[i] / gCnt_[i]) : tone_.gainLog2[i];
            st_.gainMs += msSince(tg);
        }
        st_.totalMs = msSince(t0);
        st_.applied = true;
        return "";
    }

private:
    // Encoded -> linear (curve input units) with the HDR continuation, one pixel.
    void linAt(int x, int y, float* o) const {
        if (im_.rgba != nullptr) {
            const std::uint8_t* px = im_.rgba + static_cast<std::size_t>(y) * im_.stride + static_cast<std::size_t>(x) * 4;
            o[0] = lut_[px[0]];
            o[1] = lut_[px[1]];
            o[2] = lut_[px[2]];
        } else {
            const float* px = im_.rgb + static_cast<std::size_t>(y) * im_.stride + static_cast<std::size_t>(x) * 3;
            o[0] = invAt(inv_, px[0]);
            o[1] = invAt(inv_, px[1]);
            o[2] = invAt(inv_, px[2]);
        }
        if (!hdr_) return;
        const double yl = kLuma[0] * static_cast<double>(o[0]) + kLuma[1] * static_cast<double>(o[1]) + kLuma[2] * static_cast<double>(o[2]);
        if (!(yl > xk_)) return;
        const float* g0 = tone_.gainLog2 + static_cast<std::size_t>(gY_.i0[y]) * tone_.gainW;
        const float* g1 = tone_.gainLog2 + static_cast<std::size_t>(gY_.i1[y]) * tone_.gainW;
        const float wx = gX_.w[x], wy = gY_.w[y];
        const float a = g0[gX_.i0[x]] * (1 - wx) + g0[gX_.i1[x]] * wx;
        const float b = g1[gX_.i0[x]] * (1 - wx) + g1[gX_.i1[x]] * wx;
        const double g = a * (1 - wy) + b * wy;
        if (!(g > 0)) return;
        const double lsdr = srgbDecode(curve_(static_cast<float>(yl)));
        const double yh = xk_ * std::pow(std::max(lsdr * std::exp2(g) / lk_, 1e-12), 1.0 / tone_.slope);
        const float s = static_cast<float>(std::min(std::max(yh / std::max(yl, 1e-12), 1.0), std::exp2(kHdrCapEv)));
        o[0] *= s;
        o[1] *= s;
        o[2] *= s;
    }

    // The band's linear pixels when the row is held there, else computed.
    void lin(int x, int y, float* o) const {
        if (y >= linY0_ && y < linY1_) {
            const float* s = linBand_.data() + (static_cast<std::size_t>(y - linY0_) * w_ + x) * 3;
            o[0] = s[0];
            o[1] = s[1];
            o[2] = s[2];
            return;
        }
        linAt(x, y, o);
    }

    // B31 gain out: the log2 HDR gain of a linear (curve input) luma y, the B25 GainTable continuation.
    float gainOfLuma(double y) const {
        if (!(y > xk_)) return 0.0f;
        const double lsdr = srgbDecode(curve_(static_cast<float>(y)));
        if (!(lsdr > 0.0)) return 0.0f;
        return static_cast<float>(std::max(0.0, log2Lk_ + tone_.slope * (std::log2(y) - tone_.kneeEv) - std::log2(lsdr)));
    }

    // B31 gain out: an unchanged pixel's own gain, estimated as the input map (bilinear) where its SDR luma is above the
    // knee and 0 below it (only read for cells that also hold a changed pixel).
    float gainUnchanged(int x, int y) const {
        float c[3];
        if (im_.rgba != nullptr) {
            const std::uint8_t* px = im_.rgba + static_cast<std::size_t>(y) * im_.stride + static_cast<std::size_t>(x) * 4;
            c[0] = lut_[px[0]];
            c[1] = lut_[px[1]];
            c[2] = lut_[px[2]];
        } else {
            const float* px = im_.rgb + static_cast<std::size_t>(y) * im_.stride + static_cast<std::size_t>(x) * 3;
            for (int l = 0; l < 3; ++l) c[l] = invAt(inv_, px[l]);
        }
        const double yl = kLuma[0] * static_cast<double>(c[0]) + kLuma[1] * static_cast<double>(c[1]) + kLuma[2] * static_cast<double>(c[2]);
        if (!(yl > xk_)) return 0.0f;
        const float* g0 = tone_.gainLog2 + static_cast<std::size_t>(gY_.i0[y]) * tone_.gainW;
        const float* g1 = tone_.gainLog2 + static_cast<std::size_t>(gY_.i1[y]) * tone_.gainW;
        const float wx = gX_.w[x], wy = gY_.w[y];
        const float a = g0[gX_.i0[x]] * (1 - wx) + g0[gX_.i1[x]] * wx;
        const float b = g1[gX_.i0[x]] * (1 - wx) + g1[gX_.i1[x]] * wx;
        return std::max(0.0f, a * (1 - wy) + b * wy);
    }

    double pos(double a) const {  // ladderPos with the tables
        if (a < 1.0) return a * a;
        int k = a < rad_[kLadderFine] ? 1 + static_cast<int>(std::floor(2.0 * std::log2(a)))
                                      : kLadderFine + static_cast<int>(std::floor(4.0 * std::log2(a / rad_[kLadderFine])));
        k = std::min(std::max(k, 1), kmax_);
        for (int i = 0; i < 2; ++i) {
            if (k < kmax_ && rad_[static_cast<std::size_t>(k) + 1] <= a) ++k;
            if (k > 1 && rad_[static_cast<std::size_t>(k)] > a) --k;
        }
        if (k >= kmax_) return kmax_;
        const double r0 = rad2_[static_cast<std::size_t>(k)], r1 = rad2_[static_cast<std::size_t>(k) + 1];
        return k + std::min(std::max((a * a - r0) / (r1 - r0), 0.0), 1.0);
    }

    // Ladder positions per colour lane (r, g, b) and the nominal one ([3]) of the quantised radius |q| on its side.
    void lanes(int qi, bool behind, float u[4]) const {
        const std::size_t i = static_cast<std::size_t>(qi);
        u[3] = uLut_[0][i];
        if (!ca_) {
            u[0] = u[1] = u[2] = u[3];
            return;
        }
        // behind focus G +e, R/B -e; in front the reverse
        const float up = uLut_[1][i], dn = uLut_[2][i];
        u[0] = u[2] = behind ? dn : up;
        u[1] = behind ? up : dn;
    }

    // B31.1: the occluded background behind front pixels (see rear_fill in the reference): the non-front pixels' linear
    // colour from 8 x 8 cells, B40: each empty cell takes its nearest non-empty cell's (no longer a push-pull).
    void rearFill() {
        const int q = kRearQ;
        const int cw = (w_ + q - 1) / q, chh = (h_ + q - 1) / q;
        struct Lv {
            int w, h;
            std::vector<double> c;  // C r g b, W, N per cell
        };
        std::vector<Lv> lv;
        lv.push_back({cw, chh, std::vector<double>(static_cast<std::size_t>(cw) * chh * 5, 0.0)});
        {
            Lv& l0 = lv[0];
            parallelRows(chh, ex_, [&](int a, int b) {
                for (int cy = a; cy < b; ++cy)
                    for (int y = cy * q; y < std::min((cy + 1) * q, h_); ++y) {
                        const std::int16_t* rr = radQ_.data() + static_cast<std::size_t>(y) * w_;
                        for (int cx = 0; cx < cw; ++cx) {
                            float s0 = 0, s1 = 0, s2 = 0;
                            int nw = 0;
                            const int xa = cx * q, xb = std::min(xa + q, w_);
                            for (int x = xa; x < xb; ++x) {
                                const int rq = rr[x];
                                if (rq < 0 && uLut_[0][static_cast<std::size_t>(-rq)] > 0.0f) continue;
                                float c[3];
                                linAt(x, y, c);
                                s0 += c[0];
                                s1 += c[1];
                                s2 += c[2];
                                ++nw;
                            }
                            double* o = l0.c.data() + (static_cast<std::size_t>(cy) * cw + cx) * 5;
                            o[0] += s0;
                            o[1] += s1;
                            o[2] += s2;
                            o[3] += nw;
                            o[4] += xb - xa;
                        }
                    }
            });
        }
        while (lv.back().w > 1 || lv.back().h > 1) {
            const Lv& f = lv.back();
            Lv n{(f.w + 1) / 2, (f.h + 1) / 2, {}};
            n.c.assign(static_cast<std::size_t>(n.w) * n.h * 5, 0.0);
            for (int y = 0; y < f.h; ++y)
                for (int x = 0; x < f.w; ++x)
                    for (int k = 0; k < 5; ++k) n.c[(static_cast<std::size_t>(y / 2) * n.w + x / 2) * 5 + k] += f.c[(static_cast<std::size_t>(y) * f.w + x) * 5 + k];
            lv.push_back(std::move(n));
        }
        // the non-front share per cell of every level (read at the level about a front pixel's blur radius)
        nearLv_.clear();
        for (std::size_t li = 0; li < lv.size(); ++li) {
            NearLevel nl;
            nl.w = lv[li].w;
            nl.h = lv[li].h;
            nl.share.resize(static_cast<std::size_t>(nl.w) * nl.h);
            for (std::size_t i = 0; i < nl.share.size(); ++i) nl.share[i] = static_cast<float>(lv[li].c[i * 5 + 3] / lv[li].c[i * 5 + 4]);
            nl.ax.planUp(nl.w, w_, q << li);
            nl.ay.planUp(nl.h, h_, q << li);
            nearLv_.push_back(std::move(nl));
        }
        // B40: the hidden colour of a cell with no non-front pixel is the mean of the NEAREST cell that has some
        // (two-pass chamfer propagation of the nearest source cell, exact squared distances, fixed scan order; see
        // rear_fill in the reference). The B31.1 push-pull blended every cell with its coarser levels by the non-front
        // share, so behind a front edge it pulled in colour from far away (a bright wall beyond a mid-grey door) and lit
        // a rim brighter than both the subject and the background next to it.
        const Lv& l0 = lv[0];
        const int n0 = cw * chh;
        std::vector<int> src(static_cast<std::size_t>(n0), -1);
        for (int i = 0; i < n0; ++i)
            if (l0.c[static_cast<std::size_t>(i) * 5 + 3] > 0) src[static_cast<std::size_t>(i)] = i;
        const auto d2 = [&](int i, int j) {
            const long dx = i % cw - j % cw, dy = i / cw - j / cw;
            return dx * dx + dy * dy;
        };
        const auto relax = [&](int i, int nx, int ny) {
            if (nx < 0 || ny < 0 || nx >= cw || ny >= chh) return;
            const int c = src[static_cast<std::size_t>(ny) * cw + nx];
            if (c < 0) return;
            int& s = src[static_cast<std::size_t>(i)];
            if (s < 0 || d2(i, c) < d2(i, s)) s = c;
        };
        for (int y = 0; y < chh; ++y)
            for (int x = 0; x < cw; ++x) {
                const int i = y * cw + x;
                relax(i, x - 1, y);
                relax(i, x - 1, y - 1);
                relax(i, x, y - 1);
                relax(i, x + 1, y - 1);
            }
        for (int y = chh - 1; y >= 0; --y)
            for (int x = cw - 1; x >= 0; --x) {
                const int i = y * cw + x;
                relax(i, x + 1, y);
                relax(i, x + 1, y + 1);
                relax(i, x, y + 1);
                relax(i, x - 1, y + 1);
            }
        std::vector<float> v(static_cast<std::size_t>(n0) * 3, 0.0f);
        for (int i = 0; i < n0; ++i) {
            const int s = src[static_cast<std::size_t>(i)];
            if (s < 0) continue;
            const double* c = l0.c.data() + static_cast<std::size_t>(s) * 5;
            for (int k = 0; k < 3; ++k) v[static_cast<std::size_t>(i) * 3 + k] = static_cast<float>(c[k] / c[3]);
        }
        const int vw = cw, vh = chh;
        rear_.swap(v);
        rearW_ = vw;
        rearH_ = vh;
        rearX_.planUp(vw, w_, q);
        rearY_.planUp(vh, h_, q);
    }

    // The non-front share near (x, y) at the scale of blur radius a (px).
    float nearAt(int x, int y, double a) const {
        const int n = static_cast<int>(nearLv_.size());
        const int li = std::min(std::max(static_cast<int>(std::floor(std::log2(std::max(a, 1.0) / kRearQ) + 0.5)), 0), n - 1);
        const NearLevel& l = nearLv_[static_cast<std::size_t>(li)];
        const int x0 = l.ax.i0[static_cast<std::size_t>(x)], x1 = l.ax.i1[static_cast<std::size_t>(x)];
        const int y0 = l.ay.i0[static_cast<std::size_t>(y)], y1 = l.ay.i1[static_cast<std::size_t>(y)];
        const float wx = l.ax.w[static_cast<std::size_t>(x)], wy = l.ay.w[static_cast<std::size_t>(y)];
        const auto at = [&](int yy, int xx) { return l.share[static_cast<std::size_t>(yy) * l.w + xx]; };
        return (at(y0, x0) * (1 - wx) + at(y0, x1) * wx) * (1 - wy) + (at(y1, x0) * (1 - wx) + at(y1, x1) * wx) * wy;
    }

    void rearAt(int x, int y, float* o) const {
        const int x0 = rearX_.i0[static_cast<std::size_t>(x)], x1 = rearX_.i1[static_cast<std::size_t>(x)];
        const int y0 = rearY_.i0[static_cast<std::size_t>(y)], y1 = rearY_.i1[static_cast<std::size_t>(y)];
        const float wx = rearX_.w[static_cast<std::size_t>(x)], wy = rearY_.w[static_cast<std::size_t>(y)];
        for (int k = 0; k < 3; ++k) {
            const auto at = [&](int yy, int xx) { return rear_[(static_cast<std::size_t>(yy) * rearW_ + xx) * 3 + k]; };
            o[k] = (at(y0, x0) * (1 - wx) + at(y0, x1) * wx) * (1 - wy) + (at(y1, x0) * (1 - wx) + at(y1, x1) * wx) * wy;
        }
    }

    // Layer k's premultiplied input on both sides from full-res rows [fy0, fy1), box-averaged into level q.
    void fill(const Layer& L, int fy0, int fy1, TileBuf* side[2]) {
        const auto tf = Clock::now();
        const int q = L.q, k = L.k;
        const float kf = static_cast<float>(k);
        const int nc = nc_;
        // threads on groups of full-res rows that never share a level pixel (max(q, 32) rows, aligned); tile flags
        // shared between groups are set with relaxed atomic stores
        const int g = std::max(q, kTile);
        const int t0 = fy0 / g, t1 = (fy1 - 1) / g + 1;
        const std::uint64_t bit = std::uint64_t{1} << k;
        parallelRows(t1 - t0, ex_, [&](int a, int b) {
            for (int ty = t0 + a; ty < t0 + b; ++ty) {
                const int ya = std::max(ty * g, fy0), yb = std::min((ty + 1) * g, fy1);
                for (int fty = ya / kTile; fty * kTile < yb; ++fty) {
                    const int y0 = std::max(fty * kTile, ya), y1 = std::min((fty + 1) * kTile, yb);
                    for (int ftx = 0; ftx < tilesW_; ++ftx) {
                        if (!(layerMask_[static_cast<std::size_t>(fty) * tilesW_ + ftx] & bit)) continue;
                        const int x0 = ftx * kTile, x1 = std::min(x0 + kTile, w_);
                        for (int y = y0; y < y1; ++y) {
                            const std::int16_t* rr = radQ_.data() + static_cast<std::size_t>(y) * w_;
                            for (int x = x0; x < x1; ++x) {
                                const int rq = rr[x];
                                const int qi = std::abs(rq);
                                const float un = uLut_[0][static_cast<std::size_t>(qi)];
                                if (un <= 0.0f || un < kf - 1.5f || un > kf + 1.5f) continue;
                                const bool behind = rq > 0;
                                float u[4];
                                lanes(qi, behind, u);
                                float wl[3];
                                bool any = false;
                                for (int l = 0; l < 3; ++l) {
                                    wl[l] = std::max(0.0f, 1.0f - std::fabs(u[l] - kf));
                                    any = any || wl[l] > 0.0f;
                                }
                                if (!any) continue;
                                float c[3];
                                lin(x, y, c);
                                TileBuf& B = *side[behind ? 0 : 1];
                                const int lx = x / q, lyy = y / q;
                                float* o = B.row(lyy) + static_cast<std::size_t>(lx) * nc;
                                o[0] += wl[0] * c[0];
                                o[1] += wl[1] * c[1];
                                o[2] += wl[2] * c[2];
                                if (nc == 4) {
                                    o[3] += wl[0];
                                } else {
                                    o[3] += wl[0];
                                    o[4] += wl[1];
                                    o[5] += wl[2];
                                }
                                B.mark(lx, lyy);
                            }
                        }
                    }
                }
            }
        });
        if (q > 1) {
            for (int s = 0; s < 2; ++s)
                side[s]->forTiles([&](int xa, int xb, int ya, int yb) {
                    for (int ly = ya; ly < yb; ++ly) {
                        const int ny = std::min(q, h_ - ly * q);
                        float* r = side[s]->row(ly);
                        for (int lx = xa; lx < xb; ++lx) {
                            const float inv = 1.0f / static_cast<float>(std::min(q, w_ - lx * q) * ny);
                            for (int c = 0; c < nc; ++c) r[static_cast<std::size_t>(lx) * nc + c] *= inv;
                        }
                    }
                });
        }
        st_.fillMs += msSince(tf);
    }

    const Kernel& kernelFor(const Layer& L, int tx, int ty, std::vector<Kernel>& cache, std::vector<unsigned char>& have, int tilesX,
                            int tyBase) const {
        const std::size_t idx = tiled_ ? static_cast<std::size_t>(ty - tyBase) * tilesX + tx : 0;
        if (!have[idx]) {
            double h = 0, ux = 1, uy = 0;
            if (tiled_) {
                const double vx = ((tx * kTile + kTile / 2.0) * L.q - w_ / 2.0) / hd_;
                const double vy = ((ty * kTile + kTile / 2.0) * L.q - h_ / 2.0) / hd_;
                bokehCatEye(L.r, p_.focalMm, p_.aperture, vx, vy, h, ux, uy);
            }
            L.blade->build(h, ux, uy, cache[idx]);
            have[idx] = 1;
        }
        return cache[idx];
    }

    // One output row segment [x0, x1) of the gather (NC floats per pixel).
    template <int NC>
    static void gatherRow(const Kernel& K, const TileBuf& in, int hl, int y, int x0, int x1, float* orow) {
        for (const Term& t : K.terms) {
            const int sy = y - t.dy;
            if (sy < 0 || sy >= hl) continue;
            const float* src = in.row(sy);
            const float wgt = t.w;
            if (t.lo == t.hi) {
                const float c = wgt * t.cl;
                for (int x = x0; x < x1; ++x) {
                    const float* s = src + static_cast<std::ptrdiff_t>(x - t.lo) * NC;
                    float* o = orow + static_cast<std::size_t>(x) * NC;
                    for (int l = 0; l < NC; ++l) o[l] += c * s[l];
                }
                continue;
            }
            float acc[NC];
            for (int l = 0; l < NC; ++l) acc[l] = 0.0f;
            for (int j = x0 - t.hi + 1; j <= x0 - t.lo - 1; ++j)
                for (int l = 0; l < NC; ++l) acc[l] += src[static_cast<std::ptrdiff_t>(j) * NC + l];
            const float cl = t.cl, chh = t.ch;
            for (int x = x0; x < x1; ++x) {
                const float* sl = src + static_cast<std::ptrdiff_t>(x - t.lo) * NC;
                const float* sh = src + static_cast<std::ptrdiff_t>(x - t.hi) * NC;
                const float* sd = src + static_cast<std::ptrdiff_t>(x - t.hi + 1) * NC;
                float* o = orow + static_cast<std::size_t>(x) * NC;
                for (int l = 0; l < NC; ++l) {
                    o[l] += wgt * (acc[l] + cl * sl[l] + chh * sh[l]);
                    acc[l] += sl[l] - sd[l];
                }
            }
        }
    }

    // Accumulates into out rows [oy0, oy1) (a TileBuf without padding) the gather of `in` with layer L.
    void gather(const Layer& L, const TileBuf& in, int hl, int oy0, int oy1, TileBuf& out) {
        const auto tg = Clock::now();
        const int wl = in.wl;
        const int tilesX = in.tilesX;
        const int ty0 = oy0 / kTile, ty1 = (oy1 - 1) / kTile;
        const int nty = ty1 - ty0 + 1;
        std::vector<unsigned char> work(static_cast<std::size_t>(nty) * tilesX, 0);
        const std::size_t nk = tiled_ ? static_cast<std::size_t>(nty) * tilesX : 1;
        std::vector<Kernel> cache(nk);
        std::vector<unsigned char> have(nk, 0);
        for (int ty = ty0; ty <= ty1; ++ty)
            for (int tx = 0; tx < tilesX; ++tx) {
                bool any = false;
                for (int yy = ty - 1; yy <= ty + 1 && !any; ++yy)
                    for (int xx = tx - 1; xx <= tx + 1 && !any; ++xx) any = in.tile(xx, yy);
                if (!any) continue;
                work[static_cast<std::size_t>(ty - ty0) * tilesX + tx] = 1;
                kernelFor(L, tx, ty, cache, have, tilesX, ty0);
                // flag the output tile (rows inside [oy0, oy1))
                out.mark(tx * kTile, std::max(ty * kTile, oy0));
            }
        st_.layers++;
        const bool six = nc_ == 6;
        parallelRows(oy1 - oy0, ex_, [&](int a, int b) {
            for (int y = oy0 + a; y < oy0 + b; ++y) {
                const int ty = y / kTile;
                float* orow = out.row(y);
                const unsigned char* wr = work.data() + static_cast<std::size_t>(ty - ty0) * tilesX;
                int tx = 0;
                while (tx < tilesX) {
                    if (!wr[tx]) {
                        ++tx;
                        continue;
                    }
                    int tx1 = tx + 1;
                    if (!tiled_)
                        while (tx1 < tilesX && wr[tx1]) ++tx1;
                    const Kernel& K = cache[tiled_ ? static_cast<std::size_t>(ty - ty0) * tilesX + tx : 0];
                    const int x0 = tx * kTile, x1 = std::min(tx1 * kTile, wl);
                    if (six) {
                        gatherRow<6>(K, in, hl, y, x0, x1, orow);
                    } else {
                        gatherRow<4>(K, in, hl, y, x0, x1, orow);
                    }
                    tx = tx1;
                }
            }
        });
        st_.gatherMs += msSince(tg);
    }

    int maxK(int q) const {
        int k = 0;
        for (const Layer& L : layers_)
            if (L.q == q) k = std::max(k, L.blade->k);
        return k;
    }

    bool hasLevel(int q) const {
        for (const Layer& L : layers_)
            if (L.q == q) return true;
        return false;
    }

    struct Level {
        int q = 0, wl = 0, hl = 0;
        TileBuf acc[2];  // [0] behind, [1] in front
        Axis ax, ay;
    };

    // Levels q >= 4: whole level images, one layer at a time.
    void wholeLevels() {
        int qmax = 1;
        for (const Layer& L : layers_) qmax = std::max(qmax, L.q);
        for (int q = 4; q <= qmax; q *= 2) {
            if (!hasLevel(q)) continue;
            const int wl = (w_ + q - 1) / q, hl = (h_ + q - 1) / q;
            levels_.emplace_back();
            Level& lv = levels_.back();
            lv.q = q;
            lv.wl = wl;
            lv.hl = hl;
            lv.acc[0].init(0, hl, wl, 0, nc_);
            lv.acc[1].init(0, hl, wl, 0, nc_);
            TileBuf in[2];
            in[0].init(0, hl, wl, maxK(q) + 2, nc_);
            in[1].init(0, hl, wl, maxK(q) + 2, nc_);
            TileBuf* sides[2] = {&in[0], &in[1]};
            levelBytes_ += lv.acc[0].bytes() * 2;
            st_.peakBytes = std::max(st_.peakBytes, levelBytes_ + in[0].bytes() * 2 + static_cast<double>(radQ_.size()) * 2);
            for (const Layer& L : layers_) {
                if (L.q != q) continue;
                fill(L, 0, h_, sides);
                for (int s = 0; s < 2; ++s) {
                    if (!in[s].any()) continue;
                    gather(L, in[s], hl, 0, hl, lv.acc[s]);
                    in[s].clear();
                }
            }
            lv.ax.planUp(wl, w_, q);
            lv.ay.planUp(hl, h_, q);
        }
    }

    template <int NC>
    static void bilinearT(const TileBuf& acc, const Axis& ax, const Axis& ay, int x, int y, float* P) {
        const int x0 = ax.i0[static_cast<std::size_t>(x)], x1 = ax.i1[static_cast<std::size_t>(x)];
        const float* r0 = acc.row(ay.i0[static_cast<std::size_t>(y)]);
        const float* r1 = acc.row(ay.i1[static_cast<std::size_t>(y)]);
        const float wx = ax.w[static_cast<std::size_t>(x)], wy = ay.w[static_cast<std::size_t>(y)];
        for (int l = 0; l < NC; ++l) {
            const float t = r0[x0 * NC + l] * (1 - wx) + r0[x1 * NC + l] * wx;
            const float v = r1[x0 * NC + l] * (1 - wx) + r1[x1 * NC + l] * wx;
            P[l] += t * (1 - wy) + v * wy;
        }
    }

    struct Pending {
        int y0 = 0, rows = 0, next = 0;
        std::vector<float> px;  // rows x w x 3 linear results (NaN: untouched)
    };

    // Levels 1 and 2 in row bands, then the composite.
    void bands() {
        const int K1 = maxK(1), K2 = maxK(2);
        const int band = std::max(p_.band, 8);
        const bool has2 = hasLevel(2);
        const int wl2 = (w_ + 1) / 2, hl2 = (h_ + 1) / 2;
        Axis up2x, up2y, idx, idy;
        idx.planUp(w_, w_, 1);
        idy.planUp(h_, h_, 1);
        idx.i1 = idx.i0;  // identity: no neighbour (the band accumulator ends at the band)
        idy.i1 = idy.i0;
        if (has2) {
            up2x.planUp(wl2, w_, 2);
            up2y.planUp(hl2, h_, 2);
        }
        std::vector<const Layer*> l1, l2;
        for (const Layer& L : layers_) {
            if (L.q == 1) l1.push_back(&L);
            if (L.q == 2) l2.push_back(&L);
        }
        // The full-res rows band [Y0, Y1) reads: its own rows plus the level-1 and level-2 halos (whole 2x2 boxes).
        auto region = [&](int Y0, int Y1, int& ly0, int& ly1, int& ry0, int& ry1) {
            ly0 = ly1 = 0;
            ry0 = std::max(Y0 - K1, 0);
            ry1 = std::min(Y1 + K1, h_);
            if (has2) {
                ly0 = up2y.i0[static_cast<std::size_t>(Y0)];
                ly1 = up2y.i1[static_cast<std::size_t>(Y1 - 1)] + 1;
                const int iy0 = std::max(ly0 - K2, 0), iy1 = std::min(ly1 + K2, hl2);
                ry0 = std::min(ry0, iy0 * 2);
                ry1 = std::max(ry1, std::min(iy1 * 2, h_));
            }
            ry0 &= ~1;
            ry1 = std::min(ry1 + (ry1 & 1), h_);
        };
        const int nc = nc_;
        // buffers reused across bands (kept zero)
        const int maxRows = std::min(h_, band + 4 * std::max(K1, K2) + 16);
        TileBuf in1[2], in2[2], acc1[2], acc2[2];
        for (int s = 0; s < 2; ++s) {
            in1[s].init(0, maxRows, w_, K1 + 2, nc);
            acc1[s].init(0, band, w_, 0, nc);
            if (has2) {
                in2[s].init(0, maxRows / 2 + 2, wl2, K2 + 2, nc);
                acc2[s].init(0, band / 2 + 4, wl2, 0, nc);
            }
        }
        linBand_.assign(static_cast<std::size_t>(maxRows + 2) * w_ * 3, 0.0f);
        double bytes = levelBytes_ + static_cast<double>(radQ_.size()) * 2 + static_cast<double>(linBand_.size()) * 4;
        for (int s = 0; s < 2; ++s) bytes += in1[s].bytes() + in2[s].bytes() + acc1[s].bytes() + acc2[s].bytes();
        // The still is written in place, so a finished row is held back until no later band reads it.
        std::vector<Pending> pending, spare;
        auto flush = [&](int below) {
            const auto ts = Clock::now();
            for (Pending& pb : pending) {
                const int stop = std::min(pb.y0 + pb.rows, below);
                if (stop > pb.next) {
                    const int a0 = pb.next;
                    parallelRows(stop - a0, ex_, [&](int a, int b) {
                        for (int y = a0 + a; y < a0 + b; ++y) {
                            const float* r = pb.px.data() + static_cast<std::size_t>(y - pb.y0) * w_ * 3;
                            for (int x = 0; x < w_; ++x)
                                if (!std::isnan(r[x * 3])) store(x, y, r + x * 3);
                        }
                    });
                    pb.next = stop;
                }
            }
            std::size_t keep = 0;
            for (std::size_t i = 0; i < pending.size(); ++i) {
                if (pending[i].next >= pending[i].y0 + pending[i].rows) {
                    spare.push_back(std::move(pending[i]));
                } else {
                    if (keep != i) pending[keep] = std::move(pending[i]);
                    ++keep;
                }
            }
            pending.resize(keep);
            st_.storeMs += msSince(ts);
        };
        std::vector<float> gBand(gout_ ? static_cast<std::size_t>(band) * w_ : 0);
        std::vector<std::uint8_t> tBand(gout_ ? static_cast<std::size_t>(band) * w_ : 0);
        bytes += static_cast<double>(band) * w_ * 3 * 4 * 3;
        st_.peakBytes = std::max(st_.peakBytes, bytes);
        for (int Y0 = 0; Y0 < h_; Y0 += band) {
            const int Y1 = std::min(Y0 + band, h_);
            const int bandRows = Y1 - Y0;
            int ly0, ly1, ry0, ry1;
            region(Y0, Y1, ly0, ly1, ry0, ry1);
            // the band's linear pixels (read by the fills and the composite), before any of its rows is overwritten
            {
                const auto tl = Clock::now();
                linY0_ = linY1_ = 0;
                const int la = ry0, lb = std::min(ry1, ry0 + maxRows + 2);
                parallelRows(lb - la, ex_, [&](int a, int b) {
                    for (int y = la + a; y < la + b; ++y)
                        for (int x = 0; x < w_; ++x) linAt(x, y, linBand_.data() + (static_cast<std::size_t>(y - la) * w_ + x) * 3);
                });
                linY0_ = la;
                linY1_ = lb;
                st_.linMs += msSince(tl);
            }
            for (int s = 0; s < 2; ++s) {
                acc1[s].clear();
                acc1[s].rebase(Y0, bandRows);
                in1[s].rebase(ry0, ry1 - ry0);
                if (has2) {
                    acc2[s].clear();
                    acc2[s].rebase(ly0, ly1 - ly0);
                    in2[s].rebase(ry0 / 2, (ry1 + 1) / 2 - ry0 / 2);
                }
            }
            TileBuf* s1[2] = {&in1[0], &in1[1]};
            for (const Layer* L : l1) {
                fill(*L, ry0, ry1, s1);
                for (int s = 0; s < 2; ++s) {
                    if (!in1[s].any()) continue;
                    gather(*L, in1[s], h_, Y0, Y1, acc1[s]);
                    in1[s].clear();
                }
            }
            if (has2) {
                TileBuf* s2[2] = {&in2[0], &in2[1]};
                for (const Layer* L : l2) {
                    fill(*L, ry0, ry1, s2);
                    for (int s = 0; s < 2; ++s) {
                        if (!in2[s].any()) continue;
                        gather(*L, in2[s], hl2, ly0, ly1, acc2[s]);
                        in2[s].clear();
                    }
                }
            }
            Pending pb;
            if (!spare.empty()) {
                pb = std::move(spare.back());
                spare.pop_back();
            }
            pb.y0 = Y0;
            pb.rows = bandRows;
            pb.next = Y0;
            pb.px.resize(static_cast<std::size_t>(band) * w_ * 3);
            std::fill(pb.px.begin(), pb.px.end(), std::numeric_limits<float>::quiet_NaN());
            const auto tc = Clock::now();
            struct Src {
                const TileBuf* acc;
                const Axis* ax;
                const Axis* ay;
                int side;
            };
            std::vector<Src> srcs;
            for (int sd = 0; sd < 2; ++sd) {
                srcs.push_back({&acc1[sd], &idx, &idy, sd});
                if (has2) srcs.push_back({&acc2[sd], &up2x, &up2y, sd});
                for (const Level& lv : levels_) srcs.push_back({&lv.acc[sd], &lv.ax, &lv.ay, sd});
            }
            const int xt = tilesW_;
            const bool six = nc == 6;
            parallelRows(bandRows, ex_, [&](int a, int b) {
                std::vector<unsigned char> fl(srcs.size() * static_cast<std::size_t>(xt));
                for (int i = a; i < b; ++i) {
                    const int y = Y0 + i;
                    // which sources can be non-zero in each 32-px column tile of this row
                    bool anyFront = false;
                    for (std::size_t si = 0; si < srcs.size(); ++si) {
                        const Src& sr = srcs[si];
                        const int r0 = sr.ay->i0[static_cast<std::size_t>(y)] / kTile, r1 = sr.ay->i1[static_cast<std::size_t>(y)] / kTile;
                        for (int t = 0; t < xt; ++t) {
                            const int c0 = sr.ax->i0[static_cast<std::size_t>(t) * kTile] / kTile;
                            const int c1 = sr.ax->i1[static_cast<std::size_t>(std::min(t * kTile + kTile - 1, w_ - 1))] / kTile;
                            bool f = false;
                            for (int c = c0; c <= c1 && !f; ++c) f = sr.acc->tile(c, r0) || sr.acc->tile(c, r1);
                            fl[si * xt + t] = f ? 1 : 0;
                            anyFront = anyFront || (f && sr.side == 1);
                        }
                    }
                    const std::int16_t* rr = radQ_.data() + static_cast<std::size_t>(y) * w_;
                    float* orow = pb.px.data() + static_cast<std::size_t>(i) * w_ * 3;
                    for (int x = 0; x < w_; ++x) {
                        const int rq = rr[x];
                        const float un = uLut_[0][static_cast<std::size_t>(std::abs(rq))];
                        const float w0 = std::max(0.0f, 1.0f - un);
                        const std::size_t gi = static_cast<std::size_t>(i) * w_ + x;
                        if (gout_) {
                            tBand[gi] = 0;
                            gBand[gi] = 0.0f;
                        }
                        if (w0 >= 1.0f && !anyFront) {
                            if (gout_) gBand[gi] = gainUnchanged(x, y);
                            continue;
                        }
                        const int t = x / kTile;
                        float P[2][6] = {{0, 0, 0, 0, 0, 0}, {0, 0, 0, 0, 0, 0}};
                        for (std::size_t si = 0; si < srcs.size(); ++si) {
                            if (!fl[si * xt + t]) continue;
                            const Src& sr = srcs[si];
                            if (sr.side == 0 && w0 >= 1.0f) continue;  // the background does not show through a sharp pixel
                            if (six) {
                                bilinearT<6>(*sr.acc, *sr.ax, *sr.ay, x, y, P[sr.side]);
                            } else {
                                bilinearT<4>(*sr.acc, *sr.ax, *sr.ay, x, y, P[sr.side]);
                            }
                        }
                        float af[3], ab[3];
                        for (int l = 0; l < 3; ++l) {
                            af[l] = P[1][six ? 3 + l : 3];
                            ab[l] = P[0][six ? 3 + l : 3];
                        }
                        const float afMax = std::max(af[0], std::max(af[1], af[2]));
                        if (w0 >= 1.0f && afMax < 1e-6f) {  // untouched: keep the input exactly
                            if (gout_) gBand[gi] = gainUnchanged(x, y);
                            continue;
                        }
                        float c[3];
                        lin(x, y, c);
                        const bool fr = rq < 0 && un > 0.0f;
                        float rc[3] = {0, 0, 0};
                        float nearS = 0.0f;
                        if (fr) {
                            rearAt(x, y, rc);
                            nearS = static_cast<float>(smoothstep(0.0, kRearNear, nearAt(x, y, -rq / 64.0)));
                        }
                        float* o = orow + static_cast<std::size_t>(x) * 3;
                        double yb = 0.0;
                        for (int l = 0; l < 3; ++l) {
                            const float fgn = af[l] > 1e-4f ? P[1][l] / af[l] : c[l];
                            const float bgb = ab[l] > 1e-4f ? P[0][l] / ab[l] : fgn;
                            float lv;
                            if (!fr) {
                                const float mid = w0 * c[l] + (1.0f - w0) * bgb;
                                const float a1 = std::min(af[l], 1.0f);  // overlapping front spreads are normalised
                                lv = a1 * fgn + (1.0f - a1) * mid;
                            } else {
                                // a pixel in front: covered by the front blur and its own sharp share; the rest shows
                                // what is behind it (the behind blur, else the occluded background at its edge)
                                const float cov = af[l] + w0;
                                const float own = P[1][l] + w0 * c[l];
                                if (cov >= 1.0f) {
                                    lv = own / cov;
                                } else {
                                    float hid = bgb;
                                    if (!(ab[l] > 1e-4f)) {
                                        const float se = static_cast<float>(smoothstep(kRearEdgeLo, kRearEdgeHi, 1.0 - cov)) * nearS;
                                        hid = se * rc[l] + (1.0f - se) * fgn;
                                    }
                                    lv = own + (1.0f - cov) * hid;
                                }
                            }
                            yb += kLuma[l] * static_cast<double>(lv);
                            o[l] = lv;
                        }
                        if (gout_) {
                            gBand[gi] = gainOfLuma(yb);
                            tBand[gi] = 1;
                        }
                    }
                }
            });
            st_.compositeMs += msSince(tc);
            // tone re-applied (B31.1: its own pass, timed)
            {
                const auto tt = Clock::now();
                parallelRows(bandRows, ex_, [&](int a, int b) {
                    for (int i = a; i < b; ++i) {
                        float* r = pb.px.data() + static_cast<std::size_t>(i) * w_ * 3;
                        for (int x = 0; x < w_ * 3; x += 3) {
                            if (std::isnan(r[x])) continue;
                            r[x] = toneLut_(r[x]);
                            r[x + 1] = toneLut_(r[x + 1]);
                            r[x + 2] = toneLut_(r[x + 2]);
                        }
                    }
                });
                st_.toneMs += msSince(tt);
            }
            if (gout_) {
                const auto tg = Clock::now();
                const int gw = tone_.gainW;
                for (int i = 0; i < bandRows; ++i) {
                    const std::size_t row = static_cast<std::size_t>(cellY_[static_cast<std::size_t>(Y0 + i)]) * gw;
                    for (int x = 0; x < w_; ++x) {
                        const std::size_t gi = static_cast<std::size_t>(i) * w_ + x;
                        const std::size_t ci = row + cellX_[static_cast<std::size_t>(x)];
                        gSum_[ci] += gBand[gi];
                        ++gCnt_[ci];
                        gTouch_[ci] |= tBand[gi];
                    }
                }
                st_.gainMs += msSince(tg);
            }
            pending.push_back(std::move(pb));
            int nly0, nly1, nry0 = h_, nry1;
            if (Y1 < h_) region(Y1, std::min(Y1 + band, h_), nly0, nly1, nry0, nry1);
            flush(nry0);
        }
        flush(h_);
    }

    void store(int x, int y, const float o[3]) {
        if (im_.rgba != nullptr) {
            std::uint8_t* px = im_.rgba + static_cast<std::size_t>(y) * im_.stride + static_cast<std::size_t>(x) * 4;
            for (int c = 0; c < 3; ++c) px[c] = static_cast<std::uint8_t>(std::min(std::max(o[c], 0.0f), 1.0f) * 255.0f + 0.5f);
        } else {
            float* px = im_.rgb + static_cast<std::size_t>(y) * im_.stride + static_cast<std::size_t>(x) * 3;
            for (int c = 0; c < 3; ++c) px[c] = o[c];
        }
    }

    const Still& im_;
    const BokehTone& tone_;
    Curve curve_;
    ToneLut toneLut_;
    const BokehParams& p_;
    BokehStats& st_;
    Exec& ex_;
    std::vector<float> map_;
    std::vector<std::int16_t> radQ_;
    std::vector<double> rad_, rad2_;
    int mw_ = 0, mh_ = 0, w_ = 0, h_ = 0, kmax_ = 1, tilesW_ = 0, tilesH_ = 0, nc_ = 4;
    std::vector<std::uint64_t> layerMask_;
    std::vector<float> uLut_[3];
    Axis gX_, gY_;
    bool ca_ = false, tiled_ = false, hdr_ = false, gout_ = false, front_ = false;
    std::vector<int> cellX_, cellY_;
    std::vector<double> gSum_;
    std::vector<int> gCnt_;
    std::vector<std::uint8_t> gTouch_;
    double log2Lk_ = 0;
    double beta_ = 0, hd_ = 1, xk_ = 0, lk_ = 1, levelBytes_ = 0;
    std::vector<float> inv_;
    float lut_[256] = {};
    std::vector<Layer> layers_;
    std::vector<Level> levels_;
    std::vector<float> linBand_;
    int linY0_ = 0, linY1_ = 0;
    std::vector<float> rear_;
    int rearW_ = 1, rearH_ = 1;
    struct NearLevel {
        int w = 1, h = 1;
        std::vector<float> share;
        Axis ax, ay;
    };
    std::vector<NearLevel> nearLv_;
    Axis rearX_, rearY_;
};

std::string validate(const Still& im, const BokehTone& t, const BokehDepth& d, const BokehParams& p) {
    if (im.w < 2 || im.h < 2 || im.w > 20000 || im.h > 20000) return "still size out of range";
    if (t.curve == nullptr || t.curvePoints < 2 || !(t.curveHi > t.curveLo)) return "bad tone curve";
    if (d.depth == nullptr || d.width < 3 || d.height < 3) return "depth map missing";
    if (!(p.focalMm > 1.0f) || !(p.aperture > 0.5f) || !(p.phoneN > 0.5f)) return "bad lens parameters";
    if (!(p.focusM * 1000.0f > p.focalMm * 1.01f)) return "focus distance inside the focal length";
    if (!(p.maxCoc > 0.0f) || p.maxCoc > 0.25f || p.workMax < 16) return "bad cap or working size";
    if (t.gainLog2 != nullptr && (t.gainW < 1 || t.gainH < 1)) return "bad gain map size";
    return "";
}

std::string renderImpl(Still& im, const BokehTone& tone, const BokehDepth& d, const BokehParams& p, BokehStats& st) {
    st = BokehStats();
    const std::string err = validate(im, tone, d, p);
    if (!err.empty()) return err;
    if (!(d.confidence >= kBokehUsable)) {
        char buf[96];
        std::snprintf(buf, sizeof(buf), "depth confidence %.2f below %.2f", static_cast<double>(d.confidence), static_cast<double>(kBokehUsable));
        st.reason = buf;
        return "";
    }
    if (!(bokehPhoneFactor(p.aperture, p.phoneN) > 0.0)) {
        char buf[128];
        std::snprintf(buf, sizeof(buf), "phone depth of field (f/%g equivalent) already as deep as f/%g", static_cast<double>(p.phoneN),
                      static_cast<double>(p.aperture));
        st.reason = buf;
        st.phoneFactor = 0;
        return "";
    }
    Exec ex;
    ex.threads = std::min(std::max(p.threads, 1), kMaxThreads);
    ex.nice = p.nice;
    ex.bigCores = p.bigCores;
#if defined(__linux__)
    // the calling thread joins every region: pin it with the workers for the render, then restore its mask
    cpu_set_t prev;
    CPU_ZERO(&prev);
    const int tid = threadId();
    const bool pinned = p.bigCores && bigSet() != nullptr && sched_getaffinity(tid, sizeof(prev), &prev) == 0;
    if (pinned) pinThread(tid, true);
#endif
    std::string res;
    {
        Renderer r(im, tone, p, st, ex);
        res = r.run(d);
    }
#if defined(__linux__)
    if (pinned) sched_setaffinity(tid, sizeof(prev), &prev);
    st.bigCoresAvailable = bigSet() != nullptr;
#endif
    st.threads = ex.threads;
    st.cpuMask = ex.cpus.load();
    st.workerNice = ex.niceSeen.load();
    st.callerNice = niceOf(threadId());
    st.regions = ex.regions.load();
    return res;
}

}  // namespace

double bokehCocMm(double f, double n, double sM, double dM) {
    const double s = sM * 1000.0;
    const double base = f * f / (n * (s - f));
    if (dM <= 0.0 || std::isinf(dM)) return base;
    const double d = dM * 1000.0;
    return base * std::fabs(d - s) / d;
}

double bokehPhoneFactor(double n, double phoneN) { return std::sqrt(std::max(0.0, 1.0 - (n / phoneN) * (n / phoneN))); }

double bokehBladeBeta(double n) { return std::min(0.5, std::max(0.0, 0.25 * std::log2(n / 2.8))); }

void bokehCatEye(double r, double f, double n, double vx, double vy, double& h, double& ux, double& uy) {
    const double t = catStrength(n);
    const double m = std::hypot(vx, vy);
    if (t <= 0.0 || m < 1e-6) {
        h = 0.0;
        ux = 1.0;
        uy = 0.0;
        return;
    }
    const double w = 1.0 - (1.0 - catCornerWidth(f)) * std::min(m, 1.0) * t;
    h = r * (1.0 - w);
    ux = vx / m;
    uy = vy / m;
}

void bokehKernelDense(double r, double beta, double h, double ux, double uy, std::vector<double>& k, int& kHalf) {
    BladeRows b(r, beta);
    Kernel K;
    b.build(h, ux, uy, K);
    kHalf = K.k;
    const int n = 2 * K.k + 1;
    k.assign(static_cast<std::size_t>(n) * n, 0.0);
    for (const Term& t : K.terms) {
        for (int dx = t.lo; dx <= t.hi; ++dx) {
            double c = 1.0;
            if (dx == t.lo) c = t.cl;
            else if (dx == t.hi) c = t.ch;
            k[static_cast<std::size_t>(t.dy + K.k) * n + (dx + K.k)] += t.w * c;
        }
    }
}

std::string bokehRadiusMap(const float* rgb, int width, int height, std::size_t strideFloats, const BokehDepth& depth, const BokehParams& p,
                           std::vector<float>& map, int& mapW, int& mapH, BokehStats& stats) {
    Still im;
    im.rgb = const_cast<float*>(rgb);
    im.stride = strideFloats;
    im.w = width;
    im.h = height;
    if (depth.depth == nullptr || depth.width < 3 || depth.height < 3) return "depth map missing";
    Exec ex;
    ex.threads = std::min(std::max(p.threads, 1), kMaxThreads);
    return radiusMapImpl(im, depth, p, map, mapW, mapH, stats, ex);
}

std::string bokehRender(std::uint8_t* rgba, int width, int height, std::size_t strideBytes, const BokehTone& tone, const BokehDepth& depth,
                        const BokehParams& p, BokehStats& stats) {
    if (rgba == nullptr || strideBytes < static_cast<std::size_t>(width) * 4) return "bad still buffer";
    Still im;
    im.rgba = rgba;
    im.stride = strideBytes;
    im.w = width;
    im.h = height;
    return renderImpl(im, tone, depth, p, stats);
}

std::string bokehRenderFloat(float* rgb, int width, int height, std::size_t strideFloats, const BokehTone& tone, const BokehDepth& depth,
                             const BokehParams& p, BokehStats& stats) {
    if (rgb == nullptr || strideFloats < static_cast<std::size_t>(width) * 3) return "bad still buffer";
    Still im;
    im.rgb = rgb;
    im.stride = strideFloats;
    im.w = width;
    im.h = height;
    return renderImpl(im, tone, depth, p, stats);
}

}  // namespace redline
