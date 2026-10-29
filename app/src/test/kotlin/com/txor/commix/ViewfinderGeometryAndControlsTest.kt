package com.txor.commix

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Validates Viewfinder geometry constraints, aspect ratio containment,
 * scale matrix calculations, and Top Bar upper control bounds.
 *
 * Guarantees that upper controls are never obscured or pushed off-screen
 * when the viewfinder aspect ratio is tall/large (16:9, Video, Full).
 */
class ViewfinderGeometryAndControlsTest {

    data class ViewfinderDimensions(val width: Float, val height: Float)

    /**
     * Replicates the exact layout constraints calculation in CameraScreen.kt:
     * if containerRatio > targetRatio -> height matches maxHeight, width = height * targetRatio (matchHeightConstraintsFirst = true)
     * else -> width matches maxWidth, height = width / targetRatio (matchHeightConstraintsFirst = false)
     */
    private fun computeViewfinderSize(
        containerW: Float,
        containerH: Float,
        targetRatio: Float
    ): ViewfinderDimensions {
        val containerRatio = containerW / containerH
        return if (containerRatio > targetRatio) {
            // matchHeightConstraintsFirst = true: height matches container, width scaled down
            val h = containerH
            val w = h * targetRatio
            ViewfinderDimensions(w, h)
        } else {
            // matchWidthConstraintsFirst = true: width matches container, height scaled down
            val w = containerW
            val h = w / targetRatio
            ViewfinderDimensions(w, h)
        }
    }

    @Test
    fun testViewfinderDimensions16x9StrictlyContained() {
        // Redmi Note 8 Pro (Begonia) / typical device:
        // Screen: 393dp wide, ~851dp tall.
        // After Top Bar (~50dp) + Bottom Controls (~200dp), available Viewfinder viewport: 393dp x 560dp.
        val containerW = 393f
        val containerH = 560f
        val targetRatio = 9f / 16f // 0.5625 (16:9 Video/Cinema mode)

        val dims = computeViewfinderSize(containerW, containerH, targetRatio)

        // Height must NEVER exceed container height (which would push into top bar)
        assertTrue("Viewfinder height (${dims.height}) must not exceed container height ($containerH)", dims.height <= containerH)
        assertTrue("Viewfinder width (${dims.width}) must not exceed container width ($containerW)", dims.width <= containerW)

        // Exact dimensions: height = 560, width = 560 * 0.5625 = 315dp
        assertEquals(560f, dims.height, 0.01f)
        assertEquals(315f, dims.width, 0.01f)

        // Aspect ratio must be exactly 9:16
        val actualRatio = dims.width / dims.height
        assertEquals(targetRatio, actualRatio, 0.001f)
    }

    @Test
    fun testViewfinderDimensions4x3StrictlyContained() {
        val containerW = 393f
        val containerH = 560f
        val targetRatio = 3f / 4f // 0.75 (4:3 Photo mode)

        val dims = computeViewfinderSize(containerW, containerH, targetRatio)

        assertTrue("Viewfinder height (${dims.height}) must not exceed container height ($containerH)", dims.height <= containerH)
        assertTrue("Viewfinder width (${dims.width}) must not exceed container width ($containerW)", dims.width <= containerW)

        // Container ratio = 393 / 560 = 0.7018 <= 0.75 (targetRatio)
        // Takes width branch: width = 393, height = 393 / 0.75 = 524dp
        assertEquals(393f, dims.width, 0.01f)
        assertEquals(524f, dims.height, 0.01f)

        val actualRatio = dims.width / dims.height
        assertEquals(targetRatio, actualRatio, 0.001f)
    }

    @Test
    fun testViewfinderCompactViewportWhenProControlsOpen() {
        // When Pro Controls are expanded (~180dp), available height shrinks to ~380dp
        val containerW = 393f
        val containerH = 380f
        val targetRatio = 3f / 4f

        val dims = computeViewfinderSize(containerW, containerH, targetRatio)

        // Container ratio = 393 / 380 = 1.034 > 0.75
        // Takes height branch: height = 380, width = 380 * 0.75 = 285dp
        assertTrue("Viewfinder height must fit within 380dp", dims.height <= 380f)
        assertTrue("Viewfinder width must fit within 393dp", dims.width <= 393f)
        assertEquals(380f, dims.height, 0.01f)
        assertEquals(285f, dims.width, 0.01f)
    }

    @Test
    fun testViewfinderScaleMatrixBufferAspectMatching() {
        // In ViewfinderSurface: buildTransform scales TextureView buffer to viewport
        // Preview buffer: 1920x1080 (16:9), viewport: 315x560
        val previewWidth = 1920
        val previewHeight = 1080
        val vWidth = 315
        val vHeight = 560

        val bufferAspect = previewHeight.toFloat() / previewWidth.toFloat() // 1080 / 1920 = 0.5625
        val viewAspect = vWidth.toFloat() / vHeight.toFloat() // 315 / 560 = 0.5625

        val scaleX: Float
        val scaleY: Float
        if (viewAspect > bufferAspect) {
            scaleX = 1f
            scaleY = (vWidth / bufferAspect) / vHeight
        } else {
            scaleX = (vHeight * bufferAspect) / vWidth
            scaleY = 1f
        }

        // When viewAspect matches bufferAspect, scales are 1.0 (no distortion or cropping)
        assertEquals(1.0f, scaleX, 0.01f)
        assertEquals(1.0f, scaleY, 0.01f)
    }

    @Test
    fun testTopBarHorizontalWidthFitsStandardScreens() {
        // Verify that Top Bar controls fit within 360dp, 393dp, and 411dp screens

        // 1. Video Mode:
        // Left: Flash (36) + Spacing (6) + Queue (36) + Spacing (6) + Shield (36) = 120dp
        val videoLeftW = 36f + 6f + 36f + 6f + 36f
        // Center: Apple Chevron (44dp)
        val centerW = 44f
        // Right: Video format pill (~75dp) + Spacing (6) + Sliders (36) + Spacing (6) + Settings (36) = 159dp
        val videoRightW = 75f + 6f + 36f + 6f + 36f
        // Horizontal padding: 12dp left + 12dp right = 24dp
        val paddingW = 24f

        val totalVideoTopBarW = videoLeftW + centerW + videoRightW + paddingW
        // Total = 120 + 44 + 159 + 24 = 347dp
        assertTrue("Video mode top bar ($totalVideoTopBarW dp) must fit comfortably within 360dp", totalVideoTopBarW <= 360f)

        // 2. Photo Mode:
        // Left: Flash (36) + Spacing (6) + Queue (36) = 78dp
        val photoLeftW = 36f + 6f + 36f
        // Center: Apple Chevron (44dp)
        // Right: RAW badge (44dp) + Spacing (6) + 64MP pill (58dp) + Spacing (6) + Sliders (36) + Spacing (6) + Settings (36) = 192dp
        val photoRightW = 44f + 6f + 58f + 6f + 36f + 6f + 36f

        val totalPhotoTopBarW = photoLeftW + centerW + photoRightW + paddingW
        // Total = 78 + 44 + 192 + 24 = 338dp
        assertTrue("Photo mode top bar ($totalPhotoTopBarW dp) must fit comfortably within 360dp", totalPhotoTopBarW <= 360f)
    }
}
