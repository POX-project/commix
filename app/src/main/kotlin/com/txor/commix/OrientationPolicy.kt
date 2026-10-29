package com.txor.commix

/**
 * Encapsulates Android Camera2 JPEG / EXIF orientation math.
 * Pure logic — 100% JVM testable with zero Android SDK dependencies.
 */
object OrientationPolicy {

    const val ROTATION_0 = 0
    const val ROTATION_90 = 90
    const val ROTATION_180 = 180
    const val ROTATION_270 = 270

    /** Maps `android.view.Surface.ROTATION_*` (0..3) to degrees. */
    fun degreesFromSurfaceRotation(surfaceRotation: Int): Int = when (surfaceRotation) {
        0 -> ROTATION_0
        1 -> ROTATION_90
        2 -> ROTATION_180
        3 -> ROTATION_270
        else -> ROTATION_0
    }

    /** Normalises any integer to one of 0/90/180/270. */
    fun quantizeDegrees(degrees: Int): Int {
        val q = Math.round(degrees / 90.0).toInt()
        return ((q * 90) % 360 + 360) % 360
    }

    /**
     * @param sensorOrientationDegrees SENSOR_ORIENTATION, degrees clockwise.
     * @param deviceRotationDegrees current device rotation; near-90 values snap to nearest quadrant.
     * @param mirrorForFrontFacing true when the delivered image is already mirrored.
     * @return 0, 90, 180 or 270 — valid for both `CaptureRequest.JPEG_ORIENTATION` and EXIF.
     */
    fun jpegOrientation(
        sensorOrientationDegrees: Int,
        deviceRotationDegrees: Int,
        mirrorForFrontFacing: Boolean
    ): Int {
        val sensor = quantizeDegrees(sensorOrientationDegrees)
        var device = quantizeDegrees(deviceRotationDegrees)
        if (mirrorForFrontFacing) device = (360 - device) % 360
        return (sensor + device) % 360
    }

    /**
     * True when the format carries no EXIF orientation tag, so the pixels themselves must be
     * rotated before writing. PNG/WebP never carry it, and `BitmapFactory` drops the tag it reads.
     */
    fun requiresPixelRotation(formatName: String): Boolean =
        !(formatName.equals("jpeg", ignoreCase = true) || formatName.equals("jpg", ignoreCase = true))
}
