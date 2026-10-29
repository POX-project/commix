package com.txor.commix

import android.media.MediaFormat
import com.txor.commix.automation.AutomationEngine
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

class MediaCodecAndSensorEngineTest {

    @Test
    fun testAudioDbAndRmsCalculation() {
        val maxRms = 32767.0
        val maxDb = AudioEngine.calculateDb(maxRms)
        assertEquals(0.0, maxDb, 0.05)

        val halfRms = 16384.0
        val halfDb = AudioEngine.calculateDb(halfRms)
        assertEquals(-6.02, halfDb, 0.05)

        val normDb = AudioEngine.calculateNorm(halfDb)
        assertTrue(normDb in 0.89..0.91)
    }

    @Test
    fun testAudioNormalizationClampsNegativeInfinity() {
        val silentRms = 0.0
        val silentDb = AudioEngine.calculateDb(silentRms)
        assertEquals(-96.0, silentDb, 0.001)
        val norm = AudioEngine.calculateNorm(silentDb)
        assertEquals(0.0, norm, 0.001)
    }

    @Test
    fun testGyroflowGcsvHeaderAndFormat() {
        val samples = listOf(
            ImuSample(
                timestampNs = 1_000_000_000L,
                gyroX = 0.012345f,
                gyroY = -0.054321f,
                gyroZ = 0.001234f,
                accelX = 0.0f,
                accelY = 9.81f,
                accelZ = 0.1f
            ),
            ImuSample(
                timestampNs = 1_010_000_000L,
                gyroX = 0.015000f,
                gyroY = -0.050000f,
                gyroZ = 0.002000f,
                accelX = 0.05f,
                accelY = 9.80f,
                accelZ = 0.12f
            )
        )

        val tempFile = File.createTempFile("gyroflow_test", ".gcsv")
        tempFile.deleteOnExit()

        // Call the production SensorHub exportGyroflowGcsv implementation
        SensorHub.exportGyroflowGcsv(tempFile, samples)

        assertTrue(tempFile.exists())
        val lines = tempFile.readLines()

        // Gyroflow .gcsv spec 1.3 mandatory lines: the identifying line, the format version,
        // a unique logger id (line 3) and the IMU orientation string (line 4).
        // https://docs.gyroflow.xyz/app/technical-details/gcsv-format
        assertEquals("GYROFLOW IMU LOG", lines[0])
        assertEquals("version,1.3", lines[1])
        assertEquals("id,commix", lines[2])
        assertEquals("orientation,YxZ", lines[3])

        // The numeric column header is the last header line, immediately before the sample rows.
        val csvHeaderIndex = lines.indexOf("t,gx,gy,gz,ax,ay,az")
        assertTrue("CSV column header line is missing", csvHeaderIndex >= 2)
        assertTrue("Must contain one row per sample after the column header", lines.size >= csvHeaderIndex + 3)

        // Row timestamps are seconds relative to the first sample (1_010_000_000 - 1_000_000_000 = 10 ms).
        val s1 = lines[csvHeaderIndex + 1].split(",")
        assertEquals(7, s1.size)
        assertEquals("0.000000", s1[0])
        assertEquals("0.012345", s1[1])
        assertEquals("-0.054321", s1[2])
        assertEquals("0.001234", s1[3])
        assertEquals("0.000000", s1[4])
        assertEquals("9.810000", s1[5])
        assertEquals("0.100000", s1[6])

        val s2 = lines[csvHeaderIndex + 2].split(",")
        assertEquals(7, s2.size)
        assertEquals("0.010000", s2[0])
        assertEquals("0.015000", s2[1])
        assertEquals("-0.050000", s2[2])
        assertEquals("0.002000", s2[3])
        assertEquals("0.050000", s2[4])
        assertEquals("9.800000", s2[5])
        assertEquals("0.120000", s2[6])
    }

    @Test
    fun testCaptureRequestParamsDefaultIntegrity() {
        val params = CaptureRequestParams(
            iso = 200,
            expNs = 20_000_000L,
            evStops = 0.3f,
            toneCurve = "neutral",
            filmGrain = "medium",
            lutLook = "kodak_portra_400",
            isProcessZero = true,
            processZeroIntensity = 0.8f
        )

        assertEquals(200, params.iso)
        assertEquals(20_000_000L, params.expNs)
        assertEquals(0.3f, params.evStops, 0.001f)
        assertEquals("neutral", params.toneCurve)
        assertEquals("medium", params.filmGrain)
        assertEquals("kodak_portra_400", params.lutLook)
        assertTrue(params.isProcessZero)
        assertEquals(0.8f, params.processZeroIntensity, 0.001f)
    }

    @Test
    fun testAutomationBuiltInPresetsRegistry() {
        // Exercise the production preset registry (AutomationEngine), not a copy pasted into the test.
        val presets = AutomationEngine.BUILT_IN_PRESETS
        assertEquals("Exactly five built-in automation presets must be registered", 5, presets.size)

        val ids = presets.map { it.id }
        assertEquals("Preset ids must be unique", ids.size, ids.toSet().size)
        assertEquals(
            listOf("dolly_zoom", "sunrise_ramp", "golden_hour", "reveal_shot", "dramatic_rack"),
            ids
        )

        for (preset in presets) {
            assertTrue("Preset ${preset.id} needs a title", preset.title.isNotBlank())
            assertTrue("Preset ${preset.id} needs a positive duration", preset.durationSeconds > 0f)
            assertTrue("Preset ${preset.id} needs at least one keyframe track", preset.allTracks.isNotEmpty())
        }

        // getPresetById must resolve every registered id back to its own config...
        for (preset in presets) {
            assertSame(preset, AutomationEngine.getPresetById(preset.id))
        }
        // ...and fall back to the dolly zoom for an unknown id.
        assertSame(AutomationEngine.PRESET_DOLLY_ZOOM, AutomationEngine.getPresetById("does_not_exist"))
    }

    @Test
    fun testProductionFrameStepPacing960HighSpeed() {
        // Production pacing math applied by MediaCodecRecorder's drain loop.
        val stepUs = MediaCodecRecorder.computeFrameStepUs(
            targetFps = 960.0,
            isSlowMotion = false,
            playbackFps = 30.0
        )
        assertEquals(1041L, stepUs)

        // 960 frames at the real-time step span ~1 second of container time.
        val lastPts = MediaCodecRecorder.computeSyntheticPtsUs(959L, stepUs)
        assertEquals(959L * 1041L, lastPts)
        val durationSeconds = lastPts / 1_000_000.0
        assertTrue("Duration of 960 frames at 960 fps must be ~1.0s", durationSeconds in 0.98..1.01)

        val computedFps = 1_000_000.0 / stepUs
        assertEquals(960.0, computedFps, 1.0)
    }

    @Test
    fun testProductionSlowMotionFrameStep32xExpansion() {
        val stepUs = MediaCodecRecorder.computeFrameStepUs(
            targetFps = 960.0,
            isSlowMotion = true,
            playbackFps = 30.0
        )
        assertEquals(33333L, stepUs)

        // 960 captured frames played back at 30 fps span 32 seconds.
        val lastFrameEndUs = MediaCodecRecorder.computeSyntheticPtsUs(959L, stepUs) + stepUs
        assertEquals(32.0, lastFrameEndUs / 1_000_000.0, 0.05)
    }

    @Test
    fun testProductionFrameStepAcrossSupportedHighSpeeds() {
        assertEquals(8333L, MediaCodecRecorder.computeFrameStepUs(120.0, false, 30.0))
        assertEquals(4166L, MediaCodecRecorder.computeFrameStepUs(240.0, false, 30.0))
        assertEquals(2083L, MediaCodecRecorder.computeFrameStepUs(480.0, false, 30.0))

        // Slow-motion always paces at the playback rate regardless of the capture rate.
        assertEquals(33333L, MediaCodecRecorder.computeFrameStepUs(120.0, true, 30.0))
        assertEquals(33333L, MediaCodecRecorder.computeFrameStepUs(960.0, true, 30.0))
    }

    @Test
    fun testCommixRecordingEnginePropertiesAndDilation() {
        val tempFile = File.createTempFile("commix_test_rec", ".mp4")
        tempFile.deleteOnExit()

        val engine960SlowMo = CommixRecordingEngine(
            outputFile = tempFile,
            width = 1280,
            height = 720,
            targetFps = 960.0,
            isSlowMotion = true,
            playbackFps = 30.0
        )

        assertEquals(30.0, engine960SlowMo.effectiveContainerFps, 0.001)
        assertEquals(32.0, engine960SlowMo.timeDilationFactor, 0.001)
        assertFalse(engine960SlowMo.isRunning)

        val engine120Hfr = CommixRecordingEngine(
            outputFile = tempFile,
            width = 1920,
            height = 1080,
            targetFps = 120.0,
            isSlowMotion = false
        )

        assertEquals(120.0, engine120Hfr.effectiveContainerFps, 0.001)
        assertEquals(1.0, engine120Hfr.timeDilationFactor, 0.001)
    }

    @Test
    fun test8KAnd4KResolutionParsingAndStandards() {
        // Verify 8K string parsing in PipelineThroughputEngine
        val (w8k, h8k) = PipelineThroughputEngine.parseResolution("8K")
        assertEquals(7680, w8k)
        assertEquals(4320, h8k)

        val (w8kFull, h8kFull) = PipelineThroughputEngine.parseResolution("7680x4320")
        assertEquals(7680, w8kFull)
        assertEquals(4320, h8kFull)

        val (w8kDci, h8kDci) = PipelineThroughputEngine.parseResolution("8192x4320")
        assertEquals(8192, w8kDci)
        assertEquals(4320, h8kDci)

        // Verify 8K resolution details and broadcast standard recognition
        val detail8kUhd = PipelineThroughputEngine.getResolutionDetail("7680x4320")
        assertTrue("7680x4320 must be recognized as standard", detail8kUhd.isRecognizedStandard)
        assertEquals("8K UHD BROADCAST (16:9)", detail8kUhd.standardName)
        assertEquals(33.1776, detail8kUhd.megapixels, 0.001)

        val detail8kDci = PipelineThroughputEngine.getResolutionDetail("8192x4320")
        assertTrue("8192x4320 must be recognized as standard", detail8kDci.isRecognizedStandard)
        assertEquals("8K DCI CINEMA (17:9)", detail8kDci.standardName)

        // Verify resolution calculation from 33.2 Megapixels
        val calculated8k = PipelineThroughputEngine.calculateResolutionForMp(33.2)
        assertEquals(7680, calculated8k.first)
        assertEquals(4320, calculated8k.second)

        // Verify 8K in standard resolutions list
        val standardRes = PipelineThroughputEngine.getStandardResolutions(listOf("7680x4320"))
        assertTrue("Standard resolutions list must include 8K when reported by sensor/device", standardRes.contains("7680x4320"))
        assertTrue("Standard resolutions list must include 4K", standardRes.contains("3840x2160"))
    }

    @Test
    fun testDriftFreeSyntheticPtsPrecisionAcrossBroadcastStandards() {
        // NTSC Cinema Broadcast 23.976023976 FPS (24000/1001)
        val fps2398 = 23.976023976
        val stepUs2398 = MediaCodecRecorder.computeFrameStepUs(fps2398, false, 30.0)
        assertEquals(41708L, stepUs2398)

        // In 1 hour of 23.976 fps capture: 86,314 frames
        val frameCount1Hr2398 = 86314L
        val accumulatedTruncatedPts = frameCount1Hr2398 * stepUs2398
        val driftFreePts = MediaCodecRecorder.computeDriftFreePtsUs(frameCount1Hr2398, fps2398)

        // Accumulated truncated step drifts by ~11.7 milliseconds (more than a quarter frame)
        val driftUs2398 = abs(accumulatedTruncatedPts - driftFreePts)
        assertTrue("Truncated step must show accumulation drift (>10ms)", driftUs2398 > 10_000L)
        assertEquals(0L, abs(driftFreePts - ((frameCount1Hr2398 * 1_000_000.0) / fps2398).toLong()))

        // Broadcast HFR 59.94005994 FPS (60000/1001)
        val fps5994 = 59.94005994
        val stepUs5994 = MediaCodecRecorder.computeFrameStepUs(fps5994, false, 30.0)
        assertEquals(16683L, stepUs5994)

        // In 1 hour of 59.94 fps capture: 215,784 frames
        val frameCount1Hr5994 = 215784L
        val accumulatedTruncated5994 = frameCount1Hr5994 * stepUs5994
        val driftFreePts5994 = MediaCodecRecorder.computeDriftFreePtsUs(frameCount1Hr5994, fps5994)

        // Truncated step drifts by ~71.9 milliseconds (more than 4 full video frames desync!)
        val driftUs5994 = abs(accumulatedTruncated5994 - driftFreePts5994)
        assertTrue("Truncated step at 59.94 FPS must show >70ms drift in 1 hour", driftUs5994 > 70_000L)

        // Verify strict monotonicity across successive frames
        var prevPts = -1L
        for (i in 0L..1000L) {
            val pts = MediaCodecRecorder.computeDriftFreePtsUs(i, fps5994)
            assertTrue("PTS must be strictly increasing for every frame", pts > prevPts)
            prevPts = pts
        }
    }

    @Test
    fun testAllIAndLongGopProfileParameters() {
        // All-I: Intra-only (iFrameIntervalSeconds <= 0)
        assertTrue(MediaCodecRecorder.isAllIProfile(0.0f))
        assertTrue(MediaCodecRecorder.isAllIProfile(-1.0f))

        // Long-GOP: Periodic keyframes (iFrameIntervalSeconds > 0)
        assertFalse(MediaCodecRecorder.isAllIProfile(1.0f))
        assertFalse(MediaCodecRecorder.isAllIProfile(2.0f))

        // Quality profile verification: Cinema Master (200 Mbps) is All-I
        val cinemaMaster = VideoQualityProfile.profiles.first { it.label == "Cinema Master" }
        assertTrue("Cinema Master profile must be All-I", cinemaMaster.isAllI)
        assertEquals(200_000_000, cinemaMaster.bitrateBps)

        val highQuality = VideoQualityProfile.profiles.first { it.label == "High Quality" }
        assertFalse("High Quality profile must be Long-GOP", highQuality.isAllI)
    }

    @Test
    fun testH265Main10AndHdr10EngineConfiguration() {
        val tempFile = File.createTempFile("commix_hdr_test", ".mp4")
        tempFile.deleteOnExit()

        val hdrEngine = CommixRecordingEngine(
            outputFile = tempFile,
            width = 7680,
            height = 4320,
            targetFps = 60.0,
            bitrateBps = 200_000_000,
            mimeType = MediaFormat.MIMETYPE_VIDEO_HEVC,
            isHdr10 = true,
            iFrameIntervalSeconds = 0.0f,
            useSyntheticPts = true
        )

        assertEquals(7680, hdrEngine.width)
        assertEquals(4320, hdrEngine.height)
        assertEquals(60.0, hdrEngine.targetFps, 0.001)
        assertEquals(MediaFormat.MIMETYPE_VIDEO_HEVC, hdrEngine.mimeType)
        assertTrue(hdrEngine.isHdr10)
        assertEquals(0.0f, hdrEngine.iFrameIntervalSeconds, 0.001f)
        assertTrue(hdrEngine.useSyntheticPts)
        assertTrue(MediaCodecRecorder.isAllIProfile(hdrEngine.iFrameIntervalSeconds))
    }

    @Test
    fun testAudioFrameSinkAndEngineIntegration() {
        val tempFile = File.createTempFile("commix_audio_test", ".mp4")
        tempFile.deleteOnExit()

        val engine = CommixRecordingEngine(
            outputFile = tempFile,
            width = 1920,
            height = 1080,
            targetFps = 30.0,
            bitrateBps = 20_000_000,
            recordAudio = true,
            audioSampleRate = 44100,
            uncompressedAudio = true
        )

        assertNotNull("AudioFrameSink must be exposed by recording engine", engine.audioFrameSink)
        assertFalse(engine.isRunning)

        // Verify audio frame sink consumes PCM data safely when not running
        val pcm = ShortArray(1024) { 0 }
        engine.audioFrameSink.onAudioFrame(pcm, pcm.size, 44100)
    }

    @Test
    fun testCinemaShutterAngleToExposureConversion() {
        // 180° shutter at 24.0 fps = 1/48s = 20,833,333 ns
        val exp180At24 = CaptureRequestFactory.shutterAngleToExposureNs(180.0, 24.0)
        assertEquals(20_833_333L, exp180At24)

        // 180° shutter at 30.0 fps = 1/60s = 16,666,666 ns
        val exp180At30 = CaptureRequestFactory.shutterAngleToExposureNs(180.0, 30.0)
        assertEquals(16_666_666L, exp180At30)

        // 90° shutter at 24.0 fps = 1/96s = 10,416,666 ns
        val exp90At24 = CaptureRequestFactory.shutterAngleToExposureNs(90.0, 24.0)
        assertEquals(10_416_666L, exp90At24)

        // 360° open shutter at 24.0 fps = 1/24s = 41,666,666 ns
        val exp360At24 = CaptureRequestFactory.shutterAngleToExposureNs(360.0, 24.0)
        assertEquals(41_666_666L, exp360At24)
    }

    @Test
    fun testCaptureRequestParamsAudioAndAdaptiveIntegrity() {
        val params = CaptureRequestParams(
            uncompressedAudio = true,
            audioLowCut = true,
            audioGain = 6.0f,
            adaptivePerformanceEnabled = false,
            nightTone = "gold"
        )

        assertTrue(params.uncompressedAudio)
        assertTrue(params.audioLowCut)
        assertEquals(6.0f, params.audioGain, 0.01f)
        assertFalse(params.adaptivePerformanceEnabled)
        assertEquals("gold", params.nightTone)
    }
}
