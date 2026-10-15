#include "keyframe_engine.h"
#include <algorithm>
#include <cmath>

namespace commix {

float KeyframeTrack::evaluate(float timeSec) const noexcept {
    if (points.empty()) return 0.0f;
    if (points.size() == 1 || timeSec <= points.front().timeSec) return points.front().value;
    if (timeSec >= points.back().timeSec) return points.back().value;

    // Find interval [p0, p1]
    for (size_t i = 0; i < points.size() - 1; ++i) {
        if (timeSec >= points[i].timeSec && timeSec <= points[i + 1].timeSec) {
            const auto& p0 = points[i];
            const auto& p1 = points[i + 1];
            float interval = p1.timeSec - p0.timeSec;
            if (interval <= 0.0001f) return p0.value;

            float u = (timeSec - p0.timeSec) / interval;
            float factor = u;

            switch (p0.curve) {
                case KeyframeCurve::Linear:
                    factor = u;
                    break;
                case KeyframeCurve::EaseIn:
                    factor = u * u;
                    break;
                case KeyframeCurve::EaseOut:
                    factor = 1.0f - (1.0f - u) * (1.0f - u);
                    break;
                case KeyframeCurve::EaseInOut:
                    factor = (u < 0.5f) ? (2.0f * u * u) : (1.0f - std::pow(-2.0f * u + 2.0f, 2.0f) * 0.5f);
                    break;
                case KeyframeCurve::Hold:
                    factor = 0.0f;
                    break;
            }

            return p0.value + factor * (p1.value - p0.value);
        }
    }

    return points.back().value;
}

void KeyframeTrack::sortPoints() noexcept {
    if (points.size() <= 1) return;
    std::sort(points.begin(), points.end(), [](const KeyframePoint& a, const KeyframePoint& b) {
        return a.timeSec < b.timeSec;
    });
}

void KeyframeEngine::reset() noexcept {
    isPlaying = false;
    currentTimeSec = 0.0f;
    durationSec = 10.0f;
    trackZoom.points.clear();
    trackIso.points.clear();
    trackShutter.points.clear();
    trackEv.points.clear();
    trackWb.points.clear();
    trackFocus.points.clear();
}

void KeyframeEngine::start() noexcept {
    trackZoom.sortPoints();
    trackIso.sortPoints();
    trackShutter.sortPoints();
    trackEv.sortPoints();
    trackWb.sortPoints();
    trackFocus.sortPoints();
    isPlaying = true;
    currentTimeSec = 0.0f;
}

void KeyframeEngine::stop() noexcept {
    isPlaying = false;
}

bool KeyframeEngine::tick(float dt, KeyframeBatchValues& outValues) noexcept {
    if (!isPlaying) return false;

    currentTimeSec += dt;
    if (currentTimeSec >= durationSec) {
        currentTimeSec = durationSec;
        isPlaying = false;
    }

    outValues.zoom = trackZoom.hasPoints() ? trackZoom.evaluate(currentTimeSec) : -1.0f;
    outValues.iso = trackIso.hasPoints() ? static_cast<int32_t>(trackIso.evaluate(currentTimeSec)) : -1;
    outValues.shutterNs = trackShutter.hasPoints() ? static_cast<int64_t>(trackShutter.evaluate(currentTimeSec)) : -1;
    outValues.ev = trackEv.hasPoints() ? trackEv.evaluate(currentTimeSec) : -999.0f;
    outValues.wbKelvin = trackWb.hasPoints() ? static_cast<int32_t>(trackWb.evaluate(currentTimeSec)) : -1;
    outValues.focusDiopters = trackFocus.hasPoints() ? trackFocus.evaluate(currentTimeSec) : -1.0f;

    return true;
}

} // namespace commix
