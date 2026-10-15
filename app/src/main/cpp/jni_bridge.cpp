#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cstdint>
#include <vector>

#include "commix_telemetry.h"
#include "scopes_engine.h"
#include "image_pipeline.h"
#include "computational_photo.h"
#include "stabilization_engine.h"
#include "color_science.h"
#include "best_shot_engine.h"
#include "keyframe_engine.h"

#define TAG "CommixJniBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#include <mutex>

static commix::BestShotEngine g_best_shot_engine;
static commix::KeyframeEngine g_keyframe_engine;
static std::mutex g_keyframe_mutex;
static std::mutex g_best_shot_mutex;

static inline bool validateDirectBuffer(JNIEnv* env, jobject buf, size_t requiredBytes) {
    if (!buf) return false;
    void* addr = env->GetDirectBufferAddress(buf);
    if (!addr) return false;
    jlong cap = env->GetDirectBufferCapacity(buf);
    return cap >= 0 && static_cast<size_t>(cap) >= requiredBytes;
}

// ============================================================================
// NativeTelemetryBridge Methods
// ============================================================================

static jlong JNICALL native_get_buffer_pointer(JNIEnv* /*env*/, jclass /*clazz*/) {
    return reinterpret_cast<jlong>(commix_get_telemetry_buffer());
}

static void JNICALL native_write_audio(
    JNIEnv* /*env*/, jclass /*clazz*/,
    jfloat norm, jfloat db, jfloat left_db, jfloat right_db
) {
    commix_write_audio(norm, db, left_db, right_db);
}

static void JNICALL native_write_imu(
    JNIEnv* /*env*/, jclass /*clazz*/,
    jfloat pitch, jfloat roll, jfloat yaw
) {
    commix_write_imu(pitch, roll, yaw);
    commix::StabilizationEngine::instance().pushImuSample(
        0, 0.f, 0.f, 0.f, pitch, roll, yaw
    );
}

static void JNICALL native_write_lens(
    JNIEnv* /*env*/, jclass /*clazz*/,
    jfloat af_distance, jint af_state
) {
    commix_write_lens(af_distance, af_state);
}

static void JNICALL native_compute_scopes_direct(
    JNIEnv* env, jclass /*clazz*/,
    jobject y_buf, jobject u_buf, jobject v_buf,
    jint width, jint height, jint y_stride, jint uv_stride, jint uv_pixel_stride, jint step
) {
    if (!y_buf || !u_buf || !v_buf || width < 2 || height < 2 || y_stride < width || uv_stride < (width / 2) || uv_pixel_stride < 1) return;

    size_t reqY = static_cast<size_t>(height - 1) * y_stride + width;
    size_t reqUV = static_cast<size_t>((height / 2) - 1) * uv_stride + ((width / 2) - 1) * uv_pixel_stride + 1;

    if (!validateDirectBuffer(env, y_buf, reqY) ||
        !validateDirectBuffer(env, u_buf, reqUV) ||
        !validateDirectBuffer(env, v_buf, reqUV)) {
        return;
    }

    const uint8_t* y = static_cast<const uint8_t*>(env->GetDirectBufferAddress(y_buf));
    const uint8_t* u = static_cast<const uint8_t*>(env->GetDirectBufferAddress(u_buf));
    const uint8_t* v = static_cast<const uint8_t*>(env->GetDirectBufferAddress(v_buf));
    if (!y || !u || !v) return;

    auto* tb = commix_get_telemetry_buffer();
    if (!tb) return;

    commix_telemetry_write_begin();

    commix::ScopeOut out;
    out.luma = tb->luma_bins;
    out.rBins = tb->r_bins;
    out.gBins = tb->g_bins;
    out.bBins = tb->b_bins;
    out.waveAvg = tb->wave_cols_avg;
    out.waveMin = tb->wave_cols_min;
    out.waveMax = tb->wave_cols_max;
    out.paradeR = tb->parade_r;
    out.paradeG = tb->parade_g;
    out.paradeB = tb->parade_b;
    out.vscope = tb->vscope_samples;

    commix::compute_scopes(y, u, v, width, height, y_stride, uv_stride, uv_pixel_stride, step, out);

    tb->spot_ire = out.spotIre;
    tb->mean_u = out.meanU;
    tb->mean_v = out.meanV;
    tb->hi_clip_pct = out.hiClipPct;
    tb->lo_clip_pct = out.loClipPct;
    tb->vscope_count = out.vscopeCount;
    tb->has_scopes = 1;

    commix_telemetry_write_end();
}

static jboolean JNICALL native_read_telemetry(
    JNIEnv* env, jclass /*clazz*/,
    jfloatArray outFloats, jintArray outLumaBins, jfloatArray outWaveCols
) {
    auto* tb = commix_get_telemetry_buffer();
    if (!tb) return JNI_FALSE;

    uint32_t b1 = tb->seq_begin;
    if (b1 & 1u) return JNI_FALSE; // Writer active
    std::atomic_thread_fence(std::memory_order_acquire);

    // Snapshot fields to local stack memory
    jfloat f[10] = {
        tb->audio_norm, tb->audio_db, tb->audio_left_db, tb->audio_right_db,
        tb->gyro_pitch, tb->gyro_roll, tb->gyro_yaw,
        tb->spot_ire, tb->hi_clip_pct, tb->lo_clip_pct
    };
    int32_t lumaSnapshot[COMMIX_HIST_BINS];
    if (outLumaBins) {
        std::memcpy(lumaSnapshot, tb->luma_bins, sizeof(lumaSnapshot));
    }
    float waveSnapshot[COMMIX_WAVE_COLS];
    if (outWaveCols) {
        std::memcpy(waveSnapshot, tb->wave_cols_avg, sizeof(waveSnapshot));
    }

    std::atomic_thread_fence(std::memory_order_acquire);
    uint32_t b2 = tb->seq_end;
    if (b1 != b2) return JNI_FALSE; // Frame torn during read

    // Safely commit local snapshot to JVM arrays
    if (outFloats && env->GetArrayLength(outFloats) >= 10) {
        env->SetFloatArrayRegion(outFloats, 0, 10, f);
    }

    if (outLumaBins && env->GetArrayLength(outLumaBins) >= COMMIX_HIST_BINS) {
        env->SetIntArrayRegion(outLumaBins, 0, COMMIX_HIST_BINS, reinterpret_cast<const jint*>(lumaSnapshot));
    }

    if (outWaveCols && env->GetArrayLength(outWaveCols) >= COMMIX_WAVE_COLS) {
        env->SetFloatArrayRegion(outWaveCols, 0, COMMIX_WAVE_COLS, waveSnapshot);
    }

    return JNI_TRUE;
}

// ============================================================================
// NativeEngineBridge Methods
// ============================================================================

static jboolean JNICALL native_init_engine(JNIEnv* /*env*/, jclass /*clazz*/) {
    commix::ImagePipeline::initToneLuts();
    return JNI_TRUE;
}

static jboolean JNICALL native_process_zero_direct(
    JNIEnv* env, jclass /*clazz*/,
    jobject buffer, jint width, jint height, jint channels,
    jint curvePreset, jint grainPreset, jfloat grainIntensity
) {
    if (!buffer || width <= 0 || height <= 0 || channels <= 0) return JNI_FALSE;
    size_t required = static_cast<size_t>(width) * height * channels;
    if (!validateDirectBuffer(env, buffer, required)) return JNI_FALSE;

    uint8_t* ptr = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!ptr) return JNI_FALSE;

    auto curve = static_cast<commix::ToneCurvePreset>(curvePreset);
    auto grain = static_cast<commix::GrainPreset>(grainPreset);

    bool res = commix::ImagePipeline::processZero(ptr, width, height, channels, curve, grain, grainIntensity);
    return res ? JNI_TRUE : JNI_FALSE;
}

static void JNICALL native_apply_unsharp_mask_direct(
    JNIEnv* env, jclass /*clazz*/,
    jobject buffer, jint width, jint height, jint channels,
    jfloat amount, jfloat radius
) {
    if (!buffer || width <= 0 || height <= 0 || channels <= 0) return;
    size_t required = static_cast<size_t>(width) * height * channels;
    if (!validateDirectBuffer(env, buffer, required)) return;

    uint8_t* ptr = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!ptr) return;

    commix::ImagePipeline::applyUnsharpMask(ptr, width, height, channels, amount, radius);
}

static void JNICALL native_apply_cdl_direct(
    JNIEnv* env, jclass /*clazz*/,
    jobject buffer, jint width, jint height, jint channels,
    jfloat slopeR, jfloat slopeG, jfloat slopeB,
    jfloat offsetR, jfloat offsetG, jfloat offsetB,
    jfloat powerR, jfloat powerG, jfloat powerB,
    jfloat saturation
) {
    if (!buffer || width <= 0 || height <= 0 || channels <= 0) return;
    size_t required = static_cast<size_t>(width) * height * channels;
    if (!validateDirectBuffer(env, buffer, required)) return;

    uint8_t* ptr = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!ptr) return;

    commix::AscCdlParams cdl;
    cdl.slopeR = slopeR; cdl.slopeG = slopeG; cdl.slopeB = slopeB;
    cdl.offsetR = offsetR; cdl.offsetG = offsetG; cdl.offsetB = offsetB;
    cdl.powerR = powerR; cdl.powerG = powerG; cdl.powerB = powerB;
    cdl.saturation = saturation;

    commix::ColorScienceEngine::applyCdl(ptr, width, height, channels, cdl);
}

static void JNICALL native_start_imu_logging(JNIEnv* /*env*/, jclass /*clazz*/) {
    commix::StabilizationEngine::instance().startLogging();
}

static jboolean JNICALL native_stop_and_export_gyroflow(
    JNIEnv* env, jclass /*clazz*/,
    jstring outputPath, jstring cameraName, jdouble frameRate
) {
    if (!outputPath) return JNI_FALSE;
    const char* pathStr = env->GetStringUTFChars(outputPath, nullptr);
    const char* camStr = cameraName ? env->GetStringUTFChars(cameraName, nullptr) : "";

    bool success = commix::StabilizationEngine::instance().stopAndExportGyroflow(pathStr, camStr, frameRate);

    env->ReleaseStringUTFChars(outputPath, pathStr);
    if (cameraName) env->ReleaseStringUTFChars(cameraName, camStr);

    return success ? JNI_TRUE : JNI_FALSE;
}

static void JNICALL native_compute_l1_path(
    JNIEnv* env, jclass /*clazz*/,
    jfloatArray inputTraj, jfloatArray outputTraj, jint count, jfloat lambda
) {
    if (!inputTraj || !outputTraj || count <= 0) return;
    if (env->GetArrayLength(inputTraj) < count || env->GetArrayLength(outputTraj) < count) return;

    jfloat* inPtr = env->GetFloatArrayElements(inputTraj, nullptr);
    jfloat* outPtr = env->GetFloatArrayElements(outputTraj, nullptr);

    if (inPtr && outPtr) {
        commix::StabilizationEngine::computeL1Path(inPtr, outPtr, count, lambda);
        env->ReleaseFloatArrayElements(inputTraj, inPtr, JNI_ABORT);
        env->ReleaseFloatArrayElements(outputTraj, outPtr, 0);
    } else {
        if (inPtr) env->ReleaseFloatArrayElements(inputTraj, inPtr, JNI_ABORT);
        if (outPtr) env->ReleaseFloatArrayElements(outputTraj, outPtr, JNI_ABORT);
    }
}

static void JNICALL native_push_best_shot_velocity(
    JNIEnv* /*env*/, jclass /*clazz*/,
    jfloat gx, jfloat gy, jfloat gz
) {
    std::lock_guard<std::mutex> lock(g_best_shot_mutex);
    g_best_shot_engine.pushVelocity(gx, gy, gz);
}

static jboolean JNICALL native_evaluate_best_shot(
    JNIEnv* env, jclass /*clazz*/,
    jfloat spotIre, jfloat hiClipPct, jfloat loClipPct,
    jfloatArray outScores
) {
    commix::BestShotScore score;
    {
        std::lock_guard<std::mutex> lock(g_best_shot_mutex);
        score = g_best_shot_engine.evaluate(nullptr, spotIre, hiClipPct, loClipPct);
    }

    if (outScores && env->GetArrayLength(outScores) >= 4) {
        jfloat scores[4] = {
            score.stabilityScore,
            score.exposureScore,
            score.dynamicRangeScore,
            score.totalScore
        };
        env->SetFloatArrayRegion(outScores, 0, 4, scores);
    }

    return score.shouldTriggerShoot ? JNI_TRUE : JNI_FALSE;
}

static jboolean JNICALL native_stack_frames_direct(
    JNIEnv* env, jclass /*clazz*/,
    jobjectArray frameBuffers, jint frameCount,
    jint width, jint height, jint channels,
    jobject outBuffer, jboolean deepShadowBoost
) {
    if (!frameBuffers || frameCount <= 0 || !outBuffer || width <= 0 || height <= 0 || channels <= 0) return JNI_FALSE;

    size_t required = static_cast<size_t>(width) * height * channels;
    if (!validateDirectBuffer(env, outBuffer, required)) return JNI_FALSE;

    std::vector<const uint8_t*> pointers(frameCount);
    for (int i = 0; i < frameCount; ++i) {
        jobject buf = env->GetObjectArrayElement(frameBuffers, i);
        if (!buf) {
            return JNI_FALSE;
        }
        if (!validateDirectBuffer(env, buf, required)) {
            env->DeleteLocalRef(buf);
            return JNI_FALSE;
        }
        pointers[i] = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buf));
        env->DeleteLocalRef(buf);
    }
    uint8_t* outPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(outBuffer));
    if (!outPtr) return JNI_FALSE;

    return commix::ComputationalPhotoEngine::stackAstroFrames(
        pointers.data(), frameCount, width, height, channels, outPtr, deepShadowBoost
    ) ? JNI_TRUE : JNI_FALSE;
}

static void JNICALL native_keyframe_reset(JNIEnv* /*env*/, jclass /*clazz*/) {
    std::lock_guard<std::mutex> lock(g_keyframe_mutex);
    g_keyframe_engine.reset();
}

static void JNICALL native_keyframe_add_point(
    JNIEnv* /*env*/, jclass /*clazz*/,
    jint trackId, jfloat timeSec, jfloat value, jint curve
) {
    std::lock_guard<std::mutex> lock(g_keyframe_mutex);
    commix::KeyframePoint pt{timeSec, value, static_cast<commix::KeyframeCurve>(curve)};
    switch (trackId) {
        case 0: g_keyframe_engine.trackZoom.points.push_back(pt); break;
        case 1: g_keyframe_engine.trackIso.points.push_back(pt); break;
        case 2: g_keyframe_engine.trackShutter.points.push_back(pt); break;
        case 3: g_keyframe_engine.trackEv.points.push_back(pt); break;
        case 4: g_keyframe_engine.trackWb.points.push_back(pt); break;
        case 5: g_keyframe_engine.trackFocus.points.push_back(pt); break;
    }
}

static void JNICALL native_keyframe_start(JNIEnv* /*env*/, jclass /*clazz*/, jfloat durationSec) {
    std::lock_guard<std::mutex> lock(g_keyframe_mutex);
    g_keyframe_engine.durationSec = durationSec;
    g_keyframe_engine.start();
}

static void JNICALL native_keyframe_stop(JNIEnv* /*env*/, jclass /*clazz*/) {
    std::lock_guard<std::mutex> lock(g_keyframe_mutex);
    g_keyframe_engine.stop();
}

static jboolean JNICALL native_keyframe_tick(
    JNIEnv* env, jclass /*clazz*/,
    jfloat dt, jfloatArray outValues
) {
    commix::KeyframeBatchValues vals;
    bool active = false;
    {
        std::lock_guard<std::mutex> lock(g_keyframe_mutex);
        active = g_keyframe_engine.tick(dt, vals);
    }
    if (active && outValues && env->GetArrayLength(outValues) >= 6) {
        jfloat f[6] = {
            vals.zoom,
            static_cast<jfloat>(vals.iso),
            static_cast<jfloat>(vals.shutterNs),
            vals.ev,
            static_cast<jfloat>(vals.wbKelvin),
            vals.focusDiopters
        };
        env->SetFloatArrayRegion(outValues, 0, 6, f);
    }
    return active ? JNI_TRUE : JNI_FALSE;
}

// ============================================================================
// Registration Tables
// ============================================================================

static const JNINativeMethod g_telemetry_methods[] = {
    {(char*)"nativeGetBufferPointer", (char*)"()J", (void*)native_get_buffer_pointer},
    {(char*)"nativeWriteAudio", (char*)"(FFFF)V", (void*)native_write_audio},
    {(char*)"nativeWriteImu", (char*)"(FFF)V", (void*)native_write_imu},
    {(char*)"nativeWriteLens", (char*)"(FI)V", (void*)native_write_lens},
    {(char*)"nativeComputeScopesDirect", (char*)"(Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;IIIIII)V", (void*)native_compute_scopes_direct},
    {(char*)"nativeReadTelemetry", (char*)"([F[I[F)Z", (void*)native_read_telemetry}
};

static const JNINativeMethod g_engine_methods[] = {
    {(char*)"nativeInitEngine", (char*)"()Z", (void*)native_init_engine},
    {(char*)"nativeProcessZeroDirect", (char*)"(Ljava/nio/ByteBuffer;IIIIIF)Z", (void*)native_process_zero_direct},
    {(char*)"nativeApplyUnsharpMaskDirect", (char*)"(Ljava/nio/ByteBuffer;IIIFF)V", (void*)native_apply_unsharp_mask_direct},
    {(char*)"nativeApplyCdlDirect", (char*)"(Ljava/nio/ByteBuffer;IIIFFFFFFFFFF)V", (void*)native_apply_cdl_direct},
    {(char*)"nativeStartImuLogging", (char*)"()V", (void*)native_start_imu_logging},
    {(char*)"nativeStopAndExportGyroflow", (char*)"(Ljava/lang/String;Ljava/lang/String;D)Z", (void*)native_stop_and_export_gyroflow},
    {(char*)"nativeComputeL1Path", (char*)"([F[FIF)V", (void*)native_compute_l1_path},
    {(char*)"nativePushBestShotVelocity", (char*)"(FFF)V", (void*)native_push_best_shot_velocity},
    {(char*)"nativeEvaluateBestShot", (char*)"(FFF[F)Z", (void*)native_evaluate_best_shot},
    {(char*)"nativeStackFramesDirect", (char*)"([Ljava/nio/ByteBuffer;IIIILjava/nio/ByteBuffer;Z)Z", (void*)native_stack_frames_direct},
    {(char*)"nativeKeyframeReset", (char*)"()V", (void*)native_keyframe_reset},
    {(char*)"nativeKeyframeAddPoint", (char*)"(IFFI)V", (void*)native_keyframe_add_point},
    {(char*)"nativeKeyframeStart", (char*)"(F)V", (void*)native_keyframe_start},
    {(char*)"nativeKeyframeStop", (char*)"()V", (void*)native_keyframe_stop},
    {(char*)"nativeKeyframeTick", (char*)"(F[F)Z", (void*)native_keyframe_tick}
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOGE("Failed to get JNIEnv in JNI_OnLoad");
        return JNI_ERR;
    }

    // Register NativeTelemetryBridge
    jclass telemetryClass = env->FindClass("com/txor/commix/bridge/NativeTelemetryBridge");
    if (telemetryClass) {
        if (env->RegisterNatives(telemetryClass, g_telemetry_methods, sizeof(g_telemetry_methods) / sizeof(g_telemetry_methods[0])) < 0) {
            LOGE("Failed to register NativeTelemetryBridge natives");
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
            return JNI_ERR;
        }
    } else {
        LOGE("Class com/txor/commix/bridge/NativeTelemetryBridge not found");
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
    }

    // Register NativeEngineBridge
    jclass engineClass = env->FindClass("com/txor/commix/bridge/NativeEngineBridge");
    if (engineClass) {
        if (env->RegisterNatives(engineClass, g_engine_methods, sizeof(g_engine_methods) / sizeof(g_engine_methods[0])) < 0) {
            LOGE("Failed to register NativeEngineBridge natives");
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
            return JNI_ERR;
        }
    } else {
        LOGE("Class com/txor/commix/bridge/NativeEngineBridge not found");
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
    }

    LOGI("Commix JNI bridges successfully registered with JNI_OnLoad");
    return JNI_VERSION_1_6;
}

