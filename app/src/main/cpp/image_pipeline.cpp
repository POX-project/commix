#include "image_pipeline.h"
#include "thread_pool.hpp"
#include <cmath>
#include <algorithm>
#include <vector>
#include <mutex>
#include <condition_variable>
#include <atomic>

namespace commix {

uint8_t ImagePipeline::s_filmPrintLut[256];
uint8_t ImagePipeline::s_cineDLut[256];
uint8_t ImagePipeline::s_neutralLut[256];
bool ImagePipeline::s_lutsInitialized = false;
static std::once_flag s_lutOnce;
static std::atomic<uint32_t> s_grainFrameCounter{0};

// Fast deterministic Xorshift32 PRNG for zero-overhead per-pixel noise
static inline uint32_t xorshift32(uint32_t& state) noexcept {
    uint32_t x = state;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    state = x;
    return x;
}

void ImagePipeline::initToneLuts() noexcept {
    std::call_once(s_lutOnce, []() {
        for (int i = 0; i < 256; ++i) {
            float x = i / 255.0f;

            // Film Print: Gentle shadow rolloff, contrasty midtones, compressed highlights
            float fp = 1.0f / (1.0f + std::exp(-7.0f * (x - 0.45f)));
            s_filmPrintLut[i] = static_cast<uint8_t>(std::clamp(fp * 255.0f, 0.0f, 255.0f));

            // Cine D: Flatter shadows and highlights for maximum dynamic range
            float cd = std::pow(x, 0.85f);
            s_cineDLut[i] = static_cast<uint8_t>(std::clamp(cd * 255.0f, 0.0f, 255.0f));

            // Neutral: Smooth filmic gamma 2.2 curve
            float n = std::pow(x, 1.0f / 1.15f);
            s_neutralLut[i] = static_cast<uint8_t>(std::clamp(n * 255.0f, 0.0f, 255.0f));
        }
        s_lutsInitialized = true;
    });
}

bool ImagePipeline::processZero(
    uint8_t* __restrict__ pixelData,
    int width,
    int height,
    int channels,
    ToneCurvePreset curve,
    GrainPreset grain,
    float grainIntensity
) noexcept {
    if (!pixelData || width <= 0 || height <= 0 || channels < 3) return false;
    initToneLuts();

    const uint8_t* lut = nullptr;
    switch (curve) {
        case ToneCurvePreset::FilmPrint: lut = s_filmPrintLut; break;
        case ToneCurvePreset::CineD: lut = s_cineDLut; break;
        case ToneCurvePreset::Neutral: lut = s_neutralLut; break;
        default: lut = nullptr; break;
    }

    float grainScale = 0.0f;
    switch (grain) {
        case GrainPreset::Fine35mm: grainScale = 12.0f * grainIntensity; break;
        case GrainPreset::Medium16mm: grainScale = 22.0f * grainIntensity; break;
        case GrainPreset::Coarse8mm: grainScale = 38.0f * grainIntensity; break;
        default: grainScale = 0.0f; break;
    }
    const bool applyGrain = (grainScale > 0.0f);

    // Frame index for non-static, organic film grain
    uint32_t frameIdx = s_grainFrameCounter.fetch_add(1, std::memory_order_relaxed);

    // Slice image across threads in ThreadPool
    const int threadCount = 8;
    const int rowsPerThread = (height + threadCount - 1) / threadCount;

    std::mutex cvMutex;
    std::condition_variable cv;
    int completedTasks = 0;
    int activeTasks = 0;

    for (int t = 0; t < threadCount; ++t) {
        const int startY = t * rowsPerThread;
        const int endY = std::min(startY + rowsPerThread, height);
        if (startY >= endY) continue;

        activeTasks++;
        ThreadPool::instance().enqueue([pixelData, width, channels, startY, endY, lut, applyGrain, grainScale, t, frameIdx, &cvMutex, &cv, &completedTasks]() {
            uint32_t seed = (0x12345678u ^ (frameIdx * 2654435761u)) ^ static_cast<uint32_t>(t * 7919);

            for (int y = startY; y < endY; ++y) {
                uint8_t* row = pixelData + (static_cast<size_t>(y) * width * channels);
                for (int x = 0; x < width; ++x) {
                    uint8_t* px = row + (x * channels);
                    int r = px[0];
                    int g = px[1];
                    int b = px[2];

                    if (lut) {
                        r = lut[r];
                        g = lut[g];
                        b = lut[b];
                    }

                    if (applyGrain) {
                        // Luma in 0.0 .. 1.0
                        float luma = (0.2126f * r + 0.7152f * g + 0.0722f * b) * (1.0f / 255.0f);
                        // Parabolic midtone mask: peaks at 0.5 (18% middle grey), attenuates at 0 and 1
                        float mask = 4.0f * luma * (1.0f - luma);
                        mask = std::max(0.0f, mask);

                        // Uniform noise in [-1.0, 1.0]
                        float randVal = (static_cast<float>(xorshift32(seed) % 1000) / 500.0f) - 1.0f;
                        float noise = randVal * grainScale * mask;

                        r = std::clamp(static_cast<int>(r + noise), 0, 255);
                        g = std::clamp(static_cast<int>(g + noise), 0, 255);
                        b = std::clamp(static_cast<int>(b + noise), 0, 255);
                    }

                    px[0] = static_cast<uint8_t>(r);
                    px[1] = static_cast<uint8_t>(g);
                    px[2] = static_cast<uint8_t>(b);
                }
            }
            {
                std::lock_guard<std::mutex> lk(cvMutex);
                completedTasks++;
            }
            cv.notify_one();
        });
    }

    // Wait for all worker slices to complete without spinning
    {
        std::unique_lock<std::mutex> lk(cvMutex);
        cv.wait(lk, [&]() { return completedTasks >= activeTasks; });
    }

    return true;
}

void ImagePipeline::applyUnsharpMask(
    uint8_t* __restrict__ pixelData,
    int width,
    int height,
    int channels,
    float amount,
    float radius
) noexcept {
    if (!pixelData || width <= 2 || height <= 2 || channels < 3) return;

    // Simple 3x3 Laplacian sharpening approximation
    const float weight = std::clamp(amount, 0.0f, 2.0f);
    if (weight <= 0.01f) return;

    const size_t rowBytes = static_cast<size_t>(width) * channels;
    std::vector<uint8_t> prevRow(pixelData, pixelData + rowBytes);
    std::vector<uint8_t> currRow(pixelData + rowBytes, pixelData + 2 * rowBytes);

    for (int y = 1; y < height - 1; ++y) {
        uint8_t* rowCurr = pixelData + (static_cast<size_t>(y) * rowBytes);
        const uint8_t* rowNext = pixelData + (static_cast<size_t>(y + 1) * rowBytes);

        for (int x = 1; x < width - 1; ++x) {
            for (int c = 0; c < 3; ++c) {
                int center = currRow[x * channels + c];
                int surrounding = (prevRow[x * channels + c] +
                                   rowNext[x * channels + c] +
                                   currRow[(x - 1) * channels + c] +
                                   currRow[(x + 1) * channels + c]) >> 2;
                int diff = center - surrounding;
                int sharpened = static_cast<int>(center + diff * weight);
                rowCurr[x * channels + c] = static_cast<uint8_t>(std::clamp(sharpened, 0, 255));
            }
        }

        prevRow = std::move(currRow);
        if (y + 1 < height - 1) {
            currRow.assign(rowNext, rowNext + rowBytes);
        }
    }
}

} // namespace commix
