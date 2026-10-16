#pragma once
#include <cstdint>

#ifdef __cplusplus
#include <atomic>
using atomic_uint32 = std::atomic<uint32_t>;
#else
#include <stdatomic.h>
typedef _Atomic uint32_t atomic_uint32;
#endif

#ifdef __cplusplus
extern "C" {
#endif

// Geometry constants for Scopes
#define COMMIX_HIST_BINS 256
#define COMMIX_WAVE_COLS 64
#define COMMIX_PARADE_N 64
#define COMMIX_VSCOPE_MAX_SAMPLES 256

/**
 * Shared memory layout for telemetry transfer from
 * Android Camera2 / AudioRecord background threads directly to Kotlin / Compose UI.
 *
 * Sequence lock (seqlock) concurrency protocol with std::atomic counters:
 * 1. Writer increments `seq_begin` to an odd number prior to writing.
 * 2. Writer copies or calculates all audio, IMU, and scope metrics.
 * 3. Writer updates `seq_begin = odd + 1` (even number) and `seq_end = even`.
 * 4. Reader loads `seq_begin` with acquire semantics; if even, snapshots buffer and verifies
 *    re-read of `seq_begin == b1`. If mismatched or odd, reader rejects torn snapshot.
 */
#pragma pack(push, 4)
typedef struct {
    atomic_uint32 seq_begin;
    uint32_t has_scopes;

    // Audio VU Meters
    float audio_norm;
    float audio_db;
    float audio_left_db;
    float audio_right_db;

    // Motion & Orientation (IMU)
    float gyro_pitch;
    float gyro_roll;
    float gyro_yaw;

    // Lens & Focus
    float af_distance;
    int32_t af_state;

    // Histograms
    int32_t luma_bins[COMMIX_HIST_BINS];
    int32_t r_bins[COMMIX_HIST_BINS];
    int32_t g_bins[COMMIX_HIST_BINS];
    int32_t b_bins[COMMIX_HIST_BINS];

    // Waveform (Luma IRE 0..100)
    float wave_cols_avg[COMMIX_WAVE_COLS];
    float wave_cols_min[COMMIX_WAVE_COLS];
    float wave_cols_max[COMMIX_WAVE_COLS];

    // RGB Parade
    float parade_r[COMMIX_PARADE_N];
    float parade_g[COMMIX_PARADE_N];
    float parade_b[COMMIX_PARADE_N];

    // Vectorscope (Interleaved X, Y pairs in -1.0 .. 1.0)
    float vscope_samples[COMMIX_VSCOPE_MAX_SAMPLES * 2];
    int32_t vscope_count;

    // Center Spot Meter & Chroma Moments
    float spot_ire;
    float mean_u;
    float mean_v;
    float hi_clip_pct;
    float lo_clip_pct;

    atomic_uint32 seq_end;
} TelemetrySharedBuffer;
#pragma pack(pop)

/**
 * Returns a direct pointer to the process-global TelemetrySharedBuffer.
 */
TelemetrySharedBuffer* commix_get_telemetry_buffer(void);

/**
 * Updates audio VU telemetry with atomic seqlock.
 */
void commix_write_audio(float norm, float db, float left_db, float right_db);

/**
 * Updates IMU orientation telemetry with atomic seqlock.
 */
void commix_write_imu(float pitch, float roll, float yaw);

/**
 * Updates lens focus telemetry with atomic seqlock.
 */
void commix_write_lens(float af_distance, int32_t af_state);

/**
 * Seqlock write transaction demarcation for multi-field/scope writers.
 */
void commix_telemetry_write_begin(void);
void commix_telemetry_write_end(void);

#ifdef __cplusplus
}
#endif
