package com.redline.camera.look

/**
 * GLSL ES 3.00 for the look preview (B20). One fragment program, two input kinds: the camera's external OES
 * texture for the live preview, and a plain 2D texture for the start-up self-check against [LutMath].
 *
 * Chain: encoded preview value -> 33^3 LUT (GL_TEXTURE_3D, hardware trilinear, half-texel scale/offset) ->
 * sRGB decode of input and LUT result -> mix by strength in linear light -> sRGB encode. B23b.1: at strength 1 the
 * output is the LUT result and at strength 0 the input (the decode/encode pair cancels), without the pow() calls.
 */
object LookShaders {
    const val VERTEX = """#version 300 es
layout(location = 0) in vec4 aPosition;
layout(location = 1) in vec4 aTexCoord;
uniform mat4 uTexMatrix;
out vec2 vTexCoord;
void main() {
    gl_Position = aPosition;
    vTexCoord = (uTexMatrix * aTexCoord).xy;
}
"""

    private const val BODY = """
precision highp sampler3D;
uniform highp sampler3D uLut;
uniform float uStrength;
uniform vec3 uDomainMin;
uniform vec3 uDomainScale;
uniform float uLutScale;
uniform float uLutOffset;
in vec2 vTexCoord;
out vec4 fragColor;

vec3 srgbDecode(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));
}

vec3 srgbEncode(vec3 l) {
    l = max(l, vec3(0.0));
    // pow's argument is kept off zero so the unused branch can never be NaN (mix would carry it through).
    return mix(l * 12.92, 1.055 * pow(max(l, vec3(0.0031308)), vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), l));
}

void main() {
    vec3 e = clamp(texture(uInput, vTexCoord).rgb, 0.0, 1.0);
    // B23b.1: strength 0 and 1 (the default) skip the linear-light mix: decode then encode is the identity there.
    if (uStrength <= 0.0) {
        fragColor = vec4(e, 1.0);
        return;
    }
    vec3 idx = clamp((e - uDomainMin) * uDomainScale, 0.0, 1.0);
    vec3 looked = texture(uLut, idx * uLutScale + uLutOffset).rgb;
    if (uStrength >= 1.0) {
        fragColor = vec4(clamp(looked, 0.0, 1.0), 1.0);
        return;
    }
    vec3 lin = mix(srgbDecode(e), srgbDecode(looked), uStrength);
    fragColor = vec4(clamp(srgbEncode(lin), 0.0, 1.0), 1.0);
}
"""

    const val FRAGMENT_OES = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uInput;
""" + BODY

    const val FRAGMENT_2D = """#version 300 es
precision highp float;
uniform sampler2D uInput;
""" + BODY
}
