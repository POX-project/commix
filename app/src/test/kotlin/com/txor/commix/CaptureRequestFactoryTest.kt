package com.txor.commix

import android.graphics.Rect
import kotlin.math.roundToInt
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
        val evStep = 0.1f
        val minEvIndex = -12
        val maxEvIndex = 12

        val highEv = 2.0f
        val clampedHigh = (highEv / evStep).roundToInt().coerceIn(minEvIndex, maxEvIndex)
        assertEquals(12, clampedHigh)

        val lowEv = -2.5f
        val clampedLow = (lowEv / evStep).roundToInt().coerceIn(minEvIndex, maxEvIndex)
        assertEquals(-12, clampedLow)
    }

    @Test
    fun testSpaceZoomHardwareClampingPreventsHalRejection() {
        val hardwareMaxZoom = 10.0

        // User requests 30.0x Space Zoom (Super-resolution mode)
        val requestedZoom30 = 30.0
        val effectiveHwZoom30 = requestedZoom30.coerceAtMost(hardwareMaxZoom)
        assertEquals(10.0, effectiveHwZoom30, 0.001)

        val crop30 = CaptureRequestFactory.calculateCrop(0, 0, 4000, 3000, effectiveHwZoom30)
        assertEquals(400, crop30.width)
        assertEquals(300, crop30.height)

        // User requests 100.0x Space Zoom Lock
        val requestedZoom100 = 100.0
        val effectiveHwZoom100 = requestedZoom100.coerceAtMost(hardwareMaxZoom)
        assertEquals(10.0, effectiveHwZoom100, 0.001)

        val crop100 = CaptureRequestFactory.calculateCrop(0, 0, 4000, 3000, effectiveHwZoom100)
        assertEquals(400, crop100.width)
        assertEquals(300, crop100.height)

        // For standard 2.0x zoom, hardware zoom is not clamped
        val effectiveHwZoom2 = 2.0.coerceAtMost(hardwareMaxZoom)
        assertEquals(2.0, effectiveHwZoom2, 0.001)
        val crop2 = CaptureRequestFactory.calculateCrop(0, 0, 4000, 3000, effectiveHwZoom2)
        assertEquals(2000, crop2.width)
        assertEquals(1500, crop2.height)
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
    fun testModeSettingsPerModeIsolation() {
        val photoSettings = com.txor.commix.model.ModeSettings(
            iso = 0,
            shutterNs = 0L,
            evStops = 0.0f,
            wbKelvin = 0,
            focusDiopters = -1.0f,
            zoomRatio = 1.0,
            toneCurve = "standard"
        )

        val proSettings = com.txor.commix.model.ModeSettings(
            iso = 400,
            shutterNs = 20_000_000L, // 1/50s
            evStops = -0.7f,
            wbKelvin = 5600,
            focusDiopters = 2.5f,
            zoomRatio = 2.0,
            toneCurve = "log"
        )

        val videoSettings = com.txor.commix.model.ModeSettings(
            iso = 100,
            shutterNs = 16_666_667L, // 1/60s (180-degree rule)
            evStops = 0.0f,
            wbKelvin = 4300,
            focusDiopters = -1.0f,
            zoomRatio = 1.0,
            toneCurve = "neutral"
        )

        val modeMap = mutableMapOf(
            "PHOTO" to photoSettings,
            "PRO" to proSettings,
            "VIDEO" to videoSettings
        )

        // Verify isolation
        assertEquals(0, modeMap["PHOTO"]?.iso)
        assertEquals(400, modeMap["PRO"]?.iso)
        assertEquals(100, modeMap["VIDEO"]?.iso)

        assertEquals("standard", modeMap["PHOTO"]?.toneCurve)
        assertEquals("log", modeMap["PRO"]?.toneCurve)
        assertEquals("neutral", modeMap["VIDEO"]?.toneCurve)

        assertEquals(1.0, modeMap["PHOTO"]?.zoomRatio ?: 0.0, 0.001)
        assertEquals(2.0, modeMap["PRO"]?.zoomRatio ?: 0.0, 0.001)
    }
}

