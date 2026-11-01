// JNI bindings for com.redline.camera.nativebridge.RedlineNative.
#include <android/bitmap.h>
#include <jni.h>

#include <cstdint>
#include <cstdlib>
#include <new>
#include <string>
#include <vector>

#include "burst_merge.h"
#include "cube_parser.h"
#include "raw_pipeline.h"
#include "superres.h"

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
    jfloatArray out = env->NewFloatArray(4);
    if (out == nullptr) return nullptr;
    const jfloat v[4] = {st.medianY, st.clippedFraction, static_cast<jfloat>(st.cells), st.p98Y};  // B23c: + p98Y
    env->SetFloatArrayRegion(out, 0, 4, v);
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
    if (err.empty()) {
        const redline::FrameWorkScope frameWork;  // B23b.1: the merge tail steps aside meanwhile
        err = h->merger.add({data, rowStrideBytes / 2}, released);
    }
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

// B23b.1: the calling lane's nice for the pool jobs it starts, and whether it is the merge tail (sliced, yields to adds).
extern "C" JNIEXPORT void JNICALL Java_com_redline_camera_nativebridge_RedlineNative_nativeBurstLane(JNIEnv*, jclass, jint nice,
                                                                                                    jboolean tail) {
    redline::burstSetLane(nice, tail == JNI_TRUE);
}

// B23b.1: [pool worker nice read back, last job nice, tail yields, tail yield ms, tail slices, render thread nice read back].
extern "C" JNIEXPORT jintArray JNICALL Java_com_redline_camera_nativebridge_RedlineNative_nativeWorkerStats(JNIEnv* env, jclass) {
    const redline::WorkerStats w = redline::burstWorkerStats();
    const std::vector<int> v = {w.workerNiceSeen, w.jobNiceLast, w.tailYields,
                                w.tailYieldMs,    w.tailSlices,  redline::renderThreadNiceSeen()};
    return intArray(env, v);
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

// ---------------------------------------------------------------------------------------------------- B23 super-resolution

namespace {

// A streaming super-resolution merge and, after finish, its output (planar float camera RGB at the output size).
struct SrHandle {
    redline::SuperResMerger merger;
    int width = 0;
    int height = 0;
    int outWidth = 0;
    int outHeight = 0;
    std::vector<float> image;
    bool finished = false;
    redline::LinearRgb linear() const {
        const std::size_t plane = static_cast<std::size_t>(outWidth) * outHeight;
        return redline::LinearRgb{image.data(), outWidth, outHeight, static_cast<std::size_t>(outWidth), plane};
    }
};

// Stats as floats: [reference, frames, region l t w h, tilesX, tilesY, coverage, sigmaG, sigmaRB, copyMs, referenceMs,
// alignMs, robustMs, gatherMs, threads, maxRegionsHeld, peakNativeMb, outputMb, then per alternate frame: index,
// meanDispPx, meanSubpixPx, meanRobustness, rejectedFraction, modelResolved, model (0/1), motionY, motionX, alignMs,
// robustMs]. Keep in step with RedlineNative.SR_HEADER / SR_PER_FRAME.
jfloatArray srStats(JNIEnv* env, const redline::SuperResResult& r, double outputMb) {
    std::vector<jfloat> v = {static_cast<jfloat>(r.reference), static_cast<jfloat>(r.framesAdded), static_cast<jfloat>(r.region[0]),
                             static_cast<jfloat>(r.region[1]), static_cast<jfloat>(r.region[2]), static_cast<jfloat>(r.region[3]),
                             static_cast<jfloat>(r.tilesX), static_cast<jfloat>(r.tilesY), static_cast<jfloat>(r.coverage),
                             static_cast<jfloat>(r.sigmaG), static_cast<jfloat>(r.sigmaRB), static_cast<jfloat>(r.copyMs),
                             static_cast<jfloat>(r.referenceMs), static_cast<jfloat>(r.alignMs), static_cast<jfloat>(r.robustMs),
                             static_cast<jfloat>(r.gatherMs), static_cast<jfloat>(r.threads), static_cast<jfloat>(r.maxRegionsHeld),
                             static_cast<jfloat>(r.peakNativeMb), static_cast<jfloat>(outputMb)};
    for (const auto& f : r.frames) {
        v.insert(v.end(), {static_cast<jfloat>(f.index), static_cast<jfloat>(f.meanDispPx), static_cast<jfloat>(f.meanSubpixPx),
                           static_cast<jfloat>(f.meanRobustness), static_cast<jfloat>(f.rejectedFraction),
                           static_cast<jfloat>(f.modelResolved), f.model ? 1.0f : 0.0f, static_cast<jfloat>(f.motionPx[0]),
                           static_cast<jfloat>(f.motionPx[1]), static_cast<jfloat>(f.alignMs), static_cast<jfloat>(f.robustMs)});
    }
    jfloatArray res = env->NewFloatArray(static_cast<jsize>(v.size()));
    if (res == nullptr) return nullptr;
    env->SetFloatArrayRegion(res, 0, static_cast<jsize>(v.size()), v.data());
    return res;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL Java_com_redline_camera_nativebridge_RedlineNative_nativeSrCreate(
    JNIEnv* env, jclass, jint width, jint height, jint cfa, jfloatArray black, jfloat white, jdouble noiseS, jdouble noiseO,
    jfloatArray shading, jint shadingRows, jint shadingCols, jintArray crop, jint outWidth, jint outHeight, jint reference, jint threads) {
    const Floats b(env, black), sh(env, shading);
    std::string err;
    redline::SuperResParams p;
    if (b.size() != 4) err = "black level needs 4 values";
    if (err.empty() && (crop == nullptr || env->GetArrayLength(crop) != 4)) err = "crop needs 4 values";
    if (err.empty() && sh.get() != nullptr && (shadingRows <= 0 || shadingCols <= 0 || sh.size() != shadingRows * shadingCols * 4)) {
        err = "bad lens shading map";
    }
    auto* h = new SrHandle();
    if (err.empty()) {
        jint c[4];
        env->GetIntArrayRegion(crop, 0, 4, c);
        p.width = width;
        p.height = height;
        p.cfa = cfa;
        for (int i = 0; i < 4; ++i) p.black[i] = b.get()[i];
        p.white = white;
        p.noiseS = noiseS;
        p.noiseO = noiseO;
        if (sh.get() != nullptr) {
            p.shading = sh.get();  // copied by begin
            p.shadingRows = shadingRows;
            p.shadingCols = shadingCols;
        }
        p.crop = redline::CropRect{c[0], c[1], c[2], c[3]};
        p.outWidth = outWidth;
        p.outHeight = outHeight;
        p.reference = reference;
        p.threads = threads;
        err = h->merger.begin(p);
    }
    if (!err.empty()) {
        delete h;
        throwIllegalArgument(env, err);
        return 0;
    }
    h->width = width;
    h->height = height;
    h->outWidth = outWidth;
    h->outHeight = outHeight;
    return reinterpret_cast<jlong>(h);
}

// Copies the frame's region; returns the indices of frames the merger no longer needs (always the frame just added).
extern "C" JNIEXPORT jintArray JNICALL Java_com_redline_camera_nativebridge_RedlineNative_nativeSrAdd(JNIEnv* env, jclass, jlong handle,
                                                                                                     jobject frame, jint rowStrideBytes) {
    auto* h = reinterpret_cast<SrHandle*>(handle);
    std::string err;
    std::vector<int> released;
    if (h == nullptr) err = "no merger";
    const std::uint16_t* data = err.empty() ? frameData(env, frame, h->width, h->height, rowStrideBytes, err) : nullptr;
    if (err.empty()) {
        const redline::FrameWorkScope frameWork;  // B23b.1: the merge tail steps aside meanwhile
        err = h->merger.add({data, rowStrideBytes / 2}, released);
    }
    if (!err.empty()) {
        throwIllegalArgument(env, err);
        return nullptr;
    }
    return intArray(env, released);
}

// Gathers all frames into the handle's output image (with the lens shading map of a burst frame's result, or none);
// returns the stats.
extern "C" JNIEXPORT jfloatArray JNICALL Java_com_redline_camera_nativebridge_RedlineNative_nativeSrFinish(
    JNIEnv* env, jclass, jlong handle, jfloatArray shading, jint shadingRows, jint shadingCols) {
    auto* h = reinterpret_cast<SrHandle*>(handle);
    std::string err;
    if (h == nullptr) err = "no merger";
    redline::SuperResResult r;
    {
        const Floats sh(env, shading);
        if (err.empty() && sh.get() != nullptr) {
            if (shadingRows <= 0 || shadingCols <= 0 || sh.size() != shadingRows * shadingCols * 4) err = "bad lens shading map";
            if (err.empty()) err = h->merger.setShading(sh.get(), shadingRows, shadingCols);
        }
    }
    if (err.empty()) {
        const std::size_t plane = static_cast<std::size_t>(h->outWidth) * h->outHeight;
        try {
            h->image.assign(plane * 3, 0.0f);
        } catch (const std::bad_alloc&) {
            err = "out of memory for the output";
        }
        if (err.empty()) err = h->merger.finish(h->image.data(), static_cast<std::size_t>(h->outWidth), plane, r);
        if (err.empty()) h->finished = true;
    }
    if (!err.empty()) {
        throwIllegalArgument(env, err);
        return nullptr;
    }
    return srStats(env, r, static_cast<double>(h->image.size() * sizeof(float)) / (1024.0 * 1024.0));
}

// Renders the finished merge into `bitmap` (ARGB_8888 at the output size) through B21 stages 4-8.
extern "C" JNIEXPORT jstring JNICALL Java_com_redline_camera_nativebridge_RedlineNative_nativeSrRender(
    JNIEnv* env, jclass, jlong handle, jfloatArray matrix, jfloatArray neutral, jfloat gain, jfloatArray curve, jfloat curveLo,
    jfloat curveHi, jfloatArray lut, jint lutSize, jint threads, jobject bitmap) {
    auto* h = reinterpret_cast<SrHandle*>(handle);
    if (h == nullptr || !h->finished) return env->NewStringUTF("no finished merge");
    const Floats m(env, matrix), n(env, neutral), cv(env, curve), lt(env, lut);
    redline::RenderSpec spec;
    if (m.size() != 9 || n.size() != 3) return env->NewStringUTF("matrix needs 9 values and neutral 3");
    for (int i = 0; i < 9; ++i) spec.colour.matrix[i] = m.get()[i];
    for (int i = 0; i < 3; ++i) spec.colour.neutral[i] = n.get()[i];
    if (cv.get() == nullptr || lt.get() == nullptr || lt.size() != lutSize * lutSize * lutSize * 3) {
        return env->NewStringUTF("curve or LUT missing or mis-sized");
    }
    AndroidBitmapInfo info{};
    if (bitmap == nullptr || AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return env->NewStringUTF("output must be an ARGB_8888 bitmap");
    }
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
    const std::string err = redline::renderLinear(h->linear(), spec, static_cast<std::uint8_t*>(pixels), info.stride);
    AndroidBitmap_unlockPixels(env, bitmap);
    return err.empty() ? nullptr : env->NewStringUTF(err.c_str());
}

// [medianY, clippedFraction, cells, p98Y] of the finished merge.
extern "C" JNIEXPORT jfloatArray JNICALL Java_com_redline_camera_nativebridge_RedlineNative_nativeSrStats(JNIEnv* env, jclass, jlong handle,
                                                                                                        jfloatArray matrix,
                                                                                                        jfloatArray neutral, jint step) {
    auto* h = reinterpret_cast<SrHandle*>(handle);
    const Floats m(env, matrix), n(env, neutral);
    std::string err;
    if (h == nullptr || !h->finished) err = "no finished merge";
    redline::ColourSpec colour;
    if (err.empty() && (m.size() != 9 || n.size() != 3)) err = "matrix needs 9 values and neutral 3";
    redline::RawStats st;
    if (err.empty()) {
        for (int i = 0; i < 9; ++i) colour.matrix[i] = m.get()[i];
        for (int i = 0; i < 3; ++i) colour.neutral[i] = n.get()[i];
        err = redline::linearStats(h->linear(), colour, step, st);
    }
    if (!err.empty()) {
        throwIllegalArgument(env, err);
        return nullptr;
    }
    jfloatArray out = env->NewFloatArray(4);
    if (out == nullptr) return nullptr;
    const jfloat v[4] = {st.medianY, st.clippedFraction, static_cast<jfloat>(st.cells), st.p98Y};  // B23c: + p98Y
    env->SetFloatArrayRegion(out, 0, 4, v);
    return out;
}

extern "C" JNIEXPORT void JNICALL Java_com_redline_camera_nativebridge_RedlineNative_nativeSrDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<SrHandle*>(handle);
}

// [flowErrorPx, coverage, rmseMerge, rmseSingle, gain, ms]; throws IllegalArgumentException on an internal error.
extern "C" JNIEXPORT jfloatArray JNICALL Java_com_redline_camera_nativebridge_RedlineNative_nativeSrSelfCheck(JNIEnv* env, jclass,
                                                                                                          jint threads) {
    redline::SuperResSelfCheck c;
    const std::string err = redline::superResSelfCheck(threads, c);
    if (!err.empty()) {
        throwIllegalArgument(env, err);
        return nullptr;
    }
    jfloatArray res = env->NewFloatArray(6);
    if (res == nullptr) return nullptr;
    const jfloat v[6] = {static_cast<jfloat>(c.flowErrorPx), static_cast<jfloat>(c.coverage), static_cast<jfloat>(c.rmseMerge),
                         static_cast<jfloat>(c.rmseSingle), static_cast<jfloat>(c.gain), static_cast<jfloat>(c.ms)};
    env->SetFloatArrayRegion(res, 0, 6, v);
    return res;
}
