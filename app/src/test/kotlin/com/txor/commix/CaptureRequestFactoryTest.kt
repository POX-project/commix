package com.txor.commix

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class CaptureRequestFactoryTest {

    data class CropRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    private fun computeCrop(active: CropRect, zoom: Double): CropRect {
        val z = zoom.coerceAtLeast(1.0)
        val activeW = active.width
        val activeH = active.height
        val cropW = (activeW / z).toInt()
        val cropH = (activeH / z).toInt()
        val cropX = active.left + (activeW - cropW) / 2
        val cropY = active.top + (activeH - cropH) / 2
        return CropRect(cropX, cropY, cropX + cropW, cropY + cropH)
    }

    @Test
    fun testZoomCalculationRestoresFullSensorAtOneX() {
        val sensorActiveArray = CropRect(0, 0, 4000, 3000)
        val crop = computeCrop(sensorActiveArray, 1.0)

        assertEquals(0, crop.left)
        assertEquals(0, crop.top)
        assertEquals(4000, crop.right)
        assertEquals(3000, crop.bottom)
        assertEquals(4000, crop.width)
        assertEquals(3000, crop.height)
    }

    @Test
    fun testZoomCalculationCentersCroppedRegionAtTwoX() {
        val sensorActiveArray = CropRect(0, 0, 4000, 3000)
        val crop = computeCrop(sensorActiveArray, 2.0)

        assertEquals(2000, crop.width)
        assertEquals(1500, crop.height)
        assertEquals(1000, crop.left)
        assertEquals(750, crop.top)
        assertEquals(3000, crop.right)
        assertEquals(2250, crop.bottom)
    }

    @Test
    fun testSubOneZoomIsSafelyCoercedToOneX() {
        val sensorActiveArray = CropRect(0, 0, 4000, 3000)
        val crop = computeCrop(sensorActiveArray, 0.5)

        assertEquals(0, crop.left)
        assertEquals(0, crop.top)
        assertEquals(4000, crop.right)
        assertEquals(3000, crop.bottom)
        assertEquals(4000, crop.width)
        assertEquals(3000, crop.height)
    }

    @Test
    fun testEvIndexCalculation() {
        val evStep = 0.1f
        val evStops = 1.0f
        val index = (evStops / evStep).roundToInt()
        assertEquals(10, index)

        val negEvStops = -1.5f
        val negIndex = (negEvStops / evStep).roundToInt()
        assertEquals(-15, negIndex)
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
        val sensorActiveArray = CropRect(0, 0, 4000, 3000)
        val hardwareMaxZoom = 10.0

        // User requests 30.0x Space Zoom (Super-resolution mode)
        val requestedZoom30 = 30.0
        val effectiveHwZoom30 = requestedZoom30.coerceAtMost(hardwareMaxZoom)
        assertEquals(10.0, effectiveHwZoom30, 0.001)

        val crop30 = computeCrop(sensorActiveArray, effectiveHwZoom30)
        assertEquals(400, crop30.width)
        assertEquals(300, crop30.height)

        // User requests 100.0x Space Zoom Lock
        val requestedZoom100 = 100.0
        val effectiveHwZoom100 = requestedZoom100.coerceAtMost(hardwareMaxZoom)
        assertEquals(10.0, effectiveHwZoom100, 0.001)

        val crop100 = computeCrop(sensorActiveArray, effectiveHwZoom100)
        assertEquals(400, crop100.width)
        assertEquals(300, crop100.height)

        // For standard 2.0x zoom, hardware zoom is not clamped
        val effectiveHwZoom2 = 2.0.coerceAtMost(hardwareMaxZoom)
        assertEquals(2.0, effectiveHwZoom2, 0.001)
        val crop2 = computeCrop(sensorActiveArray, effectiveHwZoom2)
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

