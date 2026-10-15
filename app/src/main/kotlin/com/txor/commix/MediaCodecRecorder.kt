package com.txor.commix

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * High-performance MediaCodec surface video encoder with standard MP4 and fMP4 output.
 *
 * Provides crash-resilient fragmented MP4 video recording directly from Camera2 surface
 * with MediaMuxer container integration for standard Android gallery playback.
 */
class MediaCodecRecorder(
    val outputFile: File,
    val width: Int,
    val height: Int,
    val fps: Int = 30,
    val bitrateBps: Int = 50000000, // 50 Mbps default
    val mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC,
    val persistentSurface: Surface? = null,
    val targetFps: Double = fps.toDouble(),
    val isSlowMotion: Boolean = false,
    val playbackFps: Double = 30.0
) {
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var mediaMuxer: MediaMuxer? = null
    private var fmp4Muxer: Fmp4Muxer? = null
    private var videoTrackIndex: Int = -1
    private var isMuxerStarted: Boolean = false
    @Volatile private var isRecording: Boolean = false
    @Volatile private var isEosSignaled: Boolean = false
    private var drainThread: Thread? = null

    val surface: Surface?
        get() = persistentSurface ?: inputSurface

    /**
     * Prepares and starts the encoder and muxer.
     */
    fun start() {
        if (isRecording) return

        outputFile.parentFile?.mkdirs()
        mediaMuxer = try {
            MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (_: Exception) {
            null
        }
        if (mediaMuxer == null) {
            fmp4Muxer = Fmp4Muxer(outputFile, width, height)
        }

        var encWidth = width
        var encHeight = height
        val effectiveFps = targetFps.coerceAtLeast(1.0)
        val containerFps = if (isSlowMotion) playbackFps.toInt().coerceAtLeast(1) else effectiveFps.toInt().coerceAtLeast(1)

        // Bounding box: 4K UHD ceiling (3840x2160 landscape or 2160x3840 portrait)
        val maxBoxW = if (width >= height) 3840.0 else 2160.0
        val maxBoxH = if (width >= height) 2160.0 else 3840.0

        if (encWidth > maxBoxW || encHeight > maxBoxH) {
            val scale = minOf(maxBoxW / encWidth.toDouble(), maxBoxH / encHeight.toDouble())
            encWidth = ((encWidth * scale).toInt() / 16) * 16
            encHeight = ((encHeight * scale).toInt() / 16) * 16
        }

        fun buildFormat(targetMime: String, w: Int, h: Int): MediaFormat {
            return MediaFormat.createVideoFormat(targetMime, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, containerFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1s GOP for clean fragment boundary
                try {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                } catch (_: Exception) {}
                try {
                    // Pacing and operating clock for ultra-low or high frame rates
                    val operatingRate = if (isSlowMotion) playbackFps.toInt().coerceAtLeast(30) else effectiveFps.toInt().coerceAtLeast(30)
                    setInteger(MediaFormat.KEY_OPERATING_RATE, operatingRate)
                    setFloat(MediaFormat.KEY_CAPTURE_RATE, effectiveFps.toFloat())
                } catch (_: Exception) {}
            }
        }

        var format = buildFormat(mimeType, encWidth, encHeight)
        var enc: MediaCodec? = null

        try {
            enc = MediaCodec.createEncoderByType(mimeType).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        } catch (e: Exception) {
            Log.w("MediaCodecRecorder", "Failed to configure $mimeType at ${encWidth}x${encHeight}: ${e.message}. Attempting 1080p fallback.")
            try {
                val aspect = encWidth.toDouble() / encHeight.toDouble()
                val fallbackW = if (aspect >= 1.0) 1920 else (((1080.0 * aspect).toInt() / 16) * 16)
                val fallbackH = if (aspect >= 1.0) (((1920.0 / aspect).toInt() / 16) * 16) else 1920
                encWidth = fallbackW
                encHeight = fallbackH
                format = buildFormat(mimeType, encWidth, encHeight)
                enc = MediaCodec.createEncoderByType(mimeType).apply {
                    configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
            } catch (e2: Exception) {
                Log.e("MediaCodecRecorder", "Fallback also failed: ${e2.message}", e2)
                throw e
            }
        }

        encoder = enc.apply {
            if (persistentSurface != null) {
                setInputSurface(persistentSurface)
            } else {
                inputSurface = createInputSurface()
            }
            start()
        }

        isRecording = true
        isEosSignaled = false
        startDrainLoop()
    }

    private fun startDrainLoop() {
        drainThread = Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            val enc = encoder ?: return@Thread
            var eosReceived = false
            var frameIndex = 0L
            val stepUs = if (isSlowMotion) {
                (1_000_000.0 / playbackFps.coerceAtLeast(1.0)).toLong()
            } else {
                (1_000_000.0 / targetFps.coerceAtLeast(1.0)).toLong()
            }

            while (!eosReceived) {
                if (!isRecording && !isEosSignaled) break

                val outIdx = try {
                    enc.dequeueOutputBuffer(bufferInfo, 10000) // 10ms timeout
                } catch (_: Exception) {
                    break
                }

                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = enc.outputFormat
                    mediaMuxer?.let { m ->
                        if (!isMuxerStarted) {
                            videoTrackIndex = m.addTrack(newFormat)
                            m.start()
                            isMuxerStarted = true
                        }
                    }
                } else if (outIdx >= 0) {
                    val outBuf = enc.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0) {
                        // Synthetic monotonic presentation timestamp generation:
                        // Guarantees container output frame rate exactly matches target capture FPS (e.g. 960 FPS, 240 FPS)
                        // or expanded slow-motion playback FPS (30 FPS) with 0 clock jitter.
                        val syntheticPtsUs = frameIndex * stepUs
                        bufferInfo.presentationTimeUs = syntheticPtsUs
                        frameIndex++

                        if (mediaMuxer != null && isMuxerStarted) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                mediaMuxer?.writeSampleData(videoTrackIndex, outBuf, bufferInfo)
                            }
                        } else if (fmp4Muxer != null && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)

                            val chunk = ByteArray(bufferInfo.size)
                            outBuf.get(chunk)

                            val isKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            val fragDuration = if (isSlowMotion) {
                                (90000.0 / playbackFps.coerceAtLeast(1.0)).toLong()
                            } else {
                                (90000.0 / targetFps.coerceAtLeast(1.0)).toLong()
                            }
                            fmp4Muxer?.writeFragment(chunk, fragDuration.coerceAtLeast(1L), isKey)
                        }
                    }
                    try {
                        enc.releaseOutputBuffer(outIdx, false)
                    } catch (_: Exception) {}

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        eosReceived = true
                        break
                    }
                }
            }
        }.apply {
            name = "Commix-Video-Encoder-Drain"
            start()
        }
    }

    /**
     * Cleanly stops recording and finalizes container.
     */
    fun stop() {
        if (!isRecording) return
        isEosSignaled = true

        try {
            encoder?.signalEndOfInputStream()
            val joinTimeout = if (targetFps <= 2.0) 2500L else 1500L
            drainThread?.join(joinTimeout)
        } catch (_: Exception) {}
        isRecording = false

        try {
            encoder?.stop()
            encoder?.release()
        } catch (_: Exception) {}
        encoder = null

        if (mediaMuxer != null && isMuxerStarted) {
            try {
                mediaMuxer?.stop()
                mediaMuxer?.release()
            } catch (_: Exception) {}
        }
        mediaMuxer = null
        isMuxerStarted = false

        fmp4Muxer?.close()
        fmp4Muxer = null

        try {
            inputSurface?.release()
        } catch (_: Exception) {}
        inputSurface = null
    }
}
