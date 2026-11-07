package com.redline.camera.video

/**
 * GLSL ES 3.00 for the B40 video chain. Pass A (camera OES -> RGBA16F intermediate): the 8-bit ISP value through the
 * inverse of the flat ISP curve (1D table) and the colour matrix, so the intermediate holds linear light in float. Pass B
 * (intermediate -> encoder surface, and again at screen size for the viewfinder): exposure gain, the fitted Standard tone
 * on a log2 grid (1D table), then the stills' 33^3 residual LUT, the same steps the RAW pipeline runs per channel. A fused
 * program does both in one pass when the GPU cannot render to RGBA16F. [VideoChain] is the CPU reference.
 */
object VideoShaders {
    private const val DECODE = """
uniform sampler2D uInv;
uniform float uInvScale;
uniform float uInvOffset;
uniform mat3 uMatrix;

vec3 toLinear(vec3 e) {
    e = clamp(e, 0.0, 1.0) * uInvScale + uInvOffset;
    vec3 l = vec3(texture(uInv, vec2(e.r, 0.5)).r, texture(uInv, vec2(e.g, 0.5)).r, texture(uInv, vec2(e.b, 0.5)).r);
    return uMatrix * l;
}
"""

    private const val LOOK = """
precision highp sampler3D;
uniform sampler2D uTone;
uniform float uToneScale;
uniform float uToneOffset;
uniform float uGain;
uniform float uFloor;
uniform float uTone0;
uniform float uEvMin;
uniform float uEvSpanInv;
uniform highp sampler3D uLut;
uniform vec3 uDomainMin;
uniform vec3 uDomainScale;
uniform float uLutScale;
uniform float uLutOffset;
uniform float uLook;

float tone1(float lin) {
    float x = max(lin * uGain, 0.0);
    if (x < uFloor) return x / uFloor * uTone0;
    float t = clamp((log2(x) - uEvMin) * uEvSpanInv, 0.0, 1.0);
    return texture(uTone, vec2(t * uToneScale + uToneOffset, 0.5)).r;
}

vec3 srgbEncode(vec3 l) {
    l = clamp(l, 0.0, 1.0);
    return mix(l * 12.92, 1.055 * pow(max(l, vec3(0.0031308)), vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), l));
}

vec3 look(vec3 lin) {
    if (uLook < 0.5) return srgbEncode(lin);
    vec3 toned = vec3(tone1(lin.r), tone1(lin.g), tone1(lin.b));
    vec3 idx = clamp((toned - uDomainMin) * uDomainScale, 0.0, 1.0);
    return clamp(texture(uLut, idx * uLutScale + uLutOffset).rgb, 0.0, 1.0);
}
"""

    private const val HEAD_OES = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uInput;
in vec2 vTexCoord;
out vec4 fragColor;
"""

    private const val HEAD_2D = """#version 300 es
precision highp float;
uniform sampler2D uInput;
in vec2 vTexCoord;
out vec4 fragColor;
"""

    private const val MAIN_DECODE = """
void main() {
    fragColor = vec4(toLinear(texture(uInput, vTexCoord).rgb), 1.0);
}
"""

    private const val MAIN_LOOK = """
void main() {
    fragColor = vec4(look(texture(uInput, vTexCoord).rgb), 1.0);
}
"""

    private const val MAIN_FUSED = """
void main() {
    fragColor = vec4(look(toLinear(texture(uInput, vTexCoord).rgb)), 1.0);
}
"""

    const val DECODE_OES = HEAD_OES + DECODE + MAIN_DECODE
    const val DECODE_2D = HEAD_2D + DECODE + MAIN_DECODE
    const val LOOK_2D = HEAD_2D + LOOK + MAIN_LOOK
    const val FUSED_OES = HEAD_OES + DECODE + LOOK + MAIN_FUSED
    const val FUSED_2D = HEAD_2D + DECODE + LOOK + MAIN_FUSED
}
