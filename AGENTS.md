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

## 2. Forbidden patterns (every one of these was found in `a61ed9c`; remediation status per item is in `AUDIT.md` §I)

- **Orphan natives** — a JNI symbol with no production caller: `nativeComputeL1Path`, `nativeApplyCdlDirect`, `nativeApplyUnsharpMaskDirect`, `nativePushBestShotVelocity`, `nativeKeyframeTick`, `nativeGetBufferPointer`. Wire it end-to-end or delete it.
- **Dead twins** — two implementations, neither used: `commix_native.cpp`, `commix_scopes.cpp/.h`, `ImagePipeline.kt`, `VulkanComputeEngine.kt`, `SensorHub.startImuLogging()/exportGyroflowGcsv()`. Delete or wire; never commit both halves of a feature and use neither.
- **Tautological tests** — tests asserting against a *copy of the logic* instead of the logic. Offenders found at the time: `CaptureRequestFactoryTest`, `MediaCodecAndSensorEngineTest`, parts of `KeyframeEngineAndAutomationTest` (some rewritten in batches 2–3; the fps-pacing tests in `MediaCodecAndSensorEngineTest` still re-implement the math — `AUDIT.md` §E keeps the current list). They must call the class under test.
- **Settings theater** — a preference that is persisted and displayed but read by no code (full list: `AUDIT.md` §D). Every setting ships with its consumer and a test for that consumer.
- **Fake telemetry** — hardcoded values dressed as live data. Examples from `a61ed9c`: `estimatedTimeRemainingMinutes` was a constant 180 ("3h 0m left" forever); the RecordingHud "stereo" VU right bar was the left value × 0.95. (Both fixed in batch 3 — §I; keep scanning for new ones: a value that never changes while the input does is fake telemetry.)
- **Unimplemented claims in prose** — no "lock-free", "NEON", "SIMD", "@FastNative", "120Hz", "crash-resilient", "HAL3 direct", "1kHz" anywhere unless the code and a produced artifact prove it. As of this audit, none of those claims survive; see `AUDIT.md` §§A1–A12.
- **Broken artifacts with confident names** — `Fmp4Muxer` wrote files no player could open, behind a "crash-resilient fMP4" label. (Deleted in batch 3 — §C6. A fallback that cannot be played is worse than an honest failure.)

## 3. What is actually true (verified 2026-10-15/16, this machine)

- **A clean checkout of `a61ed9c` does NOT build** — `AAPT: resource mipmap/ic_launcher not found` (icons committed only in `7ac0795`). Build claims apply from `7ac0795` onward. Never re-check "assembleRelease verified" for `a61ed9c`.
- **Latest audited commit: `28a43b7`** (batch 3, author-side hash `916fe1d`). Auditor battery through the wrapper-pinned Gradle 9.3.1: `./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:testNative :app:assembleRelease` → **BUILD SUCCESSFUL in 3m 13s**, exit 0; unit tests **81/81** both variants (down from 83 because two tautological tests were deleted — that is an improvement, not a regression); `testNative` is wired into `check` and its binary was run directly: **8/8 suites pass — 1210 valid reads, 0 torn reads**; APK (4.6 MB) contains `lib/arm64-v8a/libcommix_native.so`. The APK is **debug-signed** (`apksigner`: `CN=Android Debug`) — not a shipping artifact. Trust pasted output, not warnings.
- **Open at `28a43b7` — do not mark `[x]`:** videos still have **no audio track**; §D residual theater (`queueAutoQueueStacking`, `nightAstroAlignment`, `adaptivePerformanceEnabled`, `uncompressedAudio`, `audioLowCut`, `processZeroIntensity`; RAW toggle yields JPEG; "ProRes HQ" maps to AVC); the native stabilization ring buffer is still unfed (`pushImuSample` zero callers — the `.gcsv` is produced by the Kotlin path only); §C10 progress theater; §C11 stop race; §F (debug signing, no CI, `applicationId` impersonation); `SPEC.md` still claims lock-free queues + `std::jthread` (§A1); §A5/§A6/§A7/§A11/§A12.
- Historical: batch 2 (`7ac0795`): release path verified, 8m 29s, 83/83.
- **Minimum bar for verifying a fix:** the four-part evidence rule (§1). "It compiles", a commit message, or an `AUDIT.md` strike is not evidence — the auditor re-runs the battery and reads the production call sites.

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

**Status at `28a43b7`:** 1 ✓ (atomics + dual-counter reader; payload fields still plain), 2 partial (real gyro → Kotlin `.gcsv` + best-shot; native ring buffer unfed), 3 partial (**no audio track**; PTS and stop-threading done), 4 ✓ (deleted), 5 ✓ (reachable; engine test proves the trigger), 6 ✓ (orphans deleted or wired), 7 ✓ (stale copy gone), 8 partial (residual list in `AUDIT.md` §D), 9 partial (tautologies partly rewritten; `testNative` wired into `check`; no device test), 10 ✘ (debug signing, no CI).

## 5. When you fix something

Update `AUDIT.md`: strike the finding and append `**FIXED (date — evidence: command/file taken)**`. Un-strike nothing. Keep `TODO.md` honest, or don't touch it.

Your claims will be re-audited against this repository. Make them true this time.
