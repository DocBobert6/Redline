// Host command-line front end of the native super-resolution merge (B23), used by the Python parity test
// (calibration/tests/test_superres.py), the quality tool and the host timings.
//
//   superres_tool merge <params.txt> <out.f32> <flows.f32> <disp.i32>
//   superres_tool selfcheck [threads]
//
// params.txt is whitespace-separated "key value..." records:
//   width W  height H  cfa C  black B0 B1 B2 B3  white WL  noise S O  threads T (0 = recommended)  reference R (-1 = auto)
//   crop L T W H  out OW OH  shading ROWS COLS PATH (float32 Camera2 order) | shading none
//   frames N PATH1 .. PATHN   (uint16 little-endian, W*H, no padding)
//   repeat K   (run the merge K times and report the fastest, for timings)
// Writes the merged image as float32 planar R, G, B planes of OW*OH, the flows as float32 (per frame in order,
// tilesY*tilesX*(fy, fx) RAW px; zeros for the reference), the integer displacements as int32 the same way, and prints
// one JSON line of stats.
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <string>
#include <vector>

#include "burst_merge.h"
#include "superres.h"

namespace {

int fail(const std::string& message) {
    std::fprintf(stderr, "superres_tool: %s\n", message.c_str());
    return 2;
}

template <typename T>
bool readBinary(const std::string& path, std::vector<T>& out, std::size_t count) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    out.resize(count);
    f.read(reinterpret_cast<char*>(out.data()), static_cast<std::streamsize>(count * sizeof(T)));
    return static_cast<std::size_t>(f.gcount()) == count * sizeof(T);
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) return fail("usage: superres_tool merge params.txt out.f32 flows.f32 disp.i32 | selfcheck [threads]");
    const std::string mode = argv[1];
    if (mode == "selfcheck") {
        const int threads = argc > 2 ? std::atoi(argv[2]) : redline::burstRecommendedThreads();
        redline::SuperResSelfCheck c;
        const std::string err = redline::superResSelfCheck(threads, c);
        if (!err.empty()) return fail(err);
        std::printf("{\"flow_error_px\": %.4f, \"coverage\": %.3f, \"rmse_merge\": %.5f, \"rmse_single\": %.5f, \"gain\": %.3f, "
                    "\"ms\": %.1f, \"passed\": %s}\n",
                    c.flowErrorPx, c.coverage, c.rmseMerge, c.rmseSingle, c.gain, c.ms, c.passed() ? "true" : "false");
        return 0;
    }
    if (mode != "merge" || argc < 6) return fail("merge needs params and three output paths");
    std::ifstream pf(argv[2]);
    if (!pf) return fail("cannot read params");
    redline::SuperResParams p;
    p.keepFlows = true;
    std::vector<std::string> paths;
    std::vector<float> shading;
    std::string shadingPath;
    int repeat = 1;
    std::string key;
    while (pf >> key) {
        if (key == "width") pf >> p.width;
        else if (key == "height") pf >> p.height;
        else if (key == "cfa") pf >> p.cfa;
        else if (key == "black") pf >> p.black[0] >> p.black[1] >> p.black[2] >> p.black[3];
        else if (key == "white") pf >> p.white;
        else if (key == "noise") pf >> p.noiseS >> p.noiseO;
        else if (key == "threads") pf >> p.threads;
        else if (key == "reference") pf >> p.reference;
        else if (key == "crop") pf >> p.crop.left >> p.crop.top >> p.crop.width >> p.crop.height;
        else if (key == "out") pf >> p.outWidth >> p.outHeight;
        else if (key == "repeat") pf >> repeat;
        else if (key == "shading") {
            pf >> shadingPath;
            if (shadingPath != "none") {
                p.shadingRows = std::stoi(shadingPath);
                pf >> p.shadingCols >> shadingPath;
            }
        } else if (key == "frames") {
            int n = 0;
            pf >> n;
            paths.resize(static_cast<std::size_t>(n));
            for (auto& s : paths) pf >> s;
        } else return fail("unknown key " + key);
    }
    if (p.shadingRows > 0) {
        if (!readBinary(shadingPath, shading, static_cast<std::size_t>(p.shadingRows) * p.shadingCols * 4)) return fail("cannot read shading");
        p.shading = shading.data();
    }
    const std::size_t count = static_cast<std::size_t>(p.width) * static_cast<std::size_t>(p.height);
    std::vector<std::vector<std::uint16_t>> data(paths.size());
    std::vector<redline::BurstFrame> frames;
    for (std::size_t i = 0; i < paths.size(); ++i) {
        if (!readBinary(paths[i], data[i], count)) return fail("cannot read " + paths[i]);
        frames.push_back({data[i].data(), p.width});
    }
    if (p.threads <= 0) p.threads = redline::burstRecommendedThreads();
    const std::size_t plane = static_cast<std::size_t>(p.outWidth) * static_cast<std::size_t>(p.outHeight);
    std::vector<float> out(plane * 3);
    redline::SuperResResult r, best;
    double bestTotal = 1e300;
    for (int k = 0; k < std::max(repeat, 1); ++k) {
        const std::string err = redline::superResMerge(frames, p, out.data(), static_cast<std::size_t>(p.outWidth), plane, r);
        if (!err.empty()) return fail(err);
        const double total = r.copyMs + r.referenceMs + r.alignMs + r.robustMs + r.gatherMs;
        if (total < bestTotal) {
            bestTotal = total;
            best = r;
        }
    }
    r = best;
    std::ofstream o(argv[3], std::ios::binary);
    o.write(reinterpret_cast<const char*>(out.data()), static_cast<std::streamsize>(out.size() * sizeof(float)));
    const std::size_t nt = static_cast<std::size_t>(r.tilesX) * static_cast<std::size_t>(r.tilesY) * 2;
    std::ofstream fo(argv[4], std::ios::binary);
    std::ofstream d(argv[5], std::ios::binary);
    for (std::size_t i = 0; i < frames.size(); ++i) {
        std::vector<float> f(nt, 0.0f);
        std::vector<std::int32_t> v(nt, 0);
        if (i < r.flows.size() && r.flows[i].size() == nt) f = r.flows[i];
        if (i < r.displacements.size() && r.displacements[i].size() == nt) {
            for (std::size_t k = 0; k < nt; ++k) v[k] = r.displacements[i][k];
        }
        fo.write(reinterpret_cast<const char*>(f.data()), static_cast<std::streamsize>(nt * sizeof(float)));
        d.write(reinterpret_cast<const char*>(v.data()), static_cast<std::streamsize>(nt * sizeof(std::int32_t)));
    }
    std::printf("{\"reference\": %d, \"tiles_x\": %d, \"tiles_y\": %d, \"region\": [%d, %d, %d, %d], \"coverage\": %.6f, "
                "\"sigma_g\": %.6f, \"sigma_rb\": %.6f, \"threads\": %d, \"peak_native_mb\": %.1f, \"copy_ms\": %.2f, "
                "\"reference_ms\": %.2f, \"align_ms\": %.2f, \"robust_ms\": %.2f, \"gather_ms\": %.2f, \"total_ms\": %.2f, \"frames\": [",
                r.reference, r.tilesX, r.tilesY, r.region[0], r.region[1], r.region[2], r.region[3], r.coverage, r.sigmaG, r.sigmaRB,
                r.threads, r.peakNativeMb, r.copyMs, r.referenceMs, r.alignMs, r.robustMs, r.gatherMs, bestTotal);
    for (std::size_t i = 0; i < r.frames.size(); ++i) {
        const auto& f = r.frames[i];
        std::printf("%s{\"index\": %d, \"mean_disp_px\": %.6f, \"mean_subpix_px\": %.6f, \"mean_robustness\": %.6f, "
                    "\"rejected_fraction\": %.6f, \"model_resolved\": %.6f, \"model\": %s, \"align_ms\": %.2f, \"robust_ms\": %.2f}",
                    i ? ", " : "", f.index, f.meanDispPx, f.meanSubpixPx, f.meanRobustness, f.rejectedFraction, f.modelResolved,
                    f.model ? "true" : "false", f.alignMs, f.robustMs);
    }
    std::printf("]}\n");
    return 0;
}
