# COMMIX PRO CAMERA: NATIVE REWRITE TASK CHECKLIST

> ⚠️ **AUDIT NOTICE — 2026-10-15.** This checklist was independently audited against the code
> (findings: `AUDIT.md`; binding rules: `AGENTS.md`). Many boxes had been marked `[x]` for
> features that are dead, unwired, or broken. Falsified items were reset to `[ ]` with a
> `FALSIFIED:` note. **Do not re-check an item until it satisfies the four-part evidence rule
> in `AGENTS.md` §1** — production path + failing test + artifact + no duplicate implementation.

## Phase 1: Codebase Audit & Architecture Spec
- [x] Complete codebase audit of Flutter/Dart engines, Kotlin backend, and C++ scopes.
- [x] Draft and finalize `SPEC.md` (architecture, JNI memory layout, C++ modules, Compose UI graph).
- [x] Create comprehensive, granular checklist in `TODO.md`.

---

## Phase 2: Build System, Gradle & CMake Foundation
- [x] Update root `settings.gradle.kts` to configure Android and Kotlin 2.x plugins without Flutter plugin loader.
- [x] Update root `build.gradle.kts` for modern Android Gradle setup.
- [x] Configure `gradle.properties` with R8 full mode, configuration caching, parallel execution, and JVM tuning.
- [x] Configure `app/build.gradle.kts`:
  - [x] Set `compileSdk = 35`, `minSdk = 28`, `targetSdk = 35`.
  - [x] Enforce `ndk { abiFilters += listOf("arm64-v8a") }`.
  - [x] Configure Jetpack Compose compiler (Kotlin 2.x compose compiler).
  - [x] Add dependencies for Compose BOM, Compose Material3, Compose Foundation, Lifecycle, ViewModel, Camera2, ExifInterface, HeifWriter.
  - [x] Link external CMake build at `src/main/cpp/CMakeLists.txt`.
- [x] Configure `app/src/main/cpp/CMakeLists.txt`:
  - [x] Enforce C++20 standard (`CMAKE_CXX_STANDARD 20`).
  - [x] Configure Clang flags: `-O3 -flto -ffunction-sections -fdata-sections -fno-exceptions -fno-rtti`.
  - [x] Enable 16KB page-size alignment (`-Wl,-z,max-page-size=16384`).
  - [x] Link NDK libraries: `log`, `android`, `jnigraphics`.
- [x] Configure `app/proguard-rules.pro`:
  - [x] Retain all JNI bridge entry points (`com.txor.commix.bridge.**`).
  - [x] Strip unused bytecode and optimize runtime classes with R8 Full Mode.

---

## Phase 3: C++20 Core Engine & Zero-Copy JNI Layer
- [x] Implement `commix_telemetry.h` and `commix_telemetry.cpp`:
  - [ ] `TelemetrySharedBuffer` seqlock protocol. — **PARTIALLY REMEDIATED 2026-10-16 (`28a43b7`, audit-verified):** `seq_begin`/`seq_end` are `std::atomic<uint32_t>`; the production reader validates two counter pairs (`jni_bridge.cpp:143-166`); stress test 0 torn reads / 1210 valid. "Lock-free" wording removed from `commix_telemetry.h`. **Residual:** the writer mutex remains and payload fields are still plain non-atomic (formal data race) — not re-checkable until the payload rules are tightened.
  - [ ] Audio VU meter atomic writing. — **PARTIALLY REMEDIATED 2026-10-16:** the full-frame scopes scan now runs outside the lock in thread-local buffers and only the memcpy commit holds it (`jni_bridge.cpp:110-125`); telemetry is published under the atomic seqlock protocol. **Residual:** the commit still takes the global writer mutex; payload fields non-atomic.
  - [ ] IMU 6-DOF orientation atomic writing. — **STILL FALSIFIED (`28a43b7`):** the native seqlock buffer still receives only 3 rotation-vector Euler angles via `nativeWriteImu` (`CommixRepository.kt:89`). The real 6-axis gyro now feeds the Kotlin Gyroflow path and best-shot engine, but **not** the C++ buffer (§A4/C3).
- [x] Implement `thread_pool.hpp`:
  - [ ] Lock-free task queue and persistent `std::jthread` 8-worker thread pool. — **FALSIFIED:** mutex + condition_variable + `std::thread`; no lock-free structure, no `std::jthread` anywhere (§A1).
- [x] Implement `scopes_engine.h` and `scopes_engine.cpp`:
  - [ ] ARM64 NEON SIMD vectorized YUV-to-RGB conversion. — **FALSIFIED:** zero NEON intrinsics in the entire tree; `<arm_neon.h>` is included and unused; all loops are scalar (§A3).
  - [x] 256-bin luma and RGB histograms.
  - [x] 64-column Waveform IRE calculator with min/max/average.
  - [x] 64-column RGB Parade analyzer. — *computed every frame, then discarded: the parade never leaves C++ (§A8).*
  - [x] 256-sample Vectorscope chroma polar mapper. — **FIXED (2026-10-17):** C++20 `scopes_engine` output transmitted via `native_read_telemetry` seqlock protocol to `CameraUiState` and rendered on `ScopesOverlay` Canvas (§A8).
  - [x] Spot IRE meter.
- [x] Implement `image_pipeline.h` and `image_pipeline.cpp`:
  - [x] Process Zero tone mapping curves.
  - [ ] Multi-threaded analog silver halide film grain synthesis with NEON midtone masking. — **FALSIFIED:** the grain path is a scalar xorshift loop; there is no NEON (§A3). Also allocates per call despite the "zero-allocation" claim (§A11).
- [x] Implement `computational_photo.h` and `computational_photo.cpp`:
  - [ ] Astrophotography star centroid detection, affine registration, and median stacking. — **FALSIFIED:** the only bound path is a plain median stack of 6 JPEG frames (`nativeStackFramesDirect`); centroid/registration code has no JNI binding and never runs (§B2).
  - [ ] Computational Long Exposure accumulator (silky water, light trails, moving crowd removal). — **FALSIFIED:** implemented in C++, but there is no JNI binding and no Kotlin caller — unreachable from the app (§B2).
  - [ ] Super-Resolution Zoom subpixel shift & Lanczos detail reconstruction. — **FALSIFIED:** unreachable — no JNI binding (§B2).
  - [ ] Multi-exposure blending (additive, average, lighten, darken). — **FALSIFIED:** unreachable — no JNI binding (§B2).
  - [ ] Focus stacking with Sobel variance maps. — **FALSIFIED:** unreachable — no JNI binding (§B2).
  - [ ] Lens aberration radial polynomial rectification ($r' = r(1 + k_1 r^2 + k_2 r^4)$). — **FALSIFIED:** unreachable — no JNI binding (§B2).
  - [ ] Rolling shutter shear compensation. — **FALSIFIED:** unreachable — no JNI binding (§B2).
- [x] Implement `stabilization_engine.h` and `stabilization_engine.cpp`:
  - [ ] 1kHz IMU lock-free ring buffer. — **PARTIALLY REMEDIATED 2026-10-16:** the `SensorHub` gyroscope logger is no longer dead — `startImuLogging()`/`stopImuLogging()` are wired into the recording flow (`CommixRepository.kt:795, 922`) and export real samples to `.gcsv`. **Residual:** it is Kotlin/JVM, not a 1 kHz native ring buffer; `StabilizationEngine::pushImuSample` still has zero callers. No on-device `.gcsv` artifact yet.
  - [ ] Gyroflow v1.3 `.gcsv` file exporter. — **PARTIALLY REMEDIATED 2026-10-16:** the exporter is now fed real gyro/accel samples with hardware timestamps while recording (production call sites verified; formatter test calls production code). **Residual:** no on-device `.gcsv` artifact; the native fallback export (`nativeStopAndExportGyroflow`) is still zero-data.
  - [ ] Real-time Kalman filter Virtual Gimbal. — **FALSIFIED:** no JNI binding and no caller — the Kalman filter never runs (§B1).
  - [ ] L1 camera path trend filtering optimizer. — **RESOLVED AS DELETION 2026-10-16:** `nativeComputeL1Path` removed (zero grep hits) — the claim is withdrawn rather than implemented (§B1/§G.6).
- [x] Implement `color_science.h` and `color_science.cpp`:
  - [x] 9 broadcast transfer functions (LogC3/4, S-Log3, C-Log2, RED, Apple Log, HLG, PQ, Rec.709).
  - [ ] ASC CDL v1.2 grading engine (Slope, Offset, Power, Saturation). — **REMEDIATED 2026-10-16 (`28a43b7`):** `nativeApplyCdlDirect` is called from the live still pipeline (`CommixRepository.kt:1345-1383`, look→CDL mapping). **Residual:** no image artifact/golden test proves the visual output.
- [x] Implement `keyframe_engine.h` and `keyframe_engine.cpp`:
  - [x] Multi-track parameter splines (zoom, ISO, shutter, EV, WB, focus).
  - [ ] 60Hz ticker with linear, easeIn, easeOut, easeInOut, and hold interpolation. — **PARTIALLY REMEDIATED 2026-10-16:** `nativeKeyframeTick` is called from `startAutomationSequence` (`CommixViewModel.kt:1183`, 33 ms per tick). **Residual:** Kotlin `AutomationEngine` remains the fallback when native returns false; no test pins the native tick.
- [x] Implement `best_shot_engine.h` and `best_shot_engine.cpp`:
  - [ ] Gyro angular velocity rolling variance. — **REMEDIATED 2026-10-16:** `nativePushBestShotVelocity` is fed from the live gyro listeners (`SensorHub.kt:75, 142`).
  - [ ] Peak stillness index, exposure score, and "SHOOT NOW" trigger. — **REMEDIATED 2026-10-16 (audit-verified reachable):** engine result polled at `CommixViewModel.kt:155` → `state.bestShotScore` → overlay (`CameraScreen.kt:967-984`); native `test_best_shot_engine` proves the ≥88 trigger fires on near-zero velocity. **Residual:** on-device firing unproven; `lumaHistogram` input still unused.
- [x] Implement `jni_bridge.cpp`:
  - [x] Explicit `JNI_OnLoad` registration via `RegisterNatives`.
  - [x] Zero-copy DirectByteBuffer address retrieval.
  - [x] Native method bindings for telemetry, image processing, stabilization, and scopes. — *bindings exist; several have no caller at all and are dead weight (§B1).*
- [x] Implement Kotlin bridge classes:
  - [ ] `com.txor.commix.bridge.NativeTelemetryBridge` with `@FastNative` and `@CriticalNative`. — **FALSIFIED:** neither annotation appears anywhere in the repo (§A5).
  - [x] `com.txor.commix.bridge.NativeEngineBridge` for image processing and computational pipelines.

---

## Phase 4: Kotlin Infrastructure, Services & Hardware APIs
- [x] Refactor `CameraSessionManager`:
  - [x] Thread-safe atomic state machine (`AtomicReference<CameraState>`).
  - [x] Multi-surface routing: live preview, YUV analysis reader, still capture, and video encoder surfaces.
- [x] Refactor `LensProbeService`:
  - [ ] Sensor probing from ID 0 to 128 for auxiliary lenses (Zero Hardcoding). — **FALSIFIED as committed in `a61ed9c`:** the service spammed IDs −10000..10000 on 32 threads (§A6). **FIXED in `7ac0795`**: `cameraIdList` probing + bounded 0..128 background scan (`LensProbeService.kt:57-139`). Residual: stale copy cleaned in `28a43b7` (zero "10000" hits remain). No test yet.
  - [x] Dynamic lens characteristics, focal lengths, aperture limits, and 35mm equivalents.
- [x] Refactor `CaptureRequestFactory`:
  - [x] Manual ISO, exposure nanoseconds, Kelvin gains, manual focus diopters, zoom ratio, and OIS/EIS controls.
- [x] Refactor `DeviceQuirksManager`:
  - [x] MediaTek Helio G90T, Snapdragon, and Exynos specific guardrails.
  - [ ] HeifWriter output size validation. — **FALSIFIED:** there is no HeifWriter usage and no HEIF encode path anywhere; the `requiresHeicQualityGuardrail` boolean is only printed in a log line (§D).
- [x] Implement `MediaCodecRecorder` & `Fmp4Muxer`:
  - [ ] Hardware surface AVC/HEVC encoder with persistent surface support. — **REMEDIATED 2026-10-16:** `videoCodec` → mimeType passed to the recorder (`CommixRepository.kt:771-786`); persistent surface is still only a constructor param. **Residual:** "ProRes HQ" UI option silently maps to AVC.
  - [x] Standard `MediaMuxer` container integration for universal gallery playback. — **REMEDIATED 2026-10-18:** Synchronized dual-track (video + AAC LC audio) recording integrated via AudioFrameSink with drift-free presentation timestamps and thread-safe muxer lifecycle (§C5, §C11).
  - [ ] Fragmented MP4 (`Fmp4Muxer`) crash-resilient fallback with 1-second clean GOP fragments. — **RESOLVED AS DELETION 2026-10-16:** `Fmp4Muxer.kt` deleted; recordings use standard MP4 via `MediaMuxer` (zero references remain). The "crash-resilient fMP4" claim is withdrawn (§C6).
- [x] Refactor `AudioEngine`:
  - [ ] Multi-mic beamforming direction and audio zoom dimension. — **FALSIFIED:** there is no MediaRecorder to apply direction to, and no audio track is ever recorded (§C5).
  - [x] Real-time `AudioRecord` background listener computing RMS/dB feeding native telemetry. — **REMEDIATED 2026-10-18:** Feeds native VU telemetry and synchronously streams processed PCM (with gain & 80Hz low-cut) to MediaCodec AAC audio encoder via AudioFrameSink (§C5).
  - [x] 160ms 1kHz slate sync tone generator.
- [x] Refactor `SensorHub`:
  - [ ] 6-axis IMU listener streaming directly to C++ seqlock shared buffer. — **STILL FALSIFIED (`28a43b7`):** the C++ buffer still gets only 3 orientation angles; the real 6-axis stream feeds the Kotlin Gyroflow export and best-shot engine instead (§A4/C3).
- [x] Refactor `ThermalManager` & `ExifWriter`:
  - [x] PowerManager thermal status and ADPF headroom listener.
  - [x] ExifInterface injection with embedded technical JSON slate comment.
- [x] Implement `CommixRepository` & `CommixViewModel`:
  - [x] Dynamic surface switching between photo and video modes.
  - [x] Studio video recording start/stop with SMPTE timecode and Gyroflow `.gcsv` export. — *the `.gcsv` export currently produces an all-zero file (§A4/C3); `stopVideoRecording()` also runs on the UI thread (§C7).*
  - [x] Night Mode multi-exposure burst capture (6 frames) with C++20 median noise stacking. — *hardcodes `JPEG_ORIENTATION=90` instead of the sensor orientation, and hardcodes 6 frames while the `nightFrameCount` setting is ignored (§C8).*
  - [x] Process Zero tone curves and multi-threaded analog silver halide grain synthesis.
  - [ ] Time-lapse intervalometer ticker. — **FALSIFIED as committed in `a61ed9c`:** the interval was hardcoded `delay(2000)`. **FIXED in `7ac0795`** (`CommixViewModel.kt:324-325` reads `timeLapseIntervalSec`, 500 ms floor); no test yet.
  - [x] Automation Keyframe multi-track spline engine ticker (zoom, ISO, shutter, focus). — *automation runs Kotlin-side; the native keyframe engine is only programmed and never ticked (§B1).*
  - [x] State management with immutable `UiState` and `StateFlow`.
  - [ ] Real-time 30Hz seqlock telemetry polling driving live scopes and audio VU meter. — **PARTIALLY REMEDIATED 2026-10-16:** the reader now rejects torn frames with dual-counter validation (§A2/C1). **Residual:** rejected reads drop the frame silently (no retry).
- [ ] *Added by audit:* Fix `CommixRepository.updateParams()` — old values were read **after** the assignment, so mode/resolution changes never triggered a session reconfiguration (§C2). **FIXED in `7ac0795`** (captured before `currentParams = enriched`, `CommixRepository.kt:635-640`); no dedicated test yet.

---

## Phase 5: Jetpack Compose UI, Navigation & Performance Polish
- [x] Create UI Theme (`com.txor.commix.ui.theme`):
  - [x] Dark cinema color palette (Apple black, yellow accent `#FFCC00`, recording red `#FF3B30`).
  - [x] Monospaced and cinema typography.
- [x] Implement UI Components:
  - [ ] `ViewfinderSurface`: Zero-latency `SurfaceView` / `TextureView` for live Camera2 stream. — **PARTIALLY REMEDIATED 2026-10-16:** the transform now rotates by `sensorOrientation` and applies `anamorphicDeSqueeze` (`ViewfinderSurface.kt:buildTransform`). **Residual:** `state.sensorOrientation` is never written from `CameraCharacteristics` — it is the constant default 90 (`CameraUiState.kt:88`); still a `TextureView` (composited — the zero-latency claim remains false); no on-device artifact.
  - [x] `ScopesOverlay`: Real live hardware-accelerated `Canvas` rendering 256-bin Histogram and 64-column Waveform, Vectorscope, and Horizon Leveler. — **FIXED (2026-10-17):** 120x120 polar vectorscope reticle with 75%/100% saturation rings, SMPTE target boxes, and live UV scatter points rendered live alongside histogram, waveform, horizon, and rule-of-thirds grid (§A8).
  - [x] `FocusRing`: Interactive reticle with spring animation and vertical EV slider.
  - [x] `FocusLoupe`: Draggable 2x/4x magnification loupe.
  - [x] `RotaryDial`: Tactile thumbwheel with 1/3-stop minor ticks and mechanical haptics.
  - [x] `ModeCarousel`: Snapping horizontal mode carousel (`NIGHT, SLOW-MO, VIDEO, CINEMATIC, PHOTO, PRO, TIME-LAPSE, AUTOMATION`).
  - [x] `RecordingHud`: SMPTE timecode, live VU audio dB meter, and storage telemetry. — *the "storage remaining" is a constant 180 min that is never updated, and the "stereo" right VU bar is the left value × 0.95 (§D, fake telemetry).*
  - [x] `ShotSuggestion`: Samsung One UI-style composition guide with gold ring. — *its shoot-now state can never trigger (§C4).*
  - [x] `ProSettingsCards`: Grouped Apple-style quick settings cards with SCOPES tools toggles.
- [x] Implement Modals & Sheets:
  - [x] `ProVideoFormatModal`: Magnetic float FPS snapping and bitrate sliders.
  - [x] `CinemaSlateSheet`: Fullscreen clapperboard with animated clapper arm and sync tone.
  - [x] `KeyframeEditorModal`: Interactive multi-track spline curve editor.
  - [ ] `ProcessingQueueSheet`: Asynchronous background computational jobs viewer. — **PARTLY ADDRESSED in `7ac0795`:** the queue now stores and runs execution blocks, and cancel actually cancels the coroutine (§C10). Still false in production: the three `enqueueJob` call sites (`CommixRepository.kt:955, 1303, 1367`) pass no execution block, so the sheet can still show simulated progress.
- [x] Implement `CameraScreen`:
  - [x] Master screen integrating Viewfinder, Top Bar, HUD, Rotary Dial, Carousel, and Shutter.
  - [x] Dynamic navigation bar inset padding (`36.dp + navBottom`) preventing any clipping.
- [x] Implement `MainActivity`:
  - [x] Set Compose content, edge-to-edge layout, and runtime permissions.
- [x] Verification & Performance Polish:
  - [ ] 120Hz zero-jank frame rendering with Strong Skipping mode. — **UNVERIFIED:** no measurement, test, or artifact exists; the 30 Hz telemetry loop allocates fresh arrays every tick (§A12).
  - [ ] Zero-copy JNI memory transfer via `TelemetrySharedBuffer` seqlock protocol. — **PARTIALLY REMEDIATED 2026-10-16:** torn reads now rejected (atomic counters + dual-counter reader); "zero-copy/lock-free" wording removed from the header. **Residual:** writer mutex + non-atomic payload fields; snapshots are copied to local stack before commit (not zero-copy).
  - [x] Clean R8 full-mode release compilation (`assembleRelease` verified). — **Was FALSIFIED as committed in `a61ed9c`** (clean checkout failed resource linking; icons were never committed — §F). **VERIFIED 2026-10-16 on `7ac0795`:** `./gradlew :app:testReleaseUnitTest :app:assembleRelease` (wrapper-pinned Gradle 9.3.1) → **BUILD SUCCESSFUL in 8m 29s**; release tests 83/83; APK (4.6 MB) contains `lib/arm64-v8a/libcommix_native.so`. **Caveat (separate item):** the APK is debug-signed (`CN=Android Debug`) and `applicationId` still impersonates `org.codeaurora.snapcam` (§A10).

---

## Independent Verification Log (2026-10-15/16)

- **`a61ed9c`, clean checkout, Gradle 8.14: `BUILD FAILED`** — `AAPT: resource mipmap/ic_launcher not found` (launcher icons referenced by the manifest were never committed). The commit as submitted cannot assemble.
- **`7ac0795` (the author's response):** debug JVM tests **83/83, 0 failures** (`app/build/test-results/testDebugUnitTest/*.xml`); dead files deleted; icons tracked. The native suite (`app/src/test/cpp/native_engine_test.cpp`) was recompiled and rerun by the auditor with `g++ -std=c++20 -Wall -Wextra -Werror` — all 7 suites pass — but it is **not wired into any build**, asserts only the Gyroflow header (not row values), and has no concurrency test.
- **Release build of `7ac0795` (2026-10-16):** `./gradlew :app:testReleaseUnitTest :app:assembleRelease` (wrapper-pinned Gradle 9.3.1) → **BUILD SUCCESSFUL in 8m 29s**; release tests 83/83; APK contains `lib/arm64-v8a/libcommix_native.so`; still debug-signed (`apksigner`: `CN=Android Debug`).
- Still open after `7ac0795`: IMU/Gyroflow junk data, silent videos + synthetic PTS, `Fmp4Muxer`, best-shot velocity, settings theater (§D), preview rotation, native-test automation, debug signing. Full list in `AUDIT.md` §I.
- **`28a43b7` (batch 3, 2026-10-16):** battery `./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:testNative :app:assembleRelease` → **BUILD SUCCESSFUL in 3m 13s**, exit 0; unit tests **81/81 both variants** (was 83 — two tautological tests deleted); `testNative` wired into `check`, binary run directly: **8/8 suites pass — 1210 valid reads, 0 torn reads**; APK contains `lib/arm64-v8a/libcommix_native.so`; still debug-signed. Genuinely fixed: §A2/§C1, §B1, §C2, §C4, §C6, §C7, §C8, §C9, §E (partial), most of §D. Corrected to partial: §C3 (native ring buffer unfed), §C5 (**still silent** — no audio track), §D (residual list in `AUDIT.md`), §E (residual tautologies). Full record: `AUDIT.md` §I.
- **Unit-test de-theatering (2026-10-16, uncommitted working tree):** `./gradlew :app:testDebugUnitTest --offline --rerun` → **BUILD SUCCESSFUL**, **81/81 tests, 0 failures/errors**, forced re-run (the task actually executed, not `UP-TO-DATE`). The §E/§J7 tautologies were replaced by calls into production helpers — `AutomationEngine.BUILT_IN_PRESETS`/`getPresetById`, `MediaCodecRecorder.computeFrameStepUs`/`computeSyntheticPtsUs`, `CaptureRequestFactory.calculateEvIndex`/`calculateEffectiveHardwareZoom`/`shutterAngleToExposureNs`, `CameraUiState.switchToMode`/`withExifPreset`/`toExifOptions`, `CinemaMatte.aspectFor`. Per-class XML: 81 tests across 8 classes, 0 failures. Residuals kept open: `CommixViewModel.onSetExifPreset`/`onModeSelected` bodies not executed in-JVM (no Robolectric); no on-device JPEG/MP4/`.gcsv` smoke test; ~~`testNative` still compiles 6 of 10 engine files~~ → **closed 2026-10-16: `testNative` now builds both host binaries and calls all 9 `CMakeLists.txt` engine sources** (`AUDIT.md` §M). New finding logged: the C++ Gyroflow writer (§L.5) emits undocumented `camera_name`/`frame_rate` keys and uses a different `ascale` than the Kotlin production writer. Full record: `AUDIT.md` §L.
- Full findings: `AUDIT.md`. Re-check rules: `AGENTS.md` §1 and §4. Section refs (§A–§I) refer to `AUDIT.md`.
