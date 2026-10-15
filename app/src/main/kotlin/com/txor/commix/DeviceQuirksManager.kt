package com.txor.commix

import android.os.Build
import android.util.Log

/**
 * DeviceQuirksManager encapsulates manufacturer and SoC-specific camera behaviors,
 * preventing regressions across Xiaomi, Samsung, Google Pixel, OnePlus/Oppo, and MediaTek devices.
 */
object DeviceQuirksManager {
    private const val TAG = "CommixQuirks"

    val manufacturer: String = Build.MANUFACTURER.lowercase()
    val model: String = Build.MODEL.lowercase()
    val hardware: String = Build.HARDWARE.lowercase()
    val device: String = Build.DEVICE.lowercase()

    val isXiaomi: Boolean = manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco")
    val isSamsung: Boolean = manufacturer.contains("samsung")
    val isPixel: Boolean = manufacturer.contains("google")
    val isOppoOrOnePlus: Boolean = manufacturer.contains("oppo") || manufacturer.contains("oneplus") || manufacturer.contains("realme")
    val isMediaTek: Boolean = hardware.contains("mt") || Build.BOARD.lowercase().contains("mt")
    val isQualcomm: Boolean = hardware.contains("qcom") || Build.BOARD.lowercase().contains("qcom") || hardware.contains("qualcomm")
    val isExynos: Boolean = hardware.contains("exynos") || hardware.contains("universal") || Build.BOARD.lowercase().contains("universal") || Build.BOARD.lowercase().contains("exynos")
    val isHelioG90T: Boolean = hardware.contains("mt6785") || Build.BOARD.lowercase().contains("mt6785") || device.contains("begonia")

    /**
     * Devices like Redmi Note 8 Pro (begonia / mt6785) have video encoders that fail or starve
     * when HeifWriter feeds 512x512 multi-frame tiles in a CQ burst.
     */
    val requiresHeicQualityGuardrail: Boolean = isMediaTek || isXiaomi

    /**
     * On MediaTek mt6785/mtkcam HALs, setting CONTROL_MODE_USE_SCENE_MODE with SCENE_MODE_NIGHT
     * disrupts auto-exposure routines in preview, causing the viewfinder to become pitch black.
     */
    val prohibitsNightSceneModeInPreview: Boolean = isHelioG90T

    /**
     * 4K (3840x2160) recording on Helio G90T and older SoCs is strictly capped to 30 fps
     * even if the sensor reports 60 fps capabilities for 1080p.
     */
    val strict4k30FpsCap: Boolean = isHelioG90T || (isXiaomi && (model.contains("note 8") || device.contains("begonia")))

    /**
     * Whether this device supports Android 13+ DynamicRangeProfiles for 10-bit HLG/HDR10 capture.
     */
    val canSupportDynamicRangeProfiles: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * Whether this device supports Android 12+ CameraExtensionSession (Night, Bokeh, Face Retouch).
     */
    val canSupportExtensionSessions: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun logDeviceProfile() {
        Log.i(
            TAG,
            "Device Profile: mfg=$manufacturer model=$model hw=$hardware dev=$device " +
            "isXiaomi=$isXiaomi isSamsung=$isSamsung isPixel=$isPixel isMTK=$isMediaTek isQcom=$isQualcomm isExynos=$isExynos " +
            "isHelioG90T=$isHelioG90T guardrail=$requiresHeicQualityGuardrail nightSceneBypass=$prohibitsNightSceneModeInPreview strict4k30=$strict4k30FpsCap"
        )
    }
}
