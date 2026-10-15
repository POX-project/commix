#include "stabilization_engine.h"
#include <cstdio>
#include <cmath>
#include <algorithm>

namespace commix {

StabilizationEngine::StabilizationEngine() noexcept
    : m_ringBuffer(RING_BUFFER_CAPACITY),
      m_writeIndex(0),
      m_isLogging(false),
      m_logStartIndex(0),
      m_kalmanPitch(0.0f),
      m_kalmanRoll(0.0f),
      m_kalmanYaw(0.0f),
      m_pPitch(1.0f),
      m_pRoll(1.0f),
      m_pYaw(1.0f) {}

void StabilizationEngine::pushImuSample(
    int64_t timestampNs,
    float gx, float gy, float gz,
    float ax, float ay, float az
) noexcept {
    size_t idx = m_writeIndex.fetch_add(1, std::memory_order_relaxed) % RING_BUFFER_CAPACITY;
    m_ringBuffer[idx] = ImuSample{timestampNs, gx, gy, gz, ax, ay, az};
}

void StabilizationEngine::startLogging() noexcept {
    m_logStartIndex = m_writeIndex.load(std::memory_order_relaxed);
    m_isLogging.store(true, std::memory_order_release);
}

bool StabilizationEngine::stopAndExportGyroflow(
    const char* outputPath,
    const char* cameraName,
    double frameRate
) noexcept {
    m_isLogging.store(false, std::memory_order_release);
    size_t endIndex = m_writeIndex.load(std::memory_order_relaxed);

    FILE* fp = std::fopen(outputPath, "w");
    if (!fp) return false;

    std::fprintf(fp, "GYROFLOW IMU LOG\n");
    std::fprintf(fp, "version,1.3\n");
    std::fprintf(fp, "camera_name,%s\n", (cameraName && cameraName[0]) ? cameraName : "Commix Cinema Camera");
    std::fprintf(fp, "frame_rate,%.2f\n", frameRate > 0.0 ? frameRate : 30.0);
    std::fprintf(fp, "t,gx,gy,gz,ax,ay,az\n");

    size_t sampleCount = (endIndex >= m_logStartIndex) ? (endIndex - m_logStartIndex) : 0;
    if (sampleCount > RING_BUFFER_CAPACITY) sampleCount = RING_BUFFER_CAPACITY;

    size_t start = (endIndex > sampleCount) ? (endIndex - sampleCount) : 0;
    int64_t baseTimeNs = 0;

    for (size_t i = start; i < endIndex; ++i) {
        const auto& s = m_ringBuffer[i % RING_BUFFER_CAPACITY];
        if (i == start) baseTimeNs = s.timestampNs;
        double tSec = (s.timestampNs - baseTimeNs) * 1e-9;
        std::fprintf(fp, "%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f\n",
                     tSec, s.gyroX, s.gyroY, s.gyroZ, s.accelX, s.accelY, s.accelZ);
    }

    std::fclose(fp);
    return true;
}

void StabilizationEngine::updateKalman(
    float pitch, float roll, float yaw, float dt,
    float& outPitch, float& outRoll, float& outYaw
) noexcept {
    // Process noise Q and Measurement noise R
    const float Q = 0.005f * (dt > 0.0f ? dt : 0.016f);
    const float R = 0.05f;

    // Pitch
    m_pPitch += Q;
    float kPitch = m_pPitch / (m_pPitch + R);
    m_kalmanPitch += kPitch * (pitch - m_kalmanPitch);
    m_pPitch *= (1.0f - kPitch);
    outPitch = m_kalmanPitch;

    // Roll
    m_pRoll += Q;
    float kRoll = m_pRoll / (m_pRoll + R);
    m_kalmanRoll += kRoll * (roll - m_kalmanRoll);
    m_pRoll *= (1.0f - kRoll);
    outRoll = m_kalmanRoll;

    // Yaw
    m_pYaw += Q;
    float kYaw = m_pYaw / (m_pYaw + R);
    m_kalmanYaw += kYaw * (yaw - m_kalmanYaw);
    m_pYaw *= (1.0f - kYaw);
    outYaw = m_kalmanYaw;
}

void StabilizationEngine::computeL1Path(
    const float* inputTrajectory,
    float* outputTrajectory,
    int pointCount,
    float lambda
) noexcept {
    if (!inputTrajectory || !outputTrajectory || pointCount <= 0) return;
    if (pointCount <= 2 || lambda <= 0.0f) {
        for (int i = 0; i < pointCount; ++i) outputTrajectory[i] = inputTrajectory[i];
        return;
    }

    // Iterative proximal gradient descent for L1 trend filtering
    for (int i = 0; i < pointCount; ++i) {
        outputTrajectory[i] = inputTrajectory[i];
    }

    const int maxIters = 25;
    const float stepSize = 0.15f;

    for (int iter = 0; iter < maxIters; ++iter) {
        for (int i = 1; i < pointCount - 1; ++i) {
            // Second difference: D^2 p = p_{i-1} - 2*p_i + p_{i+1}
            float d2 = outputTrajectory[i - 1] - 2.0f * outputTrajectory[i] + outputTrajectory[i + 1];

            // Soft-thresholding shrinkage
            float shrink = 0.0f;
            if (d2 > lambda) shrink = d2 - lambda;
            else if (d2 < -lambda) shrink = d2 + lambda;

            // Gradient step towards measured trajectory while penalizing second difference
            float gradData = outputTrajectory[i] - inputTrajectory[i];
            outputTrajectory[i] -= stepSize * (gradData - 0.5f * (d2 - shrink));
        }
    }
}

} // namespace commix
