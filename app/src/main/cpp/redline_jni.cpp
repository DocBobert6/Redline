// JNI bindings for com.redline.camera.nativebridge.RedlineNative.
#include <jni.h>

#include <string>

#include "cube_parser.h"

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
