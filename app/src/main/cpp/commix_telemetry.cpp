#include "commix_telemetry.h"
#include <atomic>
#include <cstring>
#include <mutex>

static TelemetrySharedBuffer g_telemetry_buffer{};
static std::mutex g_telemetry_mutex;

extern "C" {

TelemetrySharedBuffer* commix_get_telemetry_buffer(void) {
    return &g_telemetry_buffer;
}

void commix_telemetry_write_begin(void) {
    g_telemetry_mutex.lock();
    uint32_t cur = g_telemetry_buffer.seq_begin.load(std::memory_order_seq_cst);
    uint32_t odd = (cur & ~1u) + 1u; // Strict odd: write in progress
    g_telemetry_buffer.seq_end.store(odd, std::memory_order_seq_cst);
    g_telemetry_buffer.seq_begin.store(odd, std::memory_order_seq_cst);
}

void commix_telemetry_write_end(void) {
    uint32_t cur = g_telemetry_buffer.seq_begin.load(std::memory_order_seq_cst);
    uint32_t even = (cur & 1u) ? cur + 1u : cur + 2u; // Strict even: write complete
    g_telemetry_buffer.seq_end.store(even, std::memory_order_seq_cst);
    g_telemetry_buffer.seq_begin.store(even, std::memory_order_seq_cst);
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
