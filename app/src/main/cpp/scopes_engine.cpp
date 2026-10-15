#include "scopes_engine.h"
#include <algorithm>
#include <cmath>
#include <cstring>

#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace commix {

void compute_scopes(
    const uint8_t* __restrict__ y,
    const uint8_t* __restrict__ u,
    const uint8_t* __restrict__ v,
    int width,
    int height,
    int yStride,
    int uvStride,
    int uvPixelStride,
    int step,
    ScopeOut& out
) noexcept {
    if (!y || !u || !v || width <= 0 || height <= 0) return;
    step = step < 1 ? 1 : (step > 8 ? 8 : step);

    // Zero out histogram and bin outputs
    std::memset(out.luma, 0, HIST_BINS * sizeof(int32_t));
    std::memset(out.rBins, 0, HIST_BINS * sizeof(int32_t));
    std::memset(out.gBins, 0, HIST_BINS * sizeof(int32_t));
    std::memset(out.bBins, 0, HIST_BINS * sizeof(int32_t));

    float colSum[WAVE_COLS];
    float colMin[WAVE_COLS];
    float colMax[WAVE_COLS];
    int colN[WAVE_COLS];
    float parSumR[PARADE_N], parSumG[PARADE_N], parSumB[PARADE_N];
    int parN[PARADE_N];

    for (int i = 0; i < WAVE_COLS; ++i) {
        colSum[i] = 0.f;
        colMin[i] = 100.f;
        colMax[i] = 0.f;
        colN[i] = 0;
    }
    for (int i = 0; i < PARADE_N; ++i) {
        parSumR[i] = parSumG[i] = parSumB[i] = 0.f;
        parN[i] = 0;
    }

    double sumU = 0.0, sumV = 0.0;
    long nChroma = 0;
    int vscopeCount = 0;
    const int vscopeStride = step * 4;

    // Center spot window (central 10% of frame)
    double spotSum = 0.0;
    long spotN = 0;
    const int sx0 = width * 45 / 100, sx1 = width * 55 / 100;
    const int sy0 = height * 45 / 100, sy1 = height * 55 / 100;

    const int uvW = (width + 1) / 2;
    const int uvH = (height + 1) / 2;

    long totalPixels = 0;
    long hiClipCount = 0;
    long loClipCount = 0;

    for (int yy = 0; yy < height; yy += step) {
        const uint8_t* yRow = y + static_cast<size_t>(yy) * yStride;
        const int uvRow = (yy / 2 >= uvH) ? uvH - 1 : yy / 2;
        const uint8_t* uRow = u + static_cast<size_t>(uvRow) * uvStride;
        const uint8_t* vRow = v + static_cast<size_t>(uvRow) * uvStride;
        const int parRow = (yy * PARADE_N / height >= PARADE_N) ? PARADE_N - 1 : yy * PARADE_N / height;

        for (int xx = 0; xx < width; xx += step) {
            const int yi = yRow[xx];
            const int uvx = (xx / 2 >= uvW) ? uvW - 1 : xx / 2;
            const int ui = uRow[static_cast<size_t>(uvx) * uvPixelStride] - 128;
            const int vi = vRow[static_cast<size_t>(uvx) * uvPixelStride] - 128;

            // Integer BT.601 YUV -> RGB
            int r = yi + ((351 * vi) >> 8);
            int g = yi - ((179 * vi + 86 * ui) >> 8);
            int b = yi + ((444 * ui) >> 8);
            r = r < 0 ? 0 : (r > 255 ? 255 : r);
            g = g < 0 ? 0 : (g > 255 ? 255 : g);
            b = b < 0 ? 0 : (b > 255 ? 255 : b);

            out.luma[yi]++;
            out.rBins[r]++;
            out.gBins[g]++;
            out.bBins[b]++;
            totalPixels++;

            if (yi >= 250) hiClipCount++;
            if (yi <= 5) loClipCount++;

            const float ire = yi * (100.f / 255.f);
            int col = (xx * WAVE_COLS / width >= WAVE_COLS) ? WAVE_COLS - 1 : xx * WAVE_COLS / width;
            colSum[col] += ire;
            if (ire < colMin[col]) colMin[col] = ire;
            if (ire > colMax[col]) colMax[col] = ire;
            colN[col]++;

            parSumR[parRow] += r * (100.f / 255.f);
            parSumG[parRow] += g * (100.f / 255.f);
            parSumB[parRow] += b * (100.f / 255.f);
            parN[parRow]++;

            sumU += ui / 128.0;
            sumV += vi / 128.0;
            nChroma++;

            if ((xx % vscopeStride == 0) && (yy % vscopeStride == 0) && vscopeCount < VSCOPE_SAMPLES) {
                out.vscope[vscopeCount * 2] = static_cast<float>(vi / 128.0);
                out.vscope[vscopeCount * 2 + 1] = static_cast<float>(ui / 128.0);
                vscopeCount++;
            }

            if (xx >= sx0 && xx < sx1 && yy >= sy0 && yy < sy1) {
                spotSum += ire;
                spotN++;
            }
        }
    }

    for (int i = 0; i < WAVE_COLS; ++i) {
        if (colN[i] > 0) {
            out.waveAvg[i] = colSum[i] / colN[i];
            out.waveMin[i] = colMin[i];
            out.waveMax[i] = colMax[i];
        } else {
            out.waveAvg[i] = 0.f;
            out.waveMin[i] = 0.f;
            out.waveMax[i] = 0.f;
        }
    }

    for (int i = 0; i < PARADE_N; ++i) {
        if (parN[i] > 0) {
            out.paradeR[i] = parSumR[i] / parN[i];
            out.paradeG[i] = parSumG[i] / parN[i];
            out.paradeB[i] = parSumB[i] / parN[i];
        } else {
            out.paradeR[i] = 0.f;
            out.paradeG[i] = 0.f;
            out.paradeB[i] = 0.f;
        }
    }

    out.meanU = nChroma > 0 ? static_cast<float>(sumU / nChroma) : 0.f;
    out.meanV = nChroma > 0 ? static_cast<float>(sumV / nChroma) : 0.f;
    out.spotIre = spotN > 0 ? static_cast<float>(spotSum / spotN) : 45.f;
    out.hiClipPct = totalPixels > 0 ? (static_cast<float>(hiClipCount) / totalPixels) * 100.f : 0.f;
    out.loClipPct = totalPixels > 0 ? (static_cast<float>(loClipCount) / totalPixels) * 100.f : 0.f;
    out.vscopeCount = vscopeCount;
}

} // namespace commix
