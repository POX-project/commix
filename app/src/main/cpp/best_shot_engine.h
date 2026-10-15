#pragma once

#include <cstdint>
#include <vector>

namespace commix {

struct BestShotScore {
    float stabilityScore;   // 0.0 .. 100.0
    float exposureScore;    // 0.0 .. 100.0
    float dynamicRangeScore;// 0.0 .. 100.0
    float totalScore;       // 0.0 .. 100.0
    bool shouldTriggerShoot;
};

/**
 * AI Composition and Peak Stillness Determination Engine in C++20.
 */
class BestShotEngine {
public:
    BestShotEngine() noexcept;

    // Feeds high-rate gyro angular velocities (radians/sec)
    void pushVelocity(float gx, float gy, float gz) noexcept;

    // Evaluates stillness and frame histogram metrics to produce a score
    BestShotScore evaluate(
        const int32_t* lumaHistogram,
        float spotIre,
        float hiClipPct,
        float loClipPct
    ) noexcept;

private:
    static constexpr size_t HISTORY_CAPACITY = 30;
    float m_velocityHistory[HISTORY_CAPACITY];
    size_t m_historyIndex;
    size_t m_historyCount;
    int64_t m_lastTriggerTimeMs;
};

} // namespace commix
