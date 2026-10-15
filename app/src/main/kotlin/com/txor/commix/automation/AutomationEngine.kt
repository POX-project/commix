package com.txor.commix.automation

import com.txor.commix.bridge.NativeEngineBridge
import kotlin.math.pow

enum class KeyframeCurve(val nativeId: Int) {
    Linear(0),
    EaseIn(1),
    EaseOut(2),
    EaseInOut(3),
    Hold(4)
}

data class KeyframePoint(
    val timeSec: Float,
    val value: Float,
    val curve: KeyframeCurve = KeyframeCurve.Linear
)

data class KeyframeTrack(
    val trackId: Int, // 0: zoom, 1: iso, 2: shutter, 3: ev, 4: wb, 5: focus
    val points: List<KeyframePoint>
) {
    fun evaluate(timeSec: Float): Float {
        if (points.isEmpty()) return 0f
        if (points.size == 1 || timeSec <= points.first().timeSec) return points.first().value
        if (timeSec >= points.last().timeSec) return points.last().value

        for (i in 0 until points.size - 1) {
            val p0 = points[i]
            val p1 = points[i + 1]
            if (timeSec in p0.timeSec..p1.timeSec) {
                val interval = p1.timeSec - p0.timeSec
                if (interval <= 0.0001f) return p0.value
                val u = ((timeSec - p0.timeSec) / interval).coerceIn(0f, 1f)
                val factor = when (p0.curve) {
                    KeyframeCurve.Linear -> u
                    KeyframeCurve.EaseIn -> u * u
                    KeyframeCurve.EaseOut -> 1f - (1f - u) * (1f - u)
                    KeyframeCurve.EaseInOut -> if (u < 0.5f) {
                        2f * u * u
                    } else {
                        1f - (-2f * u + 2f).pow(2) * 0.5f
                    }
                    KeyframeCurve.Hold -> 0f
                }
                return p0.value + factor * (p1.value - p0.value)
            }
        }
        return points.last().value
    }
}

data class AutomationValues(
    val zoom: Double? = null,
    val iso: Int? = null,
    val shutterNs: Long? = null,
    val ev: Float? = null,
    val wbKelvin: Int? = null,
    val focusDiopters: Float? = null
)

data class AutomationPresetConfig(
    val id: String,
    val title: String,
    val description: String,
    val icon: String,
    val durationSeconds: Float,
    val zoomTrack: KeyframeTrack? = null,
    val isoTrack: KeyframeTrack? = null,
    val shutterTrack: KeyframeTrack? = null,
    val evTrack: KeyframeTrack? = null,
    val wbTrack: KeyframeTrack? = null,
    val focusTrack: KeyframeTrack? = null
) {
    fun evaluate(timeSec: Float): AutomationValues {
        return AutomationValues(
            zoom = zoomTrack?.evaluate(timeSec)?.toDouble(),
            iso = isoTrack?.evaluate(timeSec)?.toInt(),
            shutterNs = shutterTrack?.evaluate(timeSec)?.toLong(),
            ev = evTrack?.evaluate(timeSec),
            wbKelvin = wbTrack?.evaluate(timeSec)?.toInt(),
            focusDiopters = focusTrack?.evaluate(timeSec)
        )
    }

    val allTracks: List<KeyframeTrack>
        get() = listOfNotNull(zoomTrack, isoTrack, shutterTrack, evTrack, wbTrack, focusTrack)
}

object AutomationEngine {

    val PRESET_DOLLY_ZOOM = AutomationPresetConfig(
        id = "dolly_zoom",
        title = "Dolly Zoom (Vertigo)",
        description = "Smooth zoom in from 1.0× to 3.2× while pulling focus forward",
        icon = "🔍",
        durationSeconds = 8.0f,
        zoomTrack = KeyframeTrack(
            trackId = 0,
            points = listOf(
                KeyframePoint(0f, 1.0f, KeyframeCurve.EaseInOut),
                KeyframePoint(8f, 3.2f, KeyframeCurve.EaseInOut)
            )
        ),
        focusTrack = KeyframeTrack(
            trackId = 5,
            points = listOf(
                KeyframePoint(0f, 0.2f, KeyframeCurve.EaseInOut),
                KeyframePoint(8f, 0.8f, KeyframeCurve.EaseInOut)
            )
        )
    )

    val PRESET_SUNRISE_RAMP = AutomationPresetConfig(
        id = "sunrise_ramp",
        title = "Sunrise EV Ramp",
        description = "Gradual exposure transition from -1.5 EV to +1.0 EV",
        icon = "🌅",
        durationSeconds = 20.0f,
        evTrack = KeyframeTrack(
            trackId = 3,
            points = listOf(
                KeyframePoint(0f, -1.5f, KeyframeCurve.EaseInOut),
                KeyframePoint(20f, 1.0f, KeyframeCurve.EaseInOut)
            )
        )
    )

    val PRESET_GOLDEN_HOUR = AutomationPresetConfig(
        id = "golden_hour",
        title = "Golden Hour WB Drift",
        description = "Smooth color temp drift from 3200K tungsten to 5600K daylight",
        icon = "✨",
        durationSeconds = 15.0f,
        wbTrack = KeyframeTrack(
            trackId = 4,
            points = listOf(
                KeyframePoint(0f, 3200f, KeyframeCurve.Linear),
                KeyframePoint(15f, 5600f, KeyframeCurve.Linear)
            )
        )
    )

    val PRESET_REVEAL_SHOT = AutomationPresetConfig(
        id = "reveal_shot",
        title = "Cinematic Reveal",
        description = "Start zoomed-in (4.0×) on subject, smoothly pull out to wide (1.0×)",
        icon = "🎬",
        durationSeconds = 10.0f,
        zoomTrack = KeyframeTrack(
            trackId = 0,
            points = listOf(
                KeyframePoint(0f, 4.0f, KeyframeCurve.EaseInOut),
                KeyframePoint(10f, 1.0f, KeyframeCurve.EaseInOut)
            )
        ),
        focusTrack = KeyframeTrack(
            trackId = 5,
            points = listOf(
                KeyframePoint(0f, 0.1f, KeyframeCurve.EaseInOut),
                KeyframePoint(10f, 0.9f, KeyframeCurve.EaseInOut)
            )
        )
    )

    val PRESET_DRAMATIC_RACK = AutomationPresetConfig(
        id = "dramatic_rack",
        title = "Dramatic Rack Focus",
        description = "Snap rack focus from foreground subject to background with 2s hold",
        icon = "🎯",
        durationSeconds = 9.0f,
        focusTrack = KeyframeTrack(
            trackId = 5,
            points = listOf(
                KeyframePoint(0f, 0.2f, KeyframeCurve.Hold),
                KeyframePoint(3.5f, 0.2f, KeyframeCurve.EaseInOut),
                KeyframePoint(5.5f, 0.85f, KeyframeCurve.Hold),
                KeyframePoint(9.0f, 0.85f, KeyframeCurve.Hold)
            )
        )
    )

    val BUILT_IN_PRESETS: List<AutomationPresetConfig> = listOf(
        PRESET_DOLLY_ZOOM,
        PRESET_SUNRISE_RAMP,
        PRESET_GOLDEN_HOUR,
        PRESET_REVEAL_SHOT,
        PRESET_DRAMATIC_RACK
    )

    fun getPresetById(id: String): AutomationPresetConfig {
        return BUILT_IN_PRESETS.find { it.id == id } ?: PRESET_DOLLY_ZOOM
    }

    fun createCustomConfig(
        durationSec: Double,
        startZoom: Double = 1.0,
        endZoom: Double = 1.0,
        enableZoom: Boolean = (startZoom != endZoom),
        startEv: Float = 0f,
        endEv: Float = 0f,
        enableEv: Boolean = (startEv != endEv),
        startFocus: Float = -1f,
        endFocus: Float = -1f,
        enableFocus: Boolean = (startFocus >= 0f && endFocus >= 0f && startFocus != endFocus)
    ): AutomationPresetConfig {
        val dur = durationSec.toFloat().coerceIn(1f, 60f)
        val zoomTrack = if (enableZoom) {
            KeyframeTrack(
                trackId = 0,
                points = listOf(
                    KeyframePoint(0f, startZoom.toFloat(), KeyframeCurve.EaseInOut),
                    KeyframePoint(dur, endZoom.toFloat(), KeyframeCurve.EaseInOut)
                )
            )
        } else null

        val evTrack = if (enableEv) {
            KeyframeTrack(
                trackId = 3,
                points = listOf(
                    KeyframePoint(0f, startEv, KeyframeCurve.EaseInOut),
                    KeyframePoint(dur, endEv, KeyframeCurve.EaseInOut)
                )
            )
        } else null

        val focusTrack = if (enableFocus) {
            KeyframeTrack(
                trackId = 5,
                points = listOf(
                    KeyframePoint(0f, startFocus, KeyframeCurve.EaseInOut),
                    KeyframePoint(dur, endFocus, KeyframeCurve.EaseInOut)
                )
            )
        } else null

        return AutomationPresetConfig(
            id = "custom_motion",
            title = "Custom Motion Ramp",
            description = "User-configured motion ramping sequence (${dur.toInt()}s)",
            icon = "⚡",
            durationSeconds = dur,
            zoomTrack = zoomTrack,
            evTrack = evTrack,
            focusTrack = focusTrack
        )
    }

    /**
     * Program C++ NativeEngineBridge keyframe engine with the given config.
     * Safe to call even if NativeEngineBridge is not loaded.
     */
    fun programNativeBridge(config: AutomationPresetConfig) {
        if (!NativeEngineBridge.isLoaded) return
        try {
            NativeEngineBridge.nativeKeyframeReset()
            for (track in config.allTracks) {
                for (pt in track.points) {
                    NativeEngineBridge.nativeKeyframeAddPoint(
                        track.trackId,
                        pt.timeSec,
                        pt.value,
                        pt.curve.nativeId
                    )
                }
            }
            NativeEngineBridge.nativeKeyframeStart(config.durationSeconds)
        } catch (_: Throwable) {}
    }

    /**
     * Stop C++ NativeEngineBridge keyframe engine.
     */
    fun stopNativeBridge() {
        if (!NativeEngineBridge.isLoaded) return
        try {
            NativeEngineBridge.nativeKeyframeStop()
        } catch (_: Throwable) {}
    }
}
