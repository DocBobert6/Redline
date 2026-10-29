// Host-side unit test of the native .cube parser (B20). Built and run by CI with the host compiler:
//   cmake -S app/src/test/cpp -B build/native-host && cmake --build build/native-host && ctest --test-dir build/native-host
// Optional argument: path to the bundled preview LUT, checked structurally.
#include <cmath>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>

#include "cube_parser.h"

namespace {

int failures = 0;

void check(bool ok, const char* what) {
    if (!ok) {
        std::printf("FAIL: %s\n", what);
        ++failures;
    }
}

std::string parse(const std::string& text, redline::CubeLut& lut) { return redline::parseCube(text.data(), text.size(), lut); }

bool near(float a, float b) { return std::fabs(a - b) < 1e-6f; }

void testMinimalCube() {
    const std::string text =
        "# comment line\r\n"
        "TITLE \"Tiny\"\r\n"
        "LUT_3D_SIZE 2\r\n"
        "\r\n"
        "0 0 0\r\n"
        "1 0 0 # trailing comment\r\n"
        "0 1 0\r\n"
        "1 1 0\r\n"
        "0 0 1\r\n"
        "1 0 1\r\n"
        "0 1 1\r\n"
        "1.0 1.0 1.0";  // no final newline
    redline::CubeLut lut;
    const std::string err = parse(text, lut);
    check(err.empty(), ("minimal cube parses: " + err).c_str());
    check(lut.size == 2, "size 2");
    check(lut.data.size() == 24, "8 entries");
    check(near(lut.data[3], 1.0f) && near(lut.data[4], 0.0f), "second entry is red (red varies fastest)");
    check(near(lut.domainMin[0], 0.0f) && near(lut.domainMax[2], 1.0f), "default domain");
}

void testDomainAndInputRange() {
    std::string body;
    for (int i = 0; i < 8; ++i) body += "0.5 0.5 0.5\n";
    redline::CubeLut lut;
    check(parse("LUT_3D_SIZE 2\nDOMAIN_MIN -0.1 0 0.2\nDOMAIN_MAX 1.5 2 3\n" + body, lut).empty(), "domain parses");
    check(near(lut.domainMin[0], -0.1f) && near(lut.domainMax[1], 2.0f) && near(lut.domainMax[2], 3.0f), "domain values");
    check(parse("LUT_3D_INPUT_RANGE 0 4\nLUT_3D_SIZE 2\n" + body, lut).empty(), "input range parses");
    check(near(lut.domainMin[1], 0.0f) && near(lut.domainMax[1], 4.0f), "input range sets all channels");
    check(!parse("LUT_3D_SIZE 2\nDOMAIN_MIN 1 1 1\nDOMAIN_MAX 1 1 1\n" + body, lut).empty(), "empty domain refused");
}

void testErrors() {
    redline::CubeLut lut;
    check(!parse("", lut).empty(), "empty refused");
    check(!parse("0 0 0\n", lut).empty(), "data before size refused");
    check(!parse("LUT_3D_SIZE 2\n0 0 0\n", lut).empty(), "short data refused");
    check(!parse("LUT_3D_SIZE 1\n0 0 0\n", lut).empty(), "size 1 refused");
    check(!parse("LUT_3D_SIZE 999\n", lut).empty(), "size 999 refused");
    check(!parse("LUT_1D_SIZE 16\n", lut).empty(), "1D refused");
    check(!parse("LUT_3D_SIZE 2\n0 0\n", lut).empty(), "two numbers refused");
    check(!parse("LUT_3D_SIZE 2\n0 0 0 0\n", lut).empty(), "four numbers refused");
    check(!parse("LUT_3D_SIZE 2\n0 x 0\n", lut).empty(), "garbage refused");
    check(!parse("LUT_3D_SIZE 2\n-1e999 0 0\n", lut).empty(), "overflow refused");
    std::string nine;
    for (int i = 0; i < 9; ++i) nine += "0 0 0\n";
    check(!parse("LUT_3D_SIZE 2\n" + nine, lut).empty(), "too many entries refused");
    check(!parse("LUT_3D_SIZE 2\nLUT_3D_SIZE 2\n", lut).empty(), "size twice refused");
}

void testUnknownKeywordsIgnored() {
    std::string text = "LUT_3D_SIZE 2\nVENDOR_TAG something\n";
    for (int i = 0; i < 8; ++i) text += "0.25 0.5 0.75\n";
    redline::CubeLut lut;
    check(parse(text, lut).empty(), "unknown keyword ignored");
}

void testBundledAsset(const char* path) {
    std::ifstream in(path, std::ios::binary);
    check(in.good(), "bundled LUT readable");
    if (!in.good()) return;
    std::stringstream ss;
    ss << in.rdbuf();
    redline::CubeLut lut;
    const std::string err = parse(ss.str(), lut);
    check(err.empty(), ("bundled LUT parses: " + err).c_str());
    check(lut.size == 33, "bundled LUT is 33^3");
    bool inRange = true;
    for (float v : lut.data) inRange = inRange && v >= 0.0f && v <= 1.0f;
    check(inRange, "bundled LUT values within [0, 1]");
    check(near(lut.domainMin[0], 0.0f) && near(lut.domainMax[0], 1.0f), "bundled LUT on the unit cube");
    std::printf("bundled LUT \"%s\": %d^3 ok\n", lut.title.c_str(), lut.size);
}

}  // namespace

int main(int argc, char** argv) {
    std::printf("%s\n", redline::nativeVersion());
    testMinimalCube();
    testDomainAndInputRange();
    testErrors();
    testUnknownKeywordsIgnored();
    if (argc > 1) testBundledAsset(argv[1]);
    if (failures == 0) std::printf("all native parser tests passed\n");
    return failures == 0 ? 0 : 1;
}
