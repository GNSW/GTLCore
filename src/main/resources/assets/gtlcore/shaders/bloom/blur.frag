#version 150
// Gaussian kernel derived from Shimmer's separable blur (MIT, Low-Drag-MC).
uniform sampler2D Source;
uniform vec2 OutSize;
uniform vec2 BlurDir;
uniform int Radius;
uniform sampler2D SceneDepth;
uniform vec2 DepthScale;
uniform vec2 DepthOffset;
uniform mat4 Projection;
in vec2 texCoord;
out vec4 fragColor;
float gaussianPdf(float x, float sigma) {
    return 0.39894 * exp(-0.5 * x * x / (sigma * sigma)) / sigma;
}
float sceneDistance(vec2 uv) {
    ivec2 size = textureSize(SceneDepth, 0);
    float z = texelFetch(SceneDepth, clamp(ivec2((uv * DepthScale + DepthOffset) * vec2(size)), ivec2(0), size - 1), 0).r;
    return abs(Projection[2][3]) > 0.5
            ? abs(Projection[3][2] / (2.0 * z - 1.0 + Projection[2][2]))
            : 2.0 * z / max(abs(Projection[2][2]), 0.000001);
}
vec4 visibleSample(vec2 uv, float receiver) {
    vec4 light = texture(Source, uv);
    float energy = dot(light.rgb, vec3(0.2126, 0.7152, 0.0722));
    if (energy <= 0.000001) return vec4(0.0);
    float distance = light.a / energy;
    // Small continuous allowance for half-float filtering and gently sloped surfaces.
    // This is a post-blur edge treatment; it does not loosen source visibility testing.
    float tolerance = max(0.02, receiver * 0.02);
    return light * (1.0 - smoothstep(tolerance, tolerance * 2.0, distance - receiver));
}
void main() {
    float sigma = float(Radius);
    float receiver = sceneDistance(texCoord);
    float weights = gaussianPdf(0.0, sigma);
    vec4 result = visibleSample(texCoord, receiver) * weights;
    for (int i = 1; i < Radius; ++i) {
        float w = gaussianPdf(float(i), sigma);
        vec2 offset = BlurDir / OutSize * float(i);
        result += (visibleSample(texCoord + offset, receiver) + visibleSample(texCoord - offset, receiver)) * w;
        weights += 2.0 * w;
    }
    fragColor = result / weights;
}
