package com.txor.commix

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.MicrophoneDirection
import android.media.ToneGenerator
import android.os.Build
import android.util.Log
import kotlin.math.pow

/**
 * AudioEngine manages microphone routing, polar directivity (beamforming),
 * external USB audio device detection, and cinema slate tone generation.
 */
class AudioEngine(private val context: Context) {
    private val TAG = "CommixAudioEngine"
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var toneGenerator: ToneGenerator? = null
    private var audioRecord: android.media.AudioRecord? = null
    private var audioThread: Thread? = null
    @Volatile private var isMonitoringAudio: Boolean = false
    @Volatile private var gainDb: Float = 0.0f
    @Volatile private var lowCutEnabled: Boolean = false

    fun setGain(gain: Float) {
        gainDb = gain
    }

    fun setLowCut(enabled: Boolean) {
        lowCutEnabled = enabled
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun startAudioMonitoring() {
        if (isMonitoringAudio) return
        val sampleRate = 44100
        val channelConfig = android.media.AudioFormat.CHANNEL_IN_MONO
        val audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT
        val minBuf = android.media.AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuf <= 0) {
            Log.w(TAG, "Audio hardware does not support mono 44.1kHz")
            return
        }
        val bufferSize = minBuf.coerceAtLeast(2048)

        try {
            audioRecord = android.media.AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
            if (audioRecord?.state != android.media.AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord failed to initialize")
                try { audioRecord?.release() } catch (_: Throwable) {}
                audioRecord = null
                return
            }
            audioRecord?.startRecording()
            isMonitoringAudio = true

            audioThread = Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                val buffer = ShortArray(bufferSize / 2)
                var prevX = 0.0
                var prevY = 0.0
                val alpha = 0.9887 // ~80Hz high-pass filter at 44.1kHz

                while (isMonitoringAudio) {
                    try {
                        val rec = audioRecord ?: break
                        if (rec.recordingState != android.media.AudioRecord.RECORDSTATE_RECORDING) break
                        val read = rec.read(buffer, 0, buffer.size)
                        if (read <= 0) {
                            if (read < 0) {
                                Log.w(TAG, "AudioRecord read error: $read")
                                break
                            }
                            continue
                        }
                        val currentGain = gainDb
                        val gainLinear = 10.0.pow((currentGain / 20.0).toDouble())
                        val applyLowCut = lowCutEnabled

                        var sum = 0.0
                        for (i in 0 until read) {
                            var sample = buffer[i].toDouble() * gainLinear
                            if (applyLowCut) {
                                val y = alpha * (prevY + sample - prevX)
                                prevX = sample
                                prevY = y
                                sample = y
                            }
                            sum += sample * sample
                        }
                        val rms = kotlin.math.sqrt(sum / read)
                        val db = if (rms > 0.0) 20.0 * kotlin.math.log10(rms / 32768.0) else -96.0
                        val norm = ((db + 60.0) / 60.0).coerceIn(0.0, 1.0)
                        if (com.txor.commix.bridge.NativeTelemetryBridge.isLoaded) {
                            com.txor.commix.bridge.NativeTelemetryBridge.nativeWriteAudio(
                                norm.toFloat(),
                                db.toFloat(),
                                db.toFloat(),
                                db.toFloat()
                            )
                        }
                    } catch (_: Throwable) {
                        break
                    }
                }
            }.apply {
                name = "CommixAudioMonitor"
                start()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Audio monitoring error: ${e.message}")
            try { audioRecord?.release() } catch (_: Throwable) {}
            audioRecord = null
            isMonitoringAudio = false
        }
    }

    fun stopAudioMonitoring() {
        isMonitoringAudio = false
        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        try {
            audioThread?.join(300)
        } catch (_: Exception) {}
        audioThread = null
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }

    fun applyMicrophoneDirection(recorder: MediaRecorder, direction: String, zoomAngle: Double = 0.0) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                when (direction.lowercase()) {
                    "front" -> recorder.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER)
                    "back" -> recorder.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_AWAY_FROM_USER)
                    "external" -> recorder.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_EXTERNAL)
                    else -> recorder.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_UNSPECIFIED)
                }
                if (zoomAngle > 0.0) {
                    recorder.setPreferredMicrophoneFieldDimension(zoomAngle.toFloat().coerceIn(0.0f, 1.0f))
                }
            } catch (e: Exception) {
                Log.w(TAG, "setPreferredMicrophoneDirection failed: ${e.message}")
            }
        }
    }

    fun playSlateTone() {
        try {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            }
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 160)
        } catch (e: Exception) {
            Log.w(TAG, "playSlateTone failed: ${e.message}")
        }
    }

    fun release() {
        stopAudioMonitoring()
        try {
            toneGenerator?.release()
            toneGenerator = null
        } catch (_: Exception) {}
    }
}

