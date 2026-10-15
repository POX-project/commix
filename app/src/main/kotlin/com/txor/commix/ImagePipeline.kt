package com.txor.commix

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.heifwriter.HeifWriter
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * ImagePipeline executes color science transforms, Process Zero synthesis,
 * film grain emulation, and resilient multi-format image compression (JPEG, HEIC, WebP, PNG).
 */
class ImagePipeline(private val cacheDir: File) {
    private val TAG = "CommixImagePipeline"

    fun encodeHeic(sourceBmp: Bitmap, quality: Int): Pair<ByteArray, String> {
        var scaledBmp: Bitmap? = null
        var tempFile: File? = null
        var writer: HeifWriter? = null
        try {
            var w = sourceBmp.width
            var h = sourceBmp.height

            val maxDim = 8192
            if (w > maxDim || h > maxDim) {
                val scale = maxDim.toFloat() / max(w, h)
                w = (w * scale).toInt()
                h = (h * scale).toInt()
            }
            if (w % 2 != 0) w -= 1
            if (h % 2 != 0) h -= 1

            val inputBmp = if (w != sourceBmp.width || h != sourceBmp.height) {
                scaledBmp = Bitmap.createScaledBitmap(sourceBmp, w, h, true)
                scaledBmp
            } else {
                sourceBmp
            }

            tempFile = File.createTempFile("heic_encode_", ".heic", cacheDir)
            val clampedQuality = quality.coerceIn(1, 100)

            writer = HeifWriter.Builder(tempFile.absolutePath, inputBmp.width, inputBmp.height, HeifWriter.INPUT_MODE_BITMAP)
                .setQuality(clampedQuality)
                .setMaxImages(1)
                .build()

            writer.start()
            writer.addBitmap(inputBmp)
            writer.stop(5000L)
            writer.close()
            writer = null

            val bytes = tempFile.readBytes()
            // Strict Quality Guardrail:
            // High-quality HEIC typically measures 0.08 to 0.25 bytes/pixel.
            // On certain SoCs (e.g. MediaTek Helio G90T), HeifWriter starves output down to 10-25 KB total.
            val minExpectedBytes = ((inputBmp.width.toLong() * inputBmp.height.toLong()) * 0.04).toLong().coerceIn(250_000L, 1_500_000L)
            if (bytes.size >= minExpectedBytes) {
                return Pair(bytes, "heic")
            } else {
                Log.w(TAG, "Hardware HEIC output too small (${bytes.size} bytes for ${inputBmp.width}x${inputBmp.height}, min: $minExpectedBytes). Falling back to pristine JPEG.")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "HeifWriter encoding failed (${t.message}), falling back to JPEG", t)
        } finally {
            try { writer?.close() } catch (_: Throwable) {}
            try { tempFile?.delete() } catch (_: Throwable) {}
            if (scaledBmp != null && scaledBmp != sourceBmp && !scaledBmp.isRecycled) {
                try { scaledBmp.recycle() } catch (_: Throwable) {}
            }
        }

        // Fallback to pristine JPEG
        val out = ByteArrayOutputStream()
        sourceBmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
        return Pair(out.toByteArray(), "jpg")
    }

    fun applyProcessZero(src: Bitmap, intensity: Float, grainPreset: String, curvePreset: String): Bitmap {
        val w = src.width
        val h = src.height
        val inPixels = IntArray(w * h)
        src.getPixels(inPixels, 0, w, 0, 0, w, h)
        val outPixels = IntArray(w * h)

        val curveLut = IntArray(256)
        for (i in 0..255) {
            val x = i / 255.0
            val y = when (curvePreset) {
                "film_print" -> if (x < 0.5) 2.0 * x * x else 1.0 - 2.0 * (1.0 - x) * (1.0 - x)
                "cine_d" -> Math.pow(x, 0.9)
                else -> (3.0 * x * x - 2.0 * x * x * x) * 0.4 + x * 0.6
            }
            curveLut[i] = (y * 255.0).toInt().coerceIn(0, 255)
        }

        val grainScale = when (grainPreset) {
            "rough_8mm" -> 26.0f * intensity
            "classic_16mm" -> 16.0f * intensity
            "fine_35mm" -> 9.0f * intensity
            else -> 0.0f
        }

        val numThreads = min(Runtime.getRuntime().availableProcessors(), 8)
        val threads = mutableListOf<Thread>()
        val rowsPerThread = h / numThreads

        for (t in 0 until numThreads) {
            val startY = t * rowsPerThread
            val endY = if (t == numThreads - 1) h else startY + rowsPerThread
            val thread = Thread {
                var seed = (1337L + t * 9973L)
                for (y in startY until endY) {
                    val rowIdx = y * w
                    for (x in 0 until w) {
                        val c = inPixels[rowIdx + x]
                        val rOrig = (c shr 16) and 0xFF
                        val gOrig = (c shr 8) and 0xFF
                        val bOrig = c and 0xFF

                        var r = curveLut[rOrig]
                        var g = curveLut[gOrig]
                        var b = curveLut[bOrig]

                        if (grainScale > 0.01f) {
                            seed = (seed * 1664525L + 1013904223L) and 0xFFFFFFFFL
                            val u1 = (seed shr 16) and 0xFF
                            seed = (seed * 1664525L + 1013904223L) and 0xFFFFFFFFL
                            val u2 = (seed shr 16) and 0xFF
                            seed = (seed * 1664525L + 1013904223L) and 0xFFFFFFFFL
                            val u3 = (seed shr 16) and 0xFF
                            val gNoise = ((u1 + u2 + u3) / 3.0f - 128.0f) / 128.0f

                            val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255.0f
                            val grainMask = 4.0f * luma * (1.0f - luma)
                            val grainVal = (gNoise * grainScale * grainMask).toInt()

                            r = (r + grainVal).coerceIn(0, 255)
                            g = (g + grainVal).coerceIn(0, 255)
                            b = (b + grainVal).coerceIn(0, 255)
                        }

                        outPixels[rowIdx + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
            threads.add(thread)
            thread.start()
        }
        for (thread in threads) thread.join()

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(outPixels, 0, w, 0, 0, w, h)
        return out
    }
}
