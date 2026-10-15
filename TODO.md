# COMMIX PRO CAMERA: NATIVE REWRITE TASK CHECKLIST

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
  - [x] `TelemetrySharedBuffer` seqlock protocol.
  - [x] Audio VU meter atomic writing.
  - [x] IMU 6-DOF orientation atomic writing.
- [x] Implement `thread_pool.hpp`:
  - [x] Lock-free task queue and persistent `std::jthread` 8-worker thread pool.
- [x] Implement `scopes_engine.h` and `scopes_engine.cpp`:
  - [x] ARM64 NEON SIMD vectorized YUV-to-RGB conversion.
  - [x] 256-bin luma and RGB histograms.
  - [x] 64-column Waveform IRE calculator with min/max/average.
  - [x] 64-column RGB Parade analyzer.
  - [x] 256-sample Vectorscope chroma polar mapper.
  - [x] Spot IRE meter.
- [x] Implement `image_pipeline.h` and `image_pipeline.cpp`:
  - [x] Process Zero tone mapping curves.
  - [x] Multi-threaded analog silver halide film grain synthesis with NEON midtone masking.
- [x] Implement `computational_photo.h` and `computational_photo.cpp`:
  - [x] Astrophotography star centroid detection, affine registration, and median stacking.
  - [x] Computational Long Exposure accumulator (silky water, light trails, moving crowd removal).
  - [x] Super-Resolution Zoom subpixel shift & Lanczos detail reconstruction.
  - [x] Multi-exposure blending (additive, average, lighten, darken).
  - [x] Focus stacking with Sobel variance maps.
  - [x] Lens aberration radial polynomial rectification ($r' = r(1 + k_1 r^2 + k_2 r^4)$).
  - [x] Rolling shutter shear compensation.
- [x] Implement `stabilization_engine.h` and `stabilization_engine.cpp`:
  - [x] 1kHz IMU lock-free ring buffer.
  - [x] Gyroflow v1.3 `.gcsv` file exporter.
  - [x] Real-time Kalman filter Virtual Gimbal.
  - [x] L1 camera path trend filtering optimizer.
- [x] Implement `color_science.h` and `color_science.cpp`:
  - [x] 9 broadcast transfer functions (LogC3/4, S-Log3, C-Log2, RED, Apple Log, HLG, PQ, Rec.709).
  - [x] ASC CDL v1.2 grading engine (Slope, Offset, Power, Saturation).
- [x] Implement `keyframe_engine.h` and `keyframe_engine.cpp`:
  - [x] Multi-track parameter splines (zoom, ISO, shutter, EV, WB, focus).
  - [x] 60Hz ticker with linear, easeIn, easeOut, easeInOut, and hold interpolation.
- [x] Implement `best_shot_engine.h` and `best_shot_engine.cpp`:
  - [x] Gyro angular velocity rolling variance.
  - [x] Peak stillness index, exposure score, and "SHOOT NOW" trigger.
- [x] Implement `jni_bridge.cpp`:
  - [x] Explicit `JNI_OnLoad` registration via `RegisterNatives`.
  - [x] Zero-copy DirectByteBuffer address retrieval.
  - [x] Native method bindings for telemetry, image processing, stabilization, and scopes.
- [x] Implement Kotlin bridge classes:
  - [x] `com.txor.commix.bridge.NativeTelemetryBridge` with `@FastNative` and `@CriticalNative`.
  - [x] `com.txor.commix.bridge.NativeEngineBridge` for image processing and computational pipelines.

---

## Phase 4: Kotlin Infrastructure, Services & Hardware APIs
- [x] Refactor `CameraSessionManager`:
  - [x] Thread-safe atomic state machine (`AtomicReference<CameraState>`).
  - [x] Multi-surface routing: live preview, YUV analysis reader, still capture, and video encoder surfaces.
- [x] Refactor `LensProbeService`:
  - [x] Sensor probing from ID 0 to 128 for auxiliary lenses (Zero Hardcoding).
  - [x] Dynamic lens characteristics, focal lengths, aperture limits, and 35mm equivalents.
- [x] Refactor `CaptureRequestFactory`:
  - [x] Manual ISO, exposure nanoseconds, Kelvin gains, manual focus diopters, zoom ratio, and OIS/EIS controls.
- [x] Refactor `DeviceQuirksManager`:
  - [x] MediaTek Helio G90T, Snapdragon, and Exynos specific guardrails.
  - [x] HeifWriter output size validation.
- [x] Implement `MediaCodecRecorder` & `Fmp4Muxer`:
  - [x] Hardware surface AVC/HEVC encoder with persistent surface support.
  - [x] Standard `MediaMuxer` container integration for universal gallery playback.
  - [x] Fragmented MP4 (`Fmp4Muxer`) crash-resilient fallback with 1-second clean GOP fragments.
- [x] Refactor `AudioEngine`:
  - [x] Multi-mic beamforming direction and audio zoom dimension.
  - [x] Real-time `AudioRecord` background listener computing RMS/dB feeding native telemetry.
  - [x] 160ms 1kHz slate sync tone generator.
- [x] Refactor `SensorHub`:
  - [x] 6-axis IMU listener streaming directly to C++ seqlock shared buffer.
- [x] Refactor `ThermalManager` & `ExifWriter`:
  - [x] PowerManager thermal status and ADPF headroom listener.
  - [x] ExifInterface injection with embedded technical JSON slate comment.
- [x] Implement `CommixRepository` & `CommixViewModel`:
  - [x] Dynamic surface switching between photo and video modes.
  - [x] Studio video recording start/stop with SMPTE timecode and Gyroflow `.gcsv` export.
  - [x] Night Mode multi-exposure burst capture (6 frames) with C++20 median noise stacking.
  - [x] Process Zero tone curves and multi-threaded analog silver halide grain synthesis.
  - [x] Time-lapse intervalometer ticker.
  - [x] Automation Keyframe multi-track spline engine ticker (zoom, ISO, shutter, focus).
  - [x] State management with immutable `UiState` and `StateFlow`.
  - [x] Real-time 30Hz seqlock telemetry polling driving live scopes and audio VU meter.

---

## Phase 5: Jetpack Compose UI, Navigation & Performance Polish
- [x] Create UI Theme (`com.txor.commix.ui.theme`):
  - [x] Dark cinema color palette (Apple black, yellow accent `#FFCC00`, recording red `#FF3B30`).
  - [x] Monospaced and cinema typography.
- [x] Implement UI Components:
  - [x] `ViewfinderSurface`: Zero-latency `SurfaceView` / `TextureView` for live Camera2 stream.
  - [x] `ScopesOverlay`: Real live hardware-accelerated `Canvas` rendering 256-bin Histogram and 64-column Waveform, Vectorscope, and Horizon Leveler.
  - [x] `FocusRing`: Interactive reticle with spring animation and vertical EV slider.
  - [x] `FocusLoupe`: Draggable 2x/4x magnification loupe.
  - [x] `RotaryDial`: Tactile thumbwheel with 1/3-stop minor ticks and mechanical haptics.
  - [x] `ModeCarousel`: Snapping horizontal mode carousel (`NIGHT, SLOW-MO, VIDEO, CINEMATIC, PHOTO, PRO, TIME-LAPSE, AUTOMATION`).
  - [x] `RecordingHud`: SMPTE timecode, live VU audio dB meter, and storage telemetry.
  - [x] `ShotSuggestion`: Samsung One UI-style composition guide with gold ring.
  - [x] `ProSettingsCards`: Grouped Apple-style quick settings cards with SCOPES tools toggles.
- [x] Implement Modals & Sheets:
  - [x] `ProVideoFormatModal`: Magnetic float FPS snapping and bitrate sliders.
  - [x] `CinemaSlateSheet`: Fullscreen clapperboard with animated clapper arm and sync tone.
  - [x] `KeyframeEditorModal`: Interactive multi-track spline curve editor.
  - [x] `ProcessingQueueSheet`: Asynchronous background computational jobs viewer.
- [x] Implement `CameraScreen`:
  - [x] Master screen integrating Viewfinder, Top Bar, HUD, Rotary Dial, Carousel, and Shutter.
  - [x] Dynamic navigation bar inset padding (`36.dp + navBottom`) preventing any clipping.
- [x] Implement `MainActivity`:
  - [x] Set Compose content, edge-to-edge layout, and runtime permissions.
- [x] Verification & Performance Polish:
  - [x] 120Hz zero-jank frame rendering with Strong Skipping mode.
  - [x] Zero-copy JNI memory transfer via `TelemetrySharedBuffer` seqlock protocol.
  - [x] Clean R8 full-mode release compilation (`assembleRelease` verified).

