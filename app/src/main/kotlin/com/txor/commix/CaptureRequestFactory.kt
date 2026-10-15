package com.txor.commix

import android.graphics.Rect
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Range
import kotlin.math.roundToInt

/**
 * Encapsulated parameters for building a Camera2 CaptureRequest.
 */
data class CaptureRequestParams(
    val iso: Int = 0, // 0 = Auto
    val expNs: Long = 0L, // 0 = Auto
    val evStops: Float = 0f,
    val evStep: Float = 0.1f,
    val evRange: Range<Int>? = null,
    val focusDiopters: Float = -1f, // -1 = Auto
    val wbKelvin: Int = 0, // 0 = Auto
    val zoom: Double = 1.0,
    val hardwareMaxDigitalZoom: Double = 10.0,
    val activeRect: Rect? = null,
    val zoomRatioRange: Range<Float>? = null,
    val oisMode: String = "standard", // "off", "standard", "cinematic"
    val eisMode: String = "legend",   // "off", "standard", "super_steady", "legend"
    val hasOisHardware: Boolean = false,
    val activeMode: String = "PHOTO",
    val targetPhotoMp: Double = 0.0,
    val videoResolution: String = "1920x1080",
    val toneCurve: String = "neutral",
    val filmGrain: String = "off",
    val lutLook: String = "none",
    val isProcessZero: Boolean = false,
    val processZeroIntensity: Float = 1.0f,
    val flashMode: String = "off", // "off", "auto", "on", "torch"
    val isViewfinderDisabled: Boolean = false
)


/**
 * High-performance factory that applies Camera2 HAL3 capture request controls.
 */
object CaptureRequestFactory {

    data class CropBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /**
     * Calculates digital crop bounds based on sensor active coordinates and zoom factor.
     */
    fun calculateCrop(activeLeft: Int, activeTop: Int, activeRight: Int, activeBottom: Int, zoom: Double): CropBounds {
        val activeW = activeRight - activeLeft
        val activeH = activeBottom - activeTop
        if (activeW <= 0 || activeH <= 0) return CropBounds(activeLeft, activeTop, activeRight, activeBottom)

        val z = zoom.coerceAtLeast(1.0)
        val minW = 64.coerceAtMost(activeW)
        val minH = 48.coerceAtMost(activeH)
        val cropW = (activeW / z).toInt().coerceIn(minW, activeW)
        val cropH = (activeH / z).toInt().coerceIn(minH, activeH)
        val cropX = activeLeft + (activeW - cropW) / 2
        val cropY = activeTop + (activeH - cropH) / 2
        return CropBounds(cropX, cropY, cropX + cropW, cropY + cropH)
    }

    /**
     * Calculates the centered digital crop rectangle for digital zoom.
     */
    fun calculateCropRegion(active: Rect, zoom: Double): Rect {
        val b = calculateCrop(active.left, active.top, active.right, active.bottom, zoom)
        return Rect(b.left, b.top, b.right, b.bottom)
    }

    /**
     * Applies all exposure, optical, stabilization, flash, and 3A controls to the target CaptureRequest.Builder.
     */
    fun applyControls(builder: CaptureRequest.Builder, params: CaptureRequestParams) {
        // --- 0. FLASH / TORCH MODE ---
        try {
            when (params.flashMode) {
                "torch" -> {
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                }
                "on" -> {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                }
                "auto" -> {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                }
                else -> {
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
            }
        } catch (_: Throwable) {}

        // --- 1. EXPOSURE (ISO, Shutter, EV) ---
        try {
            if (params.flashMode != "torch" && params.iso > 0 && params.expNs > 0L) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, params.iso)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, params.expNs)
                // Prevent ISP from clamping manual long exposures to 30fps
                builder.set(CaptureRequest.SENSOR_FRAME_DURATION, params.expNs.coerceAtLeast(33_333_333L))
            } else if (params.flashMode != "torch" && params.flashMode != "on" && params.flashMode != "auto") {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                if (params.evRange != null && params.evStep > 0f) {
                    val evIndex = (params.evStops / params.evStep).roundToInt().coerceIn(
                        params.evRange.lower, params.evRange.upper
                    )
                    builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex)
                }
            }
        } catch (_: Throwable) {}

        // --- 2. FOCUS (AF vs Manual) ---
        try {
            if (params.focusDiopters >= 0f) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, params.focusDiopters)
            } else {
                val isVideo = params.activeMode in listOf("VIDEO", "CINEMATIC", "SLOW-MO")
                val afMode = if (isVideo) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
            }
        } catch (_: Throwable) {}

        // --- 3. WHITE BALANCE ---
        try {
            if (params.wbKelvin > 0) {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                val gains = calculateRgbGains(params.wbKelvin)
                builder.set(
                    CaptureRequest.COLOR_CORRECTION_GAINS,
                    android.hardware.camera2.params.RggbChannelVector(gains[0], gains[1], gains[2], gains[3])
                )
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            } else {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
            }
        } catch (_: Throwable) {}

        // --- 4. OPTICAL / DIGITAL ZOOM ---
        try {
            val hwMax = params.hardwareMaxDigitalZoom.coerceAtLeast(1.0)
            val effectiveHwZoom = params.zoom.coerceAtMost(hwMax)

            if (Build.VERSION.SDK_INT >= 30 && params.zoomRatioRange != null) {
                val z = effectiveHwZoom.toFloat().coerceIn(params.zoomRatioRange.lower, params.zoomRatioRange.upper)
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, z)

                // If user zoomed beyond the hardware zoom range, apply digital crop via SCALER_CROP_REGION
                val remainderZoom = params.zoom / z.toDouble()
                if (remainderZoom > 1.01 && params.activeRect != null) {
                    val cropRect = calculateCropRegion(params.activeRect, remainderZoom)
                    builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
                }
            } else if (params.activeRect != null) {
                val cropRect = calculateCropRegion(params.activeRect, params.zoom)
                builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
            }
        } catch (_: Throwable) {}

        // --- 5. STABILIZATION (OIS & EIS) ---
        try {
            if (params.hasOisHardware) {
                val oisVal = when (params.oisMode) {
                    "off" -> CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                    else -> CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                }
                builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, oisVal)
            }

            val eisVal = when (params.eisMode) {
                "off" -> CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                else -> CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            }
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, eisVal)
        } catch (_: Throwable) {}

        // --- 6. PROCESS ZERO (ZERO COMPUTATIONAL SMOOTHING / SHARPENING HALOS) ---
        try {
            if (params.isProcessZero) {
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                builder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF)
            }
        } catch (_: Throwable) {}
    }

    /**
     * Approximates RGGB channel gains from a Kelvin temperature using standard Planckian locus approximation.
     */
    internal fun calculateRgbGains(kelvin: Int): FloatArray {
        val temp = kelvin.coerceIn(2000, 10000) / 100.0
        val r: Double
        val b: Double

        if (temp <= 66.0) {
            r = 255.0
            b = if (temp <= 19.0) 0.0 else (138.5177312231 * Math.log(temp - 10.0) - 305.0447927307).coerceIn(0.0, 255.0)
        } else {
            r = (329.698727446 * Math.pow(temp - 60.0, -0.1332047592)).coerceIn(0.0, 255.0)
            b = 255.0
        }

        // Normalize gains relative to G = 1.0 (assuming D65 baseline at 6500K)
        val rGain = (255.0 / r.coerceAtLeast(1.0)).toFloat().coerceIn(1.0f, 3.5f)
        val bGain = (255.0 / b.coerceAtLeast(1.0)).toFloat().coerceIn(1.0f, 3.5f)
        return floatArrayOf(rGain, 1.0f, 1.0f, bGain)
    }
}
