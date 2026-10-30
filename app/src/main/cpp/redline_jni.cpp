// JNI bindings for com.redline.camera.nativebridge.RedlineNative.
#include <android/bitmap.h>
#include <jni.h>

#include <cstdint>
#include <string>

#include "cube_parser.h"
#include "raw_pipeline.h"

namespace {

// Layout returned to Kotlin: [size, domainMin r g b, domainMax r g b, data...] (see RedlineNative.HEADER_FLOATS).
constexpr int kHeaderFloats = 7;

void throwIllegalArgument(JNIEnv* env, const std::string& message) {
    jclass cls = env->FindClass("java/lang/IllegalArgumentException");
    if (cls != nullptr) env->ThrowNew(cls, message.c_str());
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL Java_com_redline_camera_nativebridge_RedlineNative_nativeVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF(redline::nativeVersion());
}

extern "C" JNIEXPORT jfloatArray JNICALL Java_com_redline_camera_nativebridge_RedlineNative_nativeParseCube(
    JNIEnv* env, jclass, jbyteArray bytes) {
    if (bytes == nullptr) {
        throwIllegalArgument(env, "no data");
        return nullptr;
    }
    const jsize length = env->GetArrayLength(bytes);
    std::string text(static_cast<std::size_t>(length), '\0');
    if (length > 0) env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte*>(&text[0]));
    redline::CubeLut lut;
    const std::string error = redline::parseCube(text.data(), text.size(), lut);
    if (!error.empty()) {
        throwIllegalArgument(env, error);
        return nullptr;
    }
    const jsize total = static_cast<jsize>(kHeaderFloats + lut.data.size());
    jfloatArray out = env->NewFloatArray(total);
    if (out == nullptr) return nullptr;  // OutOfMemoryError is pending
    const jfloat header[kHeaderFloats] = {
        static_cast<jfloat>(lut.size), lut.domainMin[0], lut.domainMin[1], lut.domainMin[2],
        lut.domainMax[0], lut.domainMax[1], lut.domainMax[2]};
    env->SetFloatArrayRegion(out, 0, kHeaderFloats, header);
    env->SetFloatArrayRegion(out, kHeaderFloats, static_cast<jsize>(lut.data.size()), lut.data.data());
    return out;
}

// ---------------------------------------------------------------------------------------------------- B21 RAW pipeline

namespace {

// Float array pinned for the duration of a call (JNI_ABORT on release: never copied back).
class Floats {
public:
    Floats(JNIEnv* env, jfloatArray a) : env_(env), a_(a) {
        if (a != nullptr) {
            n_ = env->GetArrayLength(a);
            p_ = env->GetFloatArrayElements(a, nullptr);
        }
    }
    ~Floats() {
        if (p_ != nullptr) env_->ReleaseFloatArrayElements(a_, p_, JNI_ABORT);
    }
    Floats(const Floats&) = delete;
    Floats& operator=(const Floats&) = delete;
    const float* get() const { return p_; }
    jsize size() const { return n_; }

private:
    JNIEnv* env_;
    jfloatArray a_;
    jfloat* p_ = nullptr;
    jsize n_ = 0;
};

// Fills RawInput from the Kotlin arguments; returns an error message or "".
std::string rawInput(JNIEnv* env, jobject buffer, jint width, jint height, jint rowStrideBytes, jint cfa, const Floats& black,
                     jfloat white, const Floats& shading, jint shadingRows, jint shadingCols, redline::RawInput& in) {
    if (buffer == nullptr) return "no RAW buffer";
    void* data = env->GetDirectBufferAddress(buffer);
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (data == nullptr || capacity < 0) return "RAW buffer is not direct";
    if (rowStrideBytes <= 0 || (rowStrideBytes & 1) != 0) return "RAW row stride must be a positive even byte count";
    if (width <= 0 || height <= 0 || static_cast<jlong>(rowStrideBytes) * (height - 1) + static_cast<jlong>(width) * 2 > capacity) {
        return "RAW buffer smaller than width x height";
    }
    if (black.size() != 4) return "black level needs 4 values";
    in.data = static_cast<const std::uint16_t*>(data);
    in.width = width;
    in.height = height;
    in.rowStride = rowStrideBytes / 2;
    in.cfa = cfa;
    for (int i = 0; i < 4; ++i) in.black[i] = black.get()[i];
    in.white = white;
    if (shading.get() != nullptr) {
        if (shadingRows <= 0 || shadingCols <= 0 || shading.size() != shadingRows * shadingCols * 4) return "bad lens shading map";
        in.shading = shading.get();
        in.shadingRows = shadingRows;
        in.shadingCols = shadingCols;
    }
    return "";
}

std::string colourSpec(JNIEnv* env, jintArray crop, const Floats& matrix, const Floats& neutral, redline::CropRect& rect,
                       redline::ColourSpec& colour) {
    if (crop == nullptr || env->GetArrayLength(crop) != 4) return "crop needs 4 values";
    jint c[4];
    env->GetIntArrayRegion(crop, 0, 4, c);
    rect = {c[0], c[1], c[2], c[3]};
    if (matrix.size() != 9 || neutral.size() != 3) return "matrix needs 9 values and neutral 3";
    for (int i = 0; i < 9; ++i) colour.matrix[i] = matrix.get()[i];
    for (int i = 0; i < 3; ++i) colour.neutral[i] = neutral.get()[i];
    return "";
}

}  // namespace

extern "C" JNIEXPORT jfloatArray JNICALL Java_com_redline_camera_nativebridge_RedlineNative_nativeRawStats(
    JNIEnv* env, jclass, jobject buffer, jint width, jint height, jint rowStrideBytes, jint cfa, jfloatArray black,
    jfloat white, jfloatArray shading, jint shadingRows, jint shadingCols, jintArray crop, jfloatArray matrix,
    jfloatArray neutral, jint step) {
    const Floats b(env, black), sh(env, shading), m(env, matrix), n(env, neutral);
    redline::RawInput in;
    redline::CropRect rect;
    redline::ColourSpec colour;
    std::string err = rawInput(env, buffer, width, height, rowStrideBytes, cfa, b, white, sh, shadingRows, shadingCols, in);
    if (err.empty()) err = colourSpec(env, crop, m, n, rect, colour);
    redline::RawStats st;
    if (err.empty()) err = redline::rawStats(in, rect, colour, step, st);
    if (!err.empty()) {
        throwIllegalArgument(env, err);
        return nullptr;
    }
    jfloatArray out = env->NewFloatArray(3);
    if (out == nullptr) return nullptr;
    const jfloat v[3] = {st.medianY, st.clippedFraction, static_cast<jfloat>(st.cells)};
    env->SetFloatArrayRegion(out, 0, 3, v);
    return out;
}

extern "C" JNIEXPORT jstring JNICALL Java_com_redline_camera_nativebridge_RedlineNative_nativeRenderRaw(
    JNIEnv* env, jclass, jobject buffer, jint width, jint height, jint rowStrideBytes, jint cfa, jfloatArray black,
    jfloat white, jfloatArray shading, jint shadingRows, jint shadingCols, jintArray crop, jfloatArray matrix,
    jfloatArray neutral, jfloat gain, jfloatArray curve, jfloat curveLo, jfloat curveHi, jfloatArray lut, jint lutSize,
    jint threads, jobject bitmap) {
    const Floats b(env, black), sh(env, shading), m(env, matrix), n(env, neutral), cv(env, curve), lt(env, lut);
    redline::RawInput in;
    redline::RenderSpec spec;
    std::string err = rawInput(env, buffer, width, height, rowStrideBytes, cfa, b, white, sh, shadingRows, shadingCols, in);
    if (err.empty()) err = colourSpec(env, crop, m, n, spec.crop, spec.colour);
    if (err.empty() && (cv.get() == nullptr || lt.get() == nullptr || lt.size() != lutSize * lutSize * lutSize * 3)) {
        err = "curve or LUT missing or mis-sized";
    }
    AndroidBitmapInfo info{};
    if (err.empty() && (bitmap == nullptr || AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
                        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)) {
        err = "output must be an ARGB_8888 bitmap";
    }
    if (!err.empty()) return env->NewStringUTF(err.c_str());
    spec.outWidth = static_cast<int>(info.width);
    spec.outHeight = static_cast<int>(info.height);
    spec.gain = gain;
    spec.curve = cv.get();
    spec.curvePoints = cv.size();
    spec.curveLo = curveLo;
    spec.curveHi = curveHi;
    spec.lut = lt.get();
    spec.lutSize = lutSize;
    spec.threads = threads;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || pixels == nullptr) {
        return env->NewStringUTF("could not lock the bitmap");
    }
    err = redline::renderRaw(in, spec, static_cast<std::uint8_t*>(pixels), info.stride);
    AndroidBitmap_unlockPixels(env, bitmap);
    return err.empty() ? nullptr : env->NewStringUTF(err.c_str());
}
