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
vec4 surfaceBounds;
bool boundedSurface;
float weight(float factor) { return mix(factor, 1.2 - factor, Radius); }
vec3 toneMap(vec3 c) {
    // Independent, monotone channels: adding a blinking red light must not dim an
    // existing blue halo through a shared luminance denominator.
    return c / (c + 1.0);
}
vec3 receiverPlane(vec2 outputSize) {
    ivec2 size = textureSize(SceneDepth, 0);
    vec2 mapped = (texCoord * DepthScale + DepthOffset) * vec2(size);
    ivec2 first = clamp(ivec2(ceil(DepthOffset * vec2(size) - 0.5)), ivec2(0), size - 1);
    ivec2 last = clamp(ivec2(ceil((DepthOffset + DepthScale) * vec2(size) - 0.5)) - 1, first, size - 1);
    ivec2 pixel = clamp(ivec2(mapped), first, last);
    float z = texelFetch(SceneDepth, pixel, 0).r;
    vec2 slope = vec2(0.0);
    surfaceBounds = vec4(0.0, 0.0, 1.0, 1.0);
    // Flat screen-depth quads (especially sky) need no neighbour reads. Reduced
    // depth viewports must reconstruct slopes even on repeated nearest texels.
    vec2 change = vec2(dFdx(z), dFdy(z));
    if (any(lessThan(vec2(size) * DepthScale, outputSize)) || any(notEqual(change, vec2(0.0)))) {
        ivec2 stride = ivec2(1);
        ivec2 lo = max(pixel - stride, first), hi = min(pixel + stride, last);
        vec2 before = (z - vec2(texelFetch(SceneDepth, ivec2(lo.x, pixel.y), 0).r,
                               texelFetch(SceneDepth, ivec2(pixel.x, lo.y), 0).r)) / max(vec2(pixel - lo), vec2(1.0));
        vec2 after = (vec2(texelFetch(SceneDepth, ivec2(hi.x, pixel.y), 0).r,
                          texelFetch(SceneDepth, ivec2(pixel.x, hi.y), 0).r) - z) / max(vec2(hi - pixel), vec2(1.0));
        // Screen depth is affine on a plane. Use the smoother side of each axis so a
        // frame edge/depth discontinuity cannot turn into a permissive depth slope.
        // The viewport boundary has only one side, including reduced shader viewports.
        before = mix(before, after, equal(pixel, lo));
        after = mix(after, before, equal(pixel, hi));
        // Propagate the existing half-float distance precision into screen depth;
        // depth-buffer roundoff alone is too small for orthographic projections.
        float distanceRounding = abs(Projection[2][3]) > 0.5
                ? abs(2.0 * z - 1.0 + Projection[2][2]) * 0.0005 : abs(z) * 0.001;
        vec2 rounding = vec2(max(4.0 * 1.1920929e-7 * abs(z), distanceRounding));
        slope = max(sign(before * after), vec2(0.0)) * sign(before) * min(abs(before), abs(after));
        slope = mix(slope, (before + after) * 0.5, lessThanEqual(abs(before - after), rounding));
        // Longer baselines reduce depth-buffer rounding when this plane is used by
        // a wide blur kernel. Accept them only while they stay on the local plane.
        ivec2 farLo = max(pixel - 4, first), farHi = min(pixel + 4, last);
        vec2 leftSpan = max(vec2(pixel - farLo), vec2(1.0));
        vec2 rightSpan = max(vec2(farHi - pixel), vec2(1.0));
        vec2 leftSlope = (z - vec2(texelFetch(SceneDepth, ivec2(farLo.x, pixel.y), 0).r,
                                  texelFetch(SceneDepth, ivec2(pixel.x, farLo.y), 0).r)) / leftSpan;
        vec2 rightSlope = (vec2(texelFetch(SceneDepth, ivec2(farHi.x, pixel.y), 0).r,
                                texelFetch(SceneDepth, ivec2(pixel.x, farHi.y), 0).r) - z) / rightSpan;
        bvec2 leftSafe = lessThanEqual(abs(leftSlope - slope) * leftSpan, rounding);
        bvec2 rightSafe = lessThanEqual(abs(rightSlope - slope) * rightSpan, rounding);
        vec2 leftWeight = mix(vec2(0.0), leftSpan, leftSafe);
        vec2 rightWeight = mix(vec2(0.0), rightSpan, rightSafe);
        vec2 refined = (leftSlope * leftWeight + rightSlope * rightWeight) / max(leftWeight + rightWeight, vec2(1.0));
        // Three stair-stepped surfaces can mimic a slope at adjacent pixels.
        // Without a longer same-plane witness, use a flat conservative receiver.
        slope = mix(vec2(0.0), refined, greaterThan(leftWeight + rightWeight, vec2(0.0)));
        surfaceBounds.xy = mix(vec2(0.0), (vec2(pixel) / vec2(size) - DepthOffset) / DepthScale,
                               lessThan(before - slope, -rounding));
        surfaceBounds.zw = mix(vec2(1.0), ((vec2(pixel) + 1.0) / vec2(size) - DepthOffset) / DepthScale,
                               greaterThan(after - slope, rounding));
        // A longer baseline that reaches a deeper surface limits wide blur taps
        // to the nearest verified interval. Test complete source footprints below.
        surfaceBounds.xy = max(surfaceBounds.xy,
                mix(vec2(0.0), (vec2(lo) / vec2(size) - DepthOffset) / DepthScale,
                    lessThan((leftSlope - slope) * leftSpan, -rounding)));
        surfaceBounds.zw = min(surfaceBounds.zw,
                mix(vec2(1.0), ((vec2(hi) + 1.0) / vec2(size) - DepthOffset) / DepthScale,
                    greaterThan((rightSlope - slope) * rightSpan, rounding)));
        // An isolated foreground strip cannot borrow a wider source footprint.
        // A hole surrounded by nearer geometry is different: retain its own halo.
        bvec2 unsupported = equal(leftWeight + rightWeight, vec2(0.0));
        bvec2 foreground = greaterThan(max(-before, after), rounding);
        bvec2 isolated = bvec2(unsupported.x && foreground.x, unsupported.y && foreground.y);
        surfaceBounds.xy = max(surfaceBounds.xy,
                mix(vec2(0.0), (vec2(pixel) / vec2(size) - DepthOffset) / DepthScale, isolated));
        surfaceBounds.zw = min(surfaceBounds.zw,
                mix(vec2(1.0), ((vec2(pixel) + 1.0) / vec2(size) - DepthOffset) / DepthScale, isolated));
    }
    boundedSurface = any(greaterThan(surfaceBounds.xy, vec2(0.0))) || any(lessThan(surfaceBounds.zw, vec2(1.0)));
    vec3 plane = vec3(z + dot(slope, mapped - vec2(pixel) - 0.5), slope * vec2(size) * DepthScale);
    if (abs(Projection[2][3]) > 0.5) {
        // Inverse view distance is affine too; transform once instead of dividing per tap.
        plane.x = (2.0 * plane.x - 1.0 + Projection[2][2]) / Projection[3][2];
        plane.yz *= 2.0 / Projection[3][2];
    } else plane *= 2.0 / max(abs(Projection[2][2]), 0.000001);
    return plane;
}
vec3 visibleTexel(sampler2D source, ivec2 pixel, ivec2 size, vec3 plane) {
    pixel = clamp(pixel, ivec2(0), size - 1);
    vec2 uv = (vec2(pixel) + 0.5) / vec2(size);
    // A low-resolution texel straddling a foreground edge already mixes surfaces.
    // Require its footprint, not only its centre, to stay on the receiving side.
    vec2 footprint = 0.5 / vec2(size);
    if (boundedSurface && (any(lessThan(uv - footprint, surfaceBounds.xy)) ||
                           any(greaterThan(uv + footprint, surfaceBounds.zw)))) return vec3(0.0);
    vec4 light = texelFetch(source, pixel, 0);
    float expected = plane.x + dot(plane.yz, uv - texCoord);
    float difference, tolerance;
    if (abs(Projection[2][3]) > 0.5) {
        float inverse = max(abs(expected), 1.0 / 65504.0);
        difference = light.a * inverse - 1.0;
        tolerance = max(0.002 * inverse, 0.001);
    } else {
        float receiver = min(expected, 65504.0);
        difference = light.a - receiver;
        tolerance = max(0.002, receiver * 0.001);
    }
    return light.rgb * (1.0 - smoothstep(tolerance, tolerance * 2.0, difference));
}
vec3 visibleBloom(sampler2D source, vec3 plane) {
    ivec2 size = textureSize(source, 0);
    vec2 pixel = texCoord * vec2(size) - 0.5;
    ivec2 origin = ivec2(floor(pixel));
    vec2 fraction = fract(pixel);
    return mix(mix(visibleTexel(source, origin, size, plane),
                   visibleTexel(source, origin + ivec2(1, 0), size, plane), fraction.x),
               mix(visibleTexel(source, origin + ivec2(0, 1), size, plane),
                   visibleTexel(source, origin + ivec2(1, 1), size, plane), fraction.x), fraction.y);
}
void main() {
    vec3 plane = receiverPlane(vec2(textureSize(SceneDepth, 0)));
    vec3 bloom = Strength * (
        weight(1.0) * visibleBloom(Blur0, plane) +
        weight(0.8) * visibleBloom(Blur1, plane) +
        weight(0.6) * visibleBloom(Blur2, plane) +
        weight(0.4) * visibleBloom(Blur3, plane));
    // Add bloom only. Original world colour and alpha are preserved by the blend state.
    fragColor = vec4(toneMap(bloom), 0.0);
}
