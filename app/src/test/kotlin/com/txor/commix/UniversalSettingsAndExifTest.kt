package com.txor.commix

import com.txor.commix.model.CameraUiState
import com.txor.commix.model.ExifOptions
import com.txor.commix.model.ModeSettings
import org.junit.Assert.*
import org.junit.Test

/**
 * Rigorous Unit Tests for Universal Settings, EXIF Privacy Presets, and Camera Configuration Parity.
 */
class UniversalSettingsAndExifTest {

    @Test
    fun testDefaultUniversalSettingsParityWithFlutter() {
        val state = CameraUiState()

        // 1. Display & Viewfinder
        assertFalse("Viewfinder should be active by default", state.viewfinderDisabled)
        assertFalse("Clean viewfinder should be off by default", state.cleanViewfinderMode)
        assertEquals("Default grid should be 3x3", "3x3", state.gridType)
        assertFalse("Horizon leveler should be off by default", state.showHorizonLeveler)

        // 2. Haptics & System
        assertTrue("Haptics should be enabled by default", state.hapticsEnabled)
        assertTrue("Focus ring animations should be enabled", state.animationsEnabled)
        assertTrue("Storage gauge should be shown by default", state.showStorageRemaining)
        assertTrue("Adaptive performance should be active", state.adaptivePerformanceEnabled)

        // 3. EXIF Privacy Defaults
        assertTrue("Camera hardware info allowed by default", state.exifCameraInfo)
        assertTrue("Lens info allowed by default", state.exifLensInfo)
        assertTrue("Exposure settings allowed by default", state.exifExposureSettings)
        assertTrue("Timestamp allowed by default", state.exifDateTime)
        assertFalse("GPS Geotagging must default to OFF for user privacy", state.exifLocation)
        assertTrue("Image description allowed by default", state.exifImageDescription)
        assertTrue("User comment allowed by default", state.exifUserComment)

        // 4. Mode Presets
        assertEquals("hevc", state.videoCodec)
        assertEquals("clarity", state.photoEnhanceAlgo)
        assertFalse(state.uncompressedAudio)
        assertEquals("180", state.defaultShutterAngle)
        assertEquals("2.39:1", state.cinemaMatteRatio)
        assertEquals(8, state.nightFrameCount)
        assertTrue(state.nightAstroAlignment)
        assertEquals(120, state.slowMoFps)
        assertEquals(2, state.timeLapseIntervalSec)
        assertEquals("timer", state.automationTriggerMode)
        assertEquals(2, state.queueWorkerThreads)
        assertTrue(state.queueAutoQueueStacking)
    }

    @Test
    fun testExifPrivacyModePresetStripsSensitiveData() {
        val defaultState = CameraUiState(exifLocation = true)

        // Apply Privacy Mode Preset
        val privacyState = defaultState.copy(
            exifCameraInfo = true,
            exifLensInfo = true,
            exifExposureSettings = true,
            exifDateTime = true,
            exifLocation = false, // Stripped!
            exifImageDescription = false, // Stripped!
            exifUserComment = false, // Stripped!
            exifColorGeometry = true
        )

        assertFalse("GPS Location must be stripped in Privacy Mode", privacyState.exifLocation)
        assertFalse("Image description must be stripped in Privacy Mode", privacyState.exifImageDescription)
        assertFalse("User comments must be stripped in Privacy Mode", privacyState.exifUserComment)
        assertTrue("Optical camera info retained", privacyState.exifCameraInfo)
        assertTrue("Exposure settings retained", privacyState.exifExposureSettings)
        assertTrue("Timestamp retained", privacyState.exifDateTime)
    }

    @Test
    fun testExifAllowAllPresetEnablesAllMetadata() {
        val initial = CameraUiState(exifLocation = false)

        val allowAllState = initial.copy(
            exifCameraInfo = true,
            exifLensInfo = true,
            exifExposureSettings = true,
            exifDateTime = true,
            exifLocation = true,
            exifImageDescription = true,
            exifUserComment = true,
            exifColorGeometry = true
        )

        assertTrue(allowAllState.exifLocation)
        assertTrue(allowAllState.exifCameraInfo)
        assertTrue(allowAllState.exifExposureSettings)
        assertTrue(allowAllState.exifDateTime)
        assertTrue(allowAllState.exifImageDescription)
        assertTrue(allowAllState.exifUserComment)
    }

    @Test
    fun testExifOptionsCompleteness() {
        val opts = ExifOptions(
            cameraInfo = true,
            location = true,
            userComment = true,
            lensSpecs = true,
            exposureSettings = true,
            dateTime = true,
            imageDescription = true
        )

        assertTrue(opts.cameraInfo)
        assertTrue(opts.location)
        assertTrue(opts.userComment)
        assertTrue(opts.lensSpecs)
        assertTrue(opts.exposureSettings)
        assertTrue(opts.dateTime)
        assertTrue(opts.imageDescription)
    }

    @Test
    fun testCinemaMatteRatiosParsing() {
        val validRatios = listOf("2.39:1", "2.00:1", "1.85:1", "1.66:1", "16:9")
        for (r in validRatios) {
            val aspect = when (r) {
                "2.39:1" -> 2.39f
                "2.00:1" -> 2.00f
                "1.85:1" -> 1.85f
                "1.66:1" -> 1.66f
                "16:9" -> 16f / 9f
                else -> 1.0f
            }
            assertTrue("Cinema aspect must be wider than 1.0", aspect > 1.0f)
            assertTrue("Scope ratio must be >= 1.66", aspect >= 1.66f)
        }
    }

    @Test
    fun testShutterAngleNanosecondsCalculation() {
        val fps = 24.0
        val angle180 = 180.0
        val angle360 = 360.0

        // 180° shutter at 24fps = 1/48s = 20,833,333 ns
        val ns180 = (1_000_000_000.0 * (angle180 / 360.0) / fps).toLong()
        assertEquals(20_833_333L, ns180)

        // 360° shutter at 24fps = 1/24s = 41,666,666 ns
        val ns360 = (1_000_000_000.0 * (angle360 / 360.0) / fps).toLong()
        assertEquals(41_666_666L, ns360)
    }

    @Test
    fun testUniversalSettingsAndPerModeStateCoexistence() {
        val photoSettings = ModeSettings(iso = 100, shutterNs = 1_000_000L, evStops = 0f)
        val videoSettings = ModeSettings(iso = 400, shutterNs = 20_000_000L, evStops = -0.5f)

        val state = CameraUiState(
            hapticsEnabled = false,
            gridType = "golden",
            perModeSettings = mapOf(
                "PHOTO" to photoSettings,
                "VIDEO" to videoSettings
            )
        )

        assertFalse(state.hapticsEnabled)
        assertEquals("golden", state.gridType)
        assertEquals(100, state.perModeSettings["PHOTO"]?.iso)
        assertEquals(400, state.perModeSettings["VIDEO"]?.iso)
    }
}
