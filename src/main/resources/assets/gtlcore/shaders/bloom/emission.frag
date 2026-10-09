#version 150
uniform sampler2D Atlas;
uniform sampler2D SceneDepth;
uniform vec2 DepthScale;
uniform vec2 DepthOffset;
uniform mat4 Projection;
uniform int Textured;
uniform float FogStart;
uniform float FogEnd;
in vec4 vertexColor;
in vec2 texCoord;
in float fogDistance;
out vec4 fragColor;
void main() {
    vec4 color = vertexColor;
    if (Textured != 0) color *= texture(Atlas, texCoord);
    if (color.a < 0.1) discard;
    ivec2 depthSize = textureSize(SceneDepth, 0);
    vec2 depthPixel = gl_FragCoord.xy * DepthScale + DepthOffset * vec2(depthSize);
    float sceneDepth = texelFetch(SceneDepth, clamp(ivec2(depthPixel), ivec2(0), depthSize - 1), 0).r;
    // GTM overlays extend 0.001 blocks beyond their casing. Compact terrain vertices can
    // round each axis by 1/2048 block; this float VBO must tolerate that same-surface error.
    // Convert a bounded 1/1000 block allowance to depth units instead of using a large
    // constant screen-depth bias (which would let distant lights show through walls).
    float quantizationBias;
    if (abs(Projection[2][3]) > 0.5) {
        float distance = abs(Projection[3][2] / (2.0 * gl_FragCoord.z - 1.0 + Projection[2][2]));
        quantizationBias = abs(Projection[3][2]) * 0.001
                / (2.0 * distance * max(distance - 0.001, 0.001));
    } else {
        quantizationBias = abs(Projection[2][2]) * 0.0005;
    }
    float epsilon = max(0.0000002, fwidth(gl_FragCoord.z) * 0.75 / min(DepthScale.x, DepthScale.y)) + quantizationBias;
    if (gl_FragCoord.z > sceneDepth + epsilon) discard;
    float fog = clamp((FogEnd - fogDistance) / max(FogEnd - FogStart, 0.001), 0.0, 1.0);
    vec3 radiance = color.rgb * color.a * fog;
    // Resolve visibility at the full-resolution source, including opaque frame rails.
    // The following optical blur carries only light, never a brightness-weighted depth
    // or the background's depth; either would make unrelated halos cut each other off.
    fragColor = vec4(radiance, 0.0);
}
