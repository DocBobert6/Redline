// B25 Ultra HDR gain map. See gainmap.h.
#include "gainmap.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <vector>

#include "raw_pipeline.h"

namespace redline {

std::string finishGainMap(const float* sums, int cellsW, int cellsH, int outWidth, int outHeight, float capStops, float minStops,
                          std::uint8_t* out, std::size_t outStride, GainMapResult& r) {
    const int k = GainMapAccum::kCell;
    if (sums == nullptr || out == nullptr || outWidth < 1 || outHeight < 1) return "bad gain map buffers";
    if (cellsW != (outWidth + k - 1) / k || cellsH != (outHeight + k - 1) / k) return "gain map cells do not match the size";
    if (outStride < static_cast<std::size_t>(cellsW)) return "gain map stride smaller than the width";
    if (!(capStops > 0.0f) || !(minStops >= 0.0f)) return "bad headroom limits";
    r = GainMapResult{};
    const std::size_t n = static_cast<std::size_t>(cellsW) * static_cast<std::size_t>(cellsH);
    std::vector<float> mean(n);
    std::size_t lit = 0;
    for (int cy = 0; cy < cellsH; ++cy) {
        const int h = std::min(k, outHeight - cy * k);
        for (int cx = 0; cx < cellsW; ++cx) {
            const int w = std::min(k, outWidth - cx * k);
            const std::size_t i = static_cast<std::size_t>(cy) * cellsW + cx;
            const float m = std::max(sums[i], 0.0f) / static_cast<float>(w * h);
            mean[i] = std::isfinite(m) ? m : 0.0f;
            if (mean[i] > 0.0f) ++lit;
        }
    }
    r.aboveKneeFraction = static_cast<float>(lit) / static_cast<float>(n);
    r.maxStops = *std::max_element(mean.begin(), mean.end());
    std::vector<float> sorted(mean);
    const std::size_t q = static_cast<std::size_t>(std::floor(0.999 * static_cast<double>(n - 1)));
    std::nth_element(sorted.begin(), sorted.begin() + static_cast<std::ptrdiff_t>(q), sorted.end());
    r.p999Stops = sorted[q];
    r.headroomStops = std::min(r.p999Stops, capStops);
    if (lit == 0) {
        r.skip = "no highlights above the knee";
        return "";
    }
    if (r.headroomStops < minStops) {
        char buf[96];
        std::snprintf(buf, sizeof(buf), "headroom %.2f stops < %.2f", static_cast<double>(r.headroomStops), static_cast<double>(minStops));
        r.skip = buf;
        return "";
    }
    const float inv = 1.0f / r.headroomStops;
    for (int cy = 0; cy < cellsH; ++cy) {
        std::uint8_t* row = out + static_cast<std::size_t>(cy) * outStride;
        for (int cx = 0; cx < cellsW; ++cx) {
            const float v = std::min(std::max(mean[static_cast<std::size_t>(cy) * cellsW + cx] * inv, 0.0f), 1.0f);
            row[cx] = static_cast<std::uint8_t>(v * 255.0f + 0.5f);
        }
    }
    r.written = true;
    return "";
}

}  // namespace redline
