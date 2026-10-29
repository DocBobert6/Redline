#include "cube_parser.h"

#include <cerrno>
#include <cmath>
#include <cstdlib>
#include <cstring>

namespace redline {
namespace {

bool isSpace(char c) { return c == ' ' || c == '\t' || c == '\r' || c == '\f' || c == '\v'; }

// One line with the comment removed and both ends trimmed.
std::string cleanLine(const char* begin, const char* end) {
    const char* hash = static_cast<const char*>(std::memchr(begin, '#', static_cast<std::size_t>(end - begin)));
    if (hash != nullptr) end = hash;
    while (begin < end && isSpace(*begin)) ++begin;
    while (end > begin && isSpace(*(end - 1))) --end;
    return std::string(begin, end);
}

std::string firstWord(const std::string& line) {
    std::size_t i = 0;
    while (i < line.size() && !isSpace(line[i])) ++i;
    return line.substr(0, i);
}

// Parses exactly `count` floats from `s` (after an optional keyword already skipped by the caller).
bool parseFloats(const char* s, int count, float* out) {
    const char* p = s;
    for (int i = 0; i < count; ++i) {
        char* next = nullptr;
        float v = std::strtof(p, &next);  // overflow gives inf (refused); underflow to a tiny value is fine
        if (next == p || !std::isfinite(v)) return false;
        out[i] = v;
        p = next;
    }
    while (*p != '\0' && isSpace(*p)) ++p;
    return *p == '\0';
}

bool parseInt(const char* s, int* out) {
    char* next = nullptr;
    errno = 0;
    long v = std::strtol(s, &next, 10);
    if (next == s || errno == ERANGE) return false;
    while (*next != '\0' && isSpace(*next)) ++next;
    if (*next != '\0') return false;
    if (v < -1000000 || v > 1000000) return false;
    *out = static_cast<int>(v);
    return true;
}

bool looksNumeric(char c) { return (c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.'; }

}  // namespace

std::string parseCube(const char* text, std::size_t length, CubeLut& out) {
    out = CubeLut();
    if (text == nullptr || length == 0) return "empty file";
    std::size_t expected = 0;
    std::size_t lineNo = 0;
    const char* p = text;
    const char* end = text + length;
    while (p < end) {
        const char* nl = static_cast<const char*>(std::memchr(p, '\n', static_cast<std::size_t>(end - p)));
        const char* lineEnd = nl != nullptr ? nl : end;
        ++lineNo;
        std::string line = cleanLine(p, lineEnd);
        p = nl != nullptr ? nl + 1 : end;
        if (line.empty()) continue;
        auto where = [lineNo]() { return " (line " + std::to_string(lineNo) + ")"; };
        if (looksNumeric(line[0])) {
            if (out.size == 0) return "data before LUT_3D_SIZE" + where();
            if (out.data.size() >= expected) return "more entries than LUT_3D_SIZE^3" + where();
            float rgb[3];
            if (!parseFloats(line.c_str(), 3, rgb)) return "bad data line" + where();
            out.data.insert(out.data.end(), rgb, rgb + 3);
            continue;
        }
        const std::string key = firstWord(line);
        const char* rest = line.c_str() + key.size();
        if (key == "TITLE") {
            std::string t(rest);
            std::size_t a = t.find('"');
            std::size_t b = t.rfind('"');
            out.title = (a != std::string::npos && b > a) ? t.substr(a + 1, b - a - 1) : cleanLine(t.data(), t.data() + t.size());
        } else if (key == "LUT_3D_SIZE") {
            int n = 0;
            if (!parseInt(rest, &n)) return "bad LUT_3D_SIZE" + where();
            if (n < kMinCubeSize || n > kMaxCubeSize) return "LUT_3D_SIZE out of range" + where();
            if (out.size != 0) return "LUT_3D_SIZE given twice" + where();
            out.size = n;
            expected = static_cast<std::size_t>(n) * n * n * 3;
            out.data.reserve(expected);
        } else if (key == "LUT_1D_SIZE") {
            return "1D LUTs are not supported" + where();
        } else if (key == "DOMAIN_MIN") {
            if (!parseFloats(rest, 3, out.domainMin)) return "bad DOMAIN_MIN" + where();
        } else if (key == "DOMAIN_MAX") {
            if (!parseFloats(rest, 3, out.domainMax)) return "bad DOMAIN_MAX" + where();
        } else if (key == "LUT_3D_INPUT_RANGE") {
            float r[2];
            if (!parseFloats(rest, 2, r)) return "bad LUT_3D_INPUT_RANGE" + where();
            for (int i = 0; i < 3; ++i) {
                out.domainMin[i] = r[0];
                out.domainMax[i] = r[1];
            }
        }
        // Other keywords (e.g. LUT_1D_INPUT_RANGE in a 3D-only file, vendor tags) are ignored.
    }
    if (out.size == 0) return "missing LUT_3D_SIZE";
    if (out.data.size() != expected) {
        return "expected " + std::to_string(expected / 3) + " entries, found " + std::to_string(out.data.size() / 3);
    }
    for (int i = 0; i < 3; ++i) {
        if (!(out.domainMax[i] > out.domainMin[i])) return "DOMAIN_MAX must exceed DOMAIN_MIN";
    }
    return std::string();
}

const char* nativeVersion() { return REDLINE_NATIVE_VERSION; }

}  // namespace redline
