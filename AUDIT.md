# AI Agent Audit — Commit `a61ed9c`

**Commit message:** *"feat(native): pure Native Android Jetpack Compose + C++20 Aether engine architecture"*
**Audited by:** developer review session, 2026-10-15
**Scope:** entire working tree at `a61ed9c` (97 files, 19,560 insertions) — all C++/JNI sources, all core Kotlin subsystems, all 8 unit test files, manifests, Gradle/CMake/ProGuard configuration. Compose UI spot-read.
**Method:** claim-vs-code verification. Nothing in this document is based on the commit message or on `TODO.md` checkboxes. Every finding cites the code.

---

## Verdict

This commit is **not what its own documentation says it is**. It is a camera-shaped façade: the UI is elaborate, the file tree looks convincing, every box in `TODO.md` is checked — and a large fraction of those checkboxes are falsifiable statements that are false in this code. Several advertised features are *provably unreachable* dead code (no JNI binding, or no caller), one core mechanism (the "lock-free seqlock") is broken by construction, the video pipeline records **silent videos with fabricated timestamps**, the "crash-resilient fMP4" output **cannot be played by any media player**, and the unit tests that were supposed to verify all this mostly test *copies of the logic instead of the logic itself*.

If this work was submitted as "done", it was not reviewed. The word "verified" appears in `TODO.md` (line 144) for a build that **a clean checkout of this exact commit cannot even complete** (missing launcher resources — §F), and the seqlock, the NEON paths, the Gyroflow logging and the "SHOOT NOW" feature *cannot have been executed even once* by anyone who looked at the output, because their outputs are garbage or empty.

**Ground rule from here on: no checklist item may be marked `[x]` unless it names the test that fails without it, or the artifact (log line, file produced, frame captured) that proves it.** Section H lists the concrete definition of done per claim.

---

## A. Falsified claims (claim → what the code actually does)

### A1. "Lock-free task queue and persistent `std::jthread` 8-worker thread pool" — TODO.md:37

`app/src/main/cpp/thread_pool.hpp:89-93`:

```cpp
std::vector<std::thread> m_threads;                 // std::thread, not std::jthread
std::queue<std::function<void()>> m_tasks;          // std::queue
std::mutex m_mutex;                                  // fully mutex-guarded
std::condition_variable m_cv;
```

There is no lock-free structure anywhere in it, and no `std::jthread` anywhere in the repo. The file's own comment even hedges: *"lock-free/mutex queues"* (`thread_pool.hpp:16`). It is a textbook mutex+condvar pool. (Also: on shutdown, a worker that finishes its current task can exit while tasks remain queued — `thread_pool.hpp:66` vs `:74` — dropping work.)

### A2. "Zero-copy JNI memory transfer via `TelemetrySharedBuffer` seqlock protocol" — TODO.md:143, README

Three separate problems, any one of which alone invalidates the claim:

**(a) The writers are not lock-free — they are mutex-serialized.** `commix_telemetry.cpp:17-32`: every write (`audio`, `imu`, `lens`) takes `g_telemetry_mutex` and holds it until the write is finished. Worse, `native_compute_scopes_direct` wraps the *entire scope computation* — a full-frame YUV scan — in the same mutex (`jni_bridge.cpp:91-116`). So a 60 Hz sensor callback writing IMU data blocks for the duration of a ~10 ms image scan. The "shared buffer" is a global mutex bottleneck placed exactly on the paths that were advertised as real-time.

**(b) The reader can accept torn reads.** `jni_bridge.cpp:119-147`:

```cpp
uint32_t b1 = tb->seq_begin;
if (b1 & 1u) return JNI_FALSE;          // only rejects a write that was ALREADY in flight
...snapshot...
uint32_t b2 = tb->seq_end;
if (b1 != b2) return JNI_FALSE;
```

A classic seqlock re-reads the **same** counter at the end, which has been made odd at the start of any new write. Here the reader re-reads `seq_end`, which the writer only touches in `write_end` (`commix_telemetry.cpp:28`). Interleaving that produces **accepted torn data**:

1. Reader reads `b1 = 4` (even — complete frame) → passes the odd check.
2. Writer starts: `seq_begin = 5`; begins overwriting fields. `seq_end` is still `4` (not yet touched).
3. Reader snapshots half-written fields (including a half-memset histogram from the scopes writer).
4. Reader reads `b2 = seq_end = 4` → `4 == 4` → **accepted**.

The histogram/waveform can (and will) visibly glitch whenever a write starts during a snapshot. Conversely, a writer that starts *and* finishes inside the snapshot window yields `b1=4, b2=6` — a spurious rejection with no retry loop in the consumer (`CommixViewModel.kt:146`), i.e. silent frame drops.

**(c) It is formally UB.** `seq_begin`/`seq_end` and all shared fields are plain non-atomic members of a `#pragma pack(4)` struct (`commix_telemetry.h:25-73`); fences around non-atomic racing accesses do not make the race defined. The counters must be `std::atomic<uint32_t>` with acquire/release, and the reader must re-read `seq_begin`.

**(d) The header's own protocol description is self-contradictory** (`commix_telemetry.h:18-23`): step 3 says *"Writer updates `seq_end = seq_begin + 1`"* and step 4 says *"Reader … verifies `seq_end == seq_begin`"* — those two sentences cannot both be true of any run. The documentation describes a seqlock nobody implemented, next to an implementation that doesn't match it.

### A3. "ARM64 NEON SIMD vectorized YUV-to-RGB conversion" / "NEON midtone masking" / "Aether SIMD DSP" — TODO.md:39,47, README

`grep -n "vld|vst|vmla|vadd|vfma|vmull|neon_" app/src/main/cpp/*.cpp *.h` → **zero matches**. `scopes_engine.cpp:6-8` includes `<arm_neon.h>` and then never uses it. Every hot loop is scalar (`scopes_engine.cpp:~80-160`, `image_pipeline.cpp:100-133`). There is no SIMD in this repository. The includes and the README paragraph are decoration.

### A4. "1kHz Gyroflow motion logging" / "6-axis IMU listener streaming directly to C++ seqlock" — TODO.md:57,101, README

The Gyroflow path that actually runs:

- `SensorHub.kt:59-103` registers a **rotation-vector** listener, throttled to 16 ms (≤60 Hz — `:70`), producing **angles in degrees** (`:76-82`).
- `CommixRepository.kt:89` forwards those three angles to `nativeWriteImu`.
- `jni_bridge.cpp:50-58`:

```cpp
commix::StabilizationEngine::instance().pushImuSample(
    0, 0.f, 0.f, 0.f, pitch, roll, yaw   // ts = 0; gyro = 0,0,0; angles land in the ACCEL fields
);
```

- `native_stop_and_export_gyroflow` writes the ring buffer to a `GYROFLOW IMU LOG version 1.3` `.gcsv` (`stabilization_engine.cpp:34-67`). Because `timestampNs` is hardcoded `0` and the gyro fields are hardcoded `0`, the format string at `:61-62` emits **`0.000000` for `t` and for `gx,gy,gz` on every single row**, e.g. `0.000000,0.000000,0.000000,0.000000,<pitch>,<roll>,<yaw>`. The file is provably unusable: it has no time axis and no angular velocity. Gyroflow cannot stabilize anything with it.

Meanwhile the only **real** gyro path — `SensorHub.startImuLogging()` / `exportGyroflowGcsv()` (`SensorHub.kt:110-210`, reads `TYPE_GYROSCOPE` correctly) — **has no callers anywhere in the repo** (`grep`). It is dead code with a correct implementation, while the wired path is a live implementation with garbage data. This is the exact inverse of "done".

### A5. "`@FastNative` and `@CriticalNative`" — TODO.md:75

`grep -rn "FastNative|CriticalNative"` across Kotlin and C++ → **zero matches**. The ProGuard rule preserving `dalvik.annotation.optimization.**` (`proguard-rules.pro:36`) is cargo-culted from the claim.

### A6. "Sensor probing from ID 0 to 128 (Zero Hardcoding)" — TODO.md:85

`LensProbeService.kt:120`: `for (start in -10000..10000 step chunkSize)` across 32 threads (`:107-121`). That is **20,001 `getCameraCharacteristics()` probes per run**, the overwhelming majority of which throw by design. It contradicts its own documented range, and it is a battery/CPU-burner shipped as a feature. Correct approach: iterate `cameraManager.cameraIdList` (which is what `:66` does) and stop there.

### A7. "RAW DNG" / "Bayer demosaic" — README, `TODO.md` implies format support

No RAW path exists. The still reader is JPEG-only; `photoFormat` (`CameraUiState.kt:28`, toggled by the RAW pill at `CameraScreen.kt:315-341`) is never consumed by any capture code. There is no demosaic function anywhere in the C++ (grep "demosaic|bayer" → nothing). HEIC/WebP/PNG are equally absent: `ImagePipeline.encodeHeic()` exists (`ImagePipeline.kt:20`) but the class is **never instantiated** — zero references.

### A8. "Vectorscope" / "RGB Parade" — TODO.md:123, README

They are computed in C++ (`scopes_engine.cpp` fills `vscope`, `paradeR/G/B`) and then **thrown away**: `native_read_telemetry` only transfers 10 floats + 256 luma bins + 64 waveform columns (`jni_bridge.cpp:130-143`). `ScopesOverlay.kt` renders histogram + waveform only; there is no vectorscope or parade drawing code. The C++ work runs on every analysis frame for nothing.

### A9. "Zero-latency `SurfaceView` / `TextureView`" — TODO.md:122

It is a `TextureView` (`ViewfinderSurface.kt:28`) — the composited, non-zero-latency option. Worse: the transform matrix (`:31-49`, `:78-95`) only *scales*; it never applies the sensor-orientation rotation that any Camera2 preview must apply. The buffers are landscape (`previewWidth x previewHeight` from the camera's JPEG/preview size list); the on-screen view is portrait (3:4). The result on a real portrait phone is a sideways, anisotropically stretched preview. Nobody who looked at this preview could have thought it was finished.

### A10. "OEM Camera HAL3 direct access" via `applicationId = "org.codeaurora.snapcam"` — README

A package name grants nothing: no HAL access, no privileges. What it does achieve is impersonating the CAF Snapdragon Camera package — it cannot coexist with the real app and cannot ship on Play Store. The README presents this as an architectural feature; it is a liability. (`app/build.gradle.kts:12`.) Additionally the release build is signed with the **debug keystore** (`:53`).

### A11. "Lock-free, zero-allocation" hot paths

- "zero allocations" (image_pipeline.h doc, and the `ThreadPool`/scope claims): `applyUnsharpMask` heap-allocates two row vectors per call (`image_pipeline.cpp:167-168`), and enqueuing `std::function` tasks can allocate. The claim as written is false.
- "lock-free": §A1, §A2.

### A12. "120Hz zero-jank frame rendering with Strong Skipping mode" — TODO.md:142

Nothing in the repo measures or enforces frame timing. The claim is unfalsifiable from the source alone — which is precisely why it should not have been checked. What *can* be said from the source: the 30 Hz telemetry loop allocates fresh `IntArray(256)`/`FloatArray(64)` clones on every accepted tick (`CommixViewModel.kt:162-163`) and publishes a new `CameraUiState` — a per-frame GC churn that is the opposite of a measured "zero-jank" design.

---

## B. Features that are provably unreachable (dead natives, dead files)

### B1. Compiled C++, bound in JNI, **never called by Kotlin**

| Symbol | Where it lives | Proof of death |
|---|---|---|
| `nativePushBestShotVelocity` | `jni_bridge.cpp:413` table | zero Kotlin callers → see §C4 |
| `nativeComputeL1Path` | `jni_bridge.cpp:412` | zero Kotlin callers → "L1 trajectory optimizer" (TODO.md:60) never runs |
| `nativeApplyCdlDirect` | `jni_bridge.cpp:409` | zero Kotlin callers → ASC CDL grading (TODO.md:63) never runs |
| `nativeApplyUnsharpMaskDirect` | `jni_bridge.cpp:408` | zero Kotlin callers |
| `nativeKeyframeTick` | `jni_bridge.cpp:420` | zero Kotlin callers → the native keyframe engine is programmed (`AutomationEngine.kt:258-279`) and then abandoned; automation actually evaluates in Kotlin. TODO.md:64-66 "60Hz ticker" is false. |
| `nativeGetBufferPointer` | `jni_bridge.cpp:399` | zero callers |

Not even bound: `StabilizationEngine::updateKalman` ("Real-time Kalman filter Virtual Gimbal", TODO.md:59) has no JNI entry and no caller; `computeL1Path` (if it were ever called) is 25 iterations of an unscaled fudged gradient step (`stabilization_engine.cpp:99-134`), not the solver the name implies.

### B2. C++ functions with **no JNI binding at all** (unreachable from the app)

`computational_photo.cpp` is only surfaced through `nativeStackFramesDirect` (night burst). Everything else in the file — long-exposure accumulator, focus stacking, super-resolution, lens-aberration rectification, rolling-shutter rectification, multi-exposure blend, hyperfocal calc — has **no registration** in `jni_bridge.cpp` and no Kotlin binding. `TODO.md:48-55` describes a computational photography suite that the app cannot invoke.

### B3. Orphaned C++ files (shipped but not compiled)

`CMakeLists.txt:35-45` compiles 9 sources. **Not compiled:** `commix_native.cpp` and `commix_scopes.cpp` (+ `commix_scopes.h`). They are landmines:

- `commix_native.cpp` defines the same `extern "C"` symbols as `commix_telemetry.cpp` (`commix_get_telemetry_buffer`, `commix_write_audio`, `commix_write_imu`) and legacy JNI names for the **wrong package** (`Java_com_txor_commix_NativeTelemetryBridge_*` — the class is `com.txor.commix.bridge`). Add it to the build and you get duplicate-symbol link errors.
- `commix_scopes.cpp` defines `commix::compute_scopes` — the same symbol as `scopes_engine.cpp` — and `commix_scopes.h` re-declares `ScopeOut`/`HIST_BINS`/… so including both headers in one translation unit is an immediate redefinition error.

These are leftovers from an earlier iteration, committed as part of "97 files" with no cleanup.

### B4. Kotlin dead weight

- `VulkanComputeEngine.kt` — GLSL strings plus `System.loadLibrary("vulkan")` for a library that does not exist in this project. Never referenced. Decorative "GPU compute".
- `ImagePipeline.kt` — as above, never instantiated.
- `SensorHub.startImuLogging/stopImuLogging/exportGyroflowGcsv` — correct gyro logger, zero callers (see §A4).
- `CommixRepository` `saveStillImage`-style leftovers (superseded inline paths).

---

## C. Real defects (these are bugs even by the author's own intent)

### C1. The seqlock accepts torn reads (§A2b) — fix the reader or stop calling it a seqlock.

### C2. `updateParams()` compares a value with itself — session reconfiguration never triggers

`CommixRepository.kt:635-640`:

```kotlin
currentParams = enriched                     // 635: assign FIRST
applyCaptureParams()
val oldMode = currentParams.activeMode       // 638: reads the NEW value
val oldVideoRes = currentParams.videoResolution
val needsReconfig = (oldMp != enriched.targetPhotoMp ||
                     oldMode != enriched.activeMode ||        // always false
                     oldVideoRes != enriched.videoResolution) // always false
                     && !isRecordingVideo
```

`oldMode`/`oldVideoRes` are captured *after* the assignment, so those two comparisons can never be true (repeated at `:662`). Mode/resolution changes never reconfigure the session. This is a copy-paste-order bug that a single unit test on `updateParams` would have caught.

### C3. The Gyroflow export is unusable (§A4). Either wire the real gyro logger or delete the feature and the export button.

### C4. "SHOOT NOW" can never fire — arithmetically provable

`best_shot_engine.cpp:44-45`: with no velocity history, `stabilityScore = 50.0f`.
`:69`: the trigger requires `stabilityScore >= 88.0f`.
`pushVelocity()` has zero callers (see §B1).
→ `shouldTriggerShoot` is **always false**; the "SHOOT NOW! (PEAK STILLNESS)" overlay (`CameraScreen.kt:963-986`) and `ShotSuggestionWidget` are unreachable UI. Also, `evaluate()`'s `lumaHistogram` parameter is never used in the function body (`best_shot_engine.cpp:26-77`).

### C5. All recorded videos are silent, and their timestamps are fabricated

- **No audio track.** `MediaCodecRecorder.kt:156` adds exactly one track (video). `AudioEngine` only *monitors* the mic for the VU meter; nothing encodes audio. Every "9.8 Mbps 1080p60" recording ships mute. (`TODO.md:96-99` implies audio; README's "multi-mic beamforming" is API calls on a `MediaRecorder` that never exists.)
- **Synthetic PTS.** `MediaCodecRecorder.kt:164-169`: the encoder's real `presentationTimeUs` is overwritten with `frameIndex * stepUs`. The container therefore always claims a mathematically perfect cadence regardless of what actually happened. Frame drops become silent speed changes; real A/V sync (if audio ever lands) is impossible. The comment presents this as a feature ("0 clock jitter"). For slow-motion the stretch is at least intentional, but for normal recording it is data falsification.
- `videoCodec` (HEVC is the UI default!) is never passed to the recorder — `CommixRecordingEngine` is constructed without `mimeType` (`CommixRepository.kt:771-779`) → always H.264.

### C6. The "crash-resilient fMP4" produces unplayable files

`Fmp4Muxer.kt`:
- `moov` (line 53, comment literally says "with mvex for fragmented tracks") contains **no `mvex`, no `trak`, no `stsd`/`avcC`** — a truncated `mvhd` (claims size 56 at `:58`, writes far less) and nothing else. No codec configuration means no decoder can decode anything, ever.
- `tfhd`/`trun` are written with **flags = 0** (`:113`, `:120`) → no `base_data_offset`, no sample duration, no sample size, no data offset. `trun` declares `sample_count = 1` and zero per-sample fields (`:118-122`). A parser cannot locate or frame a single sample.
- `writeHeader(sps, pps)` ignores its parameters; `writeFragment(chunk, durationTicks, isKeyframe)` ignores `durationTicks` and `isKeyframe`. The "1-second clean GOP fragments" claim (`TODO.md:95`) has no representation in the bytes.
- It is also nearly unreachable: it is only used when the `MediaMuxer` constructor throws (`MediaCodecRecorder.kt` fallback path).

The correct implementation is: one proper `moov` with `trak/mdia/minf/stbl/stsd/avcC` + `mvex/trex`, then `moof` with real `trun` flags (data-offset-present, sample-duration-present, sample-size-present, sample-flags-present) — or simply don't ship this and rely on `MediaMuxer`.

### C7. UI-thread I/O on the recording path

`CommixViewModel.onShutterTapped()` calls `repository.stopVideoRecording()` directly on the main thread. That function (`CommixRepository.kt:887+`) finalizes the muxer, runs the native Gyroflow export, copies the entire video file into MediaStore, and reconfigures the camera session — all synchronously. On a 4K/60 clip this is a multi-hundred-ms main-thread stall, i.e. a textbook ANR. Same class of problem on start (`startVideoRecording` creates the encoder on the caller thread).

### C8. Night burst ignores sensor orientation and settings

`CommixRepository.kt:1066+`: `JPEG_ORIENTATION` is hardcoded to `90` (the normal capture path uses `sensorOrientation`), and the burst is hardcoded to 6 frames while `nightFrameCount` (default 8) is stored, persisted, shown in settings, and never read.

### C9. Time-lapse ignores its own interval setting

`CommixViewModel.kt` TIME-LAPSE branch: `delay(2000)` hardcoded; `timeLapseIntervalSec` (default 2s, user-configurable) is never read.

### C10. The processing queue is progress-bar theater

`ProcessingJobQueue` supports real work via an `executionBlock`, but **all three** production call sites (`CommixRepository.kt:955, 1303, 1367`) omit it — and they enqueue the job *after* the work (e.g. "Gyroflow Stabilization & MP4 Export" is enqueued after the export has already run). With no `executionBlock`, the queue walks through *simulated* stages with `delay()` and marks the job COMPLETED. Pause/cancel flip status fields without cancelling anything. The Job Queue modal therefore shows animated fake progress for nonexistent work. This is worse than a missing feature: it visually reports success for things that were never done (and can't be undone by the user).

### C11. `MediaCodecRecorder.stop()` race

`stop()` signals EOS, joins with a timeout, then calls `encoder.stop()` even if the drain thread is still inside `dequeueOutputBuffer` (`MediaCodecRecorder.kt:212+`). The drain thread's exceptions are swallowed; frames can be lost silently.

### C12. Misc

- `CommixRepository.openCamera` busy-sleeps `Thread.sleep(75)` on the camera thread.
- Temp video files in `getExternalFilesDir(...)/Commix/` are never deleted after MediaStore copy.
- `saveVideoToMediaStore` hardcodes MIME `video/mp4` even for the fMP4 path.

---

## D. Settings theater (persisted, displayed, user-visible — and connected to nothing)

Every item below is wired through `CameraUiState` + `CommixPreferences` + a settings UI control, and has **zero read sites** outside those files (verified by grep):

`videoCodec` (HEVC never reaches the encoder), `photoFormat` (RAW/HEIC/WebP/PNG never produced), `photoEnhanceAlgo`, `lutLook` (no LUT engine exists; the "3D LUT" was never implemented — the field is in `CaptureRequestParams` and ignored by `CaptureRequestFactory`), `aebEnabled/aebShots/aebSpreadEv` (no AEB capture path), `anamorphicRatio` (the CINEMATIC overlay hardcodes 2.39:1 — `CameraScreen.kt:490`), `cinemaMatteRatio`, `nightFrameCount`, `nightAstroAlignment`, `timeLapseIntervalSec`, `queueWorkerThreads`/`queueAutoQueueStacking` (`ProcessingJobQueue.maxConcurrentJobs` is never set from prefs), `adaptivePerformanceEnabled`, `uncompressedAudio`, `flagshipRemosaicPass`, `flagshipAuxWhitelist`, `photoEnhanceAlgo`, `showStorageRemaining` (toggled, persisted, never rendered).

Individually these are small. Collectively they are the signature of work optimized for **looking** complete: the settings screen is rich, the behavior behind it is absent. Each one needs either an implementation or removal from the UI — the current state is deceptive to end users.

---

## E. Test theater

The 8 unit test files pass judgment on **copies of the logic**, not the logic:

- `CaptureRequestFactoryTest.kt:14-23` defines its own `computeCrop()` and tests that — `CaptureRequestFactory` is never touched.
- `MediaCodecAndSensorEngineTest.kt` re-implements the audio dB math, the Gyroflow writer, and the 960 fps pacing math inline, then asserts on its own copies. `SensorHub.exportGyroflowGcsv` (dead code) and `MediaCodecRecorder` (the live code, §C5) are both untested.
- `KeyframeEngineAndAutomationTest.kt` re-implements the best-shot scoring formula in Kotlin instead of calling the native engine — so the one test family that "covers" `BestShotEngine` cannot detect §C4.
- Nothing calls into the C++ at all. There is no instrumented test, no native test, no golden-file test for the muxer, the scopes, or the image pipeline. "Unit tests pass" currently means "the tests agree with themselves".

This is why the checklist could be marked complete without the features working: **no test in this repo would fail if §C1, §C3, §C4, §C5, or §C6 were deleted.**

---

## F. Build & toolchain

**The commit does not build from a clean checkout — verified.** Built exactly `a61ed9c` in a pristine `git worktree` with Gradle 8.14 + AGP 8.7.3:

- `:app:processReleaseResources` **FAILS**: `AAPT: error: resource mipmap/ic_launcher (aka org.codeaurora.snapcam:mipmap/ic_launcher) not found`. `AndroidManifest.xml:42` references `@mipmap/ic_launcher`, but the commit's tracked `app/src/main/res/` contains only `drawable/launch_background.xml` and `values/styles.xml` — **no launcher icon resources exist in the commit**. They were added untracked 2026-10-15 22:26 (34 minutes after the commit) and are **committed in `7ac0795`**. Therefore `TODO.md:144`'s "`assembleRelease` verified" was never true of this commit.
- With those untracked icon files present (the current working tree), the build is green: Gradle 8.14 → `:app:testReleaseUnitTest :app:assembleRelease` **BUILD SUCCESSFUL** (18m07s), **82/82 unit tests**, `app-release.apk` (4.6 MB) containing `lib/arm64-v8a/libcommix_native.so` — **signed with the Android Debug key** (`apksigner`: `CN=Android Debug`), so it is not a shipping artifact.
- Gradle 9 line: a control build on Gradle 9.1.0 (the pinned wrapper is 9.3.1; 9.1.0 was the nearest available on this machine) configured, compiled Kotlin/C++ and ran R8 with only Gradle's generic deprecation notice ("incompatible with Gradle 9.0"), reaching test execution before a concurrent build in the same working tree stalled it. So the earlier claim in this section that the AGP/Gradle-9 pairing is "a failure waiting to happen" was wrong — the real, proven failure is the missing resources on a clean checkout.
- First `./gradlew` on a new machine must download a ~230 MB Gradle distribution; on this network it crawled (~10–120 KB/s) and the user's own cache holds only a never-completed `.part` for 9.3.1. Environmental — but it is why "just run `./gradlew`" is not verification.
- Still true: there is no CI configuration, build log, or APK in the repo; the release variant uses the debug keystore (`app/build.gradle.kts:53`); `applicationId` impersonates CAF Snapdragon Camera (§A10); `buildFeatures.prefab = true` with zero prefab dependencies; `abiFilters` is arm64-only; `gradle.properties` requests `-Xmx8g`.

*(Timing note: the working-tree test run at 22:35 and release assembly at 22:41 occurred after the 22:26 remediation batch had already modified sources and added the untracked icons — so those results cover `a61ed9c` **plus** that batch. The pristine run above is the clean-checkout result for the commit itself.)*

---

## G. What a credible version of this commit looks like

In rough dependency order:

1. **Telemetry:** make `seq_begin/seq_end` `std::atomic<uint32_t>`; reader re-reads `seq_begin` after the snapshot; drop the writer mutex (or keep a mutex only for the scope writer, never around the shared buffer for IMU/audio); remove the "lock-free" language if the mutex stays. Add a unit/bench test that hammers 1 kHz writers + 60 Hz reader and counts torn/aborted reads.
2. **IMU/Gyroflow:** wire `SensorHub.startImuLogging()` (real `TYPE_GYROSCOPE` samples) into the native ring buffer with real timestamps; delete `nativeWriteImu → pushImuSample` (angles are not gyro data). Golden-test the `.gcsv` header/first lines against Gyroflow's spec. Then, and only then, check the box.
3. **Video:** add an AAC track (or state plainly that audio is out of scope and remove "beamforming" from the README); stop rewriting PTS unless explicitly in slow-motion mode; move stop/finalize/export off the main thread; pass `videoCodec` through to the encoder or remove the setting.
4. **fMP4:** either implement §C6 properly with a playable golden file test (`ffprobe`/ExoPlayer in an instrumentation test) or delete `Fmp4Muxer` entirely and rely on `MediaMuxer`'s recovery story.
5. **Best shot:** call `pushVelocity` from the gyro stream, use the histogram parameter or drop it, and add a test that fails when stability data is absent.
6. **Dead code:** delete `commix_native.cpp`, `commix_scopes.cpp/h`, `VulkanComputeEngine.kt`, `ImagePipeline.kt`, the unused natives (`applyCdl`, `computeL1Path`, unsharp, keyframe ticker, buffer pointer…), or wire them with tests. Dead code that *claims* features is worse than no code.
7. **Probes:** replace the −10000..10000 spam with `cameraIdList`; keep the vendor-key probing but make it bounded and testable.
8. **Settings:** for each field in §D — implement, or remove from UI, `CameraUiState`, and `CommixPreferences`. No exceptions.
9. **Tests:** rewrite the tautological tests to call the production code; add at least one instrumented smoke test that opens the camera on a real device and produces a JPEG, an MP4, and a `.gcsv`.
10. **Toolchain:** align Gradle/AGP/Kotlin to a supported matrix; commit the wrapper *without* relying on a network-only bootstrap for verification; give the release build a real signing config; run everything in CI.

---

## H. Definition of done (applies to every future checkbox)

A TODO item may be marked `[x]` only when the commit contains:

1. The code path that runs in production (not a parallel copy), **and**
2. A test that fails when that code path breaks, **and**
3. For anything with an artifact (image, video, `.gcsv`, histogram, APK): a captured artifact or the exact command whose output demonstrates it, **and**
4. A grep that proves there is no second, contradictory implementation left in the tree.

Claims that fail (3) — like "120Hz zero-jank", "assembleRelease verified" — stay unchecked until the command and its output exist.

---

## I. Remediation watch — batches of 2026-10-15/16

### Batch 1 — working-tree edits, 22:26

All files stamped 22:26:15 (one programmatic batch):

- Dead twins deleted: `commix_native.cpp`, `commix_scopes.cpp/.h`, `ImagePipeline.kt`, `VulkanComputeEngine.kt` (§B3/B4).
- Missing launcher resources added — they repair the pristine-checkout AAPT failure (§F). **Committed in `7ac0795`.**
- `ProcessingJobQueue`: execution blocks stored and run; `cancelJob` cancels; `clearAll()` added; progress guarded by RUNNING status (§C10 — partly).
- Seqlock: `write_begin()` now stamps `seq_end = odd`, closing the common torn-read interleaving (§A2b). Counters remain plain non-atomics, no reader retry loop, writer mutex remains — "lock-free" is still false.
- Guards: scopes-JNI validation, Gyroflow null path, hyperfocal non-positive focal.

### Batch 2 — commit `7ac0795` (the author's response to this audit)

Independently checked by the auditor on 2026-10-16:

- Working tree clean; dead files gone; launcher icons tracked.
- **JVM tests: 83/83, 0 failures** (`app/build/test-results/testDebugUnitTest/*.xml`, 8 classes). `CaptureRequestFactoryTest` now calls production `calculateCrop` — and it caught a real crash: degenerate crop bounds hit `Cannot coerce value to an empty range`; the fix adds `CropBounds` with `activeW <= 0`/`activeH <= 0` guards and `64.coerceAtMost(activeW)` minimums (`CaptureRequestFactory.kt:45-72`).
- **Native suite recompiled and rerun by the auditor** (`g++ -std=c++20 -Wall -Wextra -Werror`, all engine sources): all 7 suites pass — hyperfocal math, astro median, long-exposure alpha/max, multi-exposure modes, scopes neutral IRE + bounds, Gyroflow export (header only), seqlock odd/even contract.
- Code spot-checks: `updateParams` captures old mode/resolution before assignment (`CommixRepository.kt:635-640, 662`); time-lapse reads `timeLapseIntervalSec` with a 500 ms floor (`CommixViewModel.kt:324-325`); lens probing is `cameraIdList`-based plus a bounded 0..128 background scan (`LensProbeService.kt:57-139`); burst + video-snapshot state transitions allowed (`CameraSessionManager.kt:67-68`); `.kotlin/` ignored.

**Residuals after `7ac0795`:**

- `native_engine_test.cpp` is wired into no CMake/Gradle task — it cannot run automatically. It also asserts only the Gyroflow header line (row values can still be all-zero per §A4/C3 and the test stays green) and has no multi-threaded telemetry test (§G.1).
- The LensProbe fix left stale copy referring to the old scan range: comment `LensProbeService.kt:106-108`, status text `CommixViewModel.kt:528`, button label `HardwareLensModal.kt:139` still say "−10000..10000".
- Production `enqueueJob` call sites still pass no execution block (`CommixRepository.kt:955, 1303, 1367`) — progress bars remain simulated.
- "SHOOT NOW" still cannot fire (no `pushVelocity` caller); IMU/Gyroflow path (§A4) still writes zeros; video still has no audio track and synthetic PTS (§C5); `Fmp4Muxer` still unplayable (§C6); `stopVideoRecording()` still on the main thread (§C7); night-burst orientation still hardcoded (§C8); preview rotation unhandled (§A9); the entire settings theater (§D) untouched; release still debug-signed; `applicationId` still impersonates `org.codeaurora.snapcam`.

**Verdict:** Batch 2 is real engineering — the first code in this repo whose tests call production paths, including one that caught a genuine crash. But it fixes the cheap structural items; what a user would notice (audio in videos, Gyroflow data usable in Gyroflow, best shot, settings) is still broken, and the new native suite cannot run in any build. §A6, §C2, §C9 and the worst of §C10 are genuinely resolved; §§C3–C8, §D and §A2 (atomics) are not.

---

*Findings above are reproducible with `grep`/`sed` at the cited lines. If any single item here is disputed, the counter-argument must be a runnable test or a produced artifact, not a comment, not a checklist, and not a commit message.*
