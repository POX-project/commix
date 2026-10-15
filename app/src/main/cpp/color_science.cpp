#include "color_science.h"
#include <cmath>
#include <algorithm>

namespace commix {

float ColorScienceEngine::linearToLog(float x, TransferCurveType curve) noexcept {
    x = std::max(0.0f, x);
    switch (curve) {
        case TransferCurveType::Rec709:
            return (x < 0.018f) ? (4.5f * x) : (1.099f * std::pow(x, 0.45f) - 0.099f);

        case TransferCurveType::ArriLogC3: {
            constexpr float a = 400.0f;
            constexpr float b = 0.040761f;
            constexpr float c = 0.247190f;
            constexpr float d = 0.385537f;
            constexpr float cut = 0.010591f;
            if (x > cut) {
                return c * std::log10(a * x + b) + d;
            }
            constexpr float e = 23.364491f;
            constexpr float f = 0.042835f;
            return e * x + f;
        }

        case TransferCurveType::ArriLogC4: {
            constexpr float a = 222.86f;
            constexpr float b = 0.0018f;
            constexpr float c = 0.07f;
            return (std::log2(a * x + 1.0f) * c) + b;
        }

        case TransferCurveType::SonySLog3: {
            if (x >= 0.01125f) {
                return (420.0f + std::log10((x + 0.01f) / (0.18f + 0.01f)) * 261.5f) / 1023.0f;
            }
            return (x * (171.2102946929f / 0.01125f) + 95.0f) / 1023.0f;
        }

        case TransferCurveType::CanonCLog2: {
            if (x >= 0.00392f) {
                return 0.281863f * std::log10(87.257f * x + 1.0f) + 0.035388f;
            }
            return 6.1396f * x + 0.035388f;
        }

        case TransferCurveType::RedLog3G10: {
            constexpr float a = 0.224282f;
            constexpr float b = 155.975327f;
            constexpr float c = 0.01f;
            constexpr float g = 0.5f;
            return a * std::log10((x + c) * b + 1.0f) + g;
        }

        case TransferCurveType::AppleLog: {
            constexpr float a = 0.25f;
            constexpr float b = 0.05f;
            return a * std::log2(x * 64.0f + 1.0f) + b;
        }

        case TransferCurveType::Hlg: {
            constexpr float a = 0.17883277f;
            constexpr float b = 0.28466892f;
            constexpr float c = 0.55991073f;
            if (x <= (1.0f / 12.0f)) {
                return std::sqrt(3.0f * x);
            }
            return a * std::log(12.0f * x - b) + c;
        }

        case TransferCurveType::Pq: {
            constexpr float m1 = 2610.0f / 16384.0f;
            constexpr float m2 = (2523.0f / 4096.0f) * 128.0f;
            constexpr float c1 = 3424.0f / 4096.0f;
            constexpr float c2 = (2413.0f / 4096.0f) * 32.0f;
            constexpr float c3 = (2392.0f / 4096.0f) * 32.0f;

            float y_m1 = std::pow(x, m1);
            float num = c1 + c2 * y_m1;
            float den = 1.0f + c3 * y_m1;
            return std::pow(num / den, m2);
        }
    }
    return x;
}

float ColorScienceEngine::logToLinear(float y, TransferCurveType curve) noexcept {
    y = std::clamp(y, 0.0f, 1.0f);
    switch (curve) {
        case TransferCurveType::Rec709:
            return (y < 0.081f) ? (y / 4.5f) : std::pow((y + 0.099f) / 1.099f, 1.0f / 0.45f);

        case TransferCurveType::ArriLogC3: {
            constexpr float a = 400.0f;
            constexpr float b = 0.040761f;
            constexpr float c = 0.247190f;
            constexpr float d = 0.385537f;
            constexpr float cut = 0.149658f;
            if (y > cut) {
                return (std::pow(10.0f, (y - d) / c) - b) / a;
            }
            constexpr float e = 23.364491f;
            constexpr float f = 0.042835f;
            return (y - f) / e;
        }

        case TransferCurveType::SonySLog3: {
            if (y >= 95.0f / 1023.0f) {
                return std::pow(10.0f, ((y * 1023.0f - 420.0f) / 261.5f)) * (0.18f + 0.01f) - 0.01f;
            }
            return (y * 1023.0f - 95.0f) * (0.01125f / 171.2102946929f);
        }

        case TransferCurveType::AppleLog: {
            constexpr float a = 0.25f;
            constexpr float b = 0.05f;
            return (std::pow(2.0f, (y - b) / a) - 1.0f) / 64.0f;
        }

        case TransferCurveType::ArriLogC4: {
            constexpr float a = (262144.0f - 16.0f) / 117.45f;
            constexpr float b = (1023.0f * 0.18f - 16.0f) / 117.45f;
            constexpr float c = 117.45f / 1023.0f;
            constexpr float s = (16384.0f * 64.0f - 64.0f) / 1023.0f;
            constexpr float t = 64.0f / 1023.0f;
            if (y >= t) {
                return (std::pow(2.0f, (y - c) * 14.0f) - b) / a;
            }
            return (y - t) / s;
        }

        case TransferCurveType::CanonCLog2: {
            if (y > 0.086307f) {
                return (std::pow(10.0f, (y - 0.252033f) / 0.281863f) - 0.035388f) * 0.9f;
            }
            return (y - 0.078604f) / 0.088649f * 0.9f;
        }

        case TransferCurveType::RedLog3G10: {
            constexpr float a = 0.224282f;
            constexpr float b = 155.975327f;
            constexpr float c = 0.01f;
            constexpr float g = 3.574423f;
            if (y >= 0.0f) {
                return (std::pow(10.0f, y / a) - 1.0f) / b;
            }
            return (y * g) - c;
        }

        case TransferCurveType::Hlg: {
            constexpr float a = 0.17883277f;
            constexpr float b = 0.28466892f;
            constexpr float c = 0.55991073f;
            if (y <= 0.5f) {
                return (y * y) / 3.0f;
            }
            return (std::exp((y - c) / a) + b) / 12.0f;
        }

        case TransferCurveType::Pq: {
            constexpr float m1 = 2610.0f / 16384.0f;
            constexpr float m2 = (2523.0f / 4096.0f) * 128.0f;
            constexpr float c1 = 3424.0f / 4096.0f;
            constexpr float c2 = (2413.0f / 4096.0f) * 32.0f;
            constexpr float c3 = (2392.0f / 4096.0f) * 32.0f;
            float y_inv_m2 = std::pow(y, 1.0f / m2);
            float num = std::max(0.0f, y_inv_m2 - c1);
            float den = c2 - c3 * y_inv_m2;
            if (den <= 0.00001f) return 1.0f;
            return std::pow(num / den, 1.0f / m1);
        }

        default:
            return y;
    }
}

void ColorScienceEngine::applyCdl(
    uint8_t* __restrict__ pixelData,
    int width,
    int height,
    int channels,
    const AscCdlParams& cdl
) noexcept {
    if (!pixelData || width <= 0 || height <= 0 || channels < 3) return;

    // Precompute 256-entry 1D LUTs for R, G, B channels
    // Reduces transcendental pow() evaluations from 36M to 768 per 12MP frame
    float rLut[256];
    float gLut[256];
    float bLut[256];

    for (int i = 0; i < 256; ++i) {
        float inVal = i / 255.0f;
        float baseR = std::max(0.0f, inVal * cdl.slopeR + cdl.offsetR);
        float baseG = std::max(0.0f, inVal * cdl.slopeG + cdl.offsetG);
        float baseB = std::max(0.0f, inVal * cdl.slopeB + cdl.offsetB);

        rLut[i] = (baseR > 0.0f || cdl.powerR > 0.0f) ? std::pow(baseR, cdl.powerR) : 0.0f;
        gLut[i] = (baseG > 0.0f || cdl.powerG > 0.0f) ? std::pow(baseG, cdl.powerG) : 0.0f;
        bLut[i] = (baseB > 0.0f || cdl.powerB > 0.0f) ? std::pow(baseB, cdl.powerB) : 0.0f;
    }

    const size_t totalPixels = static_cast<size_t>(width) * height;
    const bool adjustSat = (cdl.saturation != 1.0f);

    for (size_t i = 0; i < totalPixels; ++i) {
        uint8_t* px = pixelData + (i * channels);
        float r = rLut[px[0]];
        float g = gLut[px[1]];
        float b = bLut[px[2]];

        // Saturation adjustment
        if (adjustSat) {
            float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            r = luma + cdl.saturation * (r - luma);
            g = luma + cdl.saturation * (g - luma);
            b = luma + cdl.saturation * (b - luma);
        }

        px[0] = static_cast<uint8_t>(std::clamp(r * 255.0f, 0.0f, 255.0f));
        px[1] = static_cast<uint8_t>(std::clamp(g * 255.0f, 0.0f, 255.0f));
        px[2] = static_cast<uint8_t>(std::clamp(b * 255.0f, 0.0f, 255.0f));
    }
}

} // namespace commix
