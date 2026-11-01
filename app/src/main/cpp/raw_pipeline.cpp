// Redline native module (B21): RAW colour pipeline, the per-pixel part. See raw_pipeline.h and the Python
// reference calibration/redline_cal/raw_pipeline.py (its docstring is the contract this file implements).
#include "raw_pipeline.h"

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cmath>
#include <cstddef>
#include <thread>
#include <vector>

#if defined(__linux__)
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>
#endif

namespace redline {

namespace {

// Camera2 SENSOR_INFO_COLOR_FILTER_ARRANGEMENT -> colour (0 R, 1 G, 2 B) at CFA position (y & 1) * 2 + (x & 1).
constexpr int kCfaLayouts[4][4] = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};
constexpr float kLuma[3] = {0.2126729f, 0.7151522f, 0.0721750f};
constexpr int kMaxThreads = 16;
// B23b: render rows between voluntary yields (one tile band; about 1-2 ms of work on a performance core).
constexpr int kYieldRows = 32;
constexpr int kMaxSide = 1 << 15;

// B23b.1: render threads take the spawning thread's nice explicitly (the app's governed render lane) instead of relying
// on inheritance, and report what they read back.
constexpr int kNiceUnset = -1000;
std::atomic<int> gRenderNiceSeen{kNiceUnset};

int threadNice() {
#if defined(__linux__)
    errno = 0;
    const int v = getpriority(PRIO_PROCESS, static_cast<id_t>(syscall(SYS_gettid)));
    return errno == 0 ? v : kNiceUnset;
#else
    return kNiceUnset;
#endif
}

void takeNice(int nice) {
#if defined(__linux__)
    if (nice != kNiceUnset) setpriority(PRIO_PROCESS, static_cast<id_t>(syscall(SYS_gettid)), nice);
#else
    (void)nice;
#endif
    gRenderNiceSeen.store(threadNice());
}

// Mirror (reflect-101) index, the border mode of scipy.ndimage "mirror": -1 -> 1, n -> n - 2. Keeps the CFA phase.
inline int mirror(int i, int n) {
    if (n == 1) return 0;
    while (i < 0 || i >= n) {
        if (i < 0) i = -i;
        if (i >= n) i = 2 * (n - 1) - i;
    }
    return i;
}

// Shading-map channel (Camera2 order R, G_even, G_odd, B) for each CFA position.
void shadingChannels(int cfa, int out[4]) {
    for (int p = 0; p < 4; ++p) {
        const int colour = kCfaLayouts[cfa][p];
        const int py = p >> 1;
        out[p] = colour == 0 ? 0 : colour == 2 ? 3 : (py == 0 ? 1 : 2);
    }
}

// Bilinear sample positions of the shading grid along one axis, per full-frame pixel (same as the reference).
struct Axis {
    std::vector<int> i0;
    std::vector<int> i1;
    std::vector<float> t;
};

Axis gridAxis(int pixels, int nodes) {
    Axis a;
    a.i0.resize(pixels);
    a.i1.resize(pixels);
    a.t.resize(pixels);
    for (int i = 0; i < pixels; ++i) {
        const double f = pixels > 1 ? static_cast<double>(i) * (nodes - 1) / (pixels - 1) : 0.0;
        int lo = nodes > 1 ? std::min(static_cast<int>(std::floor(f)), nodes - 2) : 0;
        lo = std::max(lo, 0);
        a.i0[i] = lo;
        a.i1[i] = std::min(lo + 1, nodes - 1);
        a.t[i] = static_cast<float>(f - lo);
    }
    return a;
}

// Normalises full-frame pixels on demand: black/white levels and the lens shading map.
class Normaliser {
public:
    explicit Normaliser(const RawInput& in) : in_(in) {
        for (int p = 0; p < 4; ++p) scale_[p] = 1.0f / (in.white - in.black[p]);
        hasShading_ = in.shading != nullptr && in.shadingRows > 0 && in.shadingCols > 0;
        if (hasShading_) {
            shadingChannels(in.cfa, channel_);
            ax_ = gridAxis(in.width, in.shadingCols);
            ay_ = gridAxis(in.height, in.shadingRows);
        }
    }

    // Writes v for full-frame row y at columns cols[0..n) into out.
    void row(int y, const int* cols, int n, float* out) const {
        const std::uint16_t* src = in_.data + static_cast<std::size_t>(y) * in_.rowStride;
        const int py = y & 1;
        for (int k = 0; k < n; ++k) {
            const int x = cols[k];
            const int p = (py << 1) | (x & 1);
            float v = (static_cast<float>(src[x]) - in_.black[p]) * scale_[p];
            if (hasShading_) v *= shade(x, y, p);
            out[k] = v;
        }
    }

private:
    float shade(int x, int y, int p) const {
        const int ch = channel_[p];
        const int cols = in_.shadingCols;
        const float* g = in_.shading;
        const int a = ay_.i0[y], c = ay_.i1[y], b = ax_.i0[x], d = ax_.i1[x];
        const float ty = ay_.t[y], tx = ax_.t[x];
        const float gab = g[(a * cols + b) * 4 + ch];
        const float gad = g[(a * cols + d) * 4 + ch];
        const float gcb = g[(c * cols + b) * 4 + ch];
        const float gcd = g[(c * cols + d) * 4 + ch];
        return gab * (1 - ty) * (1 - tx) + gad * (1 - ty) * tx + gcb * ty * (1 - tx) + gcd * ty * tx;
    }

    const RawInput& in_;
    float scale_[4] = {1, 1, 1, 1};
    bool hasShading_ = false;
    int channel_[4] = {0, 1, 2, 3};
    Axis ax_;
    Axis ay_;
};

// Box-filter (area) weights for one axis: output i averages the input interval [i*s, (i+1)*s).
struct AreaTaps {
    std::vector<int> first;   // first input index per output
    std::vector<int> count;   // number of inputs per output
    std::vector<float> w;     // weights, flattened, `count[i]` per output starting at offset[i]
    std::vector<int> offset;
};

AreaTaps areaTaps(int nIn, int nOut) {
    AreaTaps t;
    t.first.resize(nOut);
    t.count.resize(nOut);
    t.offset.resize(nOut);
    const double s = static_cast<double>(nIn) / nOut;
    for (int i = 0; i < nOut; ++i) {
        const double a = i * s, b = (i + 1) * s;
        const int j0 = static_cast<int>(std::floor(a));
        const int j1 = std::min(static_cast<int>(std::ceil(b)), nIn);
        t.first[i] = j0;
        t.offset[i] = static_cast<int>(t.w.size());
        int n = 0;
        for (int j = j0; j < j1; ++j) {
            const double ov = std::max(0.0, std::min(j + 1.0, b) - std::max(static_cast<double>(j), a));
            t.w.push_back(static_cast<float>(ov / s));
            ++n;
        }
        t.count[i] = n;
    }
    return t;
}

// Stages 1-5 for one crop row: normalise (via a 5-row window), MHC demosaic, balance + clip, 3x3.
class LinearRows {
public:
    LinearRows(const RawInput& in, const RenderSpec& spec) : in_(in), spec_(spec), norm_(in) {
        const CropRect& c = spec.crop;
        width_ = c.width;
        cols_.resize(width_ + 4);
        for (int k = 0; k < width_ + 4; ++k) cols_[k] = mirror(c.left - 2 + k, in.width);
        for (int r = 0; r < 5; ++r) window_[r].resize(width_ + 4);
        for (int i = 0; i < 3; ++i) invNeutral_[i] = 1.0f / spec.colour.neutral[i];
    }

    // Linear sRGB of crop row `cy` (0-based within the crop) into out[width*3].
    void row(int cy, float* out) {
        const int y = spec_.crop.top + cy;
        loadWindow(y);
        const float* r0 = slot(y - 2);
        const float* r1 = slot(y - 1);
        const float* r2 = slot(y);
        const float* r3 = slot(y + 1);
        const float* r4 = slot(y + 2);
        const int py = y & 1;
        const int* layout = kCfaLayouts[in_.cfa];
        const float* m = spec_.colour.matrix;
        for (int k = 0; k < width_; ++k) {
            const int x = spec_.crop.left + k;
            const int px = x & 1;
            const int p = (py << 1) | px;
            const int colour = layout[p];
            const int j = k + 2;  // index into the window rows
            const float c0 = r2[j];
            float rgb[3];
            rgb[colour] = c0;
            if (colour == 1) {
                const float rowv = (0.5f * (r0[j] + r4[j]) - (r1[j - 1] + r1[j + 1] + r3[j - 1] + r3[j + 1]) -
                                    (r2[j - 2] + r2[j + 2]) + 4.0f * (r2[j - 1] + r2[j + 1]) + 5.0f * c0) * 0.125f;
                const float colv = (0.5f * (r2[j - 2] + r2[j + 2]) - (r1[j - 1] + r1[j + 1] + r3[j - 1] + r3[j + 1]) -
                                    (r0[j] + r4[j]) + 4.0f * (r1[j] + r3[j]) + 5.0f * c0) * 0.125f;
                rgb[layout[(py << 1) | (1 - px)]] = rowv;
                rgb[layout[((1 - py) << 1) | px]] = colv;
            } else {
                const float g = (4.0f * c0 + 2.0f * (r1[j] + r3[j] + r2[j - 1] + r2[j + 1]) -
                                 (r0[j] + r4[j] + r2[j - 2] + r2[j + 2])) * 0.125f;
                const float d = (6.0f * c0 + 2.0f * (r1[j - 1] + r1[j + 1] + r3[j - 1] + r3[j + 1]) -
                                 1.5f * (r0[j] + r4[j] + r2[j - 2] + r2[j + 2])) * 0.125f;
                rgb[1] = g;
                rgb[2 - colour] = d;
            }
            float c[3];
            for (int i = 0; i < 3; ++i) c[i] = std::min(std::max(rgb[i] * invNeutral_[i], 0.0f), 1.0f);
            float* o = out + static_cast<std::size_t>(k) * 3;
            o[0] = m[0] * c[0] + m[1] * c[1] + m[2] * c[2];
            o[1] = m[3] * c[0] + m[4] * c[1] + m[5] * c[2];
            o[2] = m[6] * c[0] + m[7] * c[1] + m[8] * c[2];
        }
    }

private:
    // Full-frame row index (mirrored) -> window slot; rows are cached by their mirrored index.
    float* slot(int y) {
        const int src = mirror(y, in_.height);
        for (int r = 0; r < 5; ++r) {
            if (rowOf_[r] == src) return window_[r].data();
        }
        return nullptr;  // not reached: loadWindow filled it
    }

    void loadWindow(int y) {
        for (int dy = -2; dy <= 2; ++dy) {
            const int src = mirror(y + dy, in_.height);
            bool have = false;
            for (int r = 0; r < 5; ++r) have = have || rowOf_[r] == src;
            if (have) continue;
            // Evict a slot whose row is outside y-2..y+2.
            int victim = -1;
            for (int r = 0; r < 5 && victim < 0; ++r) {
                bool needed = false;
                for (int e = -2; e <= 2; ++e) needed = needed || rowOf_[r] == mirror(y + e, in_.height);
                if (!needed) victim = r;
            }
            if (victim < 0) victim = 0;
            norm_.row(src, cols_.data(), width_ + 4, window_[victim].data());
            rowOf_[victim] = src;
        }
    }

    const RawInput& in_;
    const RenderSpec& spec_;
    Normaliser norm_;
    int width_ = 0;
    std::vector<int> cols_;
    std::vector<float> window_[5];
    int rowOf_[5] = {-1, -1, -1, -1, -1};
    float invNeutral_[3] = {1, 1, 1};
};

class Curve {
public:
    explicit Curve(const RenderSpec& s)
        : t_(s.curve), n_(s.curvePoints), lo_(s.curveLo), hi_(s.curveHi), gain_(s.gain) {
        floor_ = std::exp2(lo_);
        step_ = (n_ - 1) / (hi_ - lo_);
    }

    float operator()(float lin) const {
        const float x = gain_ * lin;
        if (!(x > 0.0f)) return 0.0f;
        if (x < floor_) return std::min(x / floor_ * t_[0], 1.0f);
        const float f = (std::log2(x) - lo_) * step_;
        if (f >= static_cast<float>(n_ - 1)) return std::min(std::max(t_[n_ - 1], 0.0f), 1.0f);
        const int i = static_cast<int>(f);
        const float u = f - static_cast<float>(i);
        const float y = t_[i] + (t_[i + 1] - t_[i]) * u;
        return std::min(std::max(y, 0.0f), 1.0f);
    }

private:
    const float* t_;
    int n_;
    float lo_, hi_, gain_, floor_ = 0, step_ = 1;
};

inline void lutSample(const float* lut, int n, const float in[3], float out[3]) {
    float x[3];
    int i0[3];
    for (int c = 0; c < 3; ++c) {
        x[c] = std::min(std::max(in[c], 0.0f), 1.0f) * (n - 1);
        i0[c] = std::min(std::max(static_cast<int>(std::floor(x[c])), 0), n - 2);
        x[c] -= static_cast<float>(i0[c]);
    }
    out[0] = out[1] = out[2] = 0.0f;
    for (int db = 0; db <= 1; ++db) {
        const float wb = db ? x[2] : 1 - x[2];
        for (int dg = 0; dg <= 1; ++dg) {
            const float wg = dg ? x[1] : 1 - x[1];
            for (int dr = 0; dr <= 1; ++dr) {
                const float w = wb * wg * (dr ? x[0] : 1 - x[0]);
                const float* e = lut + ((static_cast<std::size_t>(i0[2] + db) * n + (i0[1] + dg)) * n + (i0[0] + dr)) * 3;
                out[0] += w * e[0];
                out[1] += w * e[1];
                out[2] += w * e[2];
            }
        }
    }
}

inline std::uint8_t to8(float v) {
    const float c = std::min(std::max(v, 0.0f), 1.0f);
    return static_cast<std::uint8_t>(c * 255.0f + 0.5f);
}

std::string validateSpec(const RawInput& in, const RenderSpec& s) {
    const CropRect& c = s.crop;
    if (c.width <= 0 || c.height <= 0 || c.left < 0 || c.top < 0 || c.left + c.width > in.width ||
        c.top + c.height > in.height) {
        return "crop outside the frame";
    }
    if (s.outWidth <= 0 || s.outHeight <= 0 || s.outWidth > c.width || s.outHeight > c.height) {
        return "output size must be 1..crop size";
    }
    for (float v : s.colour.matrix) {
        if (!std::isfinite(v)) return "matrix not finite";
    }
    for (float v : s.colour.neutral) {
        if (!(v > 0.0f) || !std::isfinite(v)) return "neutral must be positive";
    }
    if (!(s.gain > 0.0f) || !std::isfinite(s.gain)) return "gain must be positive";
    if (s.curve == nullptr || s.curvePoints < 2 || !(s.curveHi > s.curveLo)) return "bad tone curve";
    if (s.lut == nullptr || s.lutSize < 2 || s.lutSize > 256) return "bad LUT";
    return "";
}

// Output writers: RGBA8 for the app's Bitmap, float RGB for the host parity tool.
struct Rgba8Writer {
    std::uint8_t* base;
    std::size_t stride;
    void put(int x, int y, const float o[3]) const {
        std::uint8_t* d = base + static_cast<std::size_t>(y) * stride + static_cast<std::size_t>(x) * 4;
        d[0] = to8(o[0]);
        d[1] = to8(o[1]);
        d[2] = to8(o[2]);
        d[3] = 255;
    }
};

struct FloatWriter {
    float* base;
    std::size_t stride;
    void put(int x, int y, const float o[3]) const {
        float* d = base + static_cast<std::size_t>(y) * stride + static_cast<std::size_t>(x) * 3;
        for (int c = 0; c < 3; ++c) d[c] = std::min(std::max(o[c], 0.0f), 1.0f);
    }
};

template <typename Writer>
void renderBlock(const RawInput& in, const RenderSpec& spec, const AreaTaps& ty, const AreaTaps& tx, bool resample,
                 int o0, int o1, const Writer& out) {
    LinearRows rows(in, spec);
    const Curve curve(spec);
    const int cw = spec.crop.width;
    const int ow = spec.outWidth;
    std::vector<float> line(static_cast<std::size_t>(cw) * 3);
    std::vector<float> acc(static_cast<std::size_t>(ow) * 3);
    for (int oy = o0; oy < o1; ++oy) {
        // B23b: give the CPU back between row tiles so the camera, GL and UI threads are never held off for a whole band.
        if (oy != o0 && (oy - o0) % kYieldRows == 0) std::this_thread::yield();
        const float* lin;
        if (resample) {
            std::fill(acc.begin(), acc.end(), 0.0f);
            for (int k = 0; k < ty.count[oy]; ++k) {
                const float wy = ty.w[ty.offset[oy] + k];
                rows.row(ty.first[oy] + k, line.data());
                for (int ox = 0; ox < ow; ++ox) {
                    float s0 = 0, s1 = 0, s2 = 0;
                    const float* wx = &tx.w[tx.offset[ox]];
                    const float* src = &line[static_cast<std::size_t>(tx.first[ox]) * 3];
                    for (int q = 0; q < tx.count[ox]; ++q) {
                        s0 += wx[q] * src[q * 3];
                        s1 += wx[q] * src[q * 3 + 1];
                        s2 += wx[q] * src[q * 3 + 2];
                    }
                    float* a = &acc[static_cast<std::size_t>(ox) * 3];
                    a[0] += wy * s0;
                    a[1] += wy * s1;
                    a[2] += wy * s2;
                }
            }
            lin = acc.data();
        } else {
            rows.row(oy, line.data());
            lin = line.data();
        }
        for (int ox = 0; ox < ow; ++ox) {
            const float* l = lin + static_cast<std::size_t>(ox) * 3;
            const float toned[3] = {curve(l[0]), curve(l[1]), curve(l[2])};
            float o[3];
            lutSample(spec.lut, spec.lutSize, toned, o);
            out.put(ox, oy, o);
        }
    }
}

}  // namespace

std::string validate(const RawInput& in) {
    if (in.data == nullptr) return "no RAW data";
    if (in.width < 4 || in.height < 4 || in.width > kMaxSide || in.height > kMaxSide) return "RAW size out of range";
    if (in.rowStride < in.width) return "row stride smaller than the width";
    if (in.cfa < 0 || in.cfa > 3) return "unsupported colour filter arrangement";
    for (float b : in.black) {
        if (!std::isfinite(b) || b < 0.0f || !(in.white > b)) return "white level must exceed the black level";
    }
    if (in.shading != nullptr && (in.shadingRows < 1 || in.shadingCols < 1)) return "bad lens shading map size";
    return "";
}

namespace {

template <typename Writer>
void renderAll(const RawInput& in, const RenderSpec& spec, const Writer& out) {
    const bool resample = spec.outWidth != spec.crop.width || spec.outHeight != spec.crop.height;
    AreaTaps ty, tx;
    if (resample) {
        ty = areaTaps(spec.crop.height, spec.outHeight);
        tx = areaTaps(spec.crop.width, spec.outWidth);
    }
    const int threads = std::min(std::max(spec.threads, 1), std::min(kMaxThreads, spec.outHeight));
    if (threads == 1) {
        renderBlock(in, spec, ty, tx, resample, 0, spec.outHeight, out);
        return;
    }
    const int nice = threadNice();
    std::vector<std::thread> pool;
    pool.reserve(threads);
    for (int t = 0; t < threads; ++t) {
        const int o0 = static_cast<int>(static_cast<long long>(spec.outHeight) * t / threads);
        const int o1 = static_cast<int>(static_cast<long long>(spec.outHeight) * (t + 1) / threads);
        pool.emplace_back([&, o0, o1, nice] {
            takeNice(nice);
            renderBlock(in, spec, ty, tx, resample, o0, o1, out);
        });
    }
    for (auto& th : pool) th.join();
}

std::string checkAll(const RawInput& in, const RenderSpec& spec, const void* buffer, std::size_t stride, std::size_t minStride) {
    std::string err = validate(in);
    if (err.empty()) err = validateSpec(in, spec);
    if (err.empty() && (buffer == nullptr || stride < minStride)) err = "bad output buffer";
    return err;
}

}  // namespace

std::string renderRaw(const RawInput& in, const RenderSpec& spec, std::uint8_t* rgba, std::size_t strideBytes) {
    const std::string err = checkAll(in, spec, rgba, strideBytes, static_cast<std::size_t>(std::max(spec.outWidth, 0)) * 4);
    if (!err.empty()) return err;
    renderAll(in, spec, Rgba8Writer{rgba, strideBytes});
    return "";
}

std::string renderRawFloat(const RawInput& in, const RenderSpec& spec, float* rgb, std::size_t strideFloats) {
    const std::string err = checkAll(in, spec, rgb, strideFloats, static_cast<std::size_t>(std::max(spec.outWidth, 0)) * 3);
    if (!err.empty()) return err;
    renderAll(in, spec, FloatWriter{rgb, strideFloats});
    return "";
}

// B23c: the order statistic at floor(0.98 * (n - 1)) (= reference upper_quantile); reorders `v`.
static float upperQuantile(std::vector<float>& v) {
    if (v.empty()) return 0.0f;
    const std::size_t k = static_cast<std::size_t>(std::floor(0.98 * static_cast<double>(v.size() - 1)));
    std::nth_element(v.begin(), v.begin() + static_cast<std::ptrdiff_t>(k), v.end());
    return v[k];
}

std::string rawStats(const RawInput& in, const CropRect& crop, const ColourSpec& colour, int step, RawStats& out) {
    std::string err = validate(in);
    if (!err.empty()) return err;
    if (crop.width < 2 || crop.height < 2 || crop.left < 0 || crop.top < 0 || crop.left + crop.width > in.width ||
        crop.top + crop.height > in.height) {
        return "crop outside the frame";
    }
    if (step < 1) return "step must be >= 1";
    for (float v : colour.neutral) {
        if (!(v > 0.0f)) return "neutral must be positive";
    }
    const Normaliser norm(in);
    const int* layout = kCfaLayouts[in.cfa];
    std::vector<int> xs;
    for (int x = crop.left; x < crop.left + crop.width - 1; x += 2 * step) xs.push_back(x);
    std::vector<int> cols(xs.size() * 2);
    for (std::size_t i = 0; i < xs.size(); ++i) {
        cols[2 * i] = xs[i];
        cols[2 * i + 1] = xs[i] + 1;
    }
    std::vector<float> r0(cols.size()), r1(cols.size());
    std::vector<float> ys;
    int clipped = 0;
    const float* m = colour.matrix;
    for (int y = crop.top; y < crop.top + crop.height - 1; y += 2 * step) {
        norm.row(y, cols.data(), static_cast<int>(cols.size()), r0.data());
        norm.row(y + 1, cols.data(), static_cast<int>(cols.size()), r1.data());
        for (std::size_t i = 0; i < xs.size(); ++i) {
            float sum[3] = {0, 0, 0};
            int count[3] = {0, 0, 0};
            const float vals[4] = {r0[2 * i], r0[2 * i + 1], r1[2 * i], r1[2 * i + 1]};
            for (int p = 0; p < 4; ++p) {
                // Row parity of the cell start: crop.top and crop.left are even, so (y & 1) == 0.
                const int pp = (((y + (p >> 1)) & 1) << 1) | ((xs[i] + (p & 1)) & 1);
                sum[layout[pp]] += vals[p];
                ++count[layout[pp]];
            }
            float c[3];
            bool clip = false;
            for (int k = 0; k < 3; ++k) {
                c[k] = (count[k] > 0 ? sum[k] / count[k] : 0.0f) / colour.neutral[k];
                clip = clip || c[k] >= 1.0f;
                c[k] = std::min(std::max(c[k], 0.0f), 1.0f);
            }
            if (clip) ++clipped;
            float yv = 0.0f;
            for (int k = 0; k < 3; ++k) yv += kLuma[k] * (m[k * 3] * c[0] + m[k * 3 + 1] * c[1] + m[k * 3 + 2] * c[2]);
            ys.push_back(yv);
        }
    }
    if (ys.empty()) return "crop too small for statistics";
    const std::size_t n = ys.size();
    out.p98Y = upperQuantile(ys);
    std::nth_element(ys.begin(), ys.begin() + n / 2, ys.end());
    float med = ys[n / 2];
    if (n % 2 == 0) {
        const float lower = *std::max_element(ys.begin(), ys.begin() + n / 2);
        med = 0.5f * (med + lower);
    }
    out.medianY = med;
    out.clippedFraction = static_cast<float>(clipped) / static_cast<float>(n);
    out.cells = static_cast<int>(n);
    return "";
}

// ------------------------------------------------------------------------------------------------ B23 linear input

namespace {

std::string validateLinear(const LinearRgb& in, const RenderSpec& s) {
    if (in.data == nullptr || in.width < 1 || in.height < 1 || in.width > kMaxSide || in.height > kMaxSide) return "bad linear image";
    if (in.rowStride < static_cast<std::size_t>(in.width) || in.planeStride < in.rowStride * static_cast<std::size_t>(in.height)) {
        return "bad linear image strides";
    }
    if (s.outWidth != in.width || s.outHeight != in.height) return "output size must equal the linear image size";
    for (float v : s.colour.matrix) {
        if (!std::isfinite(v)) return "matrix not finite";
    }
    for (float v : s.colour.neutral) {
        if (!(v > 0.0f) || !std::isfinite(v)) return "neutral must be positive";
    }
    if (!(s.gain > 0.0f) || !std::isfinite(s.gain)) return "gain must be positive";
    if (s.curve == nullptr || s.curvePoints < 2 || !(s.curveHi > s.curveLo)) return "bad tone curve";
    if (s.lut == nullptr || s.lutSize < 2 || s.lutSize > 256) return "bad LUT";
    return "";
}

template <typename Writer>
void renderLinearRows(const LinearRgb& in, const RenderSpec& spec, int y0, int y1, const Writer& out) {
    const Curve curve(spec);
    const float* m = spec.colour.matrix;
    const float inv[3] = {1.0f / spec.colour.neutral[0], 1.0f / spec.colour.neutral[1], 1.0f / spec.colour.neutral[2]};
    for (int y = y0; y < y1; ++y) {
        if (y != y0 && (y - y0) % kYieldRows == 0) std::this_thread::yield();  // B23b, as in renderBlock
        const float* r = in.data + static_cast<std::size_t>(y) * in.rowStride;
        const float* g = r + in.planeStride;
        const float* b = g + in.planeStride;
        for (int x = 0; x < in.width; ++x) {
            const float c[3] = {std::min(std::max(r[x] * inv[0], 0.0f), 1.0f), std::min(std::max(g[x] * inv[1], 0.0f), 1.0f),
                                std::min(std::max(b[x] * inv[2], 0.0f), 1.0f)};
            float toned[3];
            for (int k = 0; k < 3; ++k) toned[k] = curve(m[k * 3] * c[0] + m[k * 3 + 1] * c[1] + m[k * 3 + 2] * c[2]);
            float o[3];
            lutSample(spec.lut, spec.lutSize, toned, o);
            out.put(x, y, o);
        }
    }
}

template <typename Writer>
void renderLinearAll(const LinearRgb& in, const RenderSpec& spec, const Writer& out) {
    const int threads = std::min(std::max(spec.threads, 1), std::min(kMaxThreads, in.height));
    if (threads == 1) {
        renderLinearRows(in, spec, 0, in.height, out);
        return;
    }
    const int nice = threadNice();
    std::vector<std::thread> pool;
    pool.reserve(threads);
    for (int t = 0; t < threads; ++t) {
        const int a = static_cast<int>(static_cast<long long>(in.height) * t / threads);
        const int b = static_cast<int>(static_cast<long long>(in.height) * (t + 1) / threads);
        pool.emplace_back([&, a, b, nice] {
            takeNice(nice);
            renderLinearRows(in, spec, a, b, out);
        });
    }
    for (auto& th : pool) th.join();
}

}  // namespace

std::string renderLinear(const LinearRgb& in, const RenderSpec& spec, std::uint8_t* rgba, std::size_t strideBytes) {
    std::string err = validateLinear(in, spec);
    if (err.empty() && (rgba == nullptr || strideBytes < static_cast<std::size_t>(in.width) * 4)) err = "bad output buffer";
    if (!err.empty()) return err;
    renderLinearAll(in, spec, Rgba8Writer{rgba, strideBytes});
    return "";
}

std::string renderLinearFloat(const LinearRgb& in, const RenderSpec& spec, float* rgb, std::size_t strideFloats) {
    std::string err = validateLinear(in, spec);
    if (err.empty() && (rgb == nullptr || strideFloats < static_cast<std::size_t>(in.width) * 3)) err = "bad output buffer";
    if (!err.empty()) return err;
    renderLinearAll(in, spec, FloatWriter{rgb, strideFloats});
    return "";
}

std::string linearStats(const LinearRgb& in, const ColourSpec& colour, int step, RawStats& out) {
    if (in.data == nullptr || in.width < 1 || in.height < 1) return "bad linear image";
    if (step < 1) return "step must be >= 1";
    for (float v : colour.neutral) {
        if (!(v > 0.0f)) return "neutral must be positive";
    }
    const float* m = colour.matrix;
    std::vector<float> ys;
    int clipped = 0;
    for (int y = 0; y < in.height; y += step) {
        const float* r = in.data + static_cast<std::size_t>(y) * in.rowStride;
        const float* planes[3] = {r, r + in.planeStride, r + 2 * in.planeStride};
        for (int x = 0; x < in.width; x += step) {
            float c[3];
            bool clip = false;
            for (int k = 0; k < 3; ++k) {
                c[k] = planes[k][x] / colour.neutral[k];
                clip = clip || c[k] >= 1.0f;
                c[k] = std::min(std::max(c[k], 0.0f), 1.0f);
            }
            if (clip) ++clipped;
            float yv = 0.0f;
            for (int k = 0; k < 3; ++k) yv += kLuma[k] * (m[k * 3] * c[0] + m[k * 3 + 1] * c[1] + m[k * 3 + 2] * c[2]);
            ys.push_back(yv);
        }
    }
    const std::size_t n = ys.size();
    out.p98Y = upperQuantile(ys);
    std::nth_element(ys.begin(), ys.begin() + n / 2, ys.end());
    float med = ys[n / 2];
    if (n % 2 == 0) {
        const float lower = *std::max_element(ys.begin(), ys.begin() + n / 2);
        med = 0.5f * (med + lower);
    }
    out.medianY = med;
    out.clippedFraction = static_cast<float>(clipped) / static_cast<float>(n);
    out.cells = static_cast<int>(n);
    return "";
}

int renderThreadNiceSeen() { return gRenderNiceSeen.load(); }

}  // namespace redline
