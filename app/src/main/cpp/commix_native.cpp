#include "commix_telemetry.h"
#include "commix_scopes.h"
#include <jni.h>
#include <atomic>
#include <cstring>

static TelemetrySharedBuffer g_telemetry_buffer = {0};
static std::atomic<uint32_t> g_sequence_counter{0};

extern "C" {

TelemetrySharedBuffer* commix_get_telemetry_buffer(void) {
    return &g_telemetry_buffer;
}

void commix_write_audio(float norm, float db, float left_db, float right_db) {
    uint32_t seq = g_sequence_counter.fetch_add(1, std::memory_order_relaxed);
    uint32_t begin = (seq << 1) | 1; // odd
    g_telemetry_buffer.seq_begin = begin;
    std::atomic_thread_fence(std::memory_order_release);

    g_telemetry_buffer.audio_norm = norm;
    g_telemetry_buffer.audio_db = db;
    g_telemetry_buffer.audio_left_db = left_db;
    g_telemetry_buffer.audio_right_db = right_db;

    std::atomic_thread_fence(std::memory_order_release);
    g_telemetry_buffer.seq_end = begin + 1; // even
}

void commix_write_imu(float pitch, float roll, float yaw) {
    uint32_t seq = g_sequence_counter.fetch_add(1, std::memory_order_relaxed);
    uint32_t begin = (seq << 1) | 1; // odd
    g_telemetry_buffer.seq_begin = begin;
    std::atomic_thread_fence(std::memory_order_release);

    g_telemetry_buffer.gyro_pitch = pitch;
    g_telemetry_buffer.gyro_roll = roll;
    g_telemetry_buffer.gyro_yaw = yaw;

    std::atomic_thread_fence(std::memory_order_release);
    g_telemetry_buffer.seq_end = begin + 1; // even
}

// JNI bindings for Kotlin / Android side
JNIEXPORT jlong JNICALL
Java_com_txor_commix_NativeTelemetryBridge_getBufferPointer(JNIEnv* env, jclass clazz) {
    return reinterpret_cast<jlong>(&g_telemetry_buffer);
}

JNIEXPORT void JNICALL
Java_com_txor_commix_NativeTelemetryBridge_writeAudio(
    JNIEnv* env, jclass clazz, jfloat norm, jfloat db, jfloat left_db, jfloat right_db) {
    commix_write_audio(norm, db, left_db, right_db);
}

JNIEXPORT void JNICALL
Java_com_txor_commix_NativeTelemetryBridge_writeImu(
    JNIEnv* env, jclass clazz, jfloat pitch, jfloat roll, jfloat yaw) {
    commix_write_imu(pitch, roll, yaw);
}

JNIEXPORT void JNICALL
Java_com_txor_commix_NativeTelemetryBridge_computeScopesDirect(
    JNIEnv* env, jclass clazz,
    jobject y_buf, jobject u_buf, jobject v_buf,
    jint width, jint height, jint y_stride, jint uv_stride, jint uv_pixel_stride, jint step) {

    const uint8_t* y = static_cast<const uint8_t*>(env->GetDirectBufferAddress(y_buf));
    const uint8_t* u = static_cast<const uint8_t*>(env->GetDirectBufferAddress(u_buf));
    const uint8_t* v = static_cast<const uint8_t*>(env->GetDirectBufferAddress(v_buf));
    if (!y || !u || !v) return;

    uint32_t seq = g_sequence_counter.fetch_add(1, std::memory_order_relaxed);
    uint32_t begin = (seq << 1) | 1; // odd
    g_telemetry_buffer.seq_begin = begin;
    std::atomic_thread_fence(std::memory_order_release);

    commix::ScopeOut out;
    out.luma = g_telemetry_buffer.luma_bins;
    out.rBins = g_telemetry_buffer.r_bins;
    out.gBins = g_telemetry_buffer.g_bins;
    out.bBins = g_telemetry_buffer.b_bins;
    out.waveAvg = g_telemetry_buffer.wave_cols_avg;
    out.waveMin = g_telemetry_buffer.wave_cols_min;
    out.waveMax = g_telemetry_buffer.wave_cols_max;
    out.paradeR = g_telemetry_buffer.parade_r;
    out.paradeG = g_telemetry_buffer.parade_g;
    out.paradeB = g_telemetry_buffer.parade_b;
    out.vscope = g_telemetry_buffer.vscope_samples;

    commix::compute_scopes(y, u, v, width, height, y_stride, uv_stride, uv_pixel_stride, step, out);

    g_telemetry_buffer.spot_ire = out.spotIre;
    g_telemetry_buffer.mean_u = out.meanU;
    g_telemetry_buffer.mean_v = out.meanV;
    g_telemetry_buffer.hi_clip_pct = out.hiClipPct;
    g_telemetry_buffer.lo_clip_pct = out.loClipPct;
    g_telemetry_buffer.vscope_count = out.vscopeCount;
    g_telemetry_buffer.has_scopes = 1;

    std::atomic_thread_fence(std::memory_order_release);
    g_telemetry_buffer.seq_end = begin + 1; // even
}

} // extern "C"
