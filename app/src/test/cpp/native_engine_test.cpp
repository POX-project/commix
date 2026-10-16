#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <iostream>
#include <vector>
#include <thread>
#include <atomic>

#include "../../main/cpp/computational_photo.h"
#include "../../main/cpp/stabilization_engine.h"
#include "../../main/cpp/scopes_engine.h"
#include "../../main/cpp/best_shot_engine.h"
#include "../../main/cpp/keyframe_engine.h"
#include "../../main/cpp/commix_telemetry.h"

using namespace commix;

void test_hyperfocal_calculation() {
    std::cout << "[RUN] test_hyperfocal_calculation..." << std::endl;
    // Standard 50mm, f/2.8, CoC = 0.03mm (Full Frame)
    // H = (0.05 * 0.05) / (2.8 * 0.00003) + 0.05 = 29.8119m
    float h = ComputationalPhotoEngine::calculateHyperfocalDistance(50.0f, 2.8f, 0.03f);
    assert(std::abs(h - 29.8119f) < 0.05f);

    // Guard test: negative or zero focal length must return 0.0f
    assert(ComputationalPhotoEngine::calculateHyperfocalDistance(0.0f, 2.8f, 0.03f) == 0.0f);
    assert(ComputationalPhotoEngine::calculateHyperfocalDistance(-50.0f, 2.8f, 0.03f) == 0.0f);

    // Guard test: non-positive fNumber or circleOfConfusion
    assert(ComputationalPhotoEngine::calculateHyperfocalDistance(50.0f, 0.0f, 0.03f) == 0.0f);
    assert(ComputationalPhotoEngine::calculateHyperfocalDistance(50.0f, -2.8f, 0.03f) == 0.0f);
    assert(ComputationalPhotoEngine::calculateHyperfocalDistance(50.0f, 2.8f, 0.0f) == 0.0f);
    assert(ComputationalPhotoEngine::calculateHyperfocalDistance(50.0f, 2.8f, -0.03f) == 0.0f);
    std::cout << "  [PASS] Hyperfocal distance calculation & bounds validation." << std::endl;
}

void test_astro_stacking_median() {
    std::cout << "[RUN] test_astro_stacking_median..." << std::endl;
    // 3 frames, 2x2 pixels, 3 channels (RGB)
    const int w = 2, h = 2, c = 3;
    const size_t totalBytes = w * h * c;

    std::vector<uint8_t> frame0(totalBytes, 10);
    std::vector<uint8_t> frame1(totalBytes, 90);
    std::vector<uint8_t> frame2(totalBytes, 40);

    const uint8_t* frames[3] = { frame0.data(), frame1.data(), frame2.data() };
    std::vector<uint8_t> out(totalBytes, 0);

    bool ok = ComputationalPhotoEngine::stackAstroFrames(frames, 3, w, h, c, out.data(), false);
    assert(ok);
    // Median of [10, 40, 90] is 40
    for (size_t i = 0; i < totalBytes; ++i) {
        assert(out[i] == 40);
    }

    // Invalid parameters guard test
    assert(!ComputationalPhotoEngine::stackAstroFrames(nullptr, 3, w, h, c, out.data(), false));
    assert(!ComputationalPhotoEngine::stackAstroFrames(frames, 0, w, h, c, out.data(), false));
    assert(!ComputationalPhotoEngine::stackAstroFrames(frames, 3, 0, h, c, out.data(), false));
    assert(!ComputationalPhotoEngine::stackAstroFrames(frames, 3, w, h, 2, out.data(), false));
    std::cout << "  [PASS] Astro median stacking & input bounds validation." << std::endl;
}

void test_long_exposure_modes() {
    std::cout << "[RUN] test_long_exposure_modes..." << std::endl;
    const int w = 2, h = 2, c = 3;
    const size_t totalPixels = w * h * c;

    std::vector<uint8_t> f0(totalPixels, 100);
    std::vector<uint8_t> f1(totalPixels, 50);
    std::vector<float> acc(totalPixels, 0.0f);

    // Frame 0 initialization
    bool ok0 = ComputationalPhotoEngine::accumulateLongExposure(f0.data(), acc.data(), w, h, c, 0, LongExposureStyle::SilkyWater, 0.5f);
    assert(ok0);
    assert(acc[0] == 100.0f);

    // Frame 1 with SilkyWater (alpha=0.5 -> 0.5 * 100 + 0.5 * 50 = 75)
    bool ok1 = ComputationalPhotoEngine::accumulateLongExposure(f1.data(), acc.data(), w, h, c, 1, LongExposureStyle::SilkyWater, 0.5f);
    assert(ok1);
    assert(std::abs(acc[0] - 75.0f) < 0.01f);

    // LightTrails (max blending: max(75, 120) = 120)
    std::vector<uint8_t> f2(totalPixels, 120);
    bool ok2 = ComputationalPhotoEngine::accumulateLongExposure(f2.data(), acc.data(), w, h, c, 2, LongExposureStyle::LightTrails, 0.5f);
    assert(ok2);
    assert(std::abs(acc[0] - 120.0f) < 0.01f);

    std::vector<uint8_t> finalized(totalPixels, 0);
    ComputationalPhotoEngine::finalizeLongExposure(acc.data(), finalized.data(), w * h, c);
    assert(finalized[0] == 120);
    std::cout << "  [PASS] Long exposure accumulation (SilkyWater & LightTrails)." << std::endl;
}

void test_multi_exposure_blend_modes() {
    std::cout << "[RUN] test_multi_exposure_blend_modes..." << std::endl;
    const int w = 2, h = 2, c = 3;
    const size_t total = w * h * c;

    std::vector<uint8_t> base(total, 200);
    std::vector<uint8_t> blend(total, 100);
    std::vector<uint8_t> out(total, 0);

    // Additive: 200 + 100 = 300 -> clamped to 255
    ComputationalPhotoEngine::blendMultiExposure(base.data(), blend.data(), out.data(), w, h, c, MultiExposureBlendMode::Additive);
    assert(out[0] == 255);

    // Average: (200 + 100) / 2 = 150
    ComputationalPhotoEngine::blendMultiExposure(base.data(), blend.data(), out.data(), w, h, c, MultiExposureBlendMode::Average);
    assert(out[0] == 150);

    // Darken: min(200, 100) = 100
    ComputationalPhotoEngine::blendMultiExposure(base.data(), blend.data(), out.data(), w, h, c, MultiExposureBlendMode::Darken);
    assert(out[0] == 100);

    // Lighten: max(200, 100) = 200
    ComputationalPhotoEngine::blendMultiExposure(base.data(), blend.data(), out.data(), w, h, c, MultiExposureBlendMode::Lighten);
    assert(out[0] == 200);
    std::cout << "  [PASS] Multi-exposure blend modes (Additive, Average, Darken, Lighten)." << std::endl;
}

void test_stabilization_engine() {
    std::cout << "[RUN] test_stabilization_engine..." << std::endl;
    auto& stab = StabilizationEngine::instance();

    // Push samples
    for (int i = 0; i < 100; ++i) {
        stab.pushImuSample(i * 1000000LL, 0.01f, -0.02f, 0.0f, 0.0f, 9.8f, 0.0f);
    }

    stab.startLogging();
    for (int i = 100; i < 200; ++i) {
        stab.pushImuSample(i * 1000000LL, 0.02f, -0.01f, 0.0f, 0.0f, 9.8f, 0.0f);
    }

    // Guard test: nullptr outputPath must return false without crash
    assert(!stab.stopAndExportGyroflow(nullptr, "TestCam", 30.0));
    assert(!stab.stopAndExportGyroflow("", "TestCam", 30.0));

    // Valid export
    const char* tmpFile = "/tmp/commix_gyroflow_test.csv";
    bool exported = stab.stopAndExportGyroflow(tmpFile, "TestCam", 30.0);
    assert(exported);

    FILE* fp = std::fopen(tmpFile, "r");
    assert(fp != nullptr);
    char line[128];
    char* l1 = std::fgets(line, sizeof(line), fp);
    assert(l1 != nullptr && std::strncmp(line, "GYROFLOW IMU LOG", 16) == 0);
    std::fclose(fp);
    std::remove(tmpFile);

    // Kalman filter test
    float p = 0.f, r = 0.f, y = 0.f;
    for (int i = 0; i < 20; ++i) {
        stab.updateKalman(1.0f, 2.0f, 3.0f, 0.016f, p, r, y);
    }
    // Should have converged towards 1.0, 2.0, 3.0
    assert(p > 0.8f && p <= 1.0f);
    assert(r > 1.6f && r <= 2.0f);
    assert(y > 2.4f && y <= 3.0f);
    std::cout << "  [PASS] Stabilization engine IMU ring buffer, Gyroflow export & Kalman filter." << std::endl;
}

void test_scopes_engine_neutral_and_bounds() {
    std::cout << "[RUN] test_scopes_engine_neutral_and_bounds..." << std::endl;
    const int w = 8, h = 8;
    std::vector<uint8_t> yPlane(w * h, 128); // Neutral grey 50%
    std::vector<uint8_t> uPlane((w / 2) * (h / 2), 128);
    std::vector<uint8_t> vPlane((w / 2) * (h / 2), 128);

    int32_t luma[HIST_BINS];
    int32_t rBins[HIST_BINS], gBins[HIST_BINS], bBins[HIST_BINS];
    float waveAvg[WAVE_COLS], waveMin[WAVE_COLS], waveMax[WAVE_COLS];
    float paradeR[PARADE_N], paradeG[PARADE_N], paradeB[PARADE_N];
    float vscope[VSCOPE_SAMPLES * 2];

    ScopeOut out;
    out.luma = luma;
    out.rBins = rBins;
    out.gBins = gBins;
    out.bBins = bBins;
    out.waveAvg = waveAvg;
    out.waveMin = waveMin;
    out.waveMax = waveMax;
    out.paradeR = paradeR;
    out.paradeG = paradeG;
    out.paradeB = paradeB;
    out.vscope = vscope;

    compute_scopes(yPlane.data(), uPlane.data(), vPlane.data(), w, h, w, w / 2, 1, 1, out);

    // All pixels are Y=128
    assert(out.luma[128] == w * h);
    assert(out.hiClipPct == 0.0f);
    assert(out.loClipPct == 0.0f);
    assert(std::abs(out.spotIre - 50.196f) < 1.0f);

    // Degraded buffer guard: width <= 0 or null pointers must return cleanly
    compute_scopes(nullptr, uPlane.data(), vPlane.data(), w, h, w, w / 2, 1, 1, out);
    compute_scopes(yPlane.data(), uPlane.data(), vPlane.data(), 0, 0, w, w / 2, 1, 1, out);
    std::cout << "  [PASS] Scopes engine neutral grey IRE & input bounds validation." << std::endl;
}

void test_telemetry_seqlock() {
    std::cout << "[RUN] test_telemetry_seqlock..." << std::endl;
    // Write audio and verify consistent seqlock sequence counter
    commix_write_audio(0.75f, -6.0f, -6.5f, -5.8f);
    auto* tb = commix_get_telemetry_buffer();
    assert(tb != nullptr);
    assert(tb->seq_begin.load() == tb->seq_end.load());
    assert((tb->seq_begin.load() & 1u) == 0); // Must be even (completed)
    assert(std::abs(tb->audio_norm - 0.75f) < 0.001f);
    assert(std::abs(tb->audio_db - -6.0f) < 0.001f);

    // While write_begin is in progress, sequence must be odd
    commix_telemetry_write_begin();
    assert(tb->seq_begin.load() & 1u);
    assert(tb->seq_end.load() & 1u); // Both begin and end must be odd during transaction
    commix_telemetry_write_end();
    assert((tb->seq_begin.load() & 1u) == 0);
    assert(tb->seq_begin.load() == tb->seq_end.load());

    // Concurrent reader/writer stress test:
    // Hammer buffer with high-frequency writes while reader validates seqlock contract
    std::atomic<bool> stopStress{false};
    std::atomic<size_t> tornReads{0};
    std::atomic<size_t> validReads{0};

    std::thread writerThread([&]() {
        for (int i = 0; i < 5000 && !stopStress.load(std::memory_order_relaxed); ++i) {
            float val = static_cast<float>(i);
            commix_write_audio(val, val, val, val);
        }
    });

    std::thread readerThread([&]() {
        auto* buf = commix_get_telemetry_buffer();
        for (int i = 0; i < 5000; ++i) {
            uint32_t s1 = buf->seq_begin.load(std::memory_order_acquire);
            uint32_t e1 = buf->seq_end.load(std::memory_order_acquire);
            if ((s1 & 1u) || (s1 != e1)) continue; // In-flight write rejected correctly
            std::atomic_thread_fence(std::memory_order_seq_cst);

            float n = buf->audio_norm;
            float db = buf->audio_db;
            float l = buf->audio_left_db;
            float r = buf->audio_right_db;

            std::atomic_thread_fence(std::memory_order_seq_cst);
            uint32_t s2 = buf->seq_begin.load(std::memory_order_acquire);
            uint32_t e2 = buf->seq_end.load(std::memory_order_acquire);
            if (s1 != s2 || s1 != e2) continue; // Torn read rejected correctly

            // If accepted, all 4 fields MUST match because writer wrote identical floats
            if (n != db || n != l || n != r) {
                tornReads.fetch_add(1, std::memory_order_relaxed);
            } else {
                validReads.fetch_add(1, std::memory_order_relaxed);
            }
        }
    });

    writerThread.join();
    stopStress.store(true);
    readerThread.join();

    assert(tornReads.load() == 0); // Must be strictly ZERO accepted torn reads
    assert(validReads.load() > 0);  // Must have successfully read valid frames
    std::cout << "  [PASS] Telemetry seqlock concurrency contract (" << validReads.load() << " valid reads, 0 torn reads)." << std::endl;
}

void test_best_shot_engine() {
    std::cout << "[RUN] test_best_shot_engine..." << std::endl;
    BestShotEngine engine;

    // 1. Without velocity history, stability is capped at 50% and trigger can never fire
    auto s0 = engine.evaluate(nullptr, 50.0f, 0.0f, 0.0f);
    assert(s0.stabilityScore == 50.0f);
    assert(!s0.shouldTriggerShoot);

    // 2. Severe shake camera (large angular velocities) -> stability drops to 0%
    for (int i = 0; i < 10; ++i) {
        engine.pushVelocity(0.5f, 0.5f, 0.5f);
    }
    auto sShaky = engine.evaluate(nullptr, 50.0f, 0.0f, 0.0f);
    assert(sShaky.stabilityScore == 0.0f);
    assert(!sShaky.shouldTriggerShoot);

    // 3. Peak stillness (near-zero angular velocity) + good exposure + zero clipping -> trigger fires
    for (int i = 0; i < 30; ++i) {
        engine.pushVelocity(0.005f, 0.005f, 0.005f);
    }
    auto sStill = engine.evaluate(nullptr, 50.0f, 0.0f, 0.0f);
    assert(sStill.stabilityScore >= 88.0f);
    assert(sStill.totalScore >= 82.0f);
    assert(sStill.shouldTriggerShoot);

    std::cout << "  [PASS] BestShotEngine peak stillness determination & trigger contract." << std::endl;
}

int main() {
    std::cout << "==========================================================" << std::endl;
    std::cout << " Commix Native C++20 Aether Engine Verification Suite" << std::endl;
    std::cout << "==========================================================" << std::endl;
    test_hyperfocal_calculation();
    test_astro_stacking_median();
    test_long_exposure_modes();
    test_multi_exposure_blend_modes();
    test_stabilization_engine();
    test_scopes_engine_neutral_and_bounds();
    test_telemetry_seqlock();
    test_best_shot_engine();
    std::cout << "==========================================================" << std::endl;
    std::cout << " ALL NATIVE C++ ENGINE TESTS PASSED PERFECTLY!" << std::endl;
    std::cout << "==========================================================" << std::endl;
    return 0;
}
