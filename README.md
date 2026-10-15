# ⚡ Commix Pro Camera (Native Jetpack Compose + C++20 Aether NDK)

> **Platform:** Standalone Native Android (Kotlin 2.1 + Jetpack Compose + C++20 ARM64 NDK)  
> **Application ID:** `org.codeaurora.snapcam` (OEM Camera HAL3 direct access)  
> **Version:** 1.0.0-beta.1 (VersionCode: 15)  
> **Target SDK:** Android 15 (API 35), Min SDK: Android 9 (API 28)  

---

## 🌟 Architecture Overview
- **C++20 Aether SIMD DSP**: Lock-free queues, 8-worker thread pool, real-time waveform/parade/vectorscope/histogram, and Bayer demosaic.
- **Direct Camera2 HAL3**: Direct hardware session control (Manual ISO, Shutter, Focus, RAW DNG).
- **Jetpack Compose UI**: 120Hz zero-jank declarative interface with Strong Skipping mode, rotary dials, and Apple cinema aesthetic.
- **Hardware Encoders**: High-speed `MediaCodec` surface video recording (4K/60, 1080p60) and Gyroflow GCSV IMU logging.

---

## 🛠️ Build Commands

### Run Unit Tests
```bash
./gradlew testReleaseUnitTest
```

### Build Release APK
```bash
./gradlew assembleRelease
```
Output APK is located at:
`app/build/outputs/apk/release/app-release.apk`
