// Host command-line front end of the native burst merge (B22), used by the Python parity test
// (calibration/tests/test_burst_merge.py) and the local quality runs.
//
//   burst_merge_tool <params.txt> <out.f32> <disp.i32>
//
// params.txt is whitespace-separated "key value..." records:
//   width W  height H  black B0 B1 B2 B3  white WL  noise S O  threads T  reference R (-1 = auto)
//   frames N PATH1 .. PATHN   (uint16 little-endian, W*H, no padding)
// Writes the merged frame as float32 W*H, the displacements as int32 (per frame in order, tilesY*tilesX*(dy, dx);
// zeros for the reference) and prints one JSON line of stats on stdout.
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <string>
#include <vector>

#include "burst_merge.h"

namespace {

int fail(const std::string& message) {
    std::fprintf(stderr, "burst_merge_tool: %s\n", message.c_str());
    return 2;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 4) return fail("usage: burst_merge_tool params.txt out.f32 disp.i32");
    std::ifstream pf(argv[1]);
    if (!pf) return fail("cannot read params");
    redline::BurstParams p;
    p.keepDisplacements = true;
    std::vector<std::string> paths;
    std::string key;
    while (pf >> key) {
        if (key == "width") pf >> p.width;
        else if (key == "height") pf >> p.height;
        else if (key == "black") pf >> p.black[0] >> p.black[1] >> p.black[2] >> p.black[3];
        else if (key == "white") pf >> p.white;
        else if (key == "noise") pf >> p.noiseS >> p.noiseO;
        else if (key == "threads") pf >> p.threads;
        else if (key == "reference") pf >> p.reference;
        else if (key == "frames") {
            int n = 0;
            pf >> n;
            paths.resize(static_cast<std::size_t>(n));
            for (auto& s : paths) pf >> s;
        } else return fail("unknown key " + key);
    }
    const std::size_t count = static_cast<std::size_t>(p.width) * static_cast<std::size_t>(p.height);
    std::vector<std::vector<std::uint16_t>> data(paths.size());
    std::vector<redline::BurstFrame> frames;
    for (std::size_t i = 0; i < paths.size(); ++i) {
        std::ifstream f(paths[i], std::ios::binary);
        if (!f) return fail("cannot read " + paths[i]);
        data[i].resize(count);
        f.read(reinterpret_cast<char*>(data[i].data()), static_cast<std::streamsize>(count * 2));
        if (static_cast<std::size_t>(f.gcount()) != count * 2) return fail("short frame " + paths[i]);
        frames.push_back({data[i].data(), p.width});
    }
    std::vector<float> out(count);
    redline::BurstResult r;
    const std::string err = redline::mergeBurstFloat(frames, p, out.data(), static_cast<std::size_t>(p.width), r);
    if (!err.empty()) return fail(err);
    std::ofstream o(argv[2], std::ios::binary);
    o.write(reinterpret_cast<const char*>(out.data()), static_cast<std::streamsize>(count * sizeof(float)));
    std::ofstream d(argv[3], std::ios::binary);
    const std::size_t nt = static_cast<std::size_t>(r.tilesX) * static_cast<std::size_t>(r.tilesY) * 2;
    for (std::size_t i = 0; i < frames.size(); ++i) {
        std::vector<std::int32_t> v(nt, 0);
        if (r.displacements[i].size() == nt) {
            for (std::size_t k = 0; k < nt; ++k) v[k] = r.displacements[i][k];
        }
        d.write(reinterpret_cast<const char*>(v.data()), static_cast<std::streamsize>(nt * sizeof(std::int32_t)));
    }
    std::printf("{\"reference\": %d, \"tiles_x\": %d, \"tiles_y\": %d, \"pyramid_ms\": %.2f, \"reference_ms\": %.2f, "
                "\"align_ms\": %.2f, \"merge_ms\": %.2f, \"finish_ms\": %.2f, \"frames\": [",
                r.reference, r.tilesX, r.tilesY, r.pyramidMs, r.referenceMs, r.alignMs, r.mergeMs, r.finishMs);
    for (std::size_t i = 0; i < r.frames.size(); ++i) {
        const auto& s = r.frames[i];
        std::printf("%s{\"index\": %d, \"mean_disp_px\": %.4f, \"max_disp_px\": %.1f, \"mean_weight\": %.6f, "
                    "\"rejected_fraction\": %.6f}",
                    i == 0 ? "" : ", ", s.index, s.meanDispPx, s.maxDispPx, s.meanWeight, s.rejectedFraction);
    }
    std::printf("]}\n");
    return 0;
}
