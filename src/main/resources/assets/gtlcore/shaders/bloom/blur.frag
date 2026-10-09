#version 150
// Gaussian kernel derived from Shimmer's separable blur (MIT, Low-Drag-MC).
uniform sampler2D Source;
uniform vec2 OutSize;
uniform vec2 BlurDir;
uniform int Radius;
in vec2 texCoord;
out vec4 fragColor;
float gaussianPdf(float x, float sigma) {
    return 0.39894 * exp(-0.5 * x * x / (sigma * sigma)) / sigma;
}
void main() {
    // Emission has already rejected hidden source pixels. Bloom is optical scatter
    // of that visible image, not light attached to the background's surfaces.
    // Testing scene depth again would cut halos at bevels, create black speckles
    // at raster edges and make the separable passes depend on their direction.
    float sigma = float(Radius);
    float weights = gaussianPdf(0.0, sigma);
    vec3 result = texture(Source, texCoord).rgb * weights;
    vec2 step = BlurDir / OutSize;
    for (int i = 1; i < Radius; ++i) {
        float w = gaussianPdf(float(i), sigma);
        vec2 offset = step * float(i);
        result += (texture(Source, texCoord + offset).rgb + texture(Source, texCoord - offset).rgb) * w;
        weights += 2.0 * w;
    }
    fragColor = vec4(result / weights, 0.0);
}
