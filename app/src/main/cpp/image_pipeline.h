#pragma once

#include <cstdint>
#include <string>

namespace commix {

enum class ToneCurvePreset {
    Linear,
    FilmPrint,
    CineD,
    Neutral
};

enum class GrainPreset {
    Off,
    Fine35mm,
    Medium16mm,
    Coarse8mm
};

/**
 * Process Zero and computational image processing pipeline in C++20.
 */
class ImagePipeline {
public:
    static void initToneLuts() noexcept;

    /**
     * Applies Process Zero tone mapping and analog silver halide grain simulation
     * directly to RGBA/RGB byte buffers with zero allocations.
     */
    static bool processZero(
        uint8_t* __restrict__ pixelData,
        int width,
        int height,
        int channels,
        ToneCurvePreset curve,
        GrainPreset grain,
        float grainIntensity
    ) noexcept;

    /**
     * Fast unsharp mask sharpening filter.
     */
    static void applyUnsharpMask(
        uint8_t* __restrict__ pixelData,
        int width,
        int height,
        int channels,
        float amount,
        float radius
    ) noexcept;

private:
    static uint8_t s_filmPrintLut[256];
    static uint8_t s_cineDLut[256];
    static uint8_t s_neutralLut[256];
    static bool s_lutsInitialized;
};

} // namespace commix
