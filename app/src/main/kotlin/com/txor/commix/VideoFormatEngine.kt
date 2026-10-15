package com.txor.commix

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 1:1 Kotlin port of lib/video_format_engine.dart
 * Broadcast Frame Rate Standards, Precision Magnetic Snapping & Pipeline Throughput Engine.
 */

data class BroadcastFpsStandard(
    val fps: Double,
    val label: String,
    val standardName: String,
    val integerEquivalent: Int,
    val frameDurationNs: Long
) {
    companion object {
        val standards = listOf(
            BroadcastFpsStandard(23.976023976, "23.98", "CINEMA NTSC (24000/1001)", 24, 41708333L),
            BroadcastFpsStandard(24.0, "24", "DCI CINEMA (24.000)", 24, 41666667L),
            BroadcastFpsStandard(25.0, "25", "PAL BROADCAST (25.000)", 25, 40000000L),
            BroadcastFpsStandard(29.97002997, "29.97", "NTSC BROADCAST (30000/1001)", 30, 33366667L),
            BroadcastFpsStandard(30.0, "30", "STANDARD DIGITAL (30.000)", 30, 33333333L),
            BroadcastFpsStandard(48.0, "48", "HFR CINEMA (48.000)", 48, 20833333L),
            BroadcastFpsStandard(50.0, "50", "HFR PAL (50.000)", 50, 20000000L),
            BroadcastFpsStandard(59.94005994, "59.94", "BROADCAST HFR (60000/1001)", 60, 16683333L),
            BroadcastFpsStandard(60.0, "60", "STANDARD 60 (60.000)", 60, 16666667L),
            BroadcastFpsStandard(120.0, "120", "HIGH SPEED (120.000)", 120, 8333333L),
            BroadcastFpsStandard(240.0, "240", "SUPER SLOW-MO (240.000)", 240, 4166667L),
            BroadcastFpsStandard(480.0, "480", "SUPER SLOW-MO (480.000)", 480, 2083333L),
            BroadcastFpsStandard(960.0, "960", "ULTRA SLOW-MO (960.000)", 960, 1041667L)
        )

        fun findClosestStandard(targetFps: Double, tolerance: Double? = null): BroadcastFpsStandard? {
            if (standards.isEmpty()) return null
            var closest = standards.first()
            var minDiff = abs(targetFps - closest.fps)
            for (s in standards) {
                val diff = abs(targetFps - s.fps)
                if (diff < minDiff) {
                    minDiff = diff
                    closest = s
                }
            }
            if (tolerance != null && minDiff > tolerance) return null
            return closest
        }
    }
}

data class SnapResult(
    val fps: Double,
    val didSnap: Boolean,
    val matchedStandard: BroadcastFpsStandard?
)

object MagneticFpsSnapper {
    fun snap(
        sliderValue: Double,
        snapThreshold: Double = 0.85,
        candidateStandards: List<BroadcastFpsStandard>? = null
    ): SnapResult {
        val list = candidateStandards ?: BroadcastFpsStandard.standards
        var closest: BroadcastFpsStandard? = null
        var minDiff = Double.MAX_VALUE
        for (s in list) {
            val diff = abs(sliderValue - s.fps)
            if (diff <= snapThreshold && diff < minDiff) {
                minDiff = diff
                closest = s
            }
        }
        return if (closest != null) {
            SnapResult(fps = closest.fps, didSnap = true, matchedStandard = closest)
        } else {
            SnapResult(fps = sliderValue, didSnap = false, matchedStandard = null)
        }
    }

    fun calculateSensorMaxFps(minFrameDurationNs: Long): Double {
        if (minFrameDurationNs <= 0L) return 30.0
        return 1_000_000_000.0 / minFrameDurationNs
    }
}

data class VideoQualityProfile(
    val label: String,
    val bitrateBps: Int,
    val description: String
) {
    val title: String get() = label
    val megaBytesPerMinute: Double get() = estimateMbPerMinute(bitrateBps)
    fun estimateStorageMb(minutes: Int): Int = (megaBytesPerMinute * minutes).roundToInt()
    fun formattedEstimate(minutes: Int): String = "${estimateStorageMb(minutes)} MB/min"

    companion object {
        val profiles = listOf(
            VideoQualityProfile("Broadcast Eco", 25_000_000, "25 Mbps · Web & Social"),
            VideoQualityProfile("High Quality", 50_000_000, "50 Mbps · Documentary & YouTube 4K"),
            VideoQualityProfile("Pro Cinema", 100_000_000, "100 Mbps · Color Grading & Film"),
            VideoQualityProfile("Master Broadcast", 150_000_000, "150 Mbps · Studio Broadcast Quality"),
            VideoQualityProfile("Cinema Master", 200_000_000, "200 Mbps · Ultra-High Bitrate All-I")
        )

        fun estimateMbPerMinute(bitrateBps: Int): Double {
            return (bitrateBps.toDouble() / 8.0 * 60.0) / (1024.0 * 1024.0)
        }

        fun fromBitrate(bitrate: Int): VideoQualityProfile {
            var closest = profiles.first()
            var minDiff = abs(closest.bitrateBps - bitrate)
            for (p in profiles) {
                val diff = abs(p.bitrateBps - bitrate)
                if (diff < minDiff) {
                    minDiff = diff
                    closest = p
                }
            }
            return closest
        }
    }
}

enum class PipelineZone {
    OPTIMAL,
    RENDER_LAG,
    BLIND_VF_WORK,
    UNSUPPORTED
}

data class PipelineThroughputEvaluation(
    val fps: Double,
    val resolution: String,
    val zone: PipelineZone,
    val currentMegaPixelsPerSecond: Double,
    val maxDualStreamMpxPerSecond: Double,
    val maxSingleStreamMpxPerSecond: Double,
    val overloadPercentage: Double,
    val statusTitle: String,
    val statusDescription: String,
    val mitigationAction: String,
    val canWorkWithVfDisabled: Boolean,
    val requiresBlindVf: Boolean
)

object PipelineThroughputEngine {
    const val defaultMaxDualStreamMpx = 380.0
    const val defaultMaxSingleStreamMpx = 550.0

    fun parseResolution(resolution: String): Pair<Int, Int> {
        if (resolution.contains('x')) {
            val parts = resolution.split('x')
            val w = parts.getOrNull(0)?.trim()?.toIntOrNull()
            val h = parts.getOrNull(1)?.trim()?.toIntOrNull()
            if (w != null && h != null && w > 0 && h > 0) {
                return Pair(w, h)
            }
        }
        val upper = resolution.uppercase()
        return when {
            upper.contains("3840") || upper.contains("4K") -> Pair(3840, 2160)
            upper.contains("1920") || upper.contains("1080") -> Pair(1920, 1080)
            upper.contains("1280") || upper.contains("720") -> Pair(1280, 720)
            upper.contains("640") || upper.contains("480") -> Pair(640, 480)
            upper.contains("960") || upper.contains("540") -> Pair(960, 540)
            else -> Pair(1920, 1080)
        }
    }

    fun evaluate(
        resolution: String,
        fps: Double,
        isVfDisabled: Boolean = false,
        hardwareMaxFps: Double? = null,
        maxDualStreamMpx: Double = defaultMaxDualStreamMpx,
        maxSingleStreamMpx: Double = defaultMaxSingleStreamMpx
    ): PipelineThroughputEvaluation {
        val (w, h) = parseResolution(resolution)
        val recPixels = w.toDouble() * h.toDouble()

        val effectiveHardwareMax = hardwareMaxFps ?: getMaxFpsForResolution(
            resolution = resolution,
            isVfDisabled = isVfDisabled,
            maxSingleStreamMpx = maxSingleStreamMpx,
            maxDualStreamMpx = maxDualStreamMpx
        )

        val recThroughputMpx = (recPixels * fps) / 1_000_000.0

        val basePreviewPixels = 1920.0 * 1080.0 * 30.0 // 62.2 Mpx/s
        val previewThroughputMpx = if (fps > 60.0) {
            (basePreviewPixels * (fps / 30.0) * 0.55) / 1_000_000.0
        } else {
            basePreviewPixels / 1_000_000.0
        }

        val totalDualStreamMpx = recThroughputMpx + previewThroughputMpx
        val activeThroughputMpx = if (isVfDisabled) recThroughputMpx else totalDualStreamMpx

        val exceedsHardwareRate = fps > (effectiveHardwareMax + 0.05)
        val exceedsSingleStreamIsp = recThroughputMpx > maxSingleStreamMpx

        if (exceedsHardwareRate || exceedsSingleStreamIsp) {
            val overloadRatio = if (exceedsHardwareRate) {
                fps / effectiveHardwareMax
            } else {
                recThroughputMpx / maxSingleStreamMpx
            }
            val overloadPct = (overloadRatio * 100.0).coerceIn(100.0, 300.0)
            val desc = if (exceedsHardwareRate) {
                if (recPixels >= 30_000_000) {
                    "Ultra-high resolution sensor limit: ${w}x${h} readout is capped at ${effectiveHardwareMax} FPS."
                } else if (recPixels >= 3840.0 * 2160.0) {
                    "Hardware video encoder ceiling: 4K UHD is physically limited to ${effectiveHardwareMax.toInt()} FPS."
                } else {
                    "Codec macroblock limit exceeded at this frame rate."
                }
            } else {
                "Single-stream encoder throughput limit ($maxSingleStreamMpx Mpx/s) exceeded."
            }

            return PipelineThroughputEvaluation(
                fps = fps,
                resolution = resolution,
                zone = PipelineZone.UNSUPPORTED,
                currentMegaPixelsPerSecond = activeThroughputMpx,
                maxDualStreamMpxPerSecond = maxDualStreamMpx,
                maxSingleStreamMpxPerSecond = maxSingleStreamMpx,
                overloadPercentage = overloadPct,
                statusTitle = "HARDWARE LIMIT EXCEEDED",
                statusDescription = desc,
                mitigationAction = "Reduce frame rate or lower resolution.",
                canWorkWithVfDisabled = false,
                requiresBlindVf = false
            )
        }

        if (totalDualStreamMpx > maxDualStreamMpx) {
            val isWorkingNow = isVfDisabled
            val overloadRatio = if (isWorkingNow) {
                recThroughputMpx / maxSingleStreamMpx
            } else {
                totalDualStreamMpx / maxDualStreamMpx
            }
            val overloadPct = (overloadRatio * 100.0).coerceIn(0.0, 200.0)

            return PipelineThroughputEvaluation(
                fps = fps,
                resolution = resolution,
                zone = PipelineZone.BLIND_VF_WORK,
                currentMegaPixelsPerSecond = activeThroughputMpx,
                maxDualStreamMpxPerSecond = maxDualStreamMpx,
                maxSingleStreamMpxPerSecond = maxSingleStreamMpx,
                overloadPercentage = overloadPct,
                statusTitle = if (isWorkingNow) "BLIND CAPTURE ACTIVE (ISP SOLO PASS)" else "BLIND VF REQUIRED (DISABLING VF WORKS)",
                statusDescription = if (isWorkingNow) {
                    "Viewfinder stream detached. 100% of ISP and memory bus dedicated to hardware encoder."
                } else {
                    "Dual-stream ISP limit exceeded. Disabling Viewfinder during recording unlocks smooth 0-drop capture!"
                },
                mitigationAction = if (isWorkingNow) {
                    "Smooth recording unlocked. Audio VU & timecode active on OLED canvas."
                } else {
                    "Enable \"Disable Viewfinder during recording\" to eliminate all dropped frames."
                },
                canWorkWithVfDisabled = true,
                requiresBlindVf = true
            )
        }

        if (totalDualStreamMpx > 320.0 || fps > 60.1) {
            val overloadPct = ((totalDualStreamMpx / maxDualStreamMpx) * 100.0).coerceIn(0.0, 100.0)
            return PipelineThroughputEvaluation(
                fps = fps,
                resolution = resolution,
                zone = PipelineZone.RENDER_LAG,
                currentMegaPixelsPerSecond = activeThroughputMpx,
                maxDualStreamMpxPerSecond = maxDualStreamMpx,
                maxSingleStreamMpxPerSecond = maxSingleStreamMpx,
                overloadPercentage = overloadPct,
                statusTitle = "VIEWFINDER LOAD HIGH (UI STUTTER)",
                statusDescription = "Dual-stream ISP load elevated. Viewfinder UI may drop render frames or stutter while video records.",
                mitigationAction = "Scopes throttled to 15 FPS to prevent GPU buffer starvation.",
                canWorkWithVfDisabled = true,
                requiresBlindVf = false
            )
        }

        val overloadPct = ((totalDualStreamMpx / maxDualStreamMpx) * 100.0).coerceIn(0.0, 100.0)
        return PipelineThroughputEvaluation(
            fps = fps,
            resolution = resolution,
            zone = PipelineZone.OPTIMAL,
            currentMegaPixelsPerSecond = activeThroughputMpx,
            maxDualStreamMpxPerSecond = maxDualStreamMpx,
            maxSingleStreamMpxPerSecond = maxSingleStreamMpx,
            overloadPercentage = overloadPct,
            statusTitle = "OPTIMAL (FULL CADENCE)",
            statusDescription = "Both Viewfinder (60 FPS) and recording operate at 100% cadence with 0 dropped frames.",
            mitigationAction = "All scopes, waveform, and false color operate in real time.",
            canWorkWithVfDisabled = true,
            requiresBlindVf = false
        )
    }

    fun getMaxFpsForResolution(
        resolution: String,
        sensorMinFrameDurationNs: Long? = null,
        encoderMaxFps: Double? = null,
        maxSingleStreamMpx: Double = defaultMaxSingleStreamMpx,
        maxDualStreamMpx: Double = defaultMaxDualStreamMpx,
        isVfDisabled: Boolean = false
    ): Double {
        val (w, h) = parseResolution(resolution)
        val pixels = w.toDouble() * h.toDouble()
        if (pixels <= 0) return 30.0

        val sensorCapFps = if (sensorMinFrameDurationNs != null && sensorMinFrameDurationNs > 0L) {
            1_000_000_000.0 / sensorMinFrameDurationNs
        } else null

        // Continuous algorithmic pipeline throughput:
        // When Viewfinder is disabled (Blind VF / Solo Pass), 100% of single-stream ISP capacity is available for recording.
        // When Viewfinder is enabled, dual-stream capacity reserves bandwidth for live preview (~62.2 Mpx/s for 1080p preview).
        val previewBandwidthMpx = 62.2
        val baseBandwidthMpx = if (isVfDisabled) maxSingleStreamMpx else (maxDualStreamMpx - previewBandwidthMpx)

        // Physical sensor readout & remosaic scaling algorithm:
        // ADC row-time and remosaic processing scale continuously with total sensor pixel count.
        // Standard video streaming resolutions (<= 8.3 MP / 4K UHD) process at full line-rate.
        // Ultra-high megapixel readouts (> 8.3 MP up to 64MP/108MP) scale with continuous ADC line-time and remosaic overhead:
        val remosaicScaling = 1.0 + maxOf(0.0, (pixels - 8_294_400.0) / 14_000_000.0)
        val effectiveThroughputMpx = baseBandwidthMpx / remosaicScaling

        // Algorithmic frame rate = Available processing bandwidth / frame pixel count
        val algorithmicFps = (effectiveThroughputMpx * 1_000_000.0) / pixels

        val calculated = minOf(
            sensorCapFps ?: Double.MAX_VALUE,
            encoderMaxFps ?: Double.MAX_VALUE,
            algorithmicFps
        )

        // Continuous precision float FPS down to 1.0 FPS, capped at high-speed camera limit (960 FPS)
        return (kotlin.math.round(calculated * 10.0) / 10.0).coerceIn(1.0, 960.0)
    }

    data class ResolutionDetail(
        val resolution: String,
        val width: Int,
        val height: Int,
        val megapixels: Double,
        val standardName: String,
        val isRecognizedStandard: Boolean
    )

    fun calculateResolutionForMp(mp: Double): Pair<Int, Int> {
        val targetPixels = (mp * 1_000_000.0).coerceAtLeast(300_000.0)
        when {
            mp in 63.5..64.5 -> return Pair(9248, 6936)
            mp in 15.5..16.5 -> return Pair(4624, 3472)
            mp in 11.5..12.5 -> return Pair(4624, 2600)
            mp in 8.1..8.5 -> return Pair(3840, 2160)
            mp in 3.5..3.8 -> return Pair(2560, 1440)
            mp in 2.0..2.2 -> return Pair(1920, 1080)
            mp in 0.85..0.95 -> return Pair(1280, 720)
            mp in 0.28..0.35 -> return Pair(640, 480)
        }
        val aspect = if (mp >= 12.0) 4.0 / 3.0 else 16.0 / 9.0
        val rawW = kotlin.math.sqrt(targetPixels * aspect)
        val rawH = kotlin.math.sqrt(targetPixels / aspect)
        val w = ((rawW.toInt() + 8) / 16) * 16
        val h = ((rawH.toInt() + 8) / 16) * 16
        return Pair(w, h)
    }

    fun getResolutionDetail(resolution: String): ResolutionDetail {
        val (w, h) = parseResolution(resolution)
        val mp = (w.toLong() * h.toLong()) / 1_000_000.0
        val isStandard = when {
            (w == 9248 && h == 6936) || mp >= 63.5 -> true // 64MP Samsung GW1 full sensor mode
            (w == 4624 && h == 3472) || (mp in 15.5..16.5 && kotlin.math.abs((w.toDouble() / h.toDouble()) - (4.0 / 3.0)) < 0.05) -> true // 16MP 4:3 native bin
            (w == 4624 && h == 2600) || (mp in 11.5..12.5 && kotlin.math.abs((w.toDouble() / h.toDouble()) - (16.0 / 9.0)) < 0.05) -> true // 12MP 16:9 crop
            w == 4096 && h == 2160 -> true
            w == 3840 && h == 2160 -> true
            w == 2560 && h == 1440 -> true
            w == 1920 && h == 1080 -> true
            w == 1280 && h == 720 -> true
            (w == 720 || w == 640) && h == 480 -> true
            else -> false
        }
        val name = when {
            (w == 9248 && h == 6936) || mp >= 63.5 -> String.format(java.util.Locale.US, "%.0fMP FULL SENSOR", mp)
            mp >= 60.0 -> String.format(java.util.Locale.US, "%.0fMP FULL SENSOR", mp)
            (w == 4624 && h == 3472) || (mp in 15.5..16.5 && kotlin.math.abs((w.toDouble() / h.toDouble()) - (4.0 / 3.0)) < 0.05) -> "16MP 4:3 SENSOR BIN"
            (w == 4624 && h == 2600) || (mp in 11.5..12.5 && kotlin.math.abs((w.toDouble() / h.toDouble()) - (16.0 / 9.0)) < 0.05) -> "12MP 16:9 SENSOR CROP"
            w == 4096 && h == 2160 -> "4K DCI CINEMA (17:9)"
            w == 3840 && h == 2160 -> "4K UHD BROADCAST (16:9)"
            w == 2560 && h == 1440 -> "1440p QHD (16:9)"
            w == 1920 && h == 1080 -> "1080p FULL HD (16:9)"
            w == 1280 && h == 720 -> "720p HD (16:9)"
            (w == 720 || w == 640) && h == 480 -> "480p SD (4:3)"
            mp >= 10.0 -> String.format(java.util.Locale.US, "%.1fMP SENSOR MODE", mp)
            mp >= 1.0 -> String.format(java.util.Locale.US, "%.1fMP CUSTOM SENSOR", mp)
            else -> String.format(java.util.Locale.US, "%.2fMP SD", mp)
        }
        return ResolutionDetail(
            resolution = resolution,
            width = w,
            height = h,
            megapixels = mp,
            standardName = name,
            isRecognizedStandard = isStandard
        )
    }

    fun getDynamicFpsChipsForResolution(maxFps: Double): List<Double> {
        val candidates = mutableListOf<Double>()
        if (maxFps <= 3.0) {
            listOf(1.0, 1.2, 1.5, 1.8, 2.0, 2.4, 2.5, 3.0).filter { it <= maxFps + 0.05 }.forEach { candidates.add(it) }
            if (candidates.isEmpty()) candidates.add(1.0)
        } else if (maxFps <= 10.0) {
            listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0).filter { it <= maxFps + 0.05 }.forEach { candidates.add(it) }
        } else if (maxFps <= 25.0) {
            listOf(1.0, 5.0, 10.0, 12.0, 15.0, 18.0, 20.0, 24.0, 25.0).filter { it <= maxFps + 0.05 }.forEach { candidates.add(it) }
        } else {
            listOf(1.0, 5.0, 12.0).forEach { candidates.add(it) }
            BroadcastFpsStandard.standards.map { it.fps }.filter { it <= maxFps + 0.05 }.forEach { candidates.add(it) }
        }
        return candidates.distinct().sorted()
    }

    fun getStandardResolutions(availableFromDevice: List<String> = emptyList()): List<String> {
        val standardList = listOf("3840x2160", "1920x1080", "1280x720")
        val combined = (availableFromDevice + standardList).distinct()
        val sorted = combined.sortedByDescending { res ->
            val (w, h) = parseResolution(res)
            w.toLong() * h.toLong()
        }
        val deduplicated = mutableListOf<String>()
        for (res in sorted) {
            val (w, h) = parseResolution(res)
            val pixels = w.toLong() * h.toLong()
            val hasNear = deduplicated.any { existing ->
                val (ew, eh) = parseResolution(existing)
                val epixels = ew.toLong() * eh.toLong()
                kotlin.math.abs(pixels - epixels).toDouble() / pixels.toDouble() < 0.02
            }
            if (!hasNear) {
                deduplicated.add(res)
            }
        }
        return deduplicated
    }
}

