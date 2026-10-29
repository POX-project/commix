package com.txor.commix

import android.hardware.camera2.CaptureRequest
import com.txor.commix.model.CameraUiState
import com.txor.commix.model.ModeSettings
import org.junit.Assert.*
import org.junit.Test

class CaptureRequestFactoryTest {

    @Test
    fun testZoomCalculationRestoresFullSensorAtOneX() {
        val crop = CaptureRequestFactory.calculateCrop(0, 0, 4000, 3000, 1.0)

        assertEquals(0, crop.left)
        assertEquals(0, crop.top)
        assertEquals(4000, crop.right)
        assertEquals(3000, crop.bottom)
        assertEquals(4000, crop.width)
        assertEquals(3000, crop.height)
    }

    @Test
    fun testZoomCalculationCentersCroppedRegionAtTwoX() {
        val crop = CaptureRequestFactory.calculateCrop(0, 0, 4000, 3000, 2.0)

        assertEquals(2000, crop.width)
        assertEquals(1500, crop.height)
        assertEquals(1000, crop.left)
        assertEquals(750, crop.top)
        assertEquals(3000, crop.right)
        assertEquals(2250, crop.bottom)
    }

    @Test
    fun testSubOneZoomIsSafelyCoercedToOneX() {
        val crop = CaptureRequestFactory.calculateCrop(0, 0, 4000, 3000, 0.5)

        assertEquals(0, crop.left)
        assertEquals(0, crop.top)
        assertEquals(4000, crop.right)
        assertEquals(3000, crop.bottom)
        assertEquals(4000, crop.width)
        assertEquals(3000, crop.height)
    }

    @Test
    fun testDegenerateSensorActiveBoundsDoNotCrash() {
        val zeroCrop = CaptureRequestFactory.calculateCrop(0, 0, 0, 0, 2.0)
        assertEquals(0, zeroCrop.width)
        assertEquals(0, zeroCrop.height)

        val smallCrop = CaptureRequestFactory.calculateCrop(0, 0, 32, 24, 2.0)
        assertEquals(32, smallCrop.width)
        assertEquals(24, smallCrop.height)
    }

    @Test
    fun testKelvinToRggbProducesValidGains() {
        val gains6500 = CaptureRequestFactory.calculateRgbGains(6500)
        assertEquals(4, gains6500.size)
        assertEquals(1.0f, gains6500[1], 0.001f) // G_even
        assertEquals(1.0f, gains6500[2], 0.001f) // G_odd
        assertTrue(gains6500[0] in 1.0f..3.5f)   // R
        assertTrue(gains6500[3] in 1.0f..3.5f)   // B

        // Low kelvin (warm) should require higher blue gain and lower red gain to balance
        val warmGains = CaptureRequestFactory.calculateRgbGains(2500)
        assertTrue(warmGains[3] > gains6500[3])
    }

    @Test
    fun testEvCoercionWithinHardwareRange() {
        // Exercise the production EV-index helper that applyControls uses for CONTROL_AE_EXPOSURE_COMPENSATION.
        val evStep = 0.1f
        val minEvIndex = -12
        val maxEvIndex = 12

        assertEquals(12, CaptureRequestFactory.calculateEvIndex(2.0f, evStep, minEvIndex, maxEvIndex))
        assertEquals(-12, CaptureRequestFactory.calculateEvIndex(-2.5f, evStep, minEvIndex, maxEvIndex))
        assertEquals(0, CaptureRequestFactory.calculateEvIndex(0.0f, evStep, minEvIndex, maxEvIndex))
        // In-range values are rounded to the nearest step index rather than clamped.
        assertEquals(3, CaptureRequestFactory.calculateEvIndex(0.34f, evStep, minEvIndex, maxEvIndex))
        assertEquals(-7, CaptureRequestFactory.calculateEvIndex(-0.7f, evStep, minEvIndex, maxEvIndex))
        // A narrower hardware range must still clamp.
        assertEquals(2, CaptureRequestFactory.calculateEvIndex(5.0f, 1.0f, -2, 2))
    }

    @Test
    fun testSpaceZoomHardwareClampingPreventsHalRejection() {
        val hardwareMaxZoom = 10.0

        // Exercise the production clamp that applyControls applies before CONTROL_ZOOM_RATIO / SCALER_CROP_REGION.
        val effectiveHwZoom30 = CaptureRequestFactory.calculateEffectiveHardwareZoom(30.0, hardwareMaxZoom)
        assertEquals(10.0, effectiveHwZoom30, 0.001)
        val crop30 = CaptureRequestFactory.calculateCrop(0, 0, 4000, 3000, effectiveHwZoom30)
        assertEquals(400, crop30.width)
        assertEquals(300, crop30.height)

        val effectiveHwZoom100 = CaptureRequestFactory.calculateEffectiveHardwareZoom(100.0, hardwareMaxZoom)
        assertEquals(10.0, effectiveHwZoom100, 0.001)
        val crop100 = CaptureRequestFactory.calculateCrop(0, 0, 4000, 3000, effectiveHwZoom100)
        assertEquals(400, crop100.width)
        assertEquals(300, crop100.height)

        // For standard 2.0x zoom, hardware zoom is not clamped
        val effectiveHwZoom2 = CaptureRequestFactory.calculateEffectiveHardwareZoom(2.0, hardwareMaxZoom)
        assertEquals(2.0, effectiveHwZoom2, 0.001)
        val crop2 = CaptureRequestFactory.calculateCrop(0, 0, 4000, 3000, effectiveHwZoom2)
        assertEquals(2000, crop2.width)
        assertEquals(1500, crop2.height)

        // A degenerate hardware maximum is floored to 1x instead of producing a sub-1x request.
        assertEquals(1.0, CaptureRequestFactory.calculateEffectiveHardwareZoom(5.0, 0.0), 0.001)
    }

    @Test
    fun testCaptureRequestParamsFlashModeDefaults() {
        val defaultParams = CaptureRequestParams()
        assertEquals("off", defaultParams.flashMode)
        assertEquals(10.0, defaultParams.hardwareMaxDigitalZoom, 0.001)
        assertEquals(1.0, defaultParams.zoom, 0.001)

        val torchParams = defaultParams.copy(flashMode = "torch")
        assertEquals("torch", torchParams.flashMode)

        val autoParams = defaultParams.copy(flashMode = "auto")
        assertEquals("auto", autoParams.flashMode)
    }

    @Test
    fun testAntibandingAutoSupportedWhenAvailable() {
        val availableModes = intArrayOf(
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF,
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ,
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ,
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
        )
        assertTrue(CaptureRequestFactory.isAntibandingAutoSupported(availableModes))

        val paramsWithAuto = CaptureRequestParams(availableAntibandingModes = availableModes)
        assertTrue(paramsWithAuto.isAntibandingAutoSupported)
    }

    @Test
    fun testAntibandingAutoUnsupportedWhenOmittedFromHardware() {
        val legacyModes = intArrayOf(
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF,
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ,
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
        )
        assertFalse(CaptureRequestFactory.isAntibandingAutoSupported(legacyModes))

        val paramsWithoutAuto = CaptureRequestParams(availableAntibandingModes = legacyModes)
        assertFalse(paramsWithoutAuto.isAntibandingAutoSupported)

        val emptyModes = intArrayOf()
        assertFalse(CaptureRequestFactory.isAntibandingAutoSupported(emptyModes))
    }

    @Test
    fun testAntibandingAutoDefaultsToSupportedWhenUnqueried() {
        // When hardware characteristics have not specified modes (null), default to supported
        assertTrue(CaptureRequestFactory.isAntibandingAutoSupported(null))

        val defaultParams = CaptureRequestParams()
        assertNull(defaultParams.availableAntibandingModes)
        assertTrue(defaultParams.isAntibandingAutoSupported)
    }

    @Test
    fun testModeSettingsPerModeIsolation() {
        // Exercise the production reducer CameraUiState.switchToMode, which
        // CommixViewModel.onModeSelected delegates to. No locally-built map.
        val proSettings = ModeSettings(
            iso = 400,
            shutterNs = 20_000_000L, // 1/50s
            evStops = -0.7f,
            wbKelvin = 5600,
            focusDiopters = 2.5f,
            zoomRatio = 2.0,
            toneCurve = "log",
            showProControls = true
        )

        // Start in PHOTO with its own values; PRO was visited earlier and seeded.
        val photoState = CameraUiState(
            activeMode = "PHOTO",
            iso = 0,
            shutterNs = 0L,
            toneCurve = "standard",
            zoomRatio = 1.0,
            perModeSettings = mapOf("PRO" to proSettings)
        )

        // Switch to PRO: the seeded PRO settings are restored, not the PHOTO values.
        val proState = photoState.switchToMode("PRO")
        assertEquals("PRO", proState.activeMode)
        assertEquals(400, proState.iso)
        assertEquals(20_000_000L, proState.shutterNs)
        assertEquals(-0.7f, proState.evStops, 0.001f)
        assertEquals(5600, proState.wbKelvin)
        assertEquals("log", proState.toneCurve)
        assertEquals(2.0, proState.zoomRatio, 0.001)
        assertTrue(proState.showProControls)

        // PHOTO's outgoing settings were captured into the per-mode map.
        assertEquals(0, proState.perModeSettings["PHOTO"]?.iso)
        assertEquals("standard", proState.perModeSettings["PHOTO"]?.toneCurve)

        // Switch back to PHOTO: its original values are restored and PRO stays intact.
        val backToPhoto = proState.switchToMode("PHOTO")
        assertEquals("PHOTO", backToPhoto.activeMode)
        assertEquals(0, backToPhoto.iso)
        assertEquals("standard", backToPhoto.toneCurve)
        assertEquals(1.0, backToPhoto.zoomRatio, 0.001)
        assertFalse(backToPhoto.showProControls)
        assertEquals(400, backToPhoto.perModeSettings["PRO"]?.iso)

        // A never-visited mode yields production defaults.
        val freshVideo = backToPhoto.switchToMode("VIDEO")
        assertEquals(0, freshVideo.iso)
        assertEquals("neutral", freshVideo.toneCurve)
        assertEquals(-1f, freshVideo.focusDiopters, 0.001f)
        assertFalse(freshVideo.showProControls)

        // Re-selecting the active mode is a no-op (returns the same instance).
        assertSame(freshVideo, freshVideo.switchToMode("VIDEO"))
    }
}
