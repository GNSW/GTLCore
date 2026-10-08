#version 150
// Scale weighting derived from Shimmer (MIT, Low-Drag-MC).
uniform sampler2D Blur0;
uniform sampler2D Blur1;
uniform sampler2D Blur2;
uniform sampler2D Blur3;
uniform float Strength;
uniform float Radius;
uniform sampler2D SceneDepth;
uniform vec2 DepthScale;
uniform vec2 DepthOffset;
uniform mat4 Projection;
in vec2 texCoord;
out vec4 fragColor;
float weight(float factor) { return mix(factor, 1.2 - factor, Radius); }
vec3 toneMap(vec3 c) {
    // Independent, monotone channels: adding a blinking red light must not dim an
    // existing blue halo through a shared luminance denominator.
    return c / (c + 1.0);
}
vec3 visibleTexel(sampler2D source, ivec2 pixel, ivec2 size, float receiver) {
    vec4 light = texelFetch(source, clamp(pixel, ivec2(0), size - 1), 0);
    // Match the half-float rounding allowance used by the blur passes.
    float tolerance = max(0.002, receiver * 0.001);
    return light.rgb * (1.0 - smoothstep(tolerance, tolerance * 2.0, light.a - receiver));
}
vec3 visibleBloom(sampler2D source, float receiver) {
    ivec2 size = textureSize(source, 0);
    vec2 pixel = texCoord * vec2(size) - 0.5;
    ivec2 origin = ivec2(floor(pixel));
    vec2 fraction = fract(pixel);
    return mix(mix(visibleTexel(source, origin, size, receiver),
                   visibleTexel(source, origin + ivec2(1, 0), size, receiver), fraction.x),
               mix(visibleTexel(source, origin + ivec2(0, 1), size, receiver),
                   visibleTexel(source, origin + ivec2(1, 1), size, receiver), fraction.x), fraction.y);
}
void main() {
    ivec2 size = textureSize(SceneDepth, 0);
    float z = texelFetch(SceneDepth, clamp(ivec2((texCoord * DepthScale + DepthOffset) * vec2(size)), ivec2(0), size - 1), 0).r;
    float receiver = abs(Projection[2][3]) > 0.5
            ? abs(Projection[3][2] / (2.0 * z - 1.0 + Projection[2][2]))
            : 2.0 * z / max(abs(Projection[2][2]), 0.000001);
    receiver = min(receiver, 65504.0);
    vec3 bloom = Strength * (
        weight(1.0) * visibleBloom(Blur0, receiver) +
        weight(0.8) * visibleBloom(Blur1, receiver) +
        weight(0.6) * visibleBloom(Blur2, receiver) +
        weight(0.4) * visibleBloom(Blur3, receiver));
    // Add bloom only. Original world colour and alpha are preserved by the blend state.
    fragColor = vec4(toneMap(bloom), 0.0);
}
