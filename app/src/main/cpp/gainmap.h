// Redline native module (B25): Ultra HDR gain map from the cell sums the RAW render accumulated (raw_pipeline.h,
// GainMapAccum). Pure C++17 (no Android or JNI), tested on the CI host (app/src/test/cpp/gainmap_test.cpp).
//
// Map: 1 channel, one value per 4x4 cell of the output (quarter resolution), the cell's mean log2 gain encoded as
// the Ultra HDR v1 / ISO 21496-1 recovery value with ratioMin 1, gamma 1, epsilon 0:
//   v = clamp(log2(gain) / log2(ratioMax), 0, 1), byte = round(255 v),  log2(ratioMax) = headroom (stops).
// Headroom = the 99.9th percentile of the cell gains (order statistic at floor(0.999 (n - 1))), capped; when it is below
// the minimum the map is not worth writing (the still is saved as a plain JPEG and the reason reported).
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace redline {

struct GainMapResult {
    bool written = false;     // false: below minStops (or nothing above the knee); `skip` says why
    float headroomStops = 0;  // log2(ratioMax) used for the encoding (p99.9, capped)
    float p999Stops = 0;      // the uncapped p99.9 of the cell gains
    float maxStops = 0;       // the largest cell gain
    float aboveKneeFraction = 0;  // fraction of cells with any gain
    std::string skip;
};

// sums: cellsW x cellsH (cellsW = ceil(outWidth / 4), cellsH = ceil(outHeight / 4)); out: cellsW x cellsH bytes,
// rows outStride bytes apart. Returns an empty string on success (written or skipped), else an error.
std::string finishGainMap(const float* sums, int cellsW, int cellsH, int outWidth, int outHeight, float capStops, float minStops,
                          std::uint8_t* out, std::size_t outStride, GainMapResult& result);

}  // namespace redline
