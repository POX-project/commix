# Commix Multi-Agent Coordination Board

> **NOTICE TO ALL ACTIVE AGENTS / SUBAGENTS:**
> This board coordinates concurrent work across all agents in the Commix repository.
> Consult this file before making modifications to prevent overwriting in-flight work and race conditions.
> Strict rule from `AGENTS.md`: Every change must be backed by real running tests and verified artifacts.

---

## 1. Domain Ownership Matrix & Lock Status

| Domain | Assigned Lead / Agent | Owned Files | Status |
|---|---|---|---|
| **Native C++ Engine & Math** | Native & Color Specialists | `app/src/main/cpp/color_science.cpp`<br>`app/src/main/cpp/jni_bridge.cpp`<br>`app/src/main/cpp/scopes_engine.*`<br>`app/src/main/cpp/stabilization_engine.*`<br>`app/src/main/cpp/computational_photo.*`<br>`app/src/main/cpp/thread_pool.hpp`<br>`app/src/main/cpp/image_pipeline.cpp`<br>`app/src/test/cpp/native_engine_test.cpp`<br>`app/src/test/cpp/jni_bridge_test.cpp` | **VERIFIED GREEN — 2026-10-16** (`./gradlew :app:testNative` → BUILD SUCCESSFUL, exit 0; see `AUDIT.md` §M)<br>- LogC3/LogC4/CLog2/Log3G10 inverse math fixed & tested (round-trip `<1e-3`, branch cuts continuous).<br>- JNI bounds, stride checks & NULL checks hardened.<br>- `testNative` now compiles and calls **all 9** `CMakeLists.txt` engine sources (engine suite + JNI suite).<br>- `applyUnsharpMask` `radius` honored (Gaussian kernel) + tested; `processZero` uses `std::latch` (no notify-after-destroy) + dropped-work test.<br>- 0 torn reads (`>12k` valid concurrent reads per run, varies by scheduler). |
| **Kotlin Test Suite Overhaul** | Test Specialist | `app/src/test/kotlin/com/txor/commix/CaptureRequestFactoryTest.kt`<br>`app/src/test/kotlin/com/txor/commix/MediaCodecAndSensorEngineTest.kt`<br>`app/src/test/kotlin/com/txor/commix/UniversalSettingsAndExifTest.kt`<br>`app/src/test/kotlin/com/txor/commix/OrientationPolicyTest.kt`<br>`app/src/test/kotlin/com/txor/commix/ManualExposurePlanTest.kt` | **VERIFIED GREEN (101/101 tests pass across Debug & Release)**<br>- Replaced tautological inline assertions with production class invocations.<br>- Added `OrientationPolicyTest` and `ManualExposurePlanTest`. |
| **Gyroflow & Stabilization** | Stabilization Specialist | `app/src/main/kotlin/com/txor/commix/SensorHub.kt`<br>`app/src/main/cpp/stabilization_engine.cpp` | **IN PROGRESS**<br>- Standardizing headers to `GYROFLOW IMU LOG v1.3`.<br>- Synchronizing gyro and accel timestamps. |
| **Camera2 & State Engine** | Core Android Specialist | `app/src/main/kotlin/com/txor/commix/data/CommixRepository.kt`<br>`app/src/main/kotlin/com/txor/commix/ui/CommixViewModel.kt`<br>`app/src/main/kotlin/com/txor/commix/model/CameraUiState.kt`<br>`app/src/main/kotlin/com/txor/commix/CaptureRequestFactory.kt`<br>`app/src/main/kotlin/com/txor/commix/OrientationPolicy.kt` | **VERIFIED GREEN (AUDIT.md §N & §O)**<br>- Lifecycle management (onStart/onStop) & viewport freeze resolved.<br>- Video recording start dispatched off main thread to background coroutine with joined teardown.<br>- MediaStore orphan pending row deletion on I/O error.<br>- 114/114 unit tests passing across 12 test suites. |
| **Build & Toolchain** | Toolchain Specialist | `app/build.gradle.kts`<br>`gradle.properties`<br>`proguard-rules.pro` | **ACTIVE**<br>- Native tests wired into `testNative`.<br>- JVM memory & compiler daemon configuration. |
| **Real-Time Video & Codecs (Agents 031-040)** | Video & Codec Specialist Lead | `app/src/main/kotlin/com/txor/commix/MediaCodecRecorder.kt`<br>`app/src/main/kotlin/com/txor/commix/VideoFormatEngine.kt`<br>`app/src/main/kotlin/com/txor/commix/CommixRecordingEngine.kt`<br>`app/src/main/kotlin/com/txor/commix/data/CommixRepository.kt` | **VERIFIED GREEN (121/121 JVM + 15/15 Native tests pass)**<br>- 8K UHD (7680x4320) & 8K DCI (8192x4320) bounding box & fallback tiering implemented.<br>- 60/120/240fps buffer pacing with 2.5ms adaptive dequeue timeout preventing camera surface starvation.<br>- H.265 Main10 HDR10 profile negotiation (BT.2020 gamut, SMPTE ST 2084 PQ transfer) with graceful 8-bit SDR fallback.<br>- All-I Intra-only (KEY_I_FRAME_INTERVAL = 0) vs Long-GOP profile parameters wired to Cinema Master (200 Mbps).<br>- Drift-free synthetic PTS engine (eliminates ~72ms cumulative drift at 59.94fps) with strictly monotonic timestamp enforcement.<br>- Background thread recording dispatch & MediaStore IS_PENDING row orphan reconciliation. |
| **Processing Job Queue & Concurrency** | Concurrency Specialist (Agent 15) | `app/src/main/kotlin/com/txor/commix/ProcessingJobQueue.kt`<br>`app/src/test/kotlin/com/txor/commix/ProcessingJobQueueTest.kt` | **VERIFIED GREEN (114/114 tests pass across Debug & Release)**<br>- ReentrantLock eliminates double-start, dropped-job, and slot over-allocation race conditions.<br>- Priority queuing strictly orders High > Normal > Low with monotonic sequence tie-breaking (FIFO anti-starvation).<br>- Dynamic `maxConcurrentJobs` immediately triggers queued jobs when slots expand.<br>- Cooperative `CancellationToken` and `JobExecutionContext` abort cancelled pipelines immediately.<br>- Telemetry streaming throttles intermediate progress to prevent StateFlow CAS churn, with dynamic ETA estimation. |

---

## 2. In-Flight Verifications & Results

### C++ Native & JNI Verification Suite (`app/build/test-native/native_engine_test`)
```
==========================================================
 Commix Native C++20 Aether Engine Verification Suite
==========================================================
[PASS] Hyperfocal distance calculation & bounds validation.
[PASS] Astro median stacking & input bounds validation.
[PASS] Long exposure accumulation (SilkyWater & LightTrails).
[PASS] Multi-exposure blend modes (Additive, Average, Darken, Lighten).
[PASS] Stabilization engine IMU ring buffer, Gyroflow export & Kalman filter.
[PASS] Scopes engine neutral grey IRE & input bounds validation.
[PASS] Telemetry seqlock concurrency contract (18725 valid concurrent reads, 0 torn reads).
[PASS] BestShotEngine peak stillness determination & trigger contract.
[PASS] Color science round-trip, inversion & branch continuity.
[PASS] ImagePipeline tone mapping, grain simulation & unsharp mask operations.
[PASS] processZero returns after pool shutdown; dropped slices are accounted for.
==========================================================
 ALL NATIVE C++ ENGINE TESTS PASSED PERFECTLY!
==========================================================
==========================================================
 Commix JNI Boundary Verification Suite
==========================================================
[PASS] Scopes plane bounds match compute_scopes() exactly (odd and even geometry).
[PASS] frameCount validated against GetArrayLength; engine cap enforced.
[PASS] GetStringUTFChars NULL results are never dereferenced or released.
[PASS] Registration tables complete (5 telemetry + 15 engine natives).
==========================================================
 ALL JNI BOUNDARY TESTS PASSED
==========================================================
```

### Kotlin JVM Unit Test Battery (`:app:testDebugUnitTest`, `:app:testReleaseUnitTest`)
- **Debug Unit Tests:** **114 / 114 PASS** (0 failures, 100% success rate)
- **Release Unit Tests:** **114 / 114 PASS** (0 failures, 100% success rate)
- Verified coverage across:
  - `ViewfinderGeometryAndControlsTest`: Aspect ratio containment (16:9 video and 4:3 photo), zero viewport overflow, Viewfinder scale matrix buffer matching, and Top Bar width bounds ensuring upper controls remain fully visible and touch-accessible.
  - `LensDetectionAndResolutionTest`: Dynamic hardware lens classification (UltraWide, Wide, Telephoto, Macro), Quad-Bayer 64MP sensor resolution computation via HAL3 characteristics, and UI state resilience against preview size clobbering.
  - `ManualExposurePlanTest`: Pure exposure resolution across independent ISO/shutter, 3A template defaults, bounds clamping, torch & flash overrides.
  - `OrientationPolicyTest`: Rotation quantization, front camera mirroring, surface-to-quadrant mapping, and non-multiple snapping.
  - `CaptureRequestFactoryTest`: AE/AF/AWB modes, EV compensation, zoom ratio, flash, and zero-computational smoothing.
  - `MediaCodecAndSensorEngineTest`: Bitrate calculations, container format validation, frame pacing bounds.
  - `UniversalSettingsAndExifTest`: EXIF tag mapping, metadata payload extraction, settings persistence contracts.

### Changelog
- **2026-10-16 (UI Layout Specialist):** Resolved bug where upper controls got hidden when the viewfinder expanded in 16:9 / video / large mode:
  1. Enforced `matchHeightConstraintsFirst = true` on `Modifier.fillMaxHeight().aspectRatio(...)` so tall 16:9 viewfinders never exceed container bounds.
  2. Applied `.clipToBounds()` to `BoxWithConstraints`, `Box(modifier = vfModifier.clipToBounds())`, and `ViewfinderSurface`'s `AndroidView` to eliminate vertical pixel bleed.
  3. Added `.zIndex(20f)` to Top Bar `Row` and Bottom Controls `Column` so controls are never obscured by underlying surfaces.
  4. Streamlined Top Bar center chevron into a unified 44×32dp toggle matching Flutter 1:1, preventing horizontal control overflow and truncation on 360–393dp devices.
  5. Added `ViewfinderGeometryAndControlsTest` proving containment across 16:9 and 4:3 ratios. All 106 unit tests passing.
- **2026-10-16 (Lens & Resolution Specialist):** Restored official Commix icon assets from procam master across all density buckets and set `applicationId = "com.txor.commix"`. Implemented zero-hardcoded dynamic 64MP and Quad-Bayer detection in `LensProbeService` querying active array, pixel array, and remosaic maps. Fixed preview size clobbering in `CommixViewModel` which previously degraded `maxLensMp` to 12MP/16MP. Hardened lens switching with exponential backoff retries and auto-recovery fallback to primary camera. Added `LensDetectionAndResolutionTest` verifying 107/107 tests passing.
- **2026-10-16 (Native & Color):** closed the `testNative` coverage gap. `app/build.gradle.kts` now builds both host binaries so all 9 engine sources are compiled by a test that calls them; added the `radius`-sensitivity assertion to `test_image_pipeline_basic_operations` and the new `test_process_zero_never_waits_on_dropped_work` (11th engine test). Struck `AUDIT.md` §J1 (log curves / `radius` / CV race), §J2 (`testNative` 6-of-10) and the §L residual, each with a pre-fix failing reproduction; full evidence in `AUDIT.md` §M.

---

## 3. Ground Rules for Concurrent Agents

1. **Do not overwrite other domains:** Check the matrix above. If you need to change a file owned by another active domain, ensure the tests in both domains pass.
2. **No tautological tests (`AGENTS.md §E`):** Never assert against local variables or math copies inside a test method. Always invoke production classes.
3. **Keep `AUDIT.md` honest:** Strike findings only with timestamped evidence citing the verified file and passing test.



---

## 3. Active 30-Agent Swarm (Gemini 3.8 Flash High (Free Tier) 3.8 Flash High)

| # | Provider & Model | Domain / Specialization | Task Focus & Scope |
|---|---|---|---|
| **1** | Gemini (`gemini-3.8-flash-high`) | **C++20 ThreadPool & SIMD** | `thread_pool.hpp`, `image_pipeline.cpp` — Work stealing, NEON vectorization, cache line alignment. |
| **2** | Gemini (`gemini-3.8-flash-high`) | **Color Science & 3D LUT Pipeline** | `color_science.cpp` — ACEScc, ARRI LogC3/4, CLog2, Log3G10, 3D LUT tetrahedral interpolation. |
| **3** | Gemini (`gemini-3.8-flash-high`) | **Scopes Engine & Video Analysis** | `scopes_engine.*` — Sub-sampled fast paths, vectorscope IRE calibration, GPU shader parity. |
| **4** | Gemini (`gemini-3.8-flash-high`) | **Computational Photo & RAW Stacking** | `computational_photo.*` — Optical flow alignment, motion-compensated median stacking, silky water. |
| **5** | Gemini (`gemini-3.8-flash-high`) | **Gyroflow & IMU Stabilization** | `stabilization_engine.*`, `SensorHub.kt` — Rolling shutter sync, Gyroflow v1.3 standard, Kalman tuning. |
| **6** | Gemini (`gemini-3.8-flash-high`) | **Camera2 State Machine & Multi-Cam** | `CameraSessionManager.kt`, `CommixRepository.kt` — Fallback re-open, seamless physical multi-lens handoff, ZSL. |
| **7** | Gemini (`gemini-3.8-flash-high`) | **CaptureRequestFactory & 3A Engine** | `CaptureRequestFactory.kt` — Independent manual ISO/shutter resolution, AE/AF/AWB locks, 3A templates. |
| **8** | Gemini (`gemini-3.8-flash-high`) | **MediaCodec 10-bit HDR & High-Speed** | `MediaCodecRecorder.kt` — 4K/8K 60/120fps, H.265 Main10 HDR10 profile, PTS pacing, container muxer. |
| **9** | Gemini (`gemini-3.8-flash-high`) | **Pro Audio Pipeline & Monitoring** | `AudioEngine.kt` — Low-latency uncompressed PCM/WAV, AAC 320k, polar patterns, low-cut DSP filter. |
| **10** | Gemini (`gemini-3.8-flash-high`) | **Orientation & Viewfinder Geometry** | `OrientationPolicy.kt`, `ViewfinderSurface.kt` — Matrix transforms, sensor mirroring, aspect ratio preservation. |
| **11** | Gemini (`gemini-3.8-flash-high`) | **Keyframe Engine & Automation** | `AutomationEngine.kt` — Cubic Hermite/Bézier interpolation, Dolly Zoom / Rack Focus automation curves. |
| **12** | Gemini (`gemini-3.8-flash-high`) | **Cinema Matte & Anamorphic Desqueeze** | `CinemaMatte.kt`, `CinemaMatteOverlay.kt` — Anamorphic desqueeze (1.33x-2.0x), safe area guides, letterbox masks. |
| **13** | Gemini (`gemini-3.8-flash-high`) | **Cylindrical Panorama & 360 Stitching**| `PanoramaAnd360Engine.kt` — Cylindrical projection warping, exposure leveling, multi-band seam blending. |
| **14** | Gemini (`gemini-3.8-flash-high`) | **Video Formats & Encoder Profiles** | `VideoFormatEngine.kt` — Bitrate calculation matrices, All-I / Long-GOP / Constant Quality profiles. |
| **15** | Gemini (`gemini-3.8-flash-high`) | **Processing Job Queue & Concurrency** | `ProcessingJobQueue.kt` — Thread pool worker limits, priority queuing, zero-drop cancellation tokens. |
| **16** | Gemini (`gemini-3.8-flash-high`) | **Lens Probing & Optical Fusion** | `LensProbeService.kt` — Multi-camera sensor enumeration, aperture, FOV, OIS/EIS mode querying. |
| **17** | Gemini (`gemini-3.8-flash-high`) | **Universal Settings & Exif / XMP** | `UniversalSettingsEngine.kt` — EXIF/TIFF/XMP metadata injection, GPS privacy stripping, BT.2020 color tags. |
| **18** | Gemini (`gemini-3.8-flash-high`) | **Thermal Management & Telemetry Hub** | `SensorHub.kt`, `NativeTelemetryBridge.kt` — Thermal throttling safeguards, 30Hz lock-free seqlock telemetry. |
| **19** | Gemini (`gemini-3.8-flash-high`) | **Rotary Controls & Haptic Ergonomics** | `RotaryDial.kt`, `ModeCarousel.kt` — Touch inertia fling physics, haptic feedback curves, tick sounds. |
| **20** | Gemini (`gemini-3.8-flash-high`) | **Compose Viewfinder HUD Optimization** | `CameraScreen.kt`, `CommixViewModel.kt` — Recomposition hygiene, derivedStateOf, zero-jank HUD overlays. |
| **21** | Gemini (`gemini-3.8-flash-high`) | **Manual Exposure & Focus UI Ergonomics**| Manual controls — Touch sensitivity, gesture conflict prevention, numeric interval snapping. |
| **22** | Gemini (`gemini-3.8-flash-high`) | **State Persistence & Multi-Profile Config**| `CommixPreferences.kt` — Multi-profile switching (Cine, Pro, Vlog, Action), thread-safe DataStore. |
| **23** | Gemini (`gemini-3.8-flash-high`) | **Tone Curve Quantization & Grain Synthesis**| `color_science.cpp`, `image_pipeline.cpp` — Highlight roll-off splines, 35mm/16mm/8mm procedural grain kernels. |
| **24** | Gemini (`gemini-3.8-flash-high`) | **Hardware Quirk Database & Vendor ISPs** | `LensProbeService.kt` — Qualcomm Spectra, MediaTek Imagiq, Exynos, Google Tensor ISP quirks. |
| **25** | Gemini (`gemini-3.8-flash-high`) | **ProGuard/R8 & Native Binary Strip** | `proguard-rules.pro`, `build.gradle.kts` — R8 full-mode rules, JNI keep-rules, Compose compiler stability. |
| **26** | Gemini (`gemini-3.8-flash-high`) | **Security, Permissions & Privacy** | `AndroidManifest.xml`, `MainActivity.kt` — Scoped storage permissions, background camera security seals. |
| **27** | Gemini (`gemini-3.8-flash-high`) | **Unit & Integration Test Expansion** | `app/src/test/kotlin/` — Edge case test coverage expansion across orientation, exposure, and MediaStore. |
| **28** | Gemini (`gemini-3.8-flash-high`) | **Repository & ViewModel Architecture** | `CommixViewModel.kt`, `CommixRepository.kt` — Clean Architecture seams, coroutine scope discipline. |
| **29** | Gemini (`gemini-3.8-flash-high`) | **Memory Leak & Resource Safeguards** | Lifecycle safeguards — Surface/SurfaceTexture, HandlerThread, and ImageReader leak prevention. |
| **30** | Gemini (`gemini-3.8-flash-high`) | **Master Feature Mining & Cinema Roadmap**| `SPEC.md`, `TODO.md` — False Color EL-Zone, USB-C direct SSD recording, external monitor HDMI feed. |


---

## 4. Master 100-Agent Architecture & Capability Roster

| Cluster | Assigned Division | Architecture / Specialist Domains | Target Files / Systems |
|---|---|---|---|
| **001 – 010** | **UI & Ergonomic Architects** | Pro Viewfinder Layout, Anamorphic Desqueeze Matrix, HUD Controls, Safe Margins, Top Bar Occlusion Protection, Gesture Conflict Resolution | `CameraScreen.kt`, `ViewfinderSurface.kt`, `CinemaMatte.kt`, `CinemaMatteOverlay.kt`, `RotaryDial.kt` |
| **011 – 020** | **Exposure & Color Science Engineers** | ACEScc / ACEScg Color Pipeline, ARRI LogC3/LogC4, RED Log3G10, Canon CLog2, False Color EL-Zone stop latitude, 3D LUT tetrahedral interpolation | `color_science.cpp`, `scopes_engine.*`, `CaptureRequestFactory.kt` |
| **021 – 030** | **Optical Pipeline & ISP Fusion** | Multi-camera physical sensor enumeration, continuous optical zoom handoff, aperture control, focus peaking algorithms, hyperfocal distance calculations | `LensProbeService.kt`, `NativeEngineBridge.kt`, `computational_photo.cpp` |
| **031 – 040** | **Real-Time Video & Codec Specialists** | 4K/8K 60/120fps recording, H.265 Main10 HDR10 profile negotiation, All-I / Long-GOP encoder profiles, synthetic PTS pacing, container muxer stability | `MediaCodecRecorder.kt`, `VideoFormatEngine.kt`, `CommixRepository.kt` |
| **041 – 050** | **IMU Tracking & Gyroflow Stabilization** | Gyroflow v1.3 standard compliance, ascale (0.10197) accelerometer conversion, gyro rate interpolation, rolling shutter time sync, dual-stage Kalman filter | `stabilization_engine.cpp`, `SensorHub.kt` |
| **051 – 060** | **Computational Photography & Stacking** | Astrophotography median stacking, silky water accumulation, light trail synthesis, motion-compensated RAW HDR frame alignment | `computational_photo.cpp`, `image_pipeline.cpp` |
| **061 – 070** | **Concurrency & ThreadPool Engineering** | C++20 lock-free work-stealing threadpool, NEON SIMD vectorization, cache line alignment, zero-allocation real-time frame processing | `thread_pool.hpp`, `image_pipeline.cpp`, `jni_bridge.cpp` |
| **071 – 080** | **Pro Audio & Acoustic Engineering** | Low-latency uncompressed 24-bit 48kHz PCM/WAV recording, AAC 320kbps encoding, multi-mic polar patterns, high-pass low-cut DSP filters | `AudioEngine.kt` |
| **081 – 090** | **Background Processing & Job Orchestration**| ProcessingJobQueue strict FIFO prioritization, dynamic slot reallocation, cooperative cancellation tokens (CancellationToken), telemetry streaming | `ProcessingJobQueue.kt`, `ProcessingJobQueueTest.kt` |
| **091 – 100** | **Quality Assurance, R8 & Verification Lead** | Unit test suite expansion, ProGuard/R8 full-mode optimization, memory leak & Surface lifecycle safeguards, vendor ISP quirk profiling | `app/src/test/`, `build.gradle.kts`, `proguard-rules.pro` |
