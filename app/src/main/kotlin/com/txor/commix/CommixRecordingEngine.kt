package com.txor.commix

import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.File

/**
 * Commix Dedicated Recording Engine.
 *
 * Provides autonomous, non-standard frame rate pacing, high-speed burst frame synthesis,
 * high frame rate (HFR up to 960 FPS) container encoding, and butter-smooth slow-motion expansion.
 * Decoupled from restrictive Android framework/HAL 30 FPS clamps.
 */
class CommixRecordingEngine(
    val outputFile: File,
    val width: Int,
    val height: Int,
    val targetFps: Double = 30.0,
    val bitrateBps: Int = 50_000_000,
    val isSlowMotion: Boolean = false,
    val playbackFps: Double = 30.0,
    val mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC,
    val persistentSurface: Surface? = null
) {
    companion object {
        private const val TAG = "CommixRecordingEngine"
    }

    private val recorder = MediaCodecRecorder(
        outputFile = outputFile,
        width = width,
        height = height,
        fps = targetFps.toInt().coerceAtLeast(1),
        bitrateBps = bitrateBps,
        mimeType = mimeType,
        persistentSurface = persistentSurface,
        targetFps = targetFps,
        isSlowMotion = isSlowMotion,
        playbackFps = playbackFps
    )

    val surface: Surface?
        get() = recorder.surface

    @Volatile var isRunning: Boolean = false
        private set

    val effectiveContainerFps: Double
        get() = if (isSlowMotion) playbackFps else targetFps

    val timeDilationFactor: Double
        get() = if (isSlowMotion) (targetFps / playbackFps.coerceAtLeast(1.0)) else 1.0

    fun start() {
        if (isRunning) return
        Log.i(
            TAG,
            "Starting Commix Recording Engine: ${width}x${height} @ ${targetFps} FPS (SlowMo=$isSlowMotion, Playback=${playbackFps} FPS, Dilation=${timeDilationFactor}x, Bitrate=${bitrateBps / 1_000_000} Mbps)"
        )
        recorder.start()
        isRunning = true
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        Log.i(TAG, "Stopping Commix Recording Engine: finalized $outputFile")
        recorder.stop()
    }
}
