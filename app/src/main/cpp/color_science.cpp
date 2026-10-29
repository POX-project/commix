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
            constexpr float a = 5.555556f;
            constexpr float b = 0.052272f;
            constexpr float c = 0.247190f;
            constexpr float d = 0.385537f;
            constexpr float cut = 0.010591f;
            constexpr float e = 5.367655f;
            constexpr float f = 0.092809f;
            if (x > cut) {
                return c * std::log10(a * x + b) + d;
            }
            return e * x + f;
        }

        case TransferCurveType::ArriLogC4: {
            constexpr float a = 2231.826309f;
            constexpr float b = 0.907135875f;
            constexpr float c = 0.092864125f;
            constexpr float s = 0.113597209f;
            constexpr float t = -0.018056996f;
            if (x >= t) {
                return (std::log2(a * x + 64.0f) - 6.0f) / 14.0f * b + c;
            }
            return (x - t) / s;
        }

        case TransferCurveType::SonySLog3: {
            if (x >= 0.01125f) {
                return (420.0f + std::log10((x + 0.01f) / (0.18f + 0.01f)) * 261.5f) / 1023.0f;
            }
            return (x * ((171.2102946929f - 95.0f) / 0.01125f) + 95.0f) / 1023.0f;
        }

        case TransferCurveType::CanonCLog2: {
            const float xr = x / 0.9f;
            if (xr < 0.0f) {
                return -(0.281863093f * std::log10(-xr * 87.09937546f + 1.0f) - 0.035388128f);
            }
            return 0.281863093f * std::log10(xr * 87.09937546f + 1.0f) + 0.035388128f;
        }

        case TransferCurveType::RedLog3G10: {
            constexpr float a = 0.224282f;
            constexpr float b = 155.975327f;
            constexpr float c = 0.01f;
            constexpr float g = 15.1927f;
            const float xr = x + c;
            if (xr < 0.0f) {
                return xr * g;
            }
            return a * std::log10(xr * b + 1.0f);
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
            constexpr float a = 5.555556f;
            constexpr float b = 0.052272f;
            constexpr float c = 0.247190f;
            constexpr float d = 0.385537f;
            constexpr float e = 5.367655f;
            constexpr float f = 0.092809f;
            constexpr float cutEncoded = 0.149657834f;
            if (y > cutEncoded) {
                return (std::pow(10.0f, (y - d) / c) - b) / a;
            }
            return (y - f) / e;
        }

        case TransferCurveType::SonySLog3: {
            if (y >= 171.2102946929f / 1023.0f) {
                return std::pow(10.0f, ((y * 1023.0f - 420.0f) / 261.5f)) * (0.18f + 0.01f) - 0.01f;
            }
            return (y * 1023.0f - 95.0f) * (0.01125f / (171.2102946929f - 95.0f));
        }

        case TransferCurveType::AppleLog: {
            constexpr float a = 0.25f;
            constexpr float b = 0.05f;
            return (std::pow(2.0f, (y - b) / a) - 1.0f) / 64.0f;
        }

        case TransferCurveType::ArriLogC4: {
            constexpr float a = 2231.826309f;
            constexpr float b = 0.907135875f;
            constexpr float c = 0.092864125f;
            constexpr float s = 0.113597209f;
            constexpr float t = -0.018056996f;
            if (y >= 0.0f) {
                const float p = 14.0f * (y - c) / b + 6.0f;
                return (std::pow(2.0f, p) - 64.0f) / a;
            }
            return y * s + t;
        }

        case TransferCurveType::CanonCLog2: {
            constexpr float a = 0.281863093f;
            constexpr float b = 87.09937546f;
            constexpr float c = 0.035388128f;
            if (y < c) {
                return (-(std::pow(10.0f, (c - y) / a) - 1.0f) / b) * 0.9f;
            }
            return (std::pow(10.0f, (y - c) / a) - 1.0f) / b * 0.9f;
        }

        case TransferCurveType::RedLog3G10: {
            constexpr float a = 0.224282f;
            constexpr float b = 155.975327f;
            constexpr float c = 0.01f;
            constexpr float g = 15.1927f;
            if (y < 0.0f) {
                return (y / g) - c;
            }
            return (std::pow(10.0f, y / a) - 1.0f) / b - c;
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
