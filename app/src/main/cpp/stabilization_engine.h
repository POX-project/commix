#pragma once

#include <cstdint>
#include <vector>
#include <string>
#include <atomic>

namespace commix {

struct ImuSample {
    int64_t timestampNs;
    float gyroX;
    float gyroY;
    float gyroZ;
    float accelX;
    float accelY;
    float accelZ;
};

/**
 * High-speed motion tracking, 1kHz Gyroflow motion logging,
 * and L1 camera path trajectory optimization in C++20.
 */
class StabilizationEngine {
public:
    static constexpr size_t RING_BUFFER_CAPACITY = 32768; // Holds ~32s at 1kHz

    static StabilizationEngine& instance() noexcept {
        static StabilizationEngine s_instance;
        return s_instance;
    }

    StabilizationEngine() noexcept;

    // Push new sample from high-rate sensor thread
    void pushImuSample(int64_t timestampNs, float gx, float gy, float gz, float ax, float ay, float az) noexcept;

    // Start/Stop Gyroflow recording session
    void startLogging() noexcept;
    bool stopAndExportGyroflow(const char* outputPath, const char* cameraName, double frameRate) noexcept;

    // Real-Time Kalman Filter for Virtual Gimbal
    void updateKalman(float pitch, float roll, float yaw, float dt, float& outPitch, float& outRoll, float& outYaw) noexcept;

    // L1 Trend Filtering Camera Path Optimizer
    // Solves min_p 0.5 * ||p - c||_2^2 + lambda * ||D^2 p||_1
    static void computeL1Path(
        const float* inputTrajectory,
        float* outputTrajectory,
        int pointCount,
        float lambda
    ) noexcept;

private:
    std::vector<ImuSample> m_ringBuffer;
    std::atomic<size_t> m_writeIndex;
    std::atomic<bool> m_isLogging;
    size_t m_logStartIndex;

    // Kalman state
    float m_kalmanPitch;
    float m_kalmanRoll;
    float m_kalmanYaw;
    float m_pPitch;
    float m_pRoll;
    float m_pYaw;
};

} // namespace commix
