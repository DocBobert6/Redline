// B25 CI: validates an Ultra HDR JPEG written on the emulator with libultrahdr (Apache-2.0, google/libultrahdr), built
// on the CI host only; it is not part of the app. Usage:
//   uhdr_validate <file.jpg> <expected headroom stops>   -> 0 if it is a valid Ultra HDR JPEG with the expected metadata
//   uhdr_validate --not <file.jpg>                       -> 0 if it is NOT an Ultra HDR JPEG (the plain JPEG)
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#include "ultrahdr_api.h"

static std::vector<unsigned char> readFile(const char* path) {
    std::vector<unsigned char> v;
    FILE* f = std::fopen(path, "rb");
    if (!f) return v;
    std::fseek(f, 0, SEEK_END);
    const long n = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    v.resize(static_cast<size_t>(n));
    if (std::fread(v.data(), 1, v.size(), f) != v.size()) v.clear();
    std::fclose(f);
    return v;
}

static bool ok(const uhdr_error_info_t& e, const char* what) {
    if (e.error_code == UHDR_CODEC_OK) return true;
    std::printf("FAIL %s: code %d %s\n", what, static_cast<int>(e.error_code), e.has_detail ? e.detail : "");
    return false;
}

static float halfToFloat(unsigned short h) {
    const unsigned s = (h >> 15) & 1, e = (h >> 10) & 0x1F, m = h & 0x3FF;
    float v;
    if (e == 0) {
        v = std::ldexp(static_cast<float>(m), -24);
    } else if (e == 31) {
        v = m ? NAN : INFINITY;
    } else {
        v = std::ldexp(static_cast<float>(m | 0x400), static_cast<int>(e) - 25);
    }
    return s ? -v : v;
}

int main(int argc, char** argv) {
    if (argc == 3 && std::strcmp(argv[1], "--not") == 0) {
        auto data = readFile(argv[2]);
        if (data.empty()) return 2;
        const int is = is_uhdr_image(data.data(), static_cast<int>(data.size()));
        std::printf("%s: is_uhdr_image=%d (expected 0)\n", argv[2], is);
        return is ? 1 : 0;
    }
    if (argc != 3) {
        std::printf("usage: uhdr_validate <file.jpg> <headroom stops> | --not <file.jpg>\n");
        return 2;
    }
    auto data = readFile(argv[1]);
    const double expectStops = std::atof(argv[2]);
    if (data.empty()) {
        std::printf("FAIL cannot read %s\n", argv[1]);
        return 2;
    }
    int failures = 0;
    if (!is_uhdr_image(data.data(), static_cast<int>(data.size()))) {
        std::printf("FAIL is_uhdr_image = 0\n");
        return 1;
    }
    uhdr_codec_private_t* dec = uhdr_create_decoder();
    uhdr_compressed_image_t img{};
    img.data = data.data();
    img.data_sz = data.size();
    img.capacity = data.size();
    img.cg = UHDR_CG_UNSPECIFIED;
    img.ct = UHDR_CT_UNSPECIFIED;
    img.range = UHDR_CR_UNSPECIFIED;
    if (!ok(uhdr_dec_set_image(dec, &img), "set image") || !ok(uhdr_dec_probe(dec), "probe")) return 1;
    const int w = uhdr_dec_get_image_width(dec), h = uhdr_dec_get_image_height(dec);
    const int gw = uhdr_dec_get_gainmap_width(dec), gh = uhdr_dec_get_gainmap_height(dec);
    std::printf("image %dx%d, gain map %dx%d\n", w, h, gw, gh);
    if (gw != (w + 3) / 4 || gh != (h + 3) / 4) {
        std::printf("FAIL gain map is not quarter resolution\n");
        ++failures;
    }
    const uhdr_gainmap_metadata_t* md = uhdr_dec_get_gainmap_metadata(dec);
    if (md == nullptr) {
        std::printf("FAIL no gain map metadata\n");
        return 1;
    }
    std::printf("max_content_boost %.4f min %.4f gamma %.3f offset_sdr %.5f offset_hdr %.5f capacity [%.4f, %.4f]\n",
                md->max_content_boost[0], md->min_content_boost[0], md->gamma[0], md->offset_sdr[0], md->offset_hdr[0],
                md->hdr_capacity_min, md->hdr_capacity_max);
    const double stops = std::log2(md->max_content_boost[0]);
    if (std::fabs(stops - expectStops) > 0.05) {
        std::printf("FAIL headroom %.3f stops, expected %.3f\n", stops, expectStops);
        ++failures;
    }
    if (std::fabs(md->min_content_boost[0] - 1.0f) > 1e-3f || std::fabs(md->gamma[0] - 1.0f) > 1e-3f) {
        std::printf("FAIL ratioMin / gamma not 1\n");
        ++failures;
    }
    if (std::fabs(md->offset_sdr[0]) > 1e-4f || std::fabs(md->offset_hdr[0]) > 1e-4f) {
        std::printf("FAIL epsilon not 0\n");
        ++failures;
    }
    if (std::fabs(std::log2(md->hdr_capacity_max) - expectStops) > 0.05 || std::fabs(md->hdr_capacity_min - 1.0f) > 1e-3f) {
        std::printf("FAIL HDR capacity not [1, ratioMax]\n");
        ++failures;
    }
    // Full HDR decode (linear, full boost): the bright side must go above SDR white, the dark side must not move.
    // probe() ends the configurable state, so reset and configure the output before decoding.
    const float maxBoost = md->max_content_boost[0];
    uhdr_reset_decoder(dec);
    if (!ok(uhdr_dec_set_image(dec, &img), "set image (decode)") ||
        !ok(uhdr_dec_set_out_img_format(dec, UHDR_IMG_FMT_64bppRGBAHalfFloat), "out format") ||
        !ok(uhdr_dec_set_out_color_transfer(dec, UHDR_CT_LINEAR), "out transfer") ||
        !ok(uhdr_dec_set_out_max_display_boost(dec, maxBoost), "max boost") || !ok(uhdr_decode(dec), "decode")) {
        return 1;
    }
    const uhdr_raw_image_t* out = uhdr_get_decoded_image(dec);
    float maxRight = 0, maxLeft = 0;
    for (unsigned y = 0; y < out->h; ++y) {
        const unsigned short* row = static_cast<const unsigned short*>(out->planes[UHDR_PLANE_PACKED]) + static_cast<size_t>(y) * out->stride[0] * 4;
        for (unsigned x = 0; x < out->w; ++x) {
            const float g = halfToFloat(row[x * 4 + 1]);
            if (x < out->w / 4) maxLeft = std::fmax(maxLeft, g);
            if (x > out->w * 9 / 10) maxRight = std::fmax(maxRight, g);
        }
    }
    std::printf("HDR decode: max linear green left quarter %.3f, right tenth %.3f\n", maxLeft, maxRight);
    if (!(maxRight > 1.2f * maxLeft) || !(maxRight > 1.1f)) {
        std::printf("FAIL the gain map does not lift the highlights\n");
        ++failures;
    }
    uhdr_release_decoder(dec);
    std::printf(failures == 0 ? "OK valid Ultra HDR JPEG\n" : "%d failure(s)\n", failures);
    return failures == 0 ? 0 : 1;
}
