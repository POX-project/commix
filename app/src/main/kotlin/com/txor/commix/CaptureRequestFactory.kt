package com.txor.commix

import android.graphics.Rect
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.os.Build
import android.util.Range
import android.util.Rational
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
    val isViewfinderDisabled: Boolean = false,
    val videoCodec: String = "hevc",
    val nightFrameCount: Int = 8,
    val nightAstroAlignment: Boolean = false,
    val nightTone: String = "neutral",
    val timeLapseIntervalSec: Int = 2,
    val uncompressedAudio: Boolean = false,
    val audioLowCut: Boolean = false,
    val audioGain: Float = 0.0f,
    val adaptivePerformanceEnabled: Boolean = true,
    val cinemaMatteRatio: String = "off",
    val photoFormat: String = "jpeg",
    val photoQuality: Int = 95,
    val photoEnhanceAlgo: String = "clarity",
    val aebEnabled: Boolean = false,
    val aebShots: Int = 3,
    val aebSpreadEv: Float = 1.0f,
    val anamorphicRatio: Float = 1.0f,
    val sensitivityRange: IntRange? = null,
    val exposureTimeRangeNs: LongRange? = null,
    val lastAutoIso: Int = 0,
    val lastAutoExposureTimeNs: Long = 0L,
    val availableAntibandingModes: IntArray? = null
) {
    val isManualExposureRequested: Boolean get() = iso > 0 || expNs > 0L
    val isAntibandingAutoSupported: Boolean get() = CaptureRequestFactory.isAntibandingAutoSupported(availableAntibandingModes)
}


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
     * Maps exposure compensation in stops onto the HAL's integer EV index, clamped to the
     * reported [minEvIndex]..[maxEvIndex] hardware range.
     */
    fun calculateEvIndex(evStops: Float, evStep: Float, minEvIndex: Int, maxEvIndex: Int): Int {
        return (evStops / evStep).roundToInt().coerceIn(minEvIndex, maxEvIndex)
    }

    /**
     * Clamps a requested zoom ratio to the sensor's reported maximum digital zoom, preventing
     * HAL rejection of out-of-range CONTROL_ZOOM_RATIO requests.
     */
    fun calculateEffectiveHardwareZoom(requestedZoom: Double, hardwareMaxDigitalZoom: Double): Double {
        return requestedZoom.coerceAtMost(hardwareMaxDigitalZoom.coerceAtLeast(1.0))
    }

    /**
     * Converts a cinema shutter angle (degrees) at [fps] into an exposure time in nanoseconds.
     */
    fun shutterAngleToExposureNs(angleDeg: Double, fps: Double): Long {
        val safeFps = fps.coerceAtLeast(1.0)
        return (1_000_000_000.0 * (angleDeg / 360.0) / safeFps).toLong()
    }

    enum class AeMode {
        ON,
        OFF,
        UNSET
    }

    enum class ManualExposure { NONE, ISO_ONLY, SHUTTER_ONLY, ISO_AND_SHUTTER }

    data class ExposurePlan(
        val aeMode: AeMode,
        val sensitivity: Int? = null,
        val exposureTimeNs: Long? = null,
        val frameDurationNs: Long? = null,
        val evIndex: Int? = null,
        val manual: ManualExposure = ManualExposure.NONE,
        val warning: String? = null
    )

    private const val DEFAULT_AUTO_ISO = 100
    private const val DEFAULT_AUTO_EXPOSURE_NS = 33_333_333L
    private const val MIN_FRAME_DURATION_NS = 33_333_333L

    // Standard 3x3 identity color space transform matrix for white balance
    private val IDENTITY_COLOR_TRANSFORM = ColorSpaceTransform(
        arrayOf(
            Rational(1, 1), Rational(0, 1), Rational(0, 1),
            Rational(0, 1), Rational(1, 1), Rational(0, 1),
            Rational(0, 1), Rational(0, 1), Rational(1, 1)
        )
    )

    /**
     * Determines whether CONTROL_AE_ANTIBANDING_MODE_AUTO is supported.
     * When [availableModes] is null, auto antibanding is assumed supported by default.
     * When [availableModes] is provided, verifies that CONTROL_AE_ANTIBANDING_MODE_AUTO is present.
     */
    fun isAntibandingAutoSupported(availableModes: IntArray?): Boolean {
        return availableModes == null || availableModes.contains(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
    }

    fun resolveExposure(params: CaptureRequestParams): ExposurePlan {
        if (params.flashMode == "torch" || params.flashMode == "on" || params.flashMode == "auto") {
            return ExposurePlan(
                aeMode = AeMode.UNSET,
                sensitivity = null, exposureTimeNs = null, frameDurationNs = null, evIndex = null,
                manual = ManualExposure.NONE,
                warning = if (params.isManualExposureRequested)
                    "Manual exposure overridden by flash mode '${params.flashMode}'"
                else null
            )
        }

        val isoManual = params.iso > 0
        val shutterManual = params.expNs > 0L

        if (!isoManual && !shutterManual) {
            val evIndex = if (params.evRange != null && params.evStep > 0f) {
                calculateEvIndex(params.evStops, params.evStep, params.evRange.lower, params.evRange.upper)
            } else null
            return ExposurePlan(AeMode.ON, null, null, null, evIndex, ManualExposure.NONE, null)
        }

        val isoRaw = if (isoManual) params.iso else fallbackIso(params)
        val expRaw = if (shutterManual) params.expNs else fallbackExposureNs(params)
        val iso = params.sensitivityRange?.let { isoRaw.coerceIn(it) } ?: isoRaw
        val exp = params.exposureTimeRangeNs?.let { expRaw.coerceIn(it) } ?: expRaw

        val manual = when {
            isoManual && shutterManual -> ManualExposure.ISO_AND_SHUTTER
            isoManual -> ManualExposure.ISO_ONLY
            else -> ManualExposure.SHUTTER_ONLY
        }
        val warning = when {
            iso != isoRaw && exp != expRaw -> "ISO $isoRaw and exposure ${expRaw}ns clamped to $iso / ${exp}ns"
            iso != isoRaw -> "ISO $isoRaw clamped to $iso (sensor sensitivity range)"
            exp != expRaw -> "Exposure ${expRaw}ns clamped to ${exp}ns (sensor exposure range)"
            else -> null
        }

        return ExposurePlan(
            aeMode = AeMode.OFF,
            sensitivity = iso,
            exposureTimeNs = exp,
            frameDurationNs = maxOf(exp, MIN_FRAME_DURATION_NS),
            evIndex = null,
            manual = manual,
            warning = warning
        )
    }

    private fun fallbackIso(p: CaptureRequestParams): Int {
        val v = if (p.lastAutoIso > 0) p.lastAutoIso else DEFAULT_AUTO_ISO
        return p.sensitivityRange?.let { v.coerceIn(it) } ?: v
    }

    private fun fallbackExposureNs(p: CaptureRequestParams): Long {
        val v = if (p.lastAutoExposureTimeNs > 0L) p.lastAutoExposureTimeNs else DEFAULT_AUTO_EXPOSURE_NS
        return p.exposureTimeRangeNs?.let { v.coerceIn(it) } ?: v
    }

    /**
     * Applies all exposure, optical, stabilization, flash, and 3A controls to the target CaptureRequest.Builder.
     */
    fun applyControls(builder: CaptureRequest.Builder, params: CaptureRequestParams): ExposurePlan {
        // --- 0. 3A BASELINE (CONTROL_MODE) & ANTIBANDING ---
        try {
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            if (isAntibandingAutoSupported(params.availableAntibandingModes)) {
                builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
            }
        } catch (_: Throwable) {}

        // --- FLASH / TORCH MODE ---
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
        var plan = resolveExposure(params)
        try {
            when (plan.aeMode) {
                AeMode.ON -> builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                AeMode.OFF -> {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    plan.sensitivity?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
                    plan.exposureTimeNs?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
                    plan.frameDurationNs?.let { builder.set(CaptureRequest.SENSOR_FRAME_DURATION, it) }
                }
                AeMode.UNSET -> {}
            }
            plan.evIndex?.let { builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, it) }
        } catch (e: Throwable) {
            plan = plan.copy(warning = listOfNotNull(plan.warning, "HAL rejected exposure: ${e.message}").joinToString("; "))
        }

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
                    RggbChannelVector(gains[0], gains[1], gains[2], gains[3])
                )
                builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, IDENTITY_COLOR_TRANSFORM)
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            } else {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
            }
        } catch (_: Throwable) {}

        // --- 4. OPTICAL / DIGITAL ZOOM ---
        try {
            val effectiveHwZoom = calculateEffectiveHardwareZoom(params.zoom, params.hardwareMaxDigitalZoom)

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

        // --- 7. STILL CAPTURE QUALITY & LOSSLESS MAX-OUT ---
        try {
            val quality = if (PhotoFormatEngine.isLossless(params.photoFormat, params.photoQuality)) {
                100
            } else {
                params.photoQuality.coerceIn(1, 100)
            }
            builder.set(CaptureRequest.JPEG_QUALITY, quality.toByte())
        } catch (_: Throwable) {}

        return plan
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
