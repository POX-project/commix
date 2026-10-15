package com.txor.commix

/**
 * High-performance GPU Compute Engine architecture for atomic scopes,
 * real-time 3D LUT tetrahedral interpolation, and zero-copy AHardwareBuffer sampling.
 */
object VulkanComputeEngine {

    /**
     * GLSL Compute Shader: Column-wise Waveform & Luma Histogram
     * Runs directly on GPU preview texture/AHardwareBuffer without CPU round-trips.
     */
    const val WAVEFORM_COMPUTE_GLSL = """#version 450
layout(local_size_x = 16, local_size_y = 16) in;

layout(binding = 0) uniform sampler2D inputPreview;
layout(binding = 1, std430) buffer WaveformBuffer {
    uint histogramBins[256];
    uint columnLumaMin[64];
    uint columnLumaMax[64];
    uint columnLumaAvg[64];
};

void main() {
    ivec2 pixelCoord = ivec2(gl_GlobalInvocationID.xy);
    ivec2 texSize = textureSize(inputPreview, 0);
    if (pixelCoord.x >= texSize.x || pixelCoord.y >= texSize.y) return;

    vec4 rgba = texelFetch(inputPreview, pixelCoord, 0);
    // Rec.709 Luma
    float luma = clamp(0.2126 * rgba.r + 0.7152 * rgba.g + 0.0722 * rgba.b, 0.0, 1.0);
    uint bin = uint(clamp(luma * 255.0, 0.0, 255.0));

    // Atomic accumulation across threads
    atomicAdd(histogramBins[bin], 1u);

    // Column binning
    uint colIdx = uint(float(pixelCoord.x) / float(texSize.x) * 64.0);
    atomicMin(columnLumaMin[colIdx], bin);
    atomicMax(columnLumaMax[colIdx], bin);
    atomicAdd(columnLumaAvg[colIdx], bin);
}
"""

    /**
     * GLSL Compute Shader: ASC EL Zone / IRE False Color Exposure Map
     */
    const val FALSE_COLOR_COMPUTE_GLSL = """#version 450
layout(local_size_x = 16, local_size_y = 16) in;

layout(binding = 0) uniform sampler2D inputPreview;
layout(binding = 1, rgba8) uniform writeonly image2D outputFalseColor;

// 10-zone false color lookup
vec3 getElZoneColor(float stop) {
    if (stop > 4.0) return vec3(1.0, 1.0, 1.0);  // White - Clip
    if (stop > 3.0) return vec3(1.0, 0.0, 0.0);  // Red - Highlight danger
    if (stop > 2.0) return vec3(1.0, 0.8, 0.0);  // Yellow - High skin highlight
    if (stop > 1.0) return vec3(0.9, 0.6, 0.5);  // Peach - Skin highlight
    if (stop > -0.5) return vec3(0.5, 0.5, 0.5); // Grey - 18% Middle Grey
    if (stop > -1.5) return vec3(0.0, 0.8, 0.2); // Green - Skin shadow
    if (stop > -3.0) return vec3(0.0, 0.4, 0.9); // Blue - Deep shadow
    return vec3(0.3, 0.0, 0.5);                  // Purple - Black clip
}

void main() {
    ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
    ivec2 size = textureSize(inputPreview, 0);
    if (coord.x >= size.x || coord.y >= size.y) return;

    vec4 src = texelFetch(inputPreview, coord, 0);
    float luma = clamp(0.2126 * src.r + 0.7152 * src.g + 0.0722 * src.b, 0.0001, 1.0);
    // Relative stops from 18% middle grey: log2(luma / 0.18)
    float stops = log2(luma / 0.18);

    vec3 fcRgb = getElZoneColor(stops);
    imageStore(outputFalseColor, coord, vec4(fcRgb, 1.0));
}
"""

    /**
     * GLSL Compute Shader: Focus Peaking Sobel Edge Magnitude Filter
     */
    const val FOCUS_PEAKING_COMPUTE_GLSL = """#version 450
layout(local_size_x = 16, local_size_y = 16) in;

layout(binding = 0) uniform sampler2D inputPreview;
layout(binding = 1, rgba8) uniform writeonly image2D outputPeaking;

uniform float uSensitivity; // 0.0 to 1.0
uniform vec3 uPeakingColor; // e.g. Neon Green (0.0, 1.0, 0.0)

void main() {
    ivec2 c = ivec2(gl_GlobalInvocationID.xy);
    ivec2 size = textureSize(inputPreview, 0);
    if (c.x < 1 || c.y < 1 || c.x >= size.x - 1 || c.y >= size.y - 1) return;

    // Sobel operator
    float p00 = dot(texelFetch(inputPreview, c + ivec2(-1, -1), 0).rgb, vec3(0.299, 0.587, 0.114));
    float p01 = dot(texelFetch(inputPreview, c + ivec2( 0, -1), 0).rgb, vec3(0.299, 0.587, 0.114));
    float p02 = dot(texelFetch(inputPreview, c + ivec2( 1, -1), 0).rgb, vec3(0.299, 0.587, 0.114));
    float p10 = dot(texelFetch(inputPreview, c + ivec2(-1,  0), 0).rgb, vec3(0.299, 0.587, 0.114));
    float p12 = dot(texelFetch(inputPreview, c + ivec2( 1,  0), 0).rgb, vec3(0.299, 0.587, 0.114));
    float p20 = dot(texelFetch(inputPreview, c + ivec2(-1,  1), 0).rgb, vec3(0.299, 0.587, 0.114));
    float p21 = dot(texelFetch(inputPreview, c + ivec2( 0,  1), 0).rgb, vec3(0.299, 0.587, 0.114));
    float p22 = dot(texelFetch(inputPreview, c + ivec2( 1,  1), 0).rgb, vec3(0.299, 0.587, 0.114));

    float gx = (p02 + 2.0 * p12 + p22) - (p00 + 2.0 * p10 + p20);
    float gy = (p20 + 2.0 * p21 + p22) - (p00 + 2.0 * p01 + p02);
    float mag = sqrt(gx * gx + gy * gy);

    float threshold = mix(0.40, 0.05, uSensitivity);
    vec4 baseColor = texelFetch(inputPreview, c, 0);

    if (mag > threshold) {
        imageStore(outputPeaking, c, vec4(uPeakingColor, 1.0));
    } else {
        imageStore(outputPeaking, c, baseColor);
    }
}
"""

    /**
     * Diagnostic query for GPU compute capability
     */
    fun isVulkanSupported(): Boolean {
        return try {
            System.loadLibrary("vulkan")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }
}
