package com.txor.commix

import com.txor.commix.model.CameraUiState
import com.txor.commix.ui.components.CinemaMatte
import org.junit.Assert.*
import org.junit.Test

/**
 * Rigorous Unit Tests for Universal Settings, EXIF Privacy Presets, and Camera Configuration Parity.
 *
 * Every test here calls production code (the [CameraUiState] constructor/reducer, [CaptureRequestFactory]
 * helpers, or [CinemaMatte]); none re-implements the logic it asserts against.
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
        assertEquals("off", state.cinemaMatteRatio)
        assertEquals(8, state.nightFrameCount)
        assertEquals(120, state.slowMoFps)
        assertEquals(2, state.timeLapseIntervalSec)
        assertEquals("timer", state.automationTriggerMode)
        assertEquals(2, state.queueWorkerThreads)
    }

    @Test
    fun testExifPrivacyModePresetStripsSensitiveData() {
        // Exercise the production privacy preset that CommixViewModel.onSetExifPreset delegates to.
        val defaultState = CameraUiState(exifLocation = true, exifImageDescription = true, exifUserComment = true)

        val privacyState = defaultState.withExifPreset(privacyMode = true)

        assertFalse("GPS Location must be stripped in Privacy Mode", privacyState.exifLocation)
        assertFalse("Image description must be stripped in Privacy Mode", privacyState.exifImageDescription)
        assertFalse("User comments must be stripped in Privacy Mode", privacyState.exifUserComment)
        assertTrue("Optical camera info retained", privacyState.exifCameraInfo)
        assertTrue("Lens info retained", privacyState.exifLensInfo)
        assertTrue("Exposure settings retained", privacyState.exifExposureSettings)
        assertTrue("Timestamp retained", privacyState.exifDateTime)
    }

    @Test
    fun testExifAllowAllPresetEnablesAllMetadata() {
        // Exercise the production "allow all" preset (privacyMode = false).
        val initial = CameraUiState(
            exifLocation = false,
            exifImageDescription = false,
            exifUserComment = false
        )

        val allowAllState = initial.withExifPreset(privacyMode = false)

        assertTrue(allowAllState.exifLocation)
        assertTrue(allowAllState.exifCameraInfo)
        assertTrue(allowAllState.exifLensInfo)
        assertTrue(allowAllState.exifExposureSettings)
        assertTrue(allowAllState.exifDateTime)
        assertTrue(allowAllState.exifImageDescription)
        assertTrue(allowAllState.exifUserComment)
    }

    @Test
    fun testExifOptionsMappingFeedsRepositoryConsumer() {
        // Exercise the production UI -> repository mapping (CommixViewModel.updateRepositoryParams
        // assigns repository.exifOptions = state.toExifOptions()). Deliberately mismatched toggles so a
        // transposed/reversed mapping cannot pass.
        val state = CameraUiState(
            exifCameraInfo = false,
            exifLocation = true,
            exifUserComment = false,
            exifLensInfo = true,
            exifExposureSettings = false,
            exifDateTime = true,
            exifImageDescription = false
        )

        val opts = state.toExifOptions()

        assertFalse("cameraInfo <- exifCameraInfo", opts.cameraInfo)
        assertTrue("location <- exifLocation", opts.location)
        assertFalse("userComment <- exifUserComment", opts.userComment)
        assertTrue("lensSpecs <- exifLensInfo", opts.lensSpecs)
        assertFalse("exposureSettings <- exifExposureSettings", opts.exposureSettings)
        assertTrue("dateTime <- exifDateTime", opts.dateTime)
        assertFalse("imageDescription <- exifImageDescription", opts.imageDescription)

        // The privacy preset must survive all the way to the repository consumer.
        val privacyOpts = CameraUiState(exifLocation = true).withExifPreset(privacyMode = true).toExifOptions()
        assertFalse("Privacy preset must strip GPS before it reaches the repository", privacyOpts.location)
        assertTrue("Privacy preset must retain optical metadata", privacyOpts.cameraInfo)
    }

    @Test
    fun testCinemaMatteRatiosParsing() {
        // Exercise the production matte geometry used by CinemaMatteOverlay.
        assertEquals(2.39f, CinemaMatte.aspectFor("2.39:1"), 0.001f)
        assertEquals(2.00f, CinemaMatte.aspectFor("2.00:1"), 0.001f)
        assertEquals(1.85f, CinemaMatte.aspectFor("1.85:1"), 0.001f)
        assertEquals(1.66f, CinemaMatte.aspectFor("1.66:1"), 0.001f)
        assertEquals(16f / 9f, CinemaMatte.aspectFor("16:9"), 0.001f)
        assertEquals(4f / 3f, CinemaMatte.aspectFor("4:3"), 0.001f)

        // An unknown label falls back to the production default (Scope), never to a 1.0 no-op matte.
        assertEquals(CinemaMatte.DEFAULT_ASPECT, CinemaMatte.aspectFor("nonsense"), 0.001f)
        assertEquals("2.39:1", CinemaMatte.DEFAULT_RATIO)

        for (ratio in listOf("2.39:1", "2.00:1", "1.85:1", "1.66:1", "16:9", "4:3")) {
            val aspect = CinemaMatte.aspectFor(ratio)
            assertTrue("Cinema aspect for $ratio must be wider than 1.0", aspect > 1.0f)
            if (ratio !in listOf("16:9", "4:3")) {
                assertTrue("Cinema aspect for $ratio must be >= 1.66", aspect >= 1.66f)
            }
        }
    }

    @Test
    fun testShutterAngleNanosecondsCalculation() {
        // Exercise the production shutter-angle math that CommixViewModel.onShutterAngleChanged uses.
        // 180 deg at 24 fps = 1/48 s = 20,833,333 ns
        assertEquals(20_833_333L, CaptureRequestFactory.shutterAngleToExposureNs(180.0, 24.0))
        // 360 deg at 24 fps = 1/24 s = 41,666,666 ns
        assertEquals(41_666_666L, CaptureRequestFactory.shutterAngleToExposureNs(360.0, 24.0))
        // 90 deg at 30 fps = 1/120 s = 8,333,333 ns
        assertEquals(8_333_333L, CaptureRequestFactory.shutterAngleToExposureNs(90.0, 30.0))
        // A degenerate 0 fps is floored to 1 fps instead of dividing by zero.
        assertEquals(500_000_000L, CaptureRequestFactory.shutterAngleToExposureNs(180.0, 0.0))
    }

    @Test
    fun testUniversalSettingsAndPerModeStateCoexistence() {
        // Exercise the production mode-switch reducer CameraUiState.switchToMode, which
        // CommixViewModel.onModeSelected delegates to. Universal settings must survive a mode switch
        // while per-mode exposure/optics are stashed and restored.
        val photoState = CameraUiState(
            activeMode = "PHOTO",
            hapticsEnabled = false,
            gridType = "golden",
            iso = 100,
            shutterNs = 1_000_000L,
            evStops = 0f
        )

        assertFalse(photoState.hapticsEnabled)
        assertEquals("golden", photoState.gridType)
        assertTrue(photoState.perModeSettings.isEmpty())

        // Switching to a never-visited mode restores production defaults and stashes PHOTO.
        val videoState = photoState.switchToMode("VIDEO")
        assertEquals("VIDEO", videoState.activeMode)
        assertEquals(0, videoState.iso)
        assertEquals(0L, videoState.shutterNs)
        assertEquals("PHOTO's outgoing settings must be stashed", 100, videoState.perModeSettings["PHOTO"]?.iso)
        assertEquals(1_000_000L, videoState.perModeSettings["PHOTO"]?.shutterNs)
        // Universal settings are untouched by the mode switch.
        assertFalse("Haptics preference must survive a mode switch", videoState.hapticsEnabled)
        assertEquals("golden", videoState.gridType)

        // Give VIDEO its own exposure settings, then switch back: both modes keep their own values.
        val videoConfigured = videoState.copy(iso = 400, shutterNs = 20_000_000L, evStops = -0.5f)
        val backToPhoto = videoConfigured.switchToMode("PHOTO")

        assertEquals("PHOTO", backToPhoto.activeMode)
        assertEquals(100, backToPhoto.iso)
        assertEquals(1_000_000L, backToPhoto.shutterNs)
        assertEquals(0f, backToPhoto.evStops, 0.001f)
        assertEquals("VIDEO's settings must be stashed", 400, backToPhoto.perModeSettings["VIDEO"]?.iso)
        assertEquals(20_000_000L, backToPhoto.perModeSettings["VIDEO"]?.shutterNs)
    }

    @Test
    fun testVectorscopeTelemetryAndStateIntegrity() {
        val state = CameraUiState()
        assertFalse("Vectorscope should be disabled by default", state.showVectorscope)
        assertFalse("All scopes toggle should be disabled by default", state.showScopes)
        assertEquals(0, state.vectorscopeCount)
        assertEquals(0, state.vectorscopeSamples.size)

        // Vectorscope chroma samples storage & count
        val dummySamples = FloatArray(512) { i -> (i % 200 - 100) / 100f }
        val activeState = state.copy(
            showVectorscope = true,
            vectorscopeSamples = dummySamples,
            vectorscopeCount = 256
        )
        assertTrue(activeState.showVectorscope)
        assertEquals(256, activeState.vectorscopeCount)
        assertEquals(512, activeState.vectorscopeSamples.size)
        assertEquals(-1.0f, activeState.vectorscopeSamples[0], 0.001f)
    }
}
