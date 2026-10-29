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
 * High-performance MediaCodec surface video and synchronized AAC audio encoder.
 *
 * Provides crash-resilient MP4 video recording directly from Camera2 surface
 * with MediaMuxer container integration for standard Android gallery playback.
 * Supports 4K/8K recording, 60/120/240/960fps pacing, H.265 Main10 HDR10 profile negotiation,
 * All-I / Long-GOP profile parameters, and synchronized high-fidelity AAC audio encoding.
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
    val playbackFps: Double = 30.0,
    val isHdr10: Boolean = false,
    val iFrameIntervalSeconds: Float = 1.0f, // 0.0f = All-I Intra-only, 1.0f / 2.0f = Long-GOP
    val useSyntheticPts: Boolean = false,
    val recordAudio: Boolean = false,
    val audioSampleRate: Int = 44100,
    val uncompressedAudio: Boolean = false // true = 384 kbps broadcast AAC, false = 192 kbps
) {
    companion object {
        /**
         * Microsecond timestamp step between consecutive frames written to the muxer.
         * Slow-motion expands playback to [playbackFps]; otherwise frames are paced at [targetFps].
         * Mirrors the pacing applied in the drain loop (truncated to Long).
         */
        fun computeFrameStepUs(targetFps: Double, isSlowMotion: Boolean, playbackFps: Double): Long {
            return if (isSlowMotion) {
                (1_000_000.0 / playbackFps.coerceAtLeast(1.0)).toLong()
            } else {
                (1_000_000.0 / targetFps.coerceAtLeast(1.0)).toLong()
            }
        }

        /** Synthetic presentation timestamp for frame [frameIndex] given a fixed microsecond [stepUs]. */
        fun computeSyntheticPtsUs(frameIndex: Long, stepUs: Long): Long = frameIndex * stepUs

        /**
         * Drift-free synthetic presentation timestamp using continuous floating-point rate.
         * Eliminates cumulative rounding errors for fractional broadcast standards (23.976, 29.97, 59.94).
         */
        fun computeDriftFreePtsUs(frameIndex: Long, effectiveFps: Double): Long {
            val fpsSafe = effectiveFps.coerceAtLeast(1.0)
            return ((frameIndex * 1_000_000.0) / fpsSafe).toLong()
        }

        /**
         * Determine if the encoder configuration corresponds to All-I (Intra-only, every frame is an IDR keyframe).
         */
        fun isAllIProfile(iFrameIntervalSeconds: Float): Boolean = iFrameIntervalSeconds <= 0.0f
    }

    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var mediaMuxer: MediaMuxer? = null
    private var videoTrackIndex: Int = -1
    private var audioTrackIndex: Int = -1
    private var isMuxerStarted: Boolean = false
    private val muxerLock = Any()

    @Volatile private var isRecording: Boolean = false
    @Volatile private var isEosSignaled: Boolean = false
    private var drainThread: Thread? = null

    private var audioEncoder: MediaCodec? = null
    private var audioDrainThread: Thread? = null
    @Volatile private var audioSampleCount: Long = 0L

    val audioFrameSink: AudioEngine.AudioFrameSink = AudioEngine.AudioFrameSink { pcmData, length, rate ->
        enqueueAudioFrame(pcmData, length, rate)
    }

    val surface: Surface?
        get() = persistentSurface ?: inputSurface

    /**
     * Prepares and starts the encoder, audio encoder, and muxer.
     */
    fun start() {
        if (isRecording) return

        outputFile.parentFile?.mkdirs()
        synchronized(muxerLock) {
            mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            videoTrackIndex = -1
            audioTrackIndex = -1
            isMuxerStarted = false
        }

        var encWidth = width
        var encHeight = height
        val effectiveFps = targetFps.coerceAtLeast(1.0)
        val containerFps = if (isSlowMotion) playbackFps.toInt().coerceAtLeast(1) else effectiveFps.toInt().coerceAtLeast(1)

        // Bounding box: 8K UHD ceiling (7680x4320 landscape or 4320x7680 portrait)
        val maxBoxW = if (width >= height) 7680.0 else 4320.0
        val maxBoxH = if (width >= height) 4320.0 else 7680.0

        if (encWidth > maxBoxW || encHeight > maxBoxH) {
            val scale = minOf(maxBoxW / encWidth.toDouble(), maxBoxH / encHeight.toDouble())
            encWidth = ((encWidth * scale).toInt() / 16) * 16
            encHeight = ((encHeight * scale).toInt() / 16) * 16
        }

        fun buildFormat(targetMime: String, w: Int, h: Int, enableHdr10: Boolean = isHdr10): MediaFormat {
            return MediaFormat.createVideoFormat(targetMime, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, containerFps)

                // All-I vs Long-GOP profile parameter configuration
                if (isAllIProfile(iFrameIntervalSeconds)) {
                    // All-I: Intra-only, each frame is an I-frame (zero P/B frames) for instant NLE scrub
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)
                    try {
                        setInteger("intra-refresh-period", 0)
                    } catch (_: Exception) {}
                } else {
                    // Long-GOP: Periodic keyframes (default 1s interval)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSeconds.toInt().coerceAtLeast(1))
                }

                try {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                } catch (_: Exception) {}

                // Priority 0: Real-time video encoding priority on Android OS scheduler
                try {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                } catch (_: Exception) {}

                // Pacing and operating clock for ultra-low or high frame rates (up to 120/240/960 fps)
                try {
                    val operatingRate = if (isSlowMotion) playbackFps.toInt().coerceAtLeast(30) else effectiveFps.toInt().coerceAtLeast(30)
                    setInteger(MediaFormat.KEY_OPERATING_RATE, operatingRate)
                    setFloat(MediaFormat.KEY_CAPTURE_RATE, effectiveFps.toFloat())
                } catch (_: Exception) {}

                // H.265 Main10 HDR10 Profile Negotiation
                if (targetMime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                    if (enableHdr10) {
                        try {
                            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10)
                        } catch (_: Exception) {
                            try {
                                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                            } catch (_: Exception) {}
                        }
                        // BT.2020 Color Standard and SMPTE ST 2084 PQ Color Transfer for genuine HDR10
                        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
                        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                    } else {
                        try {
                            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        var format = buildFormat(mimeType, encWidth, encHeight, isHdr10)
        var enc: MediaCodec? = null

        try {
            enc = MediaCodec.createEncoderByType(mimeType).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        } catch (e: Exception) {
            Log.w("MediaCodecRecorder", "Failed to configure $mimeType at ${encWidth}x${encHeight} (HDR10=$isHdr10): ${e.message}")
            // Fallback attempt 1: If HDR10 failed, attempt standard 8-bit SDR configuration
            if (isHdr10) {
                try {
                    Log.i("MediaCodecRecorder", "Attempting 8-bit SDR fallback at ${encWidth}x${encHeight}")
                    format = buildFormat(mimeType, encWidth, encHeight, enableHdr10 = false)
                    enc = MediaCodec.createEncoderByType(mimeType).apply {
                        configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    }
                } catch (_: Exception) {
                    enc = null
                }
            }

            // Fallback attempt 2: If 8K UHD failed, fallback to 4K UHD
            if (enc == null && (encWidth > 3840 || encHeight > 2160)) {
                try {
                    Log.i("MediaCodecRecorder", "8K configuration rejected by hardware. Attempting 4K UHD fallback.")
                    val aspect = encWidth.toDouble() / encHeight.toDouble()
                    encWidth = if (aspect >= 1.0) 3840 else (((2160.0 * aspect).toInt() / 16) * 16)
                    encHeight = if (aspect >= 1.0) (((3840.0 / aspect).toInt() / 16) * 16) else 2160
                    format = buildFormat(mimeType, encWidth, encHeight, enableHdr10 = false)
                    enc = MediaCodec.createEncoderByType(mimeType).apply {
                        configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    }
                } catch (_: Exception) {
                    enc = null
                }
            }

            // Fallback attempt 3: Standard 1080p fallback
            if (enc == null) {
                try {
                    Log.i("MediaCodecRecorder", "Attempting standard 1080p fallback.")
                    val aspect = encWidth.toDouble() / encHeight.toDouble()
                    val fallbackW = if (aspect >= 1.0) 1920 else (((1080.0 * aspect).toInt() / 16) * 16)
                    val fallbackH = if (aspect >= 1.0) (((1920.0 / aspect).toInt() / 16) * 16) else 1920
                    encWidth = fallbackW
                    encHeight = fallbackH
                    format = buildFormat(mimeType, encWidth, encHeight, enableHdr10 = false)
                    enc = MediaCodec.createEncoderByType(mimeType).apply {
                        configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    }
                } catch (e2: Exception) {
                    Log.e("MediaCodecRecorder", "All fallbacks failed: ${e2.message}", e2)
                    throw e
                }
            }
        }

        encoder = enc!!.apply {
            if (persistentSurface != null) {
                setInputSurface(persistentSurface)
            } else {
                inputSurface = createInputSurface()
            }
            start()
        }

        // Initialize AAC audio encoder if audio recording is requested
        if (recordAudio) {
            try {
                val audioMime = MediaFormat.MIMETYPE_AUDIO_AAC
                val audioBitrate = if (uncompressedAudio) 384000 else 192000
                val audioFormat = MediaFormat.createAudioFormat(audioMime, audioSampleRate, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }
                audioEncoder = MediaCodec.createEncoderByType(audioMime).apply {
                    configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }
                audioSampleCount = 0L

                // Prime audio encoder with initial silent buffer to prompt INFO_OUTPUT_FORMAT_CHANGED
                try {
                    val inIdx = audioEncoder?.dequeueInputBuffer(10000L) ?: -1
                    if (inIdx >= 0) {
                        val inBuf = audioEncoder?.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            inBuf.clear()
                            val initialShorts = 1024
                            val bytes = initialShorts * 2
                            val count = minOf(bytes, inBuf.remaining())
                            for (i in 0 until count) inBuf.put(0.toByte())
                            audioEncoder?.queueInputBuffer(inIdx, 0, count, 0L, 0)
                            audioSampleCount += (count / 2)
                        }
                    }
                } catch (_: Exception) {}

                startAudioDrainLoop()
            } catch (e: Exception) {
                Log.w("MediaCodecRecorder", "Failed to initialize AAC audio encoder: ${e.message}", e)
                audioEncoder = null
            }
        }

        isRecording = true
        isEosSignaled = false
        startDrainLoop()
    }

    private fun enqueueAudioFrame(pcmData: ShortArray, length: Int, rate: Int) {
        if (!isRecording || isEosSignaled) return
        val enc = audioEncoder ?: return
        try {
            val inIdx = enc.dequeueInputBuffer(5000L) // 5ms timeout
            if (inIdx >= 0) {
                val inBuf = enc.getInputBuffer(inIdx)
                if (inBuf != null) {
                    inBuf.clear()
                    val byteCount = length * 2
                    val bytesToWrite = minOf(byteCount, inBuf.remaining())
                    val shortsToWrite = bytesToWrite / 2
                    for (i in 0 until shortsToWrite) {
                        inBuf.putShort(pcmData[i])
                    }
                    val effectiveRate = if (rate > 0) rate else audioSampleRate
                    val ptsUs = (audioSampleCount * 1_000_000L) / effectiveRate
                    audioSampleCount += shortsToWrite
                    enc.queueInputBuffer(inIdx, 0, bytesToWrite, ptsUs, 0)
                }
            }
        } catch (_: Exception) {}
    }

    private fun startAudioDrainLoop() {
        audioDrainThread = Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            val enc = audioEncoder ?: return@Thread
            var eosReceived = false

            while (!eosReceived) {
                if (!isRecording && !isEosSignaled) break

                val outIdx = try {
                    enc.dequeueOutputBuffer(bufferInfo, 10000L)
                } catch (_: Exception) {
                    break
                }

                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = enc.outputFormat
                    synchronized(muxerLock) {
                        mediaMuxer?.let { m ->
                            if (!isMuxerStarted && audioTrackIndex < 0) {
                                audioTrackIndex = m.addTrack(newFormat)
                                if (videoTrackIndex >= 0) {
                                    m.start()
                                    isMuxerStarted = true
                                }
                            }
                        }
                    }
                } else if (outIdx >= 0) {
                    val outBuf = enc.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0) {
                        synchronized(muxerLock) {
                            if (mediaMuxer != null && isMuxerStarted && audioTrackIndex >= 0) {
                                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                    outBuf.position(bufferInfo.offset)
                                    outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                    mediaMuxer?.writeSampleData(audioTrackIndex, outBuf, bufferInfo)
                                }
                            }
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
            name = "Commix-Audio-Encoder-Drain"
            start()
        }
    }

    private fun startDrainLoop() {
        drainThread = Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            val enc = encoder ?: return@Thread
            var eosReceived = false
            var frameIndex = 0L
            val pacingFps = if (isSlowMotion) playbackFps else targetFps
            var lastMuxerPtsUs = -1L

            // Adaptive dequeue timeout: 2.5ms for high frame rates (>=60fps) to prevent surface buffer starvation
            val dequeueTimeoutUs = if (targetFps >= 60.0) 2500L else 10000L

            while (!eosReceived) {
                if (!isRecording && !isEosSignaled) break

                val outIdx = try {
                    enc.dequeueOutputBuffer(bufferInfo, dequeueTimeoutUs)
                } catch (_: Exception) {
                    break
                }

                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = enc.outputFormat
                    synchronized(muxerLock) {
                        mediaMuxer?.let { m ->
                            if (!isMuxerStarted && videoTrackIndex < 0) {
                                videoTrackIndex = m.addTrack(newFormat)
                                val needAudio = recordAudio && audioEncoder != null
                                if (!needAudio || audioTrackIndex >= 0) {
                                    m.start()
                                    isMuxerStarted = true
                                }
                            }
                        }
                    }
                } else if (outIdx >= 0) {
                    val outBuf = enc.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0) {
                        if (isSlowMotion || useSyntheticPts) {
                            // Drift-free synthetic presentation timestamp
                            val pts = computeDriftFreePtsUs(frameIndex, pacingFps)
                            bufferInfo.presentationTimeUs = if (pts <= lastMuxerPtsUs) lastMuxerPtsUs + 1L else pts
                            frameIndex++
                        } else {
                            // Ensure strictly monotonic presentation timestamps for hardware Camera2 timestamps
                            if (bufferInfo.presentationTimeUs <= lastMuxerPtsUs) {
                                bufferInfo.presentationTimeUs = lastMuxerPtsUs + 1000L
                            }
                        }
                        lastMuxerPtsUs = bufferInfo.presentationTimeUs

                        // If muxer hasn't started yet because audio track format is pending, wait briefly
                        if (!isMuxerStarted) {
                            var waitCount = 0
                            while (!isMuxerStarted && isRecording && !isEosSignaled && waitCount < 30) {
                                Thread.sleep(10)
                                waitCount++
                            }
                            synchronized(muxerLock) {
                                if (!isMuxerStarted && mediaMuxer != null && videoTrackIndex >= 0) {
                                    mediaMuxer?.start()
                                    isMuxerStarted = true
                                }
                            }
                        }

                        synchronized(muxerLock) {
                            if (mediaMuxer != null && isMuxerStarted && videoTrackIndex >= 0) {
                                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                    outBuf.position(bufferInfo.offset)
                                    outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                    mediaMuxer?.writeSampleData(videoTrackIndex, outBuf, bufferInfo)
                                }
                            }
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
        } catch (_: Exception) {}

        try {
            audioEncoder?.let { aEnc ->
                val inIdx = aEnc.dequeueInputBuffer(10000L)
                if (inIdx >= 0) {
                    aEnc.queueInputBuffer(inIdx, 0, 0, (audioSampleCount * 1_000_000L) / audioSampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            }
        } catch (_: Exception) {}

        try {
            val joinTimeout = if (targetFps <= 2.0) 2500L else 1500L
            drainThread?.join(joinTimeout)
        } catch (_: Exception) {}

        try {
            audioDrainThread?.join(1000L)
        } catch (_: Exception) {}

        isRecording = false

        try {
            encoder?.stop()
            encoder?.release()
        } catch (_: Exception) {}
        encoder = null

        try {
            audioEncoder?.stop()
            audioEncoder?.release()
        } catch (_: Exception) {}
        audioEncoder = null

        synchronized(muxerLock) {
            if (mediaMuxer != null && isMuxerStarted) {
                try {
                    mediaMuxer?.stop()
                } catch (e: Exception) {
                    Log.w("MediaCodecRecorder", "Muxer stop failed: ${e.message}")
                }
                try {
                    mediaMuxer?.release()
                } catch (_: Exception) {}
            }
            mediaMuxer = null
            isMuxerStarted = false
            videoTrackIndex = -1
            audioTrackIndex = -1
        }

        try {
            inputSurface?.release()
        } catch (_: Exception) {}
        inputSurface = null
    }
}
