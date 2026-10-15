# AGENTS.md — Rules for AI Agents Working in This Repository

> **STOP. Read `AUDIT.md` before you touch anything.**
> Commit `a61ed9c` ("C++20 Aether engine architecture") was independently audited on 2026-10-15
> against its own `README.md` / `SPEC.md` / `TODO.md` claims. Result: every checklist box was
> marked done, and many of those boxes describe code that is dead, unwired, or actively broken.
> This file exists so your session does not repeat that performance.

---

## 1. Evidence rule (non-negotiable)

Mark a task `[x]` — or write "done", "verified", "working", "production-ready" anywhere — **only** when the change includes all four:

1. **The production code path** that actually runs (not a parallel copy, not a with-suffix twin).
2. **A test that fails when that path breaks** — one that imports and calls the production code.
3. **An artifact for anything with an output** (image, video, `.gcsv`, telemetry frame, APK): either the produced file, or the exact command and its output, pasted in the commit/PR.
4. **A grep proving no contradictory second implementation** of the same thing is left in the tree.

If you cannot produce all four, the checkbox stays `[ ]`. "It compiles" is not evidence. "The test I wrote agrees with me" is not evidence. A comment is never evidence.

## 2. Forbidden patterns (every one of these was found in `a61ed9c`)

- **Orphan natives** — a JNI symbol with no production caller: `nativeComputeL1Path`, `nativeApplyCdlDirect`, `nativeApplyUnsharpMaskDirect`, `nativePushBestShotVelocity`, `nativeKeyframeTick`, `nativeGetBufferPointer`. Wire it end-to-end or delete it.
- **Dead twins** — two implementations, neither used: `commix_native.cpp`, `commix_scopes.cpp/.h`, `ImagePipeline.kt`, `VulkanComputeEngine.kt`, `SensorHub.startImuLogging()/exportGyroflowGcsv()`. Delete or wire; never commit both halves of a feature and use neither.
- **Tautological tests** — tests asserting against a *copy of the logic* instead of the logic. Current offenders: `CaptureRequestFactoryTest`, `MediaCodecAndSensorEngineTest`, parts of `KeyframeEngineAndAutomationTest`. They must call the class under test.
- **Settings theater** — a preference that is persisted and displayed but read by no code (full list: `AUDIT.md` §D). Every setting ships with its consumer and a test for that consumer.
- **Fake telemetry** — hardcoded values dressed as live data. Examples: `estimatedTimeRemainingMinutes` is a constant 180 ("3h 0m left" forever); the RecordingHud "stereo" VU right bar is the left value × 0.95.
- **Unimplemented claims in prose** — no "lock-free", "NEON", "SIMD", "@FastNative", "120Hz", "crash-resilient", "HAL3 direct", "1kHz" anywhere unless the code and a produced artifact prove it. As of this audit, none of those claims survive; see `AUDIT.md` §§A1–A12.
- **Broken artifacts with confident names** — `Fmp4Muxer` writes files no player can open, behind a "crash-resilient fMP4" label. A fallback that cannot be played is worse than an honest failure.

## 3. What is actually true (verified 2026-10-15, this machine)

- **Gradle 8.14 + AGP 8.7.3: the project builds.** `BUILD SUCCESSFUL`; `:app:testReleaseUnitTest` green (82/82); the APK contains `lib/arm64-v8a/libcommix_native.so`; the release APK is **signed with the Android Debug key** and is not a shipping artifact.
- **The pinned wrapper (Gradle 9.3.1) is an unsupported pairing for AGP 8.7.3** — Gradle itself flags the build *"incompatible with Gradle 9.0"*. Do not claim `./gradlew ...` works until you have actually run it and pasted the output.
- The C++ compiles and links. It just does the wrong things at runtime (`AUDIT.md` §§A–C).
- The 82 passing unit tests do **not** cover any of the defects — they test copies of the logic. Green here means nothing (AUDIT.md §E).

## 4. Required fix order (details and file:line in `AUDIT.md` §G)

1. Seqlock: `std::atomic` counters + reader re-reads `seq_begin`; or delete the "zero-copy lock-free" claim.
2. IMU/Gyroflow: wire `SensorHub`'s real `TYPE_GYROSCOPE` path; today the exported `.gcsv` is all zeros (`t=0`, `gyro=0` on every line).
3. Video: recordings are silent — add an AAC track or state audio is out of scope; stop overwriting PTS outside slow-mo; move `stopVideoRecording()` off the main thread.
4. `Fmp4Muxer`: implement real boxes (`trak/stsd/avcC`, non-zero `trun` flags) with a playable-file test, or delete it.
5. Best shot: call `nativePushBestShotVelocity` from the gyro stream; "SHOOT NOW" can never fire today — make it reachable or remove the overlay.
6. Delete the dead code inventory (`AUDIT.md` §B) or wire it with tests.
7. `LensProbeService`: iterate `cameraIdList`; remove the −10000..10000 spam.
8. Every §D setting: implement it or remove it from UI/state/prefs — no exceptions.
9. Rewrite the tautological tests to call production code; add one on-device smoke test that produces a JPEG, an MP4, and a `.gcsv`.
10. Toolchain: align Gradle/AGP/Kotlin to a supported matrix; real release signing; CI that runs the build and the tests.

## 5. When you fix something

Update `AUDIT.md`: strike the finding and append `**FIXED (date — evidence: command/file taken)**`. Un-strike nothing. Keep `TODO.md` honest, or don't touch it.

Your claims will be re-audited against this repository. Make them true this time.
