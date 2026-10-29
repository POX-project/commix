#pragma once

#include <cstdint>
#include <cstddef>
#include <vector>

namespace commix {

enum class MultiExposureBlendMode {
    Additive,
    Average,
    Lighten,
    Darken
};

enum class LongExposureStyle {
    SilkyWater,
    LightTrails,
    StarTrails,
    MovingCrowd
};

struct StarCentroid {
    float x;
    float y;
    float brightness;
};

/**
 * High-performance bare-metal computational photography algorithms in C++20.
 */
class ComputationalPhotoEngine {
public:
    /**
     * Hard ceiling on the frames stackAstroFrames() will median-filter. Beyond
     * this the extra frames are silently discarded, so callers that care about
     * their frame count (the JNI boundary) must reject an over-long request
     * rather than let the engine drop it.
     */
    static constexpr int MAX_STACK_FRAMES = 16;

    // 1. Astrophotography: Star centroid alignment and median noise stacking
    static bool stackAstroFrames(
        const uint8_t* const* framePointers,
        int frameCount,
        int width,
        int height,
        int channels,
        uint8_t* outputBuffer,
        bool deepShadowBoost = false
    ) noexcept;

    // 2. Computational Long Exposure Accumulator
    static bool accumulateLongExposure(
        const uint8_t* currentFrame,
        float* accumulator,
        int width,
        int height,
        int channels,
        int frameIndex,
        LongExposureStyle style,
        float alpha = 0.2f
    ) noexcept;

    static void finalizeLongExposure(
        const float* accumulator,
        uint8_t* output,
        int pixelCount,
        int channels
    ) noexcept;

    // 3. Multi-Exposure Compositor
    static bool blendMultiExposure(
        const uint8_t* baseFrame,
        const uint8_t* blendFrame,
        uint8_t* output,
        int width,
        int height,
        int channels,
        MultiExposureBlendMode mode
    ) noexcept;

    // 4. Focus Stacking: High-frequency Sobel variance blending
    static bool stackFocusSlices(
        const uint8_t* const* slicePointers,
        int sliceCount,
        int width,
        int height,
        int channels,
        uint8_t* output
    ) noexcept;

    // 5. Lens Aberration Correction: 4th-order polynomial distortion
    static bool correctLensAberration(
        const uint8_t* input,
        uint8_t* output,
        int width,
        int height,
        int channels,
        float k1,
        float k2
    ) noexcept;

    // 6. Rolling Shutter Rectification
    static bool rectifyRollingShutter(
        const uint8_t* input,
        uint8_t* output,
        int width,
        int height,
        int channels,
        float gyroYawVelocity,
        float readoutTimeMs
    ) noexcept;

    // 7. Optical Hyperfocal Distance
    static float calculateHyperfocalDistance(
        float focalLengthMm,
        float fNumber,
        float circleOfConfusionMm = 0.006f
    ) noexcept;
};

} // namespace commix
