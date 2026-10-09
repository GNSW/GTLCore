#version 150
// Scale weighting derived from Shimmer (MIT, Low-Drag-MC).
uniform sampler2D Blur0;
uniform sampler2D Blur1;
uniform sampler2D Blur2;
uniform sampler2D Blur3;
uniform float Strength;
uniform float Radius;
in vec2 texCoord;
out vec4 fragColor;
float weight(float factor) { return mix(factor, 1.2 - factor, Radius); }
vec3 toneMap(vec3 c) {
    // Independent, monotone channels: adding a blinking red light must not dim an
    // existing blue halo through a shared luminance denominator.
    return c / (c + 1.0);
}
void main() {
    // Only visible emission entered the pyramid. Composite its continuous halo
    // without reinterpreting the destination's depth as the light's origin.
    vec3 bloom = Strength * (
        weight(1.0) * texture(Blur0, texCoord).rgb +
        weight(0.8) * texture(Blur1, texCoord).rgb +
        weight(0.6) * texture(Blur2, texCoord).rgb +
        weight(0.4) * texture(Blur3, texCoord).rgb);
    // Add bloom only. Original world colour and alpha are preserved by the blend state.
    fragColor = vec4(toneMap(bloom), 0.0);
}
