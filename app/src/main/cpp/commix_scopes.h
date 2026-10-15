#pragma once
#include <cstdint>

namespace commix {

inline constexpr int HIST_BINS = 256;
inline constexpr int WAVE_COLS = 64;
inline constexpr int PARADE_N = 64;
inline constexpr int VSCOPE_SAMPLES = 256;

struct ScopeOut {
    int32_t* luma;      // [256]
    int32_t* rBins;     // [256]
    int32_t* gBins;     // [256]
    int32_t* bBins;     // [256]
    float* waveAvg; // [64]
    float* waveMin; // [64]
    float* waveMax; // [64]
    float* paradeR; // [64]
    float* paradeG; // [64]
    float* paradeB; // [64]
    float* vscope;  // [256*2] interleaved x,y normalized -1..1
    // scalars:
    float spotIre = 45.f;
    float meanU = 0.f, meanV = 0.f;
    float hiClipPct = 0.f, loClipPct = 0.f;
    int32_t vscopeCount = 0;
};

void compute_scopes(
    const uint8_t* __restrict__ y, const uint8_t* __restrict__ u, const uint8_t* __restrict__ v,
    int width, int height, int yStride, int uvStride, int uvPixelStride, int step,
    ScopeOut& out) noexcept;

} // namespace commix
