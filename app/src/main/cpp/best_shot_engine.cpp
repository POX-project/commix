#include "best_shot_engine.h"
#include <cmath>
#include <algorithm>
#include <chrono>

namespace commix {

BestShotEngine::BestShotEngine() noexcept
    : m_historyIndex(0),
      m_historyCount(0),
      m_lastTriggerTimeMs(0) {
    for (size_t i = 0; i < HISTORY_CAPACITY; ++i) {
        m_velocityHistory[i] = 0.0f;
    }
}

void BestShotEngine::pushVelocity(float gx, float gy, float gz) noexcept {
    float mag = std::sqrt(gx * gx + gy * gy + gz * gz);
    m_velocityHistory[m_historyIndex] = mag;
    m_historyIndex = (m_historyIndex + 1) % HISTORY_CAPACITY;
    if (m_historyCount < HISTORY_CAPACITY) {
        m_historyCount++;
    }
}

BestShotScore BestShotEngine::evaluate(
    const int32_t* lumaHistogram,
    float spotIre,
    float hiClipPct,
    float loClipPct
) noexcept {
    BestShotScore score{};
    (void)lumaHistogram;

    // 1. Stability Score from gyro history
    if (m_historyCount > 0) {
        float sum = 0.0f;
        for (size_t i = 0; i < m_historyCount; ++i) {
            sum += m_velocityHistory[i];
        }
        float avgVel = sum / m_historyCount;
        // 0.02 rad/s or lower = 100%, 0.40 rad/s or higher = 0%
        float stabNorm = std::clamp((0.40f - avgVel) / (0.40f - 0.02f), 0.0f, 1.0f);
        score.stabilityScore = stabNorm * 100.0f;
    } else {
        score.stabilityScore = 50.0f;
    }

    // 2. Exposure Balance Score
    // Optimal center spot IRE is between 40 and 60
    float spotDiff = std::abs(spotIre - 50.0f);
    float expNorm = std::clamp(1.0f - (spotDiff / 40.0f), 0.0f, 1.0f);
    score.exposureScore = expNorm * 100.0f;

    // 3. Dynamic Range / Clipping Score
    float clipTotal = hiClipPct + loClipPct;
    float drNorm = std::clamp(1.0f - (clipTotal / 25.0f), 0.0f, 1.0f);
    score.dynamicRangeScore = drNorm * 100.0f;

    // Total Composite Score
    score.totalScore = (0.50f * score.stabilityScore) +
                       (0.30f * score.exposureScore) +
                       (0.20f * score.dynamicRangeScore);

    auto nowMs = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()
    ).count();

    // Debounce trigger by at least 2500ms
    if (score.totalScore >= 82.0f && score.stabilityScore >= 88.0f && (nowMs - m_lastTriggerTimeMs > 2500)) {
        score.shouldTriggerShoot = true;
        m_lastTriggerTimeMs = nowMs;
    } else {
        score.shouldTriggerShoot = false;
    }

    return score;
}

} // namespace commix
