#pragma once

#include <vector>
#include <cstdint>

namespace commix {

enum class KeyframeCurve {
    Linear,
    EaseIn,
    EaseOut,
    EaseInOut,
    Hold
};

struct KeyframePoint {
    float timeSec;
    float value;
    KeyframeCurve curve;
};

struct KeyframeTrack {
    std::vector<KeyframePoint> points;
    float evaluate(float timeSec) const noexcept;
    bool hasPoints() const noexcept { return !points.empty(); }
    void sortPoints() noexcept;
};

struct KeyframeBatchValues {
    float zoom;
    int32_t iso;
    int64_t shutterNs;
    float ev;
    int32_t wbKelvin;
    float focusDiopters;
};

/**
 * High-speed keyframe timeline animation ticker in C++20.
 */
class KeyframeEngine {
public:
    KeyframeTrack trackZoom;
    KeyframeTrack trackIso;
    KeyframeTrack trackShutter;
    KeyframeTrack trackEv;
    KeyframeTrack trackWb;
    KeyframeTrack trackFocus;

    float durationSec = 10.0f;
    bool isPlaying = false;
    float currentTimeSec = 0.0f;

    void reset() noexcept;
    void start() noexcept;
    void stop() noexcept;

    // Advances timeline by dt and returns interpolated batch parameter struct
    bool tick(float dt, KeyframeBatchValues& outValues) noexcept;
};

} // namespace commix
