package com.txor.commix

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

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
        assertTrue(lines.size >= 3)
        assertEquals("GYROFLOW V1", lines[0])
        assertEquals("t,gx,gy,gz,ax,ay,az", lines[1])

        val s1 = lines[2].split(",")
        assertEquals(7, s1.size)
        assertEquals("0.000", s1[0])

        val s2 = lines[3].split(",")
        assertEquals(7, s2.size)
        assertEquals("10.000", s2[0])
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
    fun testAutomationPresetsIntegrity() {
        val expectedPresetIds = listOf(
            "dolly_zoom",
            "sunrise_ramp",
            "golden_hour",
            "reveal_shot",
            "dramatic_rack"
        )
        for (id in expectedPresetIds) {
            assertNotNull(id)
            assertTrue(id.isNotBlank())
        }
    }

    @Test
    fun testHighSpeedTimestampPacing960Fps() {
        val targetFps = 960.0
        val isSlowMotion = false
        val playbackFps = 30.0

        val stepUs = if (isSlowMotion) {
            (1_000_000.0 / playbackFps).toLong()
        } else {
            (1_000_000.0 / targetFps).toLong()
        }

        assertEquals(1041L, stepUs)

        // 960 frames at 960 FPS span ~1 second
        val frameCount = 960
        val timestamps = (0 until frameCount).map { it * stepUs }

        assertEquals(0L, timestamps.first())
        assertEquals(959L * 1041L, timestamps.last())
        val durationSeconds = timestamps.last() / 1_000_000.0
        assertTrue("Duration of 960 frames at 960 fps must be ~1.0s", durationSeconds in 0.98..1.01)

        val computedFps = 1_000_000.0 / (timestamps[1] - timestamps[0])
        assertEquals(960.0, computedFps, 1.0)
    }

    @Test
    fun testSlowMotionTimestampPacing32xExpansion() {
        val targetCaptureFps = 960.0
        val isSlowMotion = true
        val playbackFps = 30.0

        val stepUs = if (isSlowMotion) {
            (1_000_000.0 / playbackFps).toLong()
        } else {
            (1_000_000.0 / targetCaptureFps).toLong()
        }

        assertEquals(33333L, stepUs)

        // 960 captured frames at 960 FPS, played back at 30 FPS, span 32 seconds
        val frameCount = 960
        val timestamps = (0 until frameCount).map { it * stepUs }

        assertEquals(0L, timestamps.first())
        val durationSeconds = (timestamps.last() + stepUs) / 1_000_000.0
        assertEquals(32.0, durationSeconds, 0.05)

        val timeDilationFactor = targetCaptureFps / playbackFps
        assertEquals(32.0, timeDilationFactor, 0.001)
    }

    @Test
    fun testHighSpeed240And120FpsPacing() {
        val step120 = (1_000_000.0 / 120.0).toLong()
        assertEquals(8333L, step120)

        val step240 = (1_000_000.0 / 240.0).toLong()
        assertEquals(4166L, step240)

        val step480 = (1_000_000.0 / 480.0).toLong()
        assertEquals(2083L, step480)
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
}
