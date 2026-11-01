// B22/B22a burst merge invariants on the host (CTest), including the streaming merger's buffer hand-back. Pixel parity with the Python reference is in
// calibration/tests/test_burst_merge.py (through burst_merge_tool).
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <chrono>
#include <string>
#include <thread>
#include <vector>

#include "burst_merge.h"
#include "burst_internal.h"

namespace {

int failures = 0;

void expect(bool ok, const std::string& what) {
    if (!ok) {
        std::fprintf(stderr, "FAIL: %s\n", what.c_str());
        ++failures;
    }
}

redline::BurstParams params(int w, int h) {
    redline::BurstParams p;
    p.width = w;
    p.height = h;
    for (float& b : p.black) b = 64.0f;
    p.white = 1023.0f;
    p.noiseS = 2e-3;
    p.noiseO = 2e-5;
    p.threads = 3;
    return p;
}

// A textured burst with whole-quad shifts (grey px) and hash noise; values within [64, 1023].
std::vector<std::vector<std::uint16_t>> burst(int w, int h, const std::vector<std::pair<int, int>>& shifts) {
    std::vector<std::vector<std::uint16_t>> out;
    std::uint32_t seed = 1;
    for (const auto& s : shifts) {
        std::vector<std::uint16_t> f(static_cast<std::size_t>(w) * h);
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                const int sy = y + 2 * s.first + 64, sx = x + 2 * s.second + 64;
                const double v = 0.4 + 0.25 * std::sin(sx / 7.3) * std::cos(sy / 5.1) + 0.15 * std::sin((sx + 3 * sy) / 2.9) +
                                 0.1 * (((sx / 6) * 7 + (sy / 6) * 13) % 5) / 5.0;
                seed = seed * 1664525u + 1013904223u;
                const double n = ((seed >> 8) & 0xffff) / 65536.0 - 0.5;
                f[static_cast<std::size_t>(y) * w + x] = static_cast<std::uint16_t>(std::lround(64 + 900 * std::min(std::max(v, 0.0), 1.0) + 20 * n));
            }
        }
        out.push_back(std::move(f));
    }
    return out;
}

void streamingTests() {
    const int W = 256, H = 192;
    const auto frames = burst(W, H, {{0, 0}, {2, -1}, {-3, 2}, {1, 3}, {-1, -2}, {3, 1}, {0, 2}, {-2, -3}});
    std::vector<redline::BurstFrame> in;
    for (const auto& f : frames) in.push_back({f.data(), W});
    redline::BurstParams p = params(W, H);
    p.keepDisplacements = true;
    std::vector<float> want(static_cast<std::size_t>(W) * H);
    redline::BurstResult ref;
    std::string err = redline::mergeBurstFloat(in, p, want.data(), W, ref);
    expect(err.empty(), "one-shot merge: " + err);

    // Streaming with private copies; each copy is overwritten with garbage the moment it is handed back, so a frame
    // used after its release would change the result.
    std::vector<std::vector<std::uint16_t>> copies = frames;
    redline::BurstMerger m;
    err = m.begin(p);
    expect(err.empty(), "begin: " + err);
    std::vector<int> released;
    int maxHeld = 0;
    for (std::size_t i = 0; i < copies.size(); ++i) {
        std::vector<int> rel;
        err = m.add({copies[i].data(), W}, rel);
        expect(err.empty(), "add: " + err);
        for (int k : rel) {
            std::fill(copies[static_cast<std::size_t>(k)].begin(), copies[static_cast<std::size_t>(k)].end(), static_cast<std::uint16_t>(977));
            released.push_back(k);
        }
        maxHeld = std::max(maxHeld, m.held());
        if (i >= 2) expect(m.held() == 1, "after the 3rd frame only the reference is held");
    }
    expect(maxHeld <= 3, "at most the 3 reference candidates are held");
    std::vector<float> got(static_cast<std::size_t>(W) * H);
    redline::BurstResult r;
    std::vector<int> rel;
    err = m.finishFloat(got.data(), W, r, rel);
    expect(err.empty(), "finish: " + err);
    released.insert(released.end(), rel.begin(), rel.end());
    std::sort(released.begin(), released.end());
    bool all = released.size() == frames.size();
    for (std::size_t i = 0; all && i < released.size(); ++i) all = released[i] == static_cast<int>(i);
    expect(all, "every frame is released exactly once");
    expect(rel.size() == 1 && rel[0] == r.reference, "finish releases the reference");
    double maxErr = 0;
    for (std::size_t i = 0; i < got.size(); ++i) maxErr = std::fmax(maxErr, std::fabs(got[i] - want[i]));
    expect(maxErr == 0.0, "streaming equals the one-shot merge");
    expect(r.reference == ref.reference && r.maxHeldFrames == 3 && r.framesAdded == 8 && r.frames.size() == 7, "streaming stats");
    std::printf("streaming: reference %d, max held %d, peak native %.1f MB\n", r.reference, r.maxHeldFrames, r.peakNativeMb);

    // In place over the reference frame (the app's path with the DNG off) equals a separate output.
    std::vector<std::uint16_t> sep(static_cast<std::size_t>(W) * H);
    err = redline::mergeBurstU16(in, p, sep.data(), W, 16.0f, r);
    expect(err.empty(), "u16: " + err);
    copies = frames;
    redline::BurstMerger m2;
    m2.begin(p);
    for (auto& c : copies) {
        std::vector<int> ignored;
        m2.add({c.data(), W}, ignored);
    }
    auto& refBuf = copies[static_cast<std::size_t>(m2.reference())];
    std::vector<int> ignored;
    err = m2.finishU16(refBuf.data(), W, 16.0f, r, ignored);
    expect(err.empty() && refBuf == sep, "in-place output over the reference equals a separate output");

    // nullptr output: in place over whichever frame became the reference.
    copies = frames;
    redline::BurstMerger m4;
    m4.begin(p);
    for (auto& c : copies) {
        std::vector<int> ignored4;
        m4.add({c.data(), W}, ignored4);
    }
    std::vector<int> rel4;
    err = m4.finishU16(nullptr, 0, 16.0f, r, rel4);
    expect(err.empty() && copies[static_cast<std::size_t>(r.reference)] == sep, "null output merges in place over the reference");

    // Fewer frames than reference candidates: the reference is picked at finish.
    redline::BurstMerger m3;
    m3.begin(p);
    std::vector<int> rel3;
    m3.add(in[0], rel3);
    m3.add(in[1], rel3);
    expect(rel3.empty() && m3.reference() == -1, "two frames: undecided until finish");
    err = m3.finishFloat(got.data(), W, r, rel3);
    expect(err.empty() && rel3.size() == 2 && r.frames.size() == 1, "two frames merge at finish");

    // Bright 14-bit-range data: the grey saturates instead of wrapping, and the merge still runs.
    std::vector<std::uint16_t> hot(static_cast<std::size_t>(W) * H, 16383);
    redline::BurstParams hp = params(W, H);
    hp.white = 16383.0f;
    err = redline::mergeBurstFloat({{hot.data(), W}, {hot.data(), W}}, hp, got.data(), W, r);
    expect(err.empty() && std::fabs(got[100] - 16383.0f) < 1e-2f, "saturated grey merges to itself");

    // B23b.1: the merge tail (finish in slices, stepping aside while a frame add is in progress) gives the same result.
    {
        copies = frames;
        redline::BurstMerger mt;
        mt.begin(p);
        for (auto& c : copies) {
            std::vector<int> ignoredT;
            mt.add({c.data(), W}, ignoredT);
        }
        const redline::WorkerStats before = redline::burstWorkerStats();
        std::thread adder([] {
            const redline::FrameWorkScope busy;
            std::this_thread::sleep_for(std::chrono::milliseconds(30));
        });
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
        redline::burstSetLane(-1000, true);  // -1000: keep the pool's nice; tail slicing on
        std::vector<float> tail(static_cast<std::size_t>(W) * H);
        std::vector<int> relT;
        redline::BurstResult rt;
        err = mt.finishFloat(tail.data(), W, rt, relT);
        redline::burstSetLane(-1000, false);
        adder.join();
        const redline::WorkerStats after = redline::burstWorkerStats();
        double tailErr = 0;
        for (std::size_t i = 0; i < tail.size(); ++i) tailErr = std::fmax(tailErr, std::fabs(tail[i] - want[i]));
        expect(err.empty() && tailErr == 0.0, "merge tail in slices equals the one-shot merge");
        // B23c: the add in progress makes the tail wait (a yield). Whether it also splits depends on timing (only an
        // add that starts mid-job splits it); the split path is checked deterministically below.
        expect(after.tailYields > before.tailYields, "merge tail waited for the frame add");
        std::printf("tail: %d splits, %d yields (%d ms)\n", after.tailSlices - before.tailSlices, after.tailYields - before.tailYields,
                    after.tailYieldMs - before.tailYieldMs);
    }

    // B23c: a tail job is split only when a frame add starts while it runs; every row still runs exactly once.
    {
        const int n = 64;
        std::vector<std::atomic<int>> hits(n);
        for (auto& h : hits) h.store(0);
        std::atomic<bool> started{false};
        std::atomic<bool> addActive{false};
        std::thread adder;
        const redline::WorkerStats before = redline::burstWorkerStats();
        redline::burstSetLane(-1000, true);
        redline::detail::runParallel(n, 2, [&](int y0, int y1) {
            if (!started.exchange(true)) {
                adder = std::thread([&addActive] {
                    const redline::FrameWorkScope busy;
                    addActive.store(true);
                    std::this_thread::sleep_for(std::chrono::milliseconds(40));
                });
                while (!addActive.load()) std::this_thread::yield();
            } else {
                std::this_thread::sleep_for(std::chrono::milliseconds(2));  // slow rows, so the add starts mid-job
            }
            for (int y = y0; y < y1; ++y) hits[static_cast<std::size_t>(y)].fetch_add(1);
        });
        redline::burstSetLane(-1000, false);
        adder.join();
        const redline::WorkerStats after = redline::burstWorkerStats();
        bool once = true;
        for (auto& h : hits) once = once && h.load() == 1;
        expect(once, "split tail covers every row exactly once");
        expect(after.tailSlices > before.tailSlices, "tail split for a frame add that started mid-job");
    }
    {
        // No add at all: one pool job, no split.
        const int n = 64;
        std::atomic<int> rows{0};
        const redline::WorkerStats before = redline::burstWorkerStats();
        redline::burstSetLane(-1000, true);
        redline::detail::runParallel(n, 2, [&](int y0, int y1) { rows.fetch_add(y1 - y0); });
        redline::burstSetLane(-1000, false);
        const redline::WorkerStats after = redline::burstWorkerStats();
        expect(rows.load() == n && after.tailSlices == before.tailSlices, "tail without a waiting add is not split");
    }

    const int t = redline::burstRecommendedThreads();
    std::printf("recommended threads %d\n", t);
    expect(t >= 1 && t <= 8, "recommended threads in 1..8");
}

}  // namespace

int main() {
    streamingTests();

    // Self-check: exact alignment on interior tiles and close to sqrt(6) noise reduction.
    redline::BurstSelfCheck sc;
    std::string err = redline::burstSelfCheck(4, sc);
    expect(err.empty(), "self-check error: " + err);
    std::printf("self-check aligned %.3f noise ratio %.3f (%.0f ms)\n", sc.alignedFraction, sc.noiseRatio, sc.ms);
    expect(sc.alignedFraction >= 0.95, "aligned fraction >= 0.95");
    expect(sc.noiseRatio >= 2.0, "noise ratio >= 2.0 for 6 frames");

    // A single frame merges to itself.
    const int W = 64, H = 48;
    std::vector<std::uint16_t> a(static_cast<std::size_t>(W) * H);
    for (std::size_t i = 0; i < a.size(); ++i) a[i] = static_cast<std::uint16_t>(64 + (i * 37) % 900);
    std::vector<float> out(a.size());
    redline::BurstResult r;
    err = redline::mergeBurstFloat({{a.data(), W}}, params(W, H), out.data(), W, r);
    expect(err.empty(), "single frame: " + err);
    bool same = true;
    for (std::size_t i = 0; i < a.size(); ++i) same = same && out[i] == static_cast<float>(a[i]);
    expect(same && r.reference == 0 && r.frames.empty(), "single frame is the identity");

    // Identical frames: identity, zero displacement, full weight.
    err = redline::mergeBurstFloat({{a.data(), W}, {a.data(), W}, {a.data(), W}}, params(W, H), out.data(), W, r);
    expect(err.empty(), "identical frames: " + err);
    double maxErr = 0;
    for (std::size_t i = 0; i < a.size(); ++i) maxErr = std::fmax(maxErr, std::fabs(out[i] - a[i]));
    expect(maxErr < 1e-3, "identical frames merge to themselves");
    expect(r.frames.size() == 2 && r.frames[0].maxDispPx == 0 && r.frames[0].meanWeight > 0.999, "identical frames: no motion");

    // Scaled uint16 output keeps the levels' scale.
    std::vector<std::uint16_t> o16(a.size());
    err = redline::mergeBurstU16({{a.data(), W}, {a.data(), W}}, params(W, H), o16.data(), W, 16.0f, r);
    expect(err.empty() && o16[5] == a[5] * 16, "uint16 output scaled by 16");

    // Bad input is refused, never crashes.
    expect(!redline::mergeBurstFloat({}, params(W, H), out.data(), W, r).empty(), "no frames refused");
    expect(!redline::mergeBurstFloat({{a.data(), W}}, params(W - 1, H), out.data(), W, r).empty(), "odd width refused");
    redline::BurstParams bad = params(W, H);
    bad.reference = 3;
    expect(!redline::mergeBurstFloat({{a.data(), W}}, bad, out.data(), W, r).empty(), "reference out of range refused");
    expect(!redline::mergeBurstU16({{a.data(), W}}, params(W, H), o16.data(), W, 80.0f, r).empty(), "16-bit overflow refused");

    if (failures == 0) std::printf("burst_merge_test: all passed\n");
    return failures == 0 ? 0 : 1;
}
