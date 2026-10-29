package com.txor.commix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationPolicyTest {

    private fun back(sensor: Int, device: Int) =
        OrientationPolicy.jpegOrientation(sensor, device, mirrorForFrontFacing = false)

    @Test
    fun portraitBackCameraKeepsTodaysValue() {
        // Regression guard: the default posture must not change.
        assertEquals(90, back(90, 0))
        assertEquals(270, back(270, 0))
    }

    @Test
    fun landscapeBackCameraRotatesByDeviceQuadrant() {
        assertEquals(180, back(90, 90))
        assertEquals(270, back(90, 180))
        assertEquals(0, back(90, 270))
        assertEquals(0, back(90, 180 + 90))
    }

    @Test
    fun nonMultipleOfNinetySnapsToQuadrant() {
        // Pure device rotation snapping (sensor = 0)
        assertEquals(90, back(0, 87))
        assertEquals(0, back(0, 44))
        assertEquals(270, back(0, -80))
        assertEquals(180, back(0, 135))

        // Combined sensor (90) + device rotation snapping
        assertEquals(180, back(90, 87))
        assertEquals(90, back(90, 44))
        assertEquals(0, back(90, -80))
        assertEquals(270, back(90, 135))
    }

    @Test
    fun frontFacingMirrorInvertsDeviceRotation() {
        assertEquals(0, OrientationPolicy.jpegOrientation(270, 90, mirrorForFrontFacing = false))
        assertEquals(180, OrientationPolicy.jpegOrientation(270, 90, mirrorForFrontFacing = true))
    }

    @Test
    fun surfaceRotationMapsToQuadrants() {
        assertEquals(0, OrientationPolicy.degreesFromSurfaceRotation(0))
        assertEquals(90, OrientationPolicy.degreesFromSurfaceRotation(1))
        assertEquals(180, OrientationPolicy.degreesFromSurfaceRotation(2))
        assertEquals(270, OrientationPolicy.degreesFromSurfaceRotation(3))
        assertEquals(0, OrientationPolicy.degreesFromSurfaceRotation(99))
    }

    @Test
    fun onlyJpegCarriesTheOrientationTag() {
        assertFalse(OrientationPolicy.requiresPixelRotation("jpeg"))
        assertFalse(OrientationPolicy.requiresPixelRotation("jpg"))
        assertFalse(OrientationPolicy.requiresPixelRotation("JPEG"))
        assertFalse(OrientationPolicy.requiresPixelRotation("JPG"))
        assertTrue(OrientationPolicy.requiresPixelRotation("png"))
        assertTrue(OrientationPolicy.requiresPixelRotation("webp"))
    }
}
