// JNI bindings for com.redline.camera.nativebridge.RedlineNative.
#include <android/bitmap.h>
#include <jni.h>

#include <cstdint>
#include <cstdlib>
#include <string>
#include <vector>

#include "burst_merge.h"
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

// ---------------------------------------------------------------------------------------------------- B22 burst merge

// A RAW frame buffer outside the Java heap (ART backs ByteBuffer.allocateDirect with heap arrays, and a burst needs
// hundreds of MB). Returns null when the allocation fails; the caller frees it with nativeFreeFrame.
extern "C" JNIEXPORT jobject JNICALL Java_com_redline_camera_nativebridge_RedlineNative_nativeAllocFrame(JNIEnv* env, jclass,
                                                                                                         jlong bytes) {
    if (bytes <= 0) return nullptr;
    void* p = std::malloc(static_cast<std::size_t>(bytes));
    if (p == nullptr) return nullptr;
    jobject buf = env->NewDirectByteBuffer(p, bytes);
    if (buf == nullptr) std::free(p);
    return buf;
}

extern "C" JNIEXPORT void JNICALL Java_com_redline_camera_nativebridge_RedlineNative_nativeFreeFrame(JNIEnv* env, jclass,
                                                                                                    jobject buffer) {
    if (buffer == nullptr) return;
    std::free(env->GetDirectBufferAddress(buffer));
}

namespace {

// Merge stats as floats: [reference, frames, tilesX, tilesY, pyramidMs, referenceMs, alignMs, mergeMs, finishMs, threads,
// maxHeldFrames, peakNativeMb, then per alternate frame: index, meanDispPx, maxDispPx, meanWeight, rejectedFraction,
// alignMs, mergeMs]. Keep in step with RedlineNative.MERGE_HEADER / MERGE_PER_FRAME.
jfloatArray mergeStats(JNIEnv* env, const redline::BurstResult& r) {
    std::vector<jfloat> v = {static_cast<jfloat>(r.reference), static_cast<jfloat>(r.framesAdded), static_cast<jfloat>(r.tilesX),
                             static_cast<jfloat>(r.tilesY), static_cast<jfloat>(r.pyramidMs), static_cast<jfloat>(r.referenceMs),
                             static_cast<jfloat>(r.alignMs), static_cast<jfloat>(r.mergeMs), static_cast<jfloat>(r.finishMs),
                             static_cast<jfloat>(r.threads), static_cast<jfloat>(r.maxHeldFrames), static_cast<jfloat>(r.peakNativeMb)};
    for (const auto& f : r.frames) {
        v.insert(v.end(), {static_cast<jfloat>(f.index), static_cast<jfloat>(f.meanDispPx), static_cast<jfloat>(f.maxDispPx),
                           static_cast<jfloat>(f.meanWeight), static_cast<jfloat>(f.rejectedFraction), static_cast<jfloat>(f.alignMs),
                           static_cast<jfloat>(f.mergeMs)});
    }
    jfloatArray res = env->NewFloatArray(static_cast<jsize>(v.size()));
    if (res == nullptr) return nullptr;
    env->SetFloatArrayRegion(res, 0, static_cast<jsize>(v.size()), v.data());
    return res;
}

std::string burstParams(JNIEnv* env, jint width, jint height, jfloatArray black, jfloat white, jdouble noiseS, jdouble noiseO,
                        jint reference, jint threads, redline::BurstParams& p) {
    const Floats b(env, black);
    if (width <= 0 || height <= 0) return "bad frame geometry";
    if (b.size() != 4) return "black level needs 4 values";
    p.width = width;
    p.height = height;
    for (int i = 0; i < 4; ++i) p.black[i] = b.get()[i];
    p.white = white;
    p.noiseS = noiseS;
    p.noiseO = noiseO;
    p.threads = threads;
    p.reference = reference;
    return "";
}

// A direct buffer holding a RAW frame of the merger's geometry, or null with `err` set.
const std::uint16_t* frameData(JNIEnv* env, jobject buf, jint width, jint height, jint rowStrideBytes, std::string& err) {
    if (rowStrideBytes < width * 2 || (rowStrideBytes & 1) != 0) {
        err = "bad row stride";
        return nullptr;
    }
    const jlong need = static_cast<jlong>(rowStrideBytes) * (height - 1) + static_cast<jlong>(width) * 2;
    void* data = buf == nullptr ? nullptr : env->GetDirectBufferAddress(buf);
    if (data == nullptr || env->GetDirectBufferCapacity(buf) < need) {
        err = "frame buffer missing, not direct or too small";
        return nullptr;
    }
    return static_cast<const std::uint16_t*>(data);
}

struct MergerHandle {
    redline::BurstMerger merger;
    int width = 0;
    int height = 0;
};

jintArray intArray(JNIEnv* env, const std::vector<int>& v) {
    jintArray a = env->NewIntArray(static_cast<jsize>(v.size()));
    if (a == nullptr) return nullptr;
    std::vector<jint> j(v.begin(), v.end());
    env->SetIntArrayRegion(a, 0, static_cast<jsize>(j.size()), j.data());
    return a;
}

}  // namespace

// One-shot merge of all frames (tests): stats as mergeStats.
extern "C" JNIEXPORT jfloatArray JNICALL Java_com_redline_camera_nativebridge_RedlineNative_nativeMergeBurst(
    JNIEnv* env, jclass, jobjectArray buffers, jint width, jint height, jint rowStrideBytes, jfloatArray black, jfloat white,
    jdouble noiseS, jdouble noiseO, jint reference, jint threads, jobject out, jfloat outScale) {
    redline::BurstParams p;
    std::string err = burstParams(env, width, height, black, white, noiseS, noiseO, reference, threads, p);
    std::vector<redline::BurstFrame> frames;
    const jsize n = buffers == nullptr ? 0 : env->GetArrayLength(buffers);
    if (err.empty() && n == 0) err = "no frames";
    for (jsize i = 0; err.empty() && i < n; ++i) {
        jobject buf = env->GetObjectArrayElement(buffers, i);
        const std::uint16_t* data = frameData(env, buf, width, height, rowStrideBytes, err);
        if (data != nullptr) frames.push_back({data, rowStrideBytes / 2});
        if (buf != nullptr) env->DeleteLocalRef(buf);
    }
    void* outData = out == nullptr ? nullptr : env->GetDirectBufferAddress(out);
    const jlong outNeed = static_cast<jlong>(width) * height * 2;
    if (err.empty() && (outData == nullptr || env->GetDirectBufferCapacity(out) < outNeed)) err = "output buffer missing or too small";
    redline::BurstResult r;
    if (err.empty()) err = redline::mergeBurstU16(frames, p, static_cast<std::uint16_t*>(outData), static_cast<std::size_t>(width), outScale, r);
    if (!err.empty()) {
        throwIllegalArgument(env, err);
        return nullptr;
    }
    return mergeStats(env, r);
}

// B22a streaming merge: create -> add (per frame, as it arrives) -> finish -> destroy. One thread per handle.
extern "C" JNIEXPORT jlong JNICALL Java_com_redline_camera_nativebridge_RedlineNative_nativeMergerCreate(
    JNIEnv* env, jclass, jint width, jint height, jfloatArray black, jfloat white, jdouble noiseS, jdouble noiseO, jint reference,
    jint threads) {
    redline::BurstParams p;
    std::string err = burstParams(env, width, height, black, white, noiseS, noiseO, reference, threads, p);
    auto* h = new MergerHandle();
    if (err.empty()) err = h->merger.begin(p);
    if (!err.empty()) {
        delete h;
        throwIllegalArgument(env, err);
        return 0;
    }
    h->width = width;
    h->height = height;
    return reinterpret_cast<jlong>(h);
}

// Adds the next frame; returns the indices of frames whose buffers the merger no longer needs.
extern "C" JNIEXPORT jintArray JNICALL Java_com_redline_camera_nativebridge_RedlineNative_nativeMergerAdd(JNIEnv* env, jclass,
                                                                                                         jlong handle, jobject frame,
                                                                                                         jint rowStrideBytes) {
    auto* h = reinterpret_cast<MergerHandle*>(handle);
    std::string err;
    std::vector<int> released;
    if (h == nullptr) err = "no merger";
    const std::uint16_t* data = err.empty() ? frameData(env, frame, h->width, h->height, rowStrideBytes, err) : nullptr;
    if (err.empty()) err = h->merger.add({data, rowStrideBytes / 2}, released);
    if (!err.empty()) {
        throwIllegalArgument(env, err);
        return nullptr;
    }
    return intArray(env, released);
}

// Writes the merge (uint16 x outScale) into `out` (null: in place over the reference frame) and returns the stats;
// every frame still held is released by this call.
extern "C" JNIEXPORT jfloatArray JNICALL Java_com_redline_camera_nativebridge_RedlineNative_nativeMergerFinish(
    JNIEnv* env, jclass, jlong handle, jobject out, jint rowStrideBytes, jfloat outScale) {
    auto* h = reinterpret_cast<MergerHandle*>(handle);
    std::string err;
    if (h == nullptr) err = "no merger";
    redline::BurstResult r;
    std::vector<int> released;
    if (err.empty() && out == nullptr) {
        err = h->merger.finishU16(nullptr, 0, outScale, r, released);  // in place over the reference frame
    } else if (err.empty()) {
        const std::uint16_t* data = frameData(env, out, h->width, h->height, rowStrideBytes, err);
        if (data != nullptr) {
            err = h->merger.finishU16(const_cast<std::uint16_t*>(data), static_cast<std::size_t>(rowStrideBytes / 2), outScale, r,
                                      released);
        }
    }
    if (!err.empty()) {
        throwIllegalArgument(env, err);
        return nullptr;
    }
    return mergeStats(env, r);
}

extern "C" JNIEXPORT void JNICALL Java_com_redline_camera_nativebridge_RedlineNative_nativeMergerDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<MergerHandle*>(handle);
}

extern "C" JNIEXPORT jint JNICALL Java_com_redline_camera_nativebridge_RedlineNative_nativeBurstThreads(JNIEnv*, jclass) {
    return redline::burstRecommendedThreads();
}

extern "C" JNIEXPORT void JNICALL Java_com_redline_camera_nativebridge_RedlineNative_nativeBurstWorkerNice(JNIEnv*, jclass, jint nice) {
    redline::burstSetWorkerNice(nice);
}

// [alignedFraction, noiseRatio, ms]; throws IllegalArgumentException on an internal error.
extern "C" JNIEXPORT jfloatArray JNICALL Java_com_redline_camera_nativebridge_RedlineNative_nativeBurstSelfCheck(JNIEnv* env, jclass,
                                                                                                             jint threads) {
    redline::BurstSelfCheck c;
    const std::string err = redline::burstSelfCheck(threads, c);
    if (!err.empty()) {
        throwIllegalArgument(env, err);
        return nullptr;
    }
    jfloatArray res = env->NewFloatArray(3);
    if (res == nullptr) return nullptr;
    const jfloat v[3] = {static_cast<jfloat>(c.alignedFraction), static_cast<jfloat>(c.noiseRatio), static_cast<jfloat>(c.ms)};
    env->SetFloatArrayRegion(res, 0, 3, v);
    return res;
}
