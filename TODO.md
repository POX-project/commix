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
  - [ ] `TelemetrySharedBuffer` seqlock protocol. — **FALSIFIED:** writers are mutex-serialized (not lock-free), the reader compares a stale `seq_end` and can accept torn reads, and the seq counters are non-atomic plain fields (§A2).
  - [ ] Audio VU meter atomic writing. — **FALSIFIED:** writes are guarded by a global mutex, not atomics; that mutex is held across the full-frame scope computation (§A2).
  - [ ] IMU 6-DOF orientation atomic writing. — **FALSIFIED:** only 3 rotation-vector Euler angles at ≤60 Hz reach the buffer, timestamp hardcoded 0 (§A4).
- [x] Implement `thread_pool.hpp`:
  - [ ] Lock-free task queue and persistent `std::jthread` 8-worker thread pool. — **FALSIFIED:** mutex + condition_variable + `std::thread`; no lock-free structure, no `std::jthread` anywhere (§A1).
- [x] Implement `scopes_engine.h` and `scopes_engine.cpp`:
  - [ ] ARM64 NEON SIMD vectorized YUV-to-RGB conversion. — **FALSIFIED:** zero NEON intrinsics in the entire tree; `<arm_neon.h>` is included and unused; all loops are scalar (§A3).
  - [x] 256-bin luma and RGB histograms.
  - [x] 64-column Waveform IRE calculator with min/max/average.
  - [x] 64-column RGB Parade analyzer. — *computed every frame, then discarded: the parade never leaves C++ (§A8).*
  - [ ] 256-sample Vectorscope chroma polar mapper. — **FALSIFIED:** computed then discarded; it is never transmitted to Kotlin nor rendered by any UI (§A8).
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
  - [ ] 1kHz IMU lock-free ring buffer. — **FALSIFIED:** the only writer pushes rotation-vector angles at ≤60 Hz with `timestamp=0` and `gyro=0,0,0`; the real 1 kHz gyroscope logger in `SensorHub` is dead code with zero callers (§A4).
  - [ ] Gyroflow v1.3 `.gcsv` file exporter. — **FALSIFIED:** the exporter runs, but the produced file has `t=0.000000` and `gx=gy=gz=0.000000` on every row — unusable by Gyroflow (§A4/C3).
  - [ ] Real-time Kalman filter Virtual Gimbal. — **FALSIFIED:** no JNI binding and no caller — the Kalman filter never runs (§B1).
  - [ ] L1 camera path trend filtering optimizer. — **FALSIFIED:** bound as `nativeComputeL1Path`, zero callers; even if called it is 25 iterations of an unscaled fudged step, not an L1 solver (§B1).
- [x] Implement `color_science.h` and `color_science.cpp`:
  - [x] 9 broadcast transfer functions (LogC3/4, S-Log3, C-Log2, RED, Apple Log, HLG, PQ, Rec.709).
  - [ ] ASC CDL v1.2 grading engine (Slope, Offset, Power, Saturation). — **FALSIFIED:** `nativeApplyCdlDirect` has zero callers — CDL grading is never applied to any image (§B1/C).
- [x] Implement `keyframe_engine.h` and `keyframe_engine.cpp`:
  - [x] Multi-track parameter splines (zoom, ISO, shutter, EV, WB, focus).
  - [ ] 60Hz ticker with linear, easeIn, easeOut, easeInOut, and hold interpolation. — **FALSIFIED:** no ticker exists anywhere; `nativeKeyframeTick` is never called (§B1).
- [x] Implement `best_shot_engine.h` and `best_shot_engine.cpp`:
  - [ ] Gyro angular velocity rolling variance. — **FALSIFIED:** the velocity history is always empty — `nativePushBestShotVelocity` has zero callers (§C4).
  - [ ] Peak stillness index, exposure score, and "SHOOT NOW" trigger. — **FALSIFIED:** with no velocity data stability stays at 50 < the 88 threshold, so "SHOOT NOW" provably can never fire; the `lumaHistogram` input is also never used (§C4).
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
  - [ ] Sensor probing from ID 0 to 128 for auxiliary lenses (Zero Hardcoding). — **FALSIFIED:** the service spams `getCameraCharacteristics()` over IDs −10000..10000 (20,001 attempts, most throwing) on 32 threads — a battery/CPU burner that contradicts its own documented range (§A6).
  - [x] Dynamic lens characteristics, focal lengths, aperture limits, and 35mm equivalents.
- [x] Refactor `CaptureRequestFactory`:
  - [x] Manual ISO, exposure nanoseconds, Kelvin gains, manual focus diopters, zoom ratio, and OIS/EIS controls.
- [x] Refactor `DeviceQuirksManager`:
  - [x] MediaTek Helio G90T, Snapdragon, and Exynos specific guardrails.
  - [ ] HeifWriter output size validation. — **FALSIFIED:** there is no HeifWriter usage and no HEIF encode path anywhere; the `requiresHeicQualityGuardrail` boolean is only printed in a log line (§D).
- [x] Implement `MediaCodecRecorder` & `Fmp4Muxer`:
  - [ ] Hardware surface AVC/HEVC encoder with persistent surface support. — **FALSIFIED:** H.264 only — the `videoCodec` setting is never passed to the recorder (§C5).
  - [x] Standard `MediaMuxer` container integration for universal gallery playback. — *works, but every recording is silent (no audio track) and container timestamps are fabricated from frame counters (§C5).*
  - [ ] Fragmented MP4 (`Fmp4Muxer`) crash-resilient fallback with 1-second clean GOP fragments. — **FALSIFIED:** the output is unplayable: truncated `mvhd`, no `trak`/`stsd`/`avcC`, `trun` flags = 0 (no sizes/durations/offsets); its parameters are ignored (§C6).
- [x] Refactor `AudioEngine`:
  - [ ] Multi-mic beamforming direction and audio zoom dimension. — **FALSIFIED:** there is no MediaRecorder to apply direction to, and no audio track is ever recorded (§C5).
  - [x] Real-time `AudioRecord` background listener computing RMS/dB feeding native telemetry. — *monitor-only; recorded videos contain no audio (§C5).*
  - [x] 160ms 1kHz slate sync tone generator.
- [x] Refactor `SensorHub`:
  - [ ] 6-axis IMU listener streaming directly to C++ seqlock shared buffer. — **FALSIFIED:** only 3 orientation angles at ≤60 Hz; real gyroscope logging exists but is dead code (§A4).
- [x] Refactor `ThermalManager` & `ExifWriter`:
  - [x] PowerManager thermal status and ADPF headroom listener.
  - [x] ExifInterface injection with embedded technical JSON slate comment.
- [x] Implement `CommixRepository` & `CommixViewModel`:
  - [x] Dynamic surface switching between photo and video modes.
  - [x] Studio video recording start/stop with SMPTE timecode and Gyroflow `.gcsv` export. — *the `.gcsv` export currently produces an all-zero file (§A4/C3); `stopVideoRecording()` also runs on the UI thread (§C7).*
  - [x] Night Mode multi-exposure burst capture (6 frames) with C++20 median noise stacking. — *hardcodes `JPEG_ORIENTATION=90` instead of the sensor orientation, and hardcodes 6 frames while the `nightFrameCount` setting is ignored (§C8).*
  - [x] Process Zero tone curves and multi-threaded analog silver halide grain synthesis.
  - [ ] Time-lapse intervalometer ticker. — **FALSIFIED:** the interval is hardcoded `delay(2000)`; `timeLapseIntervalSec` is never read (§C9).
  - [x] Automation Keyframe multi-track spline engine ticker (zoom, ISO, shutter, focus). — *automation runs Kotlin-side; the native keyframe engine is only programmed and never ticked (§B1).*
  - [x] State management with immutable `UiState` and `StateFlow`.
  - [ ] Real-time 30Hz seqlock telemetry polling driving live scopes and audio VU meter. — **FALSIFIED:** polling exists, but the seqlock underneath is broken and can deliver torn or silently dropped frames (§A2).
- [ ] *Added by audit:* Fix `CommixRepository.updateParams()` — `oldMode`/`oldVideoRes` are read **after** the assignment, so mode/resolution changes never trigger a session reconfiguration (§C2).

---

## Phase 5: Jetpack Compose UI, Navigation & Performance Polish
- [x] Create UI Theme (`com.txor.commix.ui.theme`):
  - [x] Dark cinema color palette (Apple black, yellow accent `#FFCC00`, recording red `#FF3B30`).
  - [x] Monospaced and cinema typography.
- [x] Implement UI Components:
  - [ ] `ViewfinderSurface`: Zero-latency `SurfaceView` / `TextureView` for live Camera2 stream. — **FALSIFIED:** it is a `TextureView` (composited, not zero-latency) whose transform scales only — no sensor-rotation handling, so on portrait devices the preview is sideways/stretched (§A9).
  - [ ] `ScopesOverlay`: Real live hardware-accelerated `Canvas` rendering 256-bin Histogram and 64-column Waveform, Vectorscope, and Horizon Leveler. — **FALSIFIED (partially):** histogram, waveform and horizon render; the vectorscope is neither transported nor drawn (§A8).
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
  - [ ] `ProcessingQueueSheet`: Asynchronous background computational jobs viewer. — **FALSIFIED:** all production jobs are enqueued with no execution block and after the work — the queue shows simulated progress bars for work it did not do; pause/cancel cancel nothing (§C10).
- [x] Implement `CameraScreen`:
  - [x] Master screen integrating Viewfinder, Top Bar, HUD, Rotary Dial, Carousel, and Shutter.
  - [x] Dynamic navigation bar inset padding (`36.dp + navBottom`) preventing any clipping.
- [x] Implement `MainActivity`:
  - [x] Set Compose content, edge-to-edge layout, and runtime permissions.
- [x] Verification & Performance Polish:
  - [ ] 120Hz zero-jank frame rendering with Strong Skipping mode. — **UNVERIFIED:** no measurement, test, or artifact exists; the 30 Hz telemetry loop allocates fresh arrays every tick (§A12).
  - [ ] Zero-copy JNI memory transfer via `TelemetrySharedBuffer` seqlock protocol. — **FALSIFIED:** same as §A2 — mutex writers, torn-read-accepting reader, non-atomic fields.
  - [x] Clean R8 full-mode release compilation (`assembleRelease` verified). — **Independently verified 2026-10-15:** `BUILD SUCCESSFUL`, 82/82 unit tests green, APK contains `lib/arm64-v8a/libcommix_native.so`. **Caveats:** APK is signed with the Android Debug key; the wrapper-pinned Gradle 9.3.1 could not be tested in this environment; the 82 green tests are tautological and cover none of the defects above (§E).

---

## Independent Verification Log (2026-10-15)

- Toolchain: Gradle 8.14 + AGP 8.7.3 → `:app:testReleaseUnitTest :app:assembleRelease` **BUILD SUCCESSFUL** (18m07s), 82/82 tests, `app-release.apk` (4.6 MB) contains `lib/arm64-v8a/libcommix_native.so`, signed with the **Android Debug** certificate.
- Gradle 9.1.0 control build (Gradle 9 line, matching the wrapper's 9.3.1 pin): **result recorded in `AUDIT.md` §F**.
- Full findings: `AUDIT.md`. Re-check rules: `AGENTS.md` §1 and §4.
- Note: several items above are cross-referenced to "§" — those refer to `AUDIT.md` sections.
