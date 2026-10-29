#include "image_pipeline.h"
#include "thread_pool.hpp"
#include <cmath>
#include <algorithm>
#include <vector>
#include <mutex>
#include <latch>
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

    int slices = 0;
    for (int t = 0; t < threadCount; ++t) {
        const int startY = t * rowsPerThread;
        if (startY < std::min(startY + rowsPerThread, height)) ++slices;
    }
    if (slices == 0) return true;

    // Completions for exactly `slices` tasks using C++20 latch: wait() returns after
    // all count_down() calls; latch is destroyed safely with zero notify-after-destroy race.
    std::latch done(slices);

    for (int t = 0; t < threadCount; ++t) {
        const int startY = t * rowsPerThread;
        const int endY = std::min(startY + rowsPerThread, height);
        if (startY >= endY) continue;

        const bool queued = ThreadPool::instance().enqueue(
            [pixelData, width, channels, startY, endY, lut, applyGrain, grainScale, t, frameIdx, &done]() {
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
                            float luma = (0.2126f * r + 0.7152f * g + 0.0722f * b) * (1.0f / 255.0f);
                            float mask = std::max(0.0f, 4.0f * luma * (1.0f - luma));
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
                done.count_down();
            }
        );
        if (!queued) {
            done.count_down();
        }
    }

    done.wait();
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
    if (!pixelData || width <= 0 || height <= 0 || channels < 3) return;

    const float weight = std::clamp(amount, 0.0f, 2.0f);
    if (weight <= 0.001f) return;

    // radius (pixels) -> odd kernel half-width R, sigma = R / 3.0
    int R = static_cast<int>(std::ceil(radius));
    if (R < 1) R = 1;
    if (R > 32) R = 32;
    const int maxR = std::min(width, height) - 1;
    if (R > maxR) R = maxR;
    if (R < 1) return;

    const float sigma = R / 3.0f;
    const int k = 2 * R + 1;
    std::vector<float> kernel(k);
    float ksum = 0.0f;
    for (int i = -R; i <= R; ++i) {
        const float w = std::exp(-(static_cast<float>(i) * i) / (2.0f * sigma * sigma));
        kernel[i + R] = w;
        ksum += w;
    }
    for (float& w : kernel) w /= ksum;

    const size_t pixels = static_cast<size_t>(width) * height;
    std::vector<float> src(pixels * 3);
    std::vector<float> tmp(pixels * 3);
    for (size_t i = 0; i < pixels; ++i) {
        src[i * 3 + 0] = pixelData[i * channels + 0];
        src[i * 3 + 1] = pixelData[i * channels + 1];
        src[i * 3 + 2] = pixelData[i * channels + 2];
    }

    // Horizontal pass: src -> tmp with clamp-to-edge
    for (int y = 0; y < height; ++y) {
        const float* srow = &src[static_cast<size_t>(y) * width * 3];
        float* trow = &tmp[static_cast<size_t>(y) * width * 3];
        for (int x = 0; x < width; ++x) {
            float a0 = 0.0f, a1 = 0.0f, a2 = 0.0f;
            for (int i = -R; i <= R; ++i) {
                int xx = x + i;
                xx = (xx < 0) ? 0 : ((xx >= width) ? width - 1 : xx);
                const float w = kernel[i + R];
                a0 += srow[xx * 3 + 0] * w;
                a1 += srow[xx * 3 + 1] * w;
                a2 += srow[xx * 3 + 2] * w;
            }
            trow[x * 3 + 0] = a0;
            trow[x * 3 + 1] = a1;
            trow[x * 3 + 2] = a2;
        }
    }

    // Vertical pass + blend: out = clamp(center + weight * (center - blurred))
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            float b0 = 0.0f, b1 = 0.0f, b2 = 0.0f;
            for (int i = -R; i <= R; ++i) {
                int yy = y + i;
                yy = (yy < 0) ? 0 : ((yy >= height) ? height - 1 : yy);
                const float w = kernel[i + R];
                const float* p = &tmp[(static_cast<size_t>(yy) * width + x) * 3];
                b0 += p[0] * w;
                b1 += p[1] * w;
                b2 += p[2] * w;
            }
            const size_t o = static_cast<size_t>(y) * width + x;
            uint8_t* px = pixelData + o * channels;
            const float c0 = src[o * 3 + 0];
            const float c1 = src[o * 3 + 1];
            const float c2 = src[o * 3 + 2];
            px[0] = static_cast<uint8_t>(std::clamp(c0 + weight * (c0 - b0), 0.0f, 255.0f));
            px[1] = static_cast<uint8_t>(std::clamp(c1 + weight * (c1 - b1), 0.0f, 255.0f));
            px[2] = static_cast<uint8_t>(std::clamp(c2 + weight * (c2 - b2), 0.0f, 255.0f));
        }
    }
}

} // namespace commix
