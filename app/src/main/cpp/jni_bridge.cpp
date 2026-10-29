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

// A direct buffer address must not be read past the byte count the caller
// claims, and the claim itself must not be computed by arithmetic that can
// wrap. Returns 0 (a size no real buffer satisfies) when the geometry is
// unusable, so callers can treat 0 as "reject".
static inline size_t checkedImageBytes(jint width, jint height, jint channels) {
    if (width <= 0 || height <= 0 || channels <= 0) return 0;
    const size_t w = static_cast<size_t>(width);
    const size_t h = static_cast<size_t>(height);
    const size_t c = static_cast<size_t>(channels);
    constexpr size_t kMaxBytes = static_cast<size_t>(1) << 40;  // 1 TiB; no camera frame is near this
    if (w > kMaxBytes / h) return 0;
    const size_t pixels = w * h;
    if (pixels > kMaxBytes / c) return 0;
    return pixels * c;
}

// ============================================================================
// NativeTelemetryBridge Methods
// ============================================================================


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
    if (!y_buf || !u_buf || !v_buf || width < 2 || height < 2 || uv_pixel_stride < 1) return;

    // The required byte counts come from scopes_engine.h, the same helpers
    // compute_scopes() uses for its own row/column clamping. The previous inline
    // formula ((height/2 - 1) * uv_stride + (width/2 - 1) * uv_pixel_stride + 1)
    // under-counted by one chroma row and column on odd frame sizes, so it
    // accepted buffers the reader then walked off the end of.
    //
    // Both helpers return 0 for geometry they consider unusable; width and height
    // are already >= 2 here, so 0 always means a stride too small for the
    // claimed frame size and must be rejected.
    const size_t reqY = commix::luma_plane_bytes(width, height, y_stride);
    const size_t reqUV = commix::chroma_plane_bytes(width, height, uv_stride, uv_pixel_stride);
    if (reqY == 0 || reqUV == 0) return;

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

    thread_local int32_t local_luma[COMMIX_HIST_BINS];
    thread_local int32_t local_r[COMMIX_HIST_BINS];
    thread_local int32_t local_g[COMMIX_HIST_BINS];
    thread_local int32_t local_b[COMMIX_HIST_BINS];
    thread_local float local_wave_avg[COMMIX_WAVE_COLS];
    thread_local float local_wave_min[COMMIX_WAVE_COLS];
    thread_local float local_wave_max[COMMIX_WAVE_COLS];
    thread_local float local_parade_r[COMMIX_PARADE_N];
    thread_local float local_parade_g[COMMIX_PARADE_N];
    thread_local float local_parade_b[COMMIX_PARADE_N];
    thread_local float local_vscope[COMMIX_VSCOPE_MAX_SAMPLES * 2];

    commix::ScopeOut out;
    out.luma = local_luma;
    out.rBins = local_r;
    out.gBins = local_g;
    out.bBins = local_b;
    out.waveAvg = local_wave_avg;
    out.waveMin = local_wave_min;
    out.waveMax = local_wave_max;
    out.paradeR = local_parade_r;
    out.paradeG = local_parade_g;
    out.paradeB = local_parade_b;
    out.vscope = local_vscope;

    // 1. Heavy image scan is computed completely lock-free in thread-local memory
    commix::compute_scopes(y, u, v, width, height, y_stride, uv_stride, uv_pixel_stride, step, out);

    // 2. High-speed sub-microsecond atomic commit to shared telemetry buffer
    commix_telemetry_write_begin();
    std::memcpy(tb->luma_bins, local_luma, sizeof(local_luma));
    std::memcpy(tb->r_bins, local_r, sizeof(local_r));
    std::memcpy(tb->g_bins, local_g, sizeof(local_g));
    std::memcpy(tb->b_bins, local_b, sizeof(local_b));
    std::memcpy(tb->wave_cols_avg, local_wave_avg, sizeof(local_wave_avg));
    std::memcpy(tb->wave_cols_min, local_wave_min, sizeof(local_wave_min));
    std::memcpy(tb->wave_cols_max, local_wave_max, sizeof(local_wave_max));
    std::memcpy(tb->parade_r, local_parade_r, sizeof(local_parade_r));
    std::memcpy(tb->parade_g, local_parade_g, sizeof(local_parade_g));
    std::memcpy(tb->parade_b, local_parade_b, sizeof(local_parade_b));
    std::memcpy(tb->vscope_samples, local_vscope, sizeof(local_vscope));
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
    jfloatArray outFloats, jintArray outLumaBins, jfloatArray outWaveCols,
    jfloatArray outVscope
) {
    auto* tb = commix_get_telemetry_buffer();
    if (!tb) return JNI_FALSE;

    uint32_t b1 = tb->seq_begin.load(std::memory_order_acquire);
    uint32_t e1 = tb->seq_end.load(std::memory_order_acquire);
    if ((b1 & 1u) || (b1 != e1)) return JNI_FALSE; // Writer active
    std::atomic_thread_fence(std::memory_order_seq_cst);

    // Snapshot fields to local stack memory (including lens AF distance and state)
    jfloat f[13] = {
        tb->audio_norm, tb->audio_db, tb->audio_left_db, tb->audio_right_db,
        tb->gyro_pitch, tb->gyro_roll, tb->gyro_yaw,
        tb->spot_ire, tb->hi_clip_pct, tb->lo_clip_pct,
        static_cast<jfloat>(tb->vscope_count),
        tb->af_distance, static_cast<jfloat>(tb->af_state)
    };
    int32_t lumaSnapshot[COMMIX_HIST_BINS];
    if (outLumaBins) {
        std::memcpy(lumaSnapshot, tb->luma_bins, sizeof(lumaSnapshot));
    }
    float waveSnapshot[COMMIX_WAVE_COLS];
    if (outWaveCols) {
        std::memcpy(waveSnapshot, tb->wave_cols_avg, sizeof(waveSnapshot));
    }
    float vscopeSnapshot[COMMIX_VSCOPE_MAX_SAMPLES * 2];
    if (outVscope) {
        std::memcpy(vscopeSnapshot, tb->vscope_samples, sizeof(vscopeSnapshot));
    }

    std::atomic_thread_fence(std::memory_order_seq_cst);
    uint32_t b2 = tb->seq_begin.load(std::memory_order_acquire);
    uint32_t e2 = tb->seq_end.load(std::memory_order_acquire);
    if (b1 != b2 || b1 != e2) return JNI_FALSE; // Frame torn during read (writer started or finished during snapshot)

    // Safely commit local snapshot to JVM arrays
    if (outFloats) {
        jsize len = env->GetArrayLength(outFloats);
        if (len >= 13) {
            env->SetFloatArrayRegion(outFloats, 0, 13, f);
        } else if (len >= 11) {
            env->SetFloatArrayRegion(outFloats, 0, 11, f);
        } else if (len >= 10) {
            env->SetFloatArrayRegion(outFloats, 0, 10, f);
        }
    }

    if (outLumaBins && env->GetArrayLength(outLumaBins) >= COMMIX_HIST_BINS) {
        env->SetIntArrayRegion(outLumaBins, 0, COMMIX_HIST_BINS, reinterpret_cast<const jint*>(lumaSnapshot));
    }

    if (outWaveCols && env->GetArrayLength(outWaveCols) >= COMMIX_WAVE_COLS) {
        env->SetFloatArrayRegion(outWaveCols, 0, COMMIX_WAVE_COLS, waveSnapshot);
    }

    if (outVscope && env->GetArrayLength(outVscope) >= COMMIX_VSCOPE_MAX_SAMPLES * 2) {
        env->SetFloatArrayRegion(outVscope, 0, COMMIX_VSCOPE_MAX_SAMPLES * 2, vscopeSnapshot);
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
    size_t required = checkedImageBytes(width, height, channels);
    if (required == 0) return JNI_FALSE;
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
    size_t required = checkedImageBytes(width, height, channels);
    if (required == 0) return;
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
    size_t required = checkedImageBytes(width, height, channels);
    if (required == 0) return;
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

static void JNICALL native_push_imu_sample(
    JNIEnv* /*env*/, jclass /*clazz*/,
    jlong timestampNs,
    jfloat gx, jfloat gy, jfloat gz,
    jfloat ax, jfloat ay, jfloat az
) {
    commix::StabilizationEngine::instance().pushImuSample(
        static_cast<int64_t>(timestampNs),
        gx, gy, gz,
        ax, ay, az
    );
}

static jboolean JNICALL native_stop_and_export_gyroflow(
    JNIEnv* env, jclass /*clazz*/,
    jstring outputPath, jstring cameraName, jdouble frameRate
) {
    if (!outputPath) return JNI_FALSE;

    // GetStringUTFChars returns NULL when the VM cannot allocate the UTF-8 copy,
    // with OutOfMemoryError already pending. A NULL result must never be
    // dereferenced, passed to the exporter, or handed to ReleaseStringUTFChars;
    // the pending exception is left alone so it propagates to Java.
    const char* pathStr = env->GetStringUTFChars(outputPath, nullptr);
    if (!pathStr) return JNI_FALSE;

    const char* camStr = nullptr;
    if (cameraName) {
        camStr = env->GetStringUTFChars(cameraName, nullptr);
        if (!camStr) {
            env->ReleaseStringUTFChars(outputPath, pathStr);
            return JNI_FALSE;
        }
    }

    bool success = commix::StabilizationEngine::instance().stopAndExportGyroflow(
        pathStr, camStr, frameRate
    );

    if (cameraName) env->ReleaseStringUTFChars(cameraName, camStr);
    env->ReleaseStringUTFChars(outputPath, pathStr);

    return success ? JNI_TRUE : JNI_FALSE;
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

    // frameCount arrives as a jint and is not implied by the array: Java may pass
    // any value. Past the end of the array GetObjectArrayElement raises
    // ArrayIndexOutOfBoundsException, and the JNI specification forbids calling
    // any further JNI function while an exception is pending. Validate the count
    // against the real array length before touching the VM.
    const jsize arrayLength = env->GetArrayLength(frameBuffers);
    if (frameCount > arrayLength) {
        LOGE("nativeStackFramesDirect: frameCount=%d exceeds array length %d",
             frameCount, static_cast<int>(arrayLength));
        return JNI_FALSE;
    }

    // stackAstroFrames() stops at MAX_STACK_FRAMES and drops the rest without
    // saying so. Reject here instead of returning a stack built from fewer
    // frames than the caller asked for.
    if (frameCount > commix::ComputationalPhotoEngine::MAX_STACK_FRAMES) {
        LOGE("nativeStackFramesDirect: frameCount=%d exceeds engine maximum %d",
             frameCount, commix::ComputationalPhotoEngine::MAX_STACK_FRAMES);
        return JNI_FALSE;
    }

    const size_t required = checkedImageBytes(width, height, channels);
    if (required == 0) return JNI_FALSE;
    if (!validateDirectBuffer(env, outBuffer, required)) return JNI_FALSE;

    std::vector<const uint8_t*> pointers(static_cast<size_t>(frameCount));
    for (int i = 0; i < frameCount; ++i) {
        jobject buf = env->GetObjectArrayElement(frameBuffers, i);
        if (!buf) {
            // Either a null element or a pending exception: stop issuing JNI
            // calls immediately, do not clear the exception.
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
    {(char*)"nativeWriteAudio", (char*)"(FFFF)V", (void*)native_write_audio},
    {(char*)"nativeWriteImu", (char*)"(FFF)V", (void*)native_write_imu},
    {(char*)"nativeWriteLens", (char*)"(FI)V", (void*)native_write_lens},
    {(char*)"nativeComputeScopesDirect", (char*)"(Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;IIIIII)V", (void*)native_compute_scopes_direct},
    {(char*)"nativeReadTelemetry", (char*)"([F[I[F[F)Z", (void*)native_read_telemetry}
};

static const JNINativeMethod g_engine_methods[] = {
    {(char*)"nativeInitEngine", (char*)"()Z", (void*)native_init_engine},
    {(char*)"nativeProcessZeroDirect", (char*)"(Ljava/nio/ByteBuffer;IIIIIF)Z", (void*)native_process_zero_direct},
    {(char*)"nativeApplyUnsharpMaskDirect", (char*)"(Ljava/nio/ByteBuffer;IIIFF)V", (void*)native_apply_unsharp_mask_direct},
    {(char*)"nativeApplyCdlDirect", (char*)"(Ljava/nio/ByteBuffer;IIIFFFFFFFFFF)V", (void*)native_apply_cdl_direct},
    {(char*)"nativeStartImuLogging", (char*)"()V", (void*)native_start_imu_logging},
    {(char*)"nativePushImuSample", (char*)"(JFFFFFF)V", (void*)native_push_imu_sample},
    {(char*)"nativeStopAndExportGyroflow", (char*)"(Ljava/lang/String;Ljava/lang/String;D)Z", (void*)native_stop_and_export_gyroflow},
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

    LOGI("Successfully registered JNI native methods for Commix");
    return JNI_VERSION_1_6;
}
