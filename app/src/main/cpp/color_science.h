#pragma once

#include <cstdint>
#include <string>

namespace commix {

enum class TransferCurveType {
    Rec709,
    AppleLog,
    ArriLogC3,
    ArriLogC4,
    SonySLog3,
    SLog3 = SonySLog3,
    CanonCLog2,
    RedLog3G10,
    Hlg,
    Pq
};

struct AscCdlParams {
    float slopeR = 1.0f, slopeG = 1.0f, slopeB = 1.0f;
    float offsetR = 0.0f, offsetG = 0.0f, offsetB = 0.0f;
    float powerR = 1.0f, powerG = 1.0f, powerB = 1.0f;
    float saturation = 1.0f;
};

/**
 * Broadcast studio color science and ASC CDL v1.2 grading in C++20.
 */
class ColorScienceEngine {
public:
    // Linear to Log Transfer Functions
    static float linearToLog(float linearVal, TransferCurveType curve) noexcept;

    // Log to Linear Transfer Functions
    static float logToLinear(float logVal, TransferCurveType curve) noexcept;

    // In-place ASC CDL v1.2 grading on RGB/RGBA pixel buffers
    static void applyCdl(
        uint8_t* __restrict__ pixelData,
        int width,
        int height,
        int channels,
        const AscCdlParams& cdl
    ) noexcept;
};

} // namespace commix
