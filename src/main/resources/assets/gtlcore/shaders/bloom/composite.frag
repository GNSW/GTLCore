#version 150
// Scale weighting and Jodie Reinhard curve derived from Shimmer (MIT, Low-Drag-MC).
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
    float luminance = dot(c, vec3(0.2126, 0.7152, 0.0722));
    vec3 tc = c / (c + 1.0);
    return mix(c / (luminance + 1.0), tc, tc);
}
vec3 visibleBloom(sampler2D source, float receiver) {
    vec4 light = texture(source, texCoord);
    float energy = dot(light.rgb, vec3(0.2126, 0.7152, 0.0722));
    if (energy <= 0.000001) return vec3(0.0);
    float tolerance = max(0.02, receiver * 0.02);
    return light.rgb * (1.0 - smoothstep(tolerance, tolerance * 2.0, light.a / energy - receiver));
}
void main() {
    ivec2 size = textureSize(SceneDepth, 0);
    float z = texelFetch(SceneDepth, clamp(ivec2((texCoord * DepthScale + DepthOffset) * vec2(size)), ivec2(0), size - 1), 0).r;
    float receiver = abs(Projection[2][3]) > 0.5
            ? abs(Projection[3][2] / (2.0 * z - 1.0 + Projection[2][2]))
            : 2.0 * z / max(abs(Projection[2][2]), 0.000001);
    vec3 bloom = Strength * (
        weight(1.0) * visibleBloom(Blur0, receiver) +
        weight(0.8) * visibleBloom(Blur1, receiver) +
        weight(0.6) * visibleBloom(Blur2, receiver) +
        weight(0.4) * visibleBloom(Blur3, receiver));
    // Add bloom only. Original world colour and alpha are preserved by the blend state.
    fragColor = vec4(toneMap(bloom), 0.0);
}
