#include "commix_telemetry.h"
#include <atomic>
#include <cstring>
#include <mutex>

static TelemetrySharedBuffer g_telemetry_buffer{};
static std::atomic<uint32_t> g_sequence_counter{0};
static std::mutex g_telemetry_mutex;
static thread_local uint32_t tl_write_seq = 0;

extern "C" {

TelemetrySharedBuffer* commix_get_telemetry_buffer(void) {
    return &g_telemetry_buffer;
}

void commix_telemetry_write_begin(void) {
    g_telemetry_mutex.lock();
    uint32_t seq = g_sequence_counter.load(std::memory_order_relaxed);
    tl_write_seq = (seq << 1) | 1u; // Guaranteed odd: write in progress
    g_telemetry_buffer.seq_end = tl_write_seq;
    g_telemetry_buffer.seq_begin = tl_write_seq;
    std::atomic_thread_fence(std::memory_order_release);
}

void commix_telemetry_write_end(void) {
    std::atomic_thread_fence(std::memory_order_release);
    uint32_t even = tl_write_seq + 1u; // Guaranteed even: write complete
    g_telemetry_buffer.seq_end = even;
    g_telemetry_buffer.seq_begin = even;
    g_sequence_counter.fetch_add(1, std::memory_order_relaxed);
    g_telemetry_mutex.unlock();
}

void commix_write_audio(float norm, float db, float left_db, float right_db) {
    commix_telemetry_write_begin();
    g_telemetry_buffer.audio_norm = norm;
    g_telemetry_buffer.audio_db = db;
    g_telemetry_buffer.audio_left_db = left_db;
    g_telemetry_buffer.audio_right_db = right_db;
    commix_telemetry_write_end();
}

void commix_write_imu(float pitch, float roll, float yaw) {
    commix_telemetry_write_begin();
    g_telemetry_buffer.gyro_pitch = pitch;
    g_telemetry_buffer.gyro_roll = roll;
    g_telemetry_buffer.gyro_yaw = yaw;
    commix_telemetry_write_end();
}

void commix_write_lens(float af_distance, int32_t af_state) {
    commix_telemetry_write_begin();
    g_telemetry_buffer.af_distance = af_distance;
    g_telemetry_buffer.af_state = af_state;
    commix_telemetry_write_end();
}

} // extern "C"
