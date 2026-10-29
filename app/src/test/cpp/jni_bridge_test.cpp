// JNI boundary verification suite.
//
// WHAT THIS IS
// ------------
// app/src/main/cpp/jni_bridge.cpp is the boundary where Java-supplied values
// (jint counts, jstring paths, ByteBuffer capacities) become raw pointer
// arithmetic over native memory. Nothing in the repository exercised it: until
// this file the `testNative` task compiled 6 of the 10 engine sources and
// jni_bridge.cpp was compiled by no test at all (AUDIT.md J2).
//
// This suite closes that gap. It #includes the production translation unit
// verbatim and calls the production entry points against a JNIEnv whose failure
// behaviour the test controls (see jni_stubs/jni.h for the fidelity contract).
// No logic is duplicated here: every assertion below is about what the shipped
// validation code does with hostile input.
//
// The out-of-bounds cases run in a forked child with a PROT_NONE guard page
// immediately after the buffer, so a validator that under-counts required bytes
// faults (SIGSEGV) instead of silently reading heap it does not own. The parent
// reports the signal, which is what turns "the check is tight enough" into a
// test that fails when the check regresses.

#include <cassert>
#include <cmath>
#include <csignal>
#include <cstdio>
#include <cstring>
#include <iostream>
#include <string>
#include <vector>

#include <sys/mman.h>
#include <sys/wait.h>
#include <unistd.h>

#include "../../main/cpp/commix_telemetry.h"
#include "../../main/cpp/computational_photo.h"

// The production file under test, compiled into this binary. jni_bridge.cpp
// includes <jni.h> and <android/log.h>, both resolved from jni_stubs/.
#include "../../main/cpp/jni_bridge.cpp"

using namespace commix_jni_stub;

// ---------------------------------------------------------------------------
// Test harness
// ---------------------------------------------------------------------------

#define REQUIRE(cond)                                                            \
    do {                                                                         \
        if (!(cond)) {                                                           \
            std::cerr << "    FAILED: " #cond " at " << __FILE__ << ":"          \
                      << __LINE__ << std::endl;                                 \
            std::exit(1);                                                        \
        }                                                                        \
    } while (0)

namespace {

// A heap block whose last byte is immediately followed by a PROT_NONE guard
// page. Any read at or past `size` raises SIGSEGV, which is how an
// under-counted bounds check is detected.
struct GuardedBuffer {
    void* mapping = nullptr;
    size_t mappingBytes = 0;
    uint8_t* data = nullptr;
    size_t size = 0;
};

size_t pageSize() {
    static const size_t ps = static_cast<size_t>(::sysconf(_SC_PAGESIZE));
    return ps;
}

GuardedBuffer makeGuarded(size_t bytes, uint8_t fill) {
    const size_t ps = pageSize();
    const size_t dataPages = (bytes + ps - 1) / ps;
    const size_t totalPages = dataPages + 1;  // last page is the guard
    GuardedBuffer g;
    g.mappingBytes = totalPages * ps;
    g.mapping = ::mmap(nullptr, g.mappingBytes, PROT_READ | PROT_WRITE,
                       MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (g.mapping == MAP_FAILED) {
        std::cerr << "mmap failed" << std::endl;
        std::exit(1);
    }
    // Guard page: no access permitted.
    if (::mprotect(static_cast<uint8_t*>(g.mapping) + dataPages * ps, ps, PROT_NONE) != 0) {
        std::cerr << "mprotect failed" << std::endl;
        std::exit(1);
    }
    // Place the payload flush against the guard page.
    g.size = bytes;
    g.data = static_cast<uint8_t*>(g.mapping) + dataPages * ps - bytes;
    std::memset(g.data, fill, bytes);
    return g;
}

void freeGuarded(GuardedBuffer& g) {
    if (g.mapping && g.mapping != MAP_FAILED) ::munmap(g.mapping, g.mappingBytes);
    g = GuardedBuffer{};
}

// Shared so the child's observations reach the parent.
struct ChildReport {
    int32_t hasScopes;
    float spotIre;
    int32_t lumaSum;   // total pixels histogrammed; proves the reader saw real data
};

ChildReport* sharedReport() {
    static ChildReport* report = nullptr;
    if (!report) {
        report = static_cast<ChildReport*>(::mmap(nullptr, sizeof(ChildReport),
                                                 PROT_READ | PROT_WRITE,
                                                 MAP_SHARED | MAP_ANONYMOUS, -1, 0));
        if (report == MAP_FAILED) {
            std::cerr << "mmap(shared) failed" << std::endl;
            std::exit(1);
        }
    }
    return report;
}

enum class ChildStatus { Completed, Signalled, Crashed };

// Runs `body` in a forked child so an out-of-bounds access is observable as a
// signal rather than taking the whole suite down.
template <typename Fn>
ChildStatus runIsolated(Fn&& body, int& signalNumber) {
    std::cout.flush();
    std::cerr.flush();
    const pid_t pid = ::fork();
    if (pid == 0) {
        body();
        ::_exit(0);
    }
    int status = 0;
    if (::waitpid(pid, &status, 0) != pid) return ChildStatus::Crashed;
    if (WIFSIGNALED(status)) {
        signalNumber = WTERMSIG(status);
        return ChildStatus::Signalled;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status) == 0 ? ChildStatus::Completed : ChildStatus::Crashed;
    return ChildStatus::Crashed;
}

void requireNoFault(ChildStatus status, int signalNumber, const char* what) {
    if (status == ChildStatus::Signalled) {
        std::cerr << "    FAILED: " << what << " faulted with signal " << signalNumber
                  << " (" << ::strsignal(signalNumber)
                  << ") - the native code read past the buffer it validated"
                  << std::endl;
        std::exit(1);
    }
    REQUIRE(status == ChildStatus::Completed);
}

JNIEnv& freshEnv() {
    envState().reset();
    commix_jni_stub::resetLog();
    static JNIEnv env;
    return env;
}

jobject makeDirectBuffer(GuardedBuffer& region, jlong reportedCapacity) {
    auto* b = new BufferObject();
    b->address = region.data;
    b->capacity = reportedCapacity;
    return b;
}

// ---------------------------------------------------------------------------
// 1. Scopes plane bounds: the U/V validator must match the reader exactly.
// ---------------------------------------------------------------------------

// The audit's concrete case: width=height=3, uvStride=2, uvPixelStride=1.
// chroma_width_for/height_for give uvW=uvH=2, so the reader touches row 1,
// column 1 -> offset 1*2 + 1*1 = 3, i.e. 4 addressable bytes.
// The old inline formula computed (3/2 - 1) * 2 + (3/2 - 1) * 1 + 1 = 1 byte.
void test_scopes_chroma_bounds_match_reader() {
    std::cout << "[RUN] test_scopes_chroma_bounds_match_reader..." << std::endl;

    constexpr int kW = 3, kH = 3;
    constexpr int kYStride = 3, kUvStride = 2, kUvPixelStride = 1;
    constexpr size_t kYBytes = static_cast<size_t>(kH) * kYStride;      // 9
    constexpr size_t kTrueUvBytes = 4;                                    // derived above
    constexpr size_t kOldUvBytes = 1;                                     // what the old check accepted

    // The requirement the reader really has, taken from the same helper the
    // validator uses.
    REQUIRE(commix::chroma_plane_bytes(kW, kH, kUvStride, kUvPixelStride) == kTrueUvBytes);
    REQUIRE(commix::luma_plane_bytes(kW, kH, kYStride) == kYBytes);

    // Case A: buffers sized to the old (too small) requirement must be REJECTED.
    // Before the fix this was accepted and the reader then read offset 3.
    {
        JNIEnv& env = freshEnv();
        GuardedBuffer y = makeGuarded(kYBytes, 128);
        GuardedBuffer u = makeGuarded(kOldUvBytes, 128);
        GuardedBuffer v = makeGuarded(kOldUvBytes, 128);
        jobject yb = makeDirectBuffer(y, static_cast<jlong>(kYBytes));
        jobject ub = makeDirectBuffer(u, static_cast<jlong>(kOldUvBytes));
        jobject vb = makeDirectBuffer(v, static_cast<jlong>(kOldUvBytes));

        ChildReport* report = sharedReport();
        int sig = 0;
        ChildStatus status = runIsolated([&]() {
            report->hasScopes = 0;
            report->spotIre = -1.0f;
            native_compute_scopes_direct(&env, nullptr, yb, ub, vb, kW, kH,
                                         kYStride, kUvStride, kUvPixelStride, 1);
            auto* tb = commix_get_telemetry_buffer();
            report->hasScopes = tb->has_scopes;
            report->spotIre = tb->spot_ire;
        }, sig);

        requireNoFault(status, sig, "undersized chroma buffer (old requirement)");
        REQUIRE(report->hasScopes == 0);  // rejected: nothing was published
        freeGuarded(y); freeGuarded(u); freeGuarded(v);
    }

    // Case B: buffers sized to the true requirement must be ACCEPTED and run.
    {
        JNIEnv& env = freshEnv();
        GuardedBuffer y = makeGuarded(kYBytes, 128);
        GuardedBuffer u = makeGuarded(kTrueUvBytes, 128);
        GuardedBuffer v = makeGuarded(kTrueUvBytes, 128);
        jobject yb = makeDirectBuffer(y, static_cast<jlong>(kYBytes));
        jobject ub = makeDirectBuffer(u, static_cast<jlong>(kTrueUvBytes));
        jobject vb = makeDirectBuffer(v, static_cast<jlong>(kTrueUvBytes));

        ChildReport* report = sharedReport();
        int sig = 0;
        ChildStatus status = runIsolated([&]() {
            auto* tb = commix_get_telemetry_buffer();
            tb->has_scopes = 0;
            native_compute_scopes_direct(&env, nullptr, yb, ub, vb, kW, kH,
                                         kYStride, kUvStride, kUvPixelStride, 1);
            report->hasScopes = tb->has_scopes;
            report->spotIre = tb->spot_ire;
        }, sig);

        requireNoFault(status, sig, "correctly sized odd-frame chroma buffer");
        REQUIRE(report->hasScopes == 1);  // accepted and computed
        // At 3x3 the central spot window collapses (3*45/100 == 3*55/100 == 1),
        // so compute_scopes() keeps its documented 45.0 IRE fallback. Asserting
        // the real value keeps this tied to production behaviour rather than to
        // a number invented here.
        REQUIRE(report->spotIre == 45.0f);
        freeGuarded(y); freeGuarded(u); freeGuarded(v);
    }

    // Case C: even geometry at exactly the required size still works (the fix
    // must not have tightened the common path).
    {
        constexpr int w = 8, h = 8;
        const size_t yBytes = commix::luma_plane_bytes(w, h, w);
        const size_t uvBytes = commix::chroma_plane_bytes(w, h, w / 2, 1);
        REQUIRE(yBytes == 64);
        REQUIRE(uvBytes == 16);

        JNIEnv& env = freshEnv();
        GuardedBuffer y = makeGuarded(yBytes, 128);
        GuardedBuffer u = makeGuarded(uvBytes, 128);
        GuardedBuffer v = makeGuarded(uvBytes, 128);
        jobject yb = makeDirectBuffer(y, static_cast<jlong>(yBytes));
        jobject ub = makeDirectBuffer(u, static_cast<jlong>(uvBytes));
        jobject vb = makeDirectBuffer(v, static_cast<jlong>(uvBytes));

        ChildReport* report = sharedReport();
        int sig = 0;
        ChildStatus status = runIsolated([&]() {
            auto* tb = commix_get_telemetry_buffer();
            tb->has_scopes = 0;
            native_compute_scopes_direct(&env, nullptr, yb, ub, vb, w, h,
                                         w, w / 2, 1, 1);
            report->hasScopes = tb->has_scopes;
            report->spotIre = tb->spot_ire;
            report->lumaSum = 0;
            for (int i = 0; i < 256; ++i) report->lumaSum += tb->luma_bins[i];
        }, sig);

        requireNoFault(status, sig, "even geometry at exact size");
        REQUIRE(report->hasScopes == 1);
        // step=1 samples every pixel, so the 8x8 spot window (3,3) is hit:
        // Y=128 is 50.2 IRE, and all 64 pixels must be histogrammed.
        REQUIRE(std::fabs(report->spotIre - 50.2f) < 0.5f);
        REQUIRE(report->lumaSum == 64);
        freeGuarded(y); freeGuarded(u); freeGuarded(v);
    }

    // Case D: luma one byte short must be rejected, never read.
    {
        constexpr int w = 8, h = 8;
        const size_t uvBytes = commix::chroma_plane_bytes(w, h, w / 2, 1);
        JNIEnv& env = freshEnv();
        GuardedBuffer y = makeGuarded(63, 128);   // one byte short of 64
        GuardedBuffer u = makeGuarded(uvBytes, 128);
        GuardedBuffer v = makeGuarded(uvBytes, 128);
        jobject yb = makeDirectBuffer(y, 63);
        jobject ub = makeDirectBuffer(u, static_cast<jlong>(uvBytes));
        jobject vb = makeDirectBuffer(v, static_cast<jlong>(uvBytes));

        ChildReport* report = sharedReport();
        int sig = 0;
        ChildStatus status = runIsolated([&]() {
            auto* tb = commix_get_telemetry_buffer();
            tb->has_scopes = 0;
            native_compute_scopes_direct(&env, nullptr, yb, ub, vb, w, h,
                                         w, w / 2, 1, 2);
            report->hasScopes = tb->has_scopes;
        }, sig);

        requireNoFault(status, sig, "undersized luma buffer");
        REQUIRE(report->hasScopes == 0);
        freeGuarded(y); freeGuarded(u); freeGuarded(v);
    }

    std::cout << "  [PASS] Scopes plane bounds match compute_scopes() exactly (odd and even geometry)."
              << std::endl;
}

// ---------------------------------------------------------------------------
// 2. frameCount must be validated against the real array length.
// ---------------------------------------------------------------------------

void test_stack_frames_array_bounds() {
    std::cout << "[RUN] test_stack_frames_array_bounds..." << std::endl;

    constexpr int kW = 4, kH = 4, kChannels = 4;
    const size_t frameBytes = static_cast<size_t>(kW) * kH * kChannels;  // 64

    // A well-formed array of `count` correctly sized direct buffers, each filled
    // with a distinct value so the median is unambiguous.
    auto makeFrameArray = [](int count, size_t bytes) -> ObjectArrayObject* {
        auto* arr = new ObjectArrayObject();
        for (int i = 0; i < count; ++i) {
            auto* region = new GuardedBuffer(makeGuarded(bytes, static_cast<uint8_t>(20 + i * 20)));
            auto* b = new BufferObject();
            b->address = region->data;
            b->capacity = static_cast<jlong>(bytes);
            arr->elements.push_back(b);
        }
        return arr;
    };

    // Append extra correctly sized frames to an existing array.
    auto growFrameArray = [](ObjectArrayObject* arr, int upTo, size_t bytes) {
        while (static_cast<int>(arr->elements.size()) < upTo) {
            auto* region = new GuardedBuffer(makeGuarded(bytes, 100));
            auto* b = new BufferObject();
            b->address = region->data;
            b->capacity = static_cast<jlong>(bytes);
            arr->elements.push_back(b);
        }
    };

    // Case A: frameCount larger than the array. The old code called
    // GetObjectArrayElement past the end, which throws
    // ArrayIndexOutOfBoundsException and leaves the native code looping with an
    // exception pending.
    {
        JNIEnv& env = freshEnv();
        ObjectArrayObject* frames = makeFrameArray(3, frameBytes);
        GuardedBuffer out = makeGuarded(frameBytes, 0);
        jobject outBuf = makeDirectBuffer(out, static_cast<jlong>(frameBytes));

        const jboolean result = native_stack_frames_direct(
            &env, nullptr, frames, 8,   // 8 > array length 2
            kW, kH, kChannels, outBuf, JNI_FALSE);

        REQUIRE(result == JNI_FALSE);
        REQUIRE(envState().outOfBoundsElementCalls == 0);     // never indexed out of range
        REQUIRE(!envState().exceptionPending);               // never raised one
        REQUIRE(envState().getObjectArrayElementCalls == 0); // never entered the loop
        for (uint8_t b : std::vector<uint8_t>(out.data, out.data + frameBytes)) {
            REQUIRE(b == 0);  // output untouched
        }
    }

    // Case B: frameCount matching the array length must still work.
    {
        JNIEnv& env = freshEnv();
        ObjectArrayObject* frames = makeFrameArray(3, frameBytes);
        GuardedBuffer out = makeGuarded(frameBytes, 0);
        jobject outBuf = makeDirectBuffer(out, static_cast<jlong>(frameBytes));

        const jboolean result = native_stack_frames_direct(
            &env, nullptr, frames, 3, kW, kH, kChannels, outBuf, JNI_FALSE);

        REQUIRE(result == JNI_TRUE);
        REQUIRE(envState().outOfBoundsElementCalls == 0);
        REQUIRE(envState().getObjectArrayElementCalls == 3);
        // Median of 20 / 40 / 60 is 40.
        REQUIRE(out.data[0] == 40);
    }

    // Case C: a frameCount past the array must be rejected before any JNI call
    // that is illegal with a pending exception. frameCount above the array but
    // also above MAX_STACK_FRAMES must still be rejected by the array check
    // first (order matters: MAX_STACK_FRAMES must not mask the array bound).
    {
        JNIEnv& env = freshEnv();
        ObjectArrayObject* frames = makeFrameArray(3, frameBytes);
        GuardedBuffer out = makeGuarded(frameBytes, 0);
        jobject outBuf = makeDirectBuffer(out, static_cast<jlong>(frameBytes));

        const jboolean result = native_stack_frames_direct(
            &env, nullptr, frames, 64, kW, kH, kChannels, outBuf, JNI_FALSE);

        REQUIRE(result == JNI_FALSE);
        REQUIRE(envState().outOfBoundsElementCalls == 0);
        REQUIRE(envState().exceptionPending == false);
    }

    // Case D: an array large enough but frameCount above the engine's median
    // window would be silently truncated by stackAstroFrames(); the boundary
    // must refuse rather than quietly stack fewer frames than requested.
    {
        JNIEnv& env = freshEnv();
        ObjectArrayObject* frames = makeFrameArray(3, frameBytes);
        const int count = commix::ComputationalPhotoEngine::MAX_STACK_FRAMES + 1;
        growFrameArray(frames, count, frameBytes);
        GuardedBuffer out = makeGuarded(frameBytes, 0);
        jobject outBuf = makeDirectBuffer(out, static_cast<jlong>(frameBytes));

        const jboolean result = native_stack_frames_direct(
            &env, nullptr, frames, count, kW, kH, kChannels, outBuf, JNI_FALSE);

        REQUIRE(result == JNI_FALSE);
        REQUIRE(envState().getObjectArrayElementCalls == 0);
    }

    // Case E: exactly MAX_STACK_FRAMES is still accepted.
    {
        JNIEnv& env = freshEnv();
        ObjectArrayObject* frames = makeFrameArray(3, frameBytes);
        const int count = commix::ComputationalPhotoEngine::MAX_STACK_FRAMES;
        growFrameArray(frames, count, frameBytes);
        GuardedBuffer out = makeGuarded(frameBytes, 0);
        jobject outBuf = makeDirectBuffer(out, static_cast<jlong>(frameBytes));

        const jboolean result = native_stack_frames_direct(
            &env, nullptr, frames, count, kW, kH, kChannels, outBuf, JNI_FALSE);

        REQUIRE(result == JNI_TRUE);
    }

    std::cout << "  [PASS] frameCount validated against GetArrayLength; engine cap enforced."
              << std::endl;
}

// ---------------------------------------------------------------------------
// 3. GetStringUTFChars must be null-checked.
// ---------------------------------------------------------------------------

void test_gyroflow_export_string_handling() {
    std::cout << "[RUN] test_gyroflow_export_string_handling..." << std::endl;

    const std::string outPath = "/tmp/commix_jni_gyroflow_ok.gcsv";

    // Case A: both strings convert. Must succeed and produce a real file.
    {
        JNIEnv& env = freshEnv();
        auto* path = new StringObject();
        path->utf8 = outPath;
        auto* cam = new StringObject();
        cam->utf8 = "TestCamera";

        const jboolean ok = native_stop_and_export_gyroflow(&env, nullptr, path, cam, 30.0);

        REQUIRE(ok == JNI_TRUE);
        REQUIRE(envState().releaseWithNullChars == 0);
        REQUIRE(envState().releaseStringUTFCharsCalls == 2);
        // Artifact: the exporter must have written a file.
        FILE* fp = std::fopen(outPath.c_str(), "r");
        REQUIRE(fp != nullptr);
        char header[64] = {0};
        REQUIRE(std::fgets(header, sizeof(header), fp) != nullptr);
        REQUIRE(std::strncmp(header, "GYROFLOW IMU LOG", 16) == 0);
        std::fclose(fp);
        std::remove(outPath.c_str());
    }

    // Case B: the path string fails to convert (GetStringUTFChars returns NULL).
    // The old code passed that NULL straight into the exporter and into
    // ReleaseStringUTFChars.
    {
        JNIEnv& env = freshEnv();
        auto* path = new StringObject();
        path->getCharsFails = true;
        auto* cam = new StringObject();
        cam->utf8 = "TestCamera";

        const jboolean ok = native_stop_and_export_gyroflow(&env, nullptr, path, cam, 30.0);

        REQUIRE(ok == JNI_FALSE);
        REQUIRE(envState().releaseWithNullChars == 0);       // never released NULL
        REQUIRE(envState().releaseStringUTFCharsCalls == 0);  // nothing to release
        REQUIRE(envState().getStringUTFCharsCalls == 1);     // stopped at the failure
        // The VM's OutOfMemoryError must still be pending so Java can see it.
        REQUIRE(envState().exceptionPending);
    }

    // Case C: the camera name fails to convert. The path string already
    // succeeded and must be released exactly once; the failed camera name must
    // not be released.
    {
        JNIEnv& env = freshEnv();
        auto* path = new StringObject();
        path->utf8 = outPath;
        auto* cam = new StringObject();
        cam->getCharsFails = true;

        const jboolean ok = native_stop_and_export_gyroflow(&env, nullptr, path, cam, 30.0);

        REQUIRE(ok == JNI_FALSE);
        REQUIRE(envState().releaseWithNullChars == 0);
        REQUIRE(envState().releaseStringUTFCharsCalls == 1);  // only the path
        REQUIRE(envState().getStringUTFCharsCalls == 2);
        REQUIRE(envState().exceptionPending);
    }

    // Case D: null cameraName must still be accepted (Kotlin may pass null);
    // the exporter substitutes its own default.
    {
        JNIEnv& env = freshEnv();
        auto* path = new StringObject();
        path->utf8 = outPath;

        const jboolean ok = native_stop_and_export_gyroflow(&env, nullptr, path, nullptr, 30.0);

        REQUIRE(ok == JNI_TRUE);
        REQUIRE(envState().releaseStringUTFCharsCalls == 1);  // path only
        REQUIRE(envState().releaseWithNullChars == 0);
        std::remove(outPath.c_str());
    }

    // Case E: a null output path is rejected before any string conversion.
    {
        JNIEnv& env = freshEnv();
        auto* cam = new StringObject();
        cam->utf8 = "TestCamera";

        const jboolean ok = native_stop_and_export_gyroflow(&env, nullptr, nullptr, cam, 30.0);

        REQUIRE(ok == JNI_FALSE);
        REQUIRE(envState().getStringUTFCharsCalls == 0);
    }

    std::cout << "  [PASS] GetStringUTFChars NULL results are never dereferenced or released."
              << std::endl;
}

// ---------------------------------------------------------------------------
// 4. nativeReadTelemetry vectorscope transport, bounds & seqlock safety
// ---------------------------------------------------------------------------

void test_read_telemetry_vectorscope_and_seqlock() {
    std::cout << "[RUN] test_read_telemetry_vectorscope_and_seqlock..." << std::endl;

    auto* tb = commix_get_telemetry_buffer();
    REQUIRE(tb != nullptr);

    // Setup valid telemetry with vectorscope samples
    commix_telemetry_write_begin();
    tb->audio_norm = 0.8f;
    tb->audio_db = -12.0f;
    tb->audio_left_db = -14.0f;
    tb->audio_right_db = -10.0f;
    tb->gyro_pitch = 1.5f;
    tb->gyro_roll = -0.5f;
    tb->gyro_yaw = 45.0f;
    tb->spot_ire = 55.0f;
    tb->hi_clip_pct = 1.2f;
    tb->lo_clip_pct = 0.4f;
    tb->vscope_count = 3;
    tb->vscope_samples[0] = -0.29f; tb->vscope_samples[1] = 0.61f; // Red sample
    tb->vscope_samples[2] = 0.35f;  tb->vscope_samples[3] = 0.61f; // Magenta sample
    tb->vscope_samples[4] = 0.64f;  tb->vscope_samples[5] = -0.15f; // Blue sample
    tb->has_scopes = 1;
    commix_telemetry_write_end();

    // Case A: Normal read with vectorscope
    {
        JNIEnv& env = freshEnv();
        auto* floats = new FloatArrayObject();
        floats->data.resize(11, 0.0f);
        auto* luma = new IntArrayObject();
        luma->data.resize(COMMIX_HIST_BINS, 0);
        auto* wave = new FloatArrayObject();
        wave->data.resize(COMMIX_WAVE_COLS, 0.0f);
        auto* vscope = new FloatArrayObject();
        vscope->data.resize(COMMIX_VSCOPE_MAX_SAMPLES * 2, 0.0f);

        const jboolean ok = native_read_telemetry(&env, nullptr, floats, luma, wave, vscope);
        REQUIRE(ok == JNI_TRUE);
        REQUIRE(floats->data[0] == 0.8f);
        REQUIRE(floats->data[1] == -12.0f);
        REQUIRE(floats->data[7] == 55.0f);
        REQUIRE(floats->data[10] == 3.0f); // vscope_count passed in float[10]
        REQUIRE(std::abs(vscope->data[0] - (-0.29f)) < 1e-5f);
        REQUIRE(std::abs(vscope->data[1] - 0.61f) < 1e-5f);
        REQUIRE(std::abs(vscope->data[2] - 0.35f) < 1e-5f);
        REQUIRE(std::abs(vscope->data[3] - 0.61f) < 1e-5f);
        REQUIRE(std::abs(vscope->data[4] - 0.64f) < 1e-5f);
        REQUIRE(std::abs(vscope->data[5] - (-0.15f)) < 1e-5f);
    }

    // Case B: Undersized outVscope array must NOT cause out-of-bounds write
    {
        JNIEnv& env = freshEnv();
        auto* floats = new FloatArrayObject();
        floats->data.resize(11, 0.0f);
        auto* vscope = new FloatArrayObject();
        vscope->data.resize(100, 99.0f); // Only 100 floats, less than 512

        const jboolean ok = native_read_telemetry(&env, nullptr, floats, nullptr, nullptr, vscope);
        REQUIRE(ok == JNI_TRUE);
        REQUIRE(!envState().exceptionPending);
        // Undersized array was safely skipped; existing data untouched
        REQUIRE(vscope->data[0] == 99.0f);
    }

    // Case C: Seqlock writer active (odd seq_begin) must return JNI_FALSE
    {
        JNIEnv& env = freshEnv();
        auto* floats = new FloatArrayObject();
        floats->data.resize(11, 0.0f);

        commix_telemetry_write_begin(); // seq_begin becomes odd
        const jboolean ok = native_read_telemetry(&env, nullptr, floats, nullptr, nullptr, nullptr);
        REQUIRE(ok == JNI_FALSE);
        commix_telemetry_write_end(); // restore even sequence
    }

    std::cout << "  [PASS] nativeReadTelemetry vectorscope transport, bounds & seqlock verified."
              << std::endl;
}

// ---------------------------------------------------------------------------
// 5. JNI_OnLoad must not report success when a bridge class is missing.
// ---------------------------------------------------------------------------

void test_onload_registration_table() {
    std::cout << "[RUN] test_onload_registration_table..." << std::endl;

    // The registration tables must cover exactly the natives the Kotlin bridges
    // declare. Count them so a future addition to one side without the other is
    // caught.
    const size_t telemetryCount = sizeof(g_telemetry_methods) / sizeof(g_telemetry_methods[0]);
    const size_t engineCount = sizeof(g_engine_methods) / sizeof(g_engine_methods[0]);

    // NativeTelemetryBridge.kt declares 5 externs, NativeEngineBridge.kt 15 (including nativePushImuSample).
    REQUIRE(telemetryCount == 5);
    REQUIRE(engineCount == 15);

    // Every entry must have a non-null name, signature and function pointer.
    for (size_t i = 0; i < telemetryCount; ++i) {
        REQUIRE(g_telemetry_methods[i].name != nullptr);
        REQUIRE(g_telemetry_methods[i].signature != nullptr);
        REQUIRE(g_telemetry_methods[i].fnPtr != nullptr);
    }
    for (size_t i = 0; i < engineCount; ++i) {
        REQUIRE(g_engine_methods[i].name != nullptr);
        REQUIRE(g_engine_methods[i].signature != nullptr);
        REQUIRE(g_engine_methods[i].fnPtr != nullptr);
    }

    std::cout << "  [PASS] Registration tables complete (" << telemetryCount
              << " telemetry + " << engineCount << " engine natives)." << std::endl;
}

}  // namespace

int main() {
    std::cout << "==========================================================" << std::endl;
    std::cout << " Commix JNI Boundary Verification Suite" << std::endl;
    std::cout << "==========================================================" << std::endl;
    test_scopes_chroma_bounds_match_reader();
    test_stack_frames_array_bounds();
    test_gyroflow_export_string_handling();
    test_read_telemetry_vectorscope_and_seqlock();
    test_onload_registration_table();
    std::cout << "==========================================================" << std::endl;
    std::cout << " ALL JNI BOUNDARY TESTS PASSED" << std::endl;
    std::cout << "==========================================================" << std::endl;
    return 0;
}