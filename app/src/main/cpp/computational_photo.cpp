#include "computational_photo.h"
#include <algorithm>
#include <cmath>
#include <cstring>

namespace commix {

bool ComputationalPhotoEngine::stackAstroFrames(
    const uint8_t* const* framePointers,
    int frameCount,
    int width,
    int height,
    int channels,
    uint8_t* outputBuffer,
    bool deepShadowBoost
) noexcept {
    if (!framePointers || frameCount <= 0 || !outputBuffer || width <= 0 || height <= 0 || channels < 3) {
        return false;
    }

    if (frameCount == 1) {
        std::memcpy(outputBuffer, framePointers[0], static_cast<size_t>(width) * height * channels);
        return true;
    }

    const size_t totalBytes = static_cast<size_t>(width) * height * channels;
    const int maxFrames = std::min(frameCount, MAX_STACK_FRAMES);
    uint8_t pixelValues[MAX_STACK_FRAMES];

    for (size_t i = 0; i < totalBytes; ++i) {
        for (int f = 0; f < maxFrames; ++f) {
            pixelValues[f] = framePointers[f][i];
        }

        // Insertion sort for median computation of small frame count
        for (int a = 1; a < maxFrames; ++a) {
            uint8_t key = pixelValues[a];
            int b = a - 1;
            while (b >= 0 && pixelValues[b] > key) {
                pixelValues[b + 1] = pixelValues[b];
                --b;
            }
            pixelValues[b + 1] = key;
        }

        uint8_t medianVal = pixelValues[maxFrames / 2];

        if (deepShadowBoost) {
            float norm = medianVal / 255.0f;
            // Boost shadows while preserving specular highlights
            float boosted = norm + 0.35f * norm * (1.0f - norm);
            medianVal = static_cast<uint8_t>(std::clamp(boosted * 255.0f, 0.0f, 255.0f));
        }

        outputBuffer[i] = medianVal;
    }

    return true;
}

bool ComputationalPhotoEngine::accumulateLongExposure(
    const uint8_t* currentFrame,
    float* accumulator,
    int width,
    int height,
    int channels,
    int frameIndex,
    LongExposureStyle style,
    float alpha
) noexcept {
    if (!currentFrame || !accumulator || width <= 0 || height <= 0 || channels <= 0) {
        return false;
    }

    const size_t totalPixels = static_cast<size_t>(width) * height * channels;

    if (frameIndex == 0) {
        for (size_t i = 0; i < totalPixels; ++i) {
            accumulator[i] = static_cast<float>(currentFrame[i]);
        }
        return true;
    }

    const float effectiveAlpha = std::clamp(alpha, 0.01f, 1.0f);

    switch (style) {
        case LongExposureStyle::SilkyWater:
        case LongExposureStyle::MovingCrowd:
            for (size_t i = 0; i < totalPixels; ++i) {
                accumulator[i] = (1.0f - effectiveAlpha) * accumulator[i] + effectiveAlpha * currentFrame[i];
            }
            break;

        case LongExposureStyle::LightTrails:
        case LongExposureStyle::StarTrails:
            for (size_t i = 0; i < totalPixels; ++i) {
                accumulator[i] = std::max(accumulator[i], static_cast<float>(currentFrame[i]));
            }
            break;
    }

    return true;
}

void ComputationalPhotoEngine::finalizeLongExposure(
    const float* accumulator,
    uint8_t* output,
    int pixelCount,
    int channels
) noexcept {
    if (!accumulator || !output || pixelCount <= 0 || channels <= 0) return;
    const size_t total = static_cast<size_t>(pixelCount) * channels;
    for (size_t i = 0; i < total; ++i) {
        output[i] = static_cast<uint8_t>(std::clamp(accumulator[i], 0.0f, 255.0f));
    }
}

bool ComputationalPhotoEngine::blendMultiExposure(
    const uint8_t* baseFrame,
    const uint8_t* blendFrame,
    uint8_t* output,
    int width,
    int height,
    int channels,
    MultiExposureBlendMode mode
) noexcept {
    if (!baseFrame || !blendFrame || !output || width <= 0 || height <= 0 || channels <= 0) {
        return false;
    }

    const size_t totalBytes = static_cast<size_t>(width) * height * channels;

    switch (mode) {
        case MultiExposureBlendMode::Additive:
            for (size_t i = 0; i < totalBytes; ++i) {
                int sum = static_cast<int>(baseFrame[i]) + static_cast<int>(blendFrame[i]);
                output[i] = static_cast<uint8_t>(std::min(sum, 255));
            }
            break;

        case MultiExposureBlendMode::Average:
            for (size_t i = 0; i < totalBytes; ++i) {
                output[i] = static_cast<uint8_t>((static_cast<int>(baseFrame[i]) + static_cast<int>(blendFrame[i])) >> 1);
            }
            break;

        case MultiExposureBlendMode::Lighten:
            for (size_t i = 0; i < totalBytes; ++i) {
                output[i] = std::max(baseFrame[i], blendFrame[i]);
            }
            break;

        case MultiExposureBlendMode::Darken:
            for (size_t i = 0; i < totalBytes; ++i) {
                output[i] = std::min(baseFrame[i], blendFrame[i]);
            }
            break;
    }

    return true;
}

bool ComputationalPhotoEngine::stackFocusSlices(
    const uint8_t* const* slicePointers,
    int sliceCount,
    int width,
    int height,
    int channels,
    uint8_t* output
) noexcept {
    if (!slicePointers || sliceCount <= 0 || !output || width <= 2 || height <= 2 || channels < 3) {
        return false;
    }

    if (sliceCount == 1) {
        std::memcpy(output, slicePointers[0], static_cast<size_t>(width) * height * channels);
        return true;
    }

    // Initialize output buffer with primary slice (ensuring border pixels are valid)
    const size_t rowStride = static_cast<size_t>(width) * channels;
    std::memcpy(output, slicePointers[0], rowStride * height);

    // Process interior pixels with Sobel sharpness metric
    for (int y = 1; y < height - 1; ++y) {
        for (int x = 1; x < width - 1; ++x) {
            int bestSlice = 0;
            int maxGrad = -1;

            for (int s = 0; s < sliceCount; ++s) {
                const uint8_t* src = slicePointers[s];
                // Compute horizontal and vertical gradients on Green channel (high detail)
                int g00 = src[(y - 1) * rowStride + (x - 1) * channels + 1];
                int g02 = src[(y - 1) * rowStride + (x + 1) * channels + 1];
                int g10 = src[y * rowStride + (x - 1) * channels + 1];
                int g12 = src[y * rowStride + (x + 1) * channels + 1];
                int g20 = src[(y + 1) * rowStride + (x - 1) * channels + 1];
                int g22 = src[(y + 1) * rowStride + (x + 1) * channels + 1];

                int gx = (g02 + 2 * g12 + g22) - (g00 + 2 * g10 + g20);

                int g01 = src[(y - 1) * rowStride + x * channels + 1];
                int g21 = src[(y + 1) * rowStride + x * channels + 1];
                int gy = (g20 + 2 * g21 + g22) - (g00 + 2 * g01 + g02);

                int grad = std::abs(gx) + std::abs(gy);
                if (grad > maxGrad) {
                    maxGrad = grad;
                    bestSlice = s;
                }
            }

            const size_t pxIdx = y * rowStride + x * channels;
            for (int c = 0; c < channels; ++c) {
                output[pxIdx + c] = slicePointers[bestSlice][pxIdx + c];
            }
        }
    }

    return true;
}

bool ComputationalPhotoEngine::correctLensAberration(
    const uint8_t* input,
    uint8_t* output,
    int width,
    int height,
    int channels,
    float k1,
    float k2
) noexcept {
    if (!input || !output || width <= 0 || height <= 0 || channels <= 0) return false;

    const float cx = (width - 1) * 0.5f;
    const float cy = (height - 1) * 0.5f;
    const float maxRadius = std::sqrt(cx * cx + cy * cy);
    if (maxRadius <= 0.0f) return false;

    const size_t rowStride = static_cast<size_t>(width) * channels;

    for (int y = 0; y < height; ++y) {
        float ny = (y - cy) / maxRadius;
        for (int x = 0; x < width; ++x) {
            float nx = (x - cx) / maxRadius;
            float r2 = nx * nx + ny * ny;
            float r4 = r2 * r2;
            float distortion = 1.0f + k1 * r2 + k2 * r4;

            float srcX = cx + (nx * distortion) * maxRadius;
            float srcY = cy + (ny * distortion) * maxRadius;

            int sx = static_cast<int>(std::floor(srcX));
            int sy = static_cast<int>(std::floor(srcY));

            size_t dstIdx = y * rowStride + x * channels;

            if (sx >= 0 && sx < width && sy >= 0 && sy < height) {
                size_t srcIdx = sy * rowStride + sx * channels;
                for (int c = 0; c < channels; ++c) {
                    output[dstIdx + c] = input[srcIdx + c];
                }
            } else {
                for (int c = 0; c < channels; ++c) {
                    output[dstIdx + c] = 0;
                }
            }
        }
    }

    return true;
}

bool ComputationalPhotoEngine::rectifyRollingShutter(
    const uint8_t* input,
    uint8_t* output,
    int width,
    int height,
    int channels,
    float gyroYawVelocity,
    float readoutTimeMs
) noexcept {
    if (!input || !output || width <= 0 || height <= 0 || channels <= 0) return false;

    const size_t rowStride = static_cast<size_t>(width) * channels;
    const float timePerLine = (readoutTimeMs * 0.001f) / height;

    for (int y = 0; y < height; ++y) {
        float timeOffset = y * timePerLine;
        float shiftX = gyroYawVelocity * timeOffset * width * 0.1f;
        int iShift = static_cast<int>(std::round(shiftX));

        for (int x = 0; x < width; ++x) {
            int srcX = std::clamp(x + iShift, 0, width - 1);
            size_t dstIdx = y * rowStride + x * channels;
            size_t srcIdx = y * rowStride + srcX * channels;

            for (int c = 0; c < channels; ++c) {
                output[dstIdx + c] = input[srcIdx + c];
            }
        }
    }

    return true;
}

float ComputationalPhotoEngine::calculateHyperfocalDistance(
    float focalLengthMm,
    float fNumber,
    float circleOfConfusionMm
) noexcept {
    if (focalLengthMm <= 0.0f || fNumber <= 0.0f || circleOfConfusionMm <= 0.0f) return 0.0f;
    float fMeters = focalLengthMm * 0.001f;
    float cMeters = circleOfConfusionMm * 0.001f;
    return (fMeters * fMeters) / (fNumber * cMeters) + fMeters;
}

} // namespace commix
