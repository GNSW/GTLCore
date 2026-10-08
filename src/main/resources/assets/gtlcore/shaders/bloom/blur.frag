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
    float distance = abs(Projection[2][3]) > 0.5
            ? abs(Projection[3][2] / (2.0 * z - 1.0 + Projection[2][2]))
            : 2.0 * z / max(abs(Projection[2][2]), 0.000001);
    return min(distance, 65504.0);
}
vec3 visibleTexel(ivec2 pixel, ivec2 size, float receiver) {
    vec4 light = texelFetch(Source, clamp(pixel, ivec2(0), size - 1), 0);
    // Allow half-float depth rounding, not a percentage of the scene's geometry.
    // A 2% allowance lets light behind nearby frame rails cross them at a distance.
    float tolerance = max(0.002, receiver * 0.001);
    return light.rgb * (1.0 - smoothstep(tolerance, tolerance * 2.0, light.a - receiver));
}
vec3 visibleSample(vec2 uv, float receiver) {
    ivec2 size = textureSize(Source, 0);
    vec2 pixel = uv * vec2(size) - 0.5;
    ivec2 origin = ivec2(floor(pixel));
    vec2 fraction = fract(pixel);
    // Test each depth before interpolation. Hardware bilinear filtering mixes unrelated
    // surfaces first, so one distant source can otherwise reject the whole mixed sample.
    return mix(mix(visibleTexel(origin, size, receiver),
                   visibleTexel(origin + ivec2(1, 0), size, receiver), fraction.x),
               mix(visibleTexel(origin + ivec2(0, 1), size, receiver),
                   visibleTexel(origin + ivec2(1, 1), size, receiver), fraction.x), fraction.y);
}
void main() {
    float sigma = float(Radius);
    float receiver = sceneDistance(texCoord);
    float weights = gaussianPdf(0.0, sigma);
    vec3 result;
    ivec2 size = textureSize(Source, 0);
    if (all(equal(size, ivec2(OutSize)))) {
        // Same-size passes sample exact texel centres. In particular, each vertical
        // pass needs one depth-tested fetch per tap, not four bilinear neighbours.
        // Integer coordinates also avoid round-off around texel boundaries.
        ivec2 pixel = ivec2(gl_FragCoord.xy);
        result = visibleTexel(pixel, size, receiver) * weights;
        for (int i = 1; i < Radius; ++i) {
            float w = gaussianPdf(float(i), sigma);
            ivec2 offset = ivec2(BlurDir) * i;
            result += (visibleTexel(pixel + offset, size, receiver) + visibleTexel(pixel - offset, size, receiver)) * w;
            weights += 2.0 * w;
        }
    } else {
        result = visibleSample(texCoord, receiver) * weights;
        for (int i = 1; i < Radius; ++i) {
            float w = gaussianPdf(float(i), sigma);
            vec2 offset = BlurDir / OutSize * float(i);
            result += (visibleSample(texCoord + offset, receiver) + visibleSample(texCoord - offset, receiver)) * w;
            weights += 2.0 * w;
        }
    }
    // The filtered light is now at this receiving surface. Its next visibility test uses
    // that fixed scene depth, never a depth whose value changes with nearby emitters.
    fragColor = vec4(result / weights, receiver);
}
