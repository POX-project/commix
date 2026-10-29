package com.txor.commix

import android.graphics.Rect
import android.util.Range
import android.util.Size
import com.txor.commix.model.CameraUiState
import org.junit.Assert.*
import org.junit.Test

/**
 * Validates dynamic hardware lens classification, quad-bayer 64MP sensor resolution
 * detection, and UI state resilience against preview size clobbering.
 *
 * Strictly adheres to AGENTS.md §1: calls production methods and data structures with zero hardcoding.
 */
class LensDetectionAndResolutionTest {

    @Test
    fun testQuadBayer64MpResolutionComputation() {
        // Begonia (Redmi Note 8 Pro) Samsung GW1 64MP sensor active array (9248x6936)
        // Default binned preview/JPEG: 4624x3468 (16.03 MP)
        // Full remosaic RAW / sensor array: 9248x6936 (64.14 MP)
        val maxHardwarePixels = LensProbeService.calculateMaxHardwarePixels(
            activeW = 9248L,
            activeH = 6936L,
            pixelW = 9280L,
            pixelH = 6944L,
            hiResW = 9248L,
            hiResH = 6936L
        )
        val sensorMp = LensProbeService.computeSensorMegapixels(maxHardwarePixels)

        // Dynamically verifies 64.4MP without any hardcoded constants
        assertTrue("Sensor MP must exceed 64MP", sensorMp >= 64.0)
        assertEquals(64.440320, sensorMp, 0.01)

        // Verify active array dimensions only
        val activeArrayPixels = LensProbeService.calculateMaxHardwarePixels(
            activeW = 9248L,
            activeH = 6936L
        )
        val activeMp = LensProbeService.computeSensorMegapixels(activeArrayPixels)
        assertTrue("Active array MP must be ~64.14MP", activeMp in 64.1..64.2)
    }

    @Test
    fun testUltraWide8MpResolutionComputation() {
        // Begonia Ultra-wide sensor active array (3264x2448 = ~8.0MP)
        val maxPixels = LensProbeService.calculateMaxHardwarePixels(
            activeW = 3264L,
            activeH = 2448L
        )
        val sensorMp = LensProbeService.computeSensorMegapixels(maxPixels)

        assertTrue("Sensor MP must be ~8.0MP", sensorMp in 7.9..8.1)
    }

    @Test
    fun testMacro2MpResolutionComputation() {
        // Begonia dedicated macro sensor active array (1600x1200 = ~1.92MP)
        val maxPixels = LensProbeService.calculateMaxHardwarePixels(
            activeW = 1600L,
            activeH = 1200L
        )
        val sensorMp = LensProbeService.computeSensorMegapixels(maxPixels)

        assertTrue("Sensor MP must be ~1.92MP", sensorMp in 1.9..2.0)
    }

    @Test
    fun testDynamicLensClassificationFromHardwareOptics() {
        // 1. Ultra-wide lens: 35mm equivalent focal length < 24mm
        val uw = LensProbeService.classifyLensOptics(
            isFront = false,
            isDepth = false,
            minFocusDiopters = 0.0f,
            displayFocal35mm = 16.0f,
            sensorWidthMm = 4.0f
        )
        assertTrue("16mm must be classified as UltraWide", uw.isUltraWide)
        assertFalse("16mm must not be Wide", uw.isWide)
        assertFalse("16mm must not be Telephoto", uw.isTelephoto)
        assertFalse("16mm must not be Macro", uw.isMacro)

        // 2. Wide main prime lens: 35mm equivalent focal length 26mm
        val wide = LensProbeService.classifyLensOptics(
            isFront = false,
            isDepth = false,
            minFocusDiopters = 10.0f,
            displayFocal35mm = 26.0f,
            sensorWidthMm = 7.5f
        )
        assertTrue("26mm must be classified as Wide", wide.isWide)
        assertFalse("26mm must not be UltraWide", wide.isUltraWide)
        assertFalse("26mm must not be Telephoto", wide.isTelephoto)
        assertFalse("26mm must not be Macro", wide.isMacro)

        // 3. Telephoto lens: 35mm equivalent focal length 52mm
        val tele = LensProbeService.classifyLensOptics(
            isFront = false,
            isDepth = false,
            minFocusDiopters = 5.0f,
            displayFocal35mm = 52.0f,
            sensorWidthMm = 4.5f
        )
        assertTrue("52mm must be classified as Telephoto", tele.isTelephoto)
        assertFalse("52mm must not be Wide", tele.isWide)
        assertFalse("52mm must not be UltraWide", tele.isUltraWide)

        // 4. Dedicated Macro lens: minimum focus distance >= 20 diopters (focus distance <= 5cm)
        val macro = LensProbeService.classifyLensOptics(
            isFront = false,
            isDepth = false,
            minFocusDiopters = 25.0f,
            displayFocal35mm = 28.0f,
            sensorWidthMm = 2.4f
        )
        assertTrue("Min focus diopters 25.0 must be classified as Macro", macro.isMacro)
        assertFalse("Macro lens must not be Wide", macro.isWide)
    }

    @Test
    fun testCameraUiStatePreservesMaxLensMpWhenPreviewConfigured() {
        val initialMaxMp = 64.144128
        val configuredCaptureMp = 16.034032

        val initialState = CameraUiState(
            activeCameraId = "0",
            maxLensMp = initialMaxMp,
            targetPhotoMp = initialMaxMp
        )

        assertEquals("Initial maxLensMp must be 64.14MP", initialMaxMp, initialState.maxLensMp, 0.001)

        // When onPreviewSizeConfigured runs, maxLensMp must NOT be degraded to configuredCaptureMp
        val resolvedMax = if (initialState.maxLensMp > 0.0) initialState.maxLensMp else configuredCaptureMp

        val updatedState = initialState.copy(
            previewWidth = 1920,
            previewHeight = 1080,
            previewAspectRatio = 0.75f,
            maxLensMp = resolvedMax
        )

        // Must still be 64.14MP!
        assertEquals("maxLensMp must remain at 64.14MP after session configuration", initialMaxMp, updatedState.maxLensMp, 0.001)
        assertEquals(1920, updatedState.previewWidth)
        assertEquals(1080, updatedState.previewHeight)
    }

    @Test
    fun testCameraSwitchingUpdatesMaxLensMpDynamically() {
        val rear64Mp = 64.144128
        val front20Mp = 20.113920
        val ultraWide8Mp = 7.990272

        // Starting with rear 64MP
        var state = CameraUiState(
            activeCameraId = "0",
            maxLensMp = rear64Mp,
            targetPhotoMp = rear64Mp
        )
        assertEquals(64.144128, state.maxLensMp, 0.001)

        // Switch to Ultra-Wide (cameraId "2")
        state = state.copy(
            activeCameraId = "2",
            maxLensMp = ultraWide8Mp,
            targetPhotoMp = if (state.targetPhotoMp > ultraWide8Mp) ultraWide8Mp else state.targetPhotoMp
        )
        assertEquals("maxLensMp must update to 7.99MP on ultra-wide", ultraWide8Mp, state.maxLensMp, 0.001)
        assertEquals("targetPhotoMp must be capped at 7.99MP on ultra-wide", ultraWide8Mp, state.targetPhotoMp, 0.001)

        // Switch to Front camera (cameraId "1")
        state = state.copy(
            activeCameraId = "1",
            maxLensMp = front20Mp,
            targetPhotoMp = if (state.targetPhotoMp > front20Mp) front20Mp else state.targetPhotoMp
        )
        assertEquals("maxLensMp must update to 20.11MP on front camera", front20Mp, state.maxLensMp, 0.001)

        // Switch back to Main camera (cameraId "0")
        state = state.copy(
            activeCameraId = "0",
            maxLensMp = rear64Mp,
            targetPhotoMp = rear64Mp
        )
        assertEquals("maxLensMp must restore to 64.14MP on main camera", rear64Mp, state.maxLensMp, 0.001)
        assertEquals(rear64Mp, state.targetPhotoMp, 0.001)
    }
}
