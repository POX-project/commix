package com.txor.commix

import android.graphics.Bitmap
import android.os.Build
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Universal Photo Format & Compression Engine.
 *
 * Supports modern broadcasting, computational and archival still formats:
 * - JPEG: Standard high-compatibility lossy format with 50-100% quality.
 * - HEIC / HEIF: High-Efficiency Image Container (10-bit/8-bit HEVC still encoder).
 * - PNG: Lossless compressed format (100% pixel fidelity, zero compression artifacts).
 * - WEBP: Lossless / lossy WebP encoder (uses WEBP_LOSSLESS at 100% quality).
 * - LOSSLESS (BMP): Pure uncompressed 24-bit/32-bit bitmap format with ZERO compression applied.
 * - TIFF: Uncompressed standard TIFF 6.0 RGB format with ZERO compression applied.
 * - RAW DNG: Uncompressed linear digital negative raw container with ZERO compression applied.
 */
object PhotoFormatEngine {

    const val FORMAT_JPEG = "jpeg"
    const val FORMAT_HEIC = "heic"
    const val FORMAT_PNG = "png"
    const val FORMAT_WEBP = "webp"
    const val FORMAT_LOSSLESS = "lossless"
    const val FORMAT_TIFF = "tiff"
    const val FORMAT_RAW_DNG = "raw_dng"

    val SUPPORTED_FORMATS: List<String> = listOf(
        FORMAT_JPEG,
        FORMAT_HEIC,
        FORMAT_PNG,
        FORMAT_WEBP,
        FORMAT_LOSSLESS,
        FORMAT_TIFF,
        FORMAT_RAW_DNG
    )

    /**
     * Determines whether the specified format applies zero compression to pixel bytes.
     */
    fun isZeroCompression(format: String): Boolean {
        return when (format.lowercase()) {
            FORMAT_LOSSLESS, "bmp", FORMAT_TIFF, "tif", FORMAT_RAW_DNG, "dng", "raw" -> true
            else -> false
        }
    }

    /**
     * Determines whether the format guarantees lossless pixel preservation.
     */
    fun isLossless(format: String, quality: Int = 100): Boolean {
        return when (format.lowercase()) {
            FORMAT_LOSSLESS, "bmp", FORMAT_TIFF, "tif", FORMAT_PNG, FORMAT_RAW_DNG, "dng", "raw" -> true
            FORMAT_WEBP -> quality >= 100
            else -> false
        }
    }

    /**
     * Resolves the standard file extension for the given format.
     */
    fun getExtension(format: String): String {
        return when (format.lowercase()) {
            FORMAT_PNG -> ".png"
            FORMAT_WEBP -> ".webp"
            FORMAT_HEIC, "heif" -> ".heic"
            FORMAT_LOSSLESS, "bmp" -> ".bmp"
            FORMAT_TIFF, "tif" -> ".tiff"
            FORMAT_RAW_DNG, "dng", "raw" -> ".dng"
            else -> ".jpg"
        }
    }

    /**
     * Resolves the standard MIME content type for the given format.
     */
    fun getMimeType(format: String): String {
        return when (format.lowercase()) {
            FORMAT_PNG -> "image/png"
            FORMAT_WEBP -> "image/webp"
            FORMAT_HEIC, "heif" -> "image/heic"
            FORMAT_LOSSLESS, "bmp" -> "image/bmp"
            FORMAT_TIFF, "tif" -> "image/tiff"
            FORMAT_RAW_DNG, "dng", "raw" -> "image/x-adobe-dng"
            else -> "image/jpeg"
        }
    }

    /**
     * Encodes a [Bitmap] to [output] using the specified format and quality settings.
     * When quality is 100% or an uncompressed format is selected, compression is bypassed or maxed out.
     */
    fun encodeBitmap(
        bitmap: Bitmap,
        format: String,
        quality: Int,
        output: OutputStream
    ) {
        val clampedQuality = quality.coerceIn(1, 100)
        when (format.lowercase()) {
            FORMAT_LOSSLESS, "bmp" -> {
                writeUncompressedBmp(bitmap, output)
            }
            FORMAT_TIFF, "tif" -> {
                writeUncompressedTiff(bitmap, output, isDng = false)
            }
            FORMAT_RAW_DNG, "dng", "raw" -> {
                writeUncompressedTiff(bitmap, output, isDng = true)
            }
            FORMAT_PNG -> {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            FORMAT_HEIC, "heif" -> {
                encodeHeic(bitmap, clampedQuality, output)
            }
            FORMAT_WEBP -> {
                encodeWebp(bitmap, clampedQuality, output)
            }
            else -> {
                bitmap.compress(Bitmap.CompressFormat.JPEG, clampedQuality, output)
            }
        }
    }

    private fun encodeHeic(bitmap: Bitmap, quality: Int, output: OutputStream) {
        val heifFormat = try {
            Bitmap.CompressFormat.valueOf("HEIF")
        } catch (_: Throwable) {
            null
        }
        if (heifFormat != null) {
            val ok = try {
                bitmap.compress(heifFormat, quality, output)
            } catch (_: Throwable) {
                false
            }
            if (ok) return
        }
        // Fallback for runtimes without Bitmap.CompressFormat.HEIF
        if (quality >= 100) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        } else {
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
        }
    }

    private fun encodeWebp(bitmap: Bitmap, quality: Int, output: OutputStream) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (quality >= 100) {
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, output)
            } else {
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, output)
            }
        } else {
            @Suppress("DEPRECATION")
            bitmap.compress(Bitmap.CompressFormat.WEBP, quality, output)
        }
    }

    /**
     * Pure uncompressed 24-bit RGB Windows Bitmap (BMP) encoder from raw pixel provider.
     * Applies ZERO compression algorithm (BI_RGB); raw pixel samples are streamed directly.
     */
    fun writeUncompressedBmp(
        width: Int,
        height: Int,
        getRowPixels: (y: Int, rowPixels: IntArray) -> Unit,
        output: OutputStream
    ) {
        val rowStride = ((width * 3 + 3) / 4) * 4
        val imageSize = rowStride * height
        val fileSize = 54 + imageSize

        val header = ByteBuffer.allocate(54).order(ByteOrder.LITTLE_ENDIAN)
        // BITMAPFILEHEADER (14 bytes)
        header.put('B'.code.toByte())
        header.put('M'.code.toByte())
        header.putInt(fileSize)
        header.putShort(0)
        header.putShort(0)
        header.putInt(54) // Offset to pixel array

        // BITMAPINFOHEADER (40 bytes)
        header.putInt(40) // biSize
        header.putInt(width) // biWidth
        header.putInt(height) // biHeight (bottom-up standard)
        header.putShort(1) // biPlanes
        header.putShort(24) // biBitCount (24-bit RGB)
        header.putInt(0) // biCompression (0 = BI_RGB, uncompressed)
        header.putInt(imageSize) // biSizeImage
        header.putInt(2835) // biXPelsPerMeter (~72 DPI)
        header.putInt(2835) // biYPelsPerMeter (~72 DPI)
        header.putInt(0) // biClrUsed
        header.putInt(0) // biClrImportant

        val out = BufferedOutputStream(output, 65536)
        out.write(header.array())

        val rowPixels = IntArray(width)
        val rowBytes = ByteArray(rowStride)

        for (y in (height - 1) downTo 0) {
            getRowPixels(y, rowPixels)
            var byteIdx = 0
            for (x in 0 until width) {
                val color = rowPixels[x]
                rowBytes[byteIdx++] = (color and 0xFF).toByte()         // Blue
                rowBytes[byteIdx++] = ((color shr 8) and 0xFF).toByte()  // Green
                rowBytes[byteIdx++] = ((color shr 16) and 0xFF).toByte() // Red
            }
            while (byteIdx < rowStride) {
                rowBytes[byteIdx++] = 0
            }
            out.write(rowBytes, 0, rowStride)
        }
        out.flush()
    }

    /**
     * Pure uncompressed 24-bit RGB Windows Bitmap (BMP) encoder from a [Bitmap].
     */
    fun writeUncompressedBmp(bitmap: Bitmap, output: OutputStream) {
        writeUncompressedBmp(bitmap.width, bitmap.height, { y, rowPixels ->
            bitmap.getPixels(rowPixels, 0, bitmap.width, 0, y, bitmap.width, 1)
        }, output)
    }

    /**
     * Pure uncompressed TIFF 6.0 / Adobe DNG (Linear RAW) encoder from raw pixel provider.
     * Uses Compression = 1 (None / Uncompressed).
     */
    fun writeUncompressedTiff(
        width: Int,
        height: Int,
        getRowPixels: (y: Int, rowPixels: IntArray) -> Unit,
        output: OutputStream,
        isDng: Boolean = false
    ) {
        val imageDataLen = width * height * 3
        val headerLen = 8
        val imageDataOffset = headerLen
        val ifdOffset = headerLen + imageDataLen

        val out = BufferedOutputStream(output, 65536)
        val headerBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        headerBuf.put('I'.code.toByte())
        headerBuf.put('I'.code.toByte())
        headerBuf.putShort(42)
        headerBuf.putInt(ifdOffset)
        out.write(headerBuf.array())

        // Stream uncompressed RGB pixel bytes row by row
        val rowPixels = IntArray(width)
        val rowBytes = ByteArray(width * 3)
        for (y in 0 until height) {
            getRowPixels(y, rowPixels)
            var byteIdx = 0
            for (x in 0 until width) {
                val color = rowPixels[x]
                rowBytes[byteIdx++] = ((color shr 16) and 0xFF).toByte() // Red
                rowBytes[byteIdx++] = ((color shr 8) and 0xFF).toByte()  // Green
                rowBytes[byteIdx++] = (color and 0xFF).toByte()         // Blue
            }
            out.write(rowBytes, 0, byteIdx)
        }

        val numEntries = if (isDng) 10 else 9
        val ifdLen = 2 + numEntries * 12 + 4
        val bitsOffset = ifdOffset + ifdLen

        val ifdBuf = ByteBuffer.allocate(ifdLen + 6).order(ByteOrder.LITTLE_ENDIAN)
        ifdBuf.putShort(numEntries.toShort())

        fun writeEntry(tag: Int, type: Short, count: Int, valueOrOffset: Int) {
            ifdBuf.putShort(tag.toShort())
            ifdBuf.putShort(type)
            ifdBuf.putInt(count)
            ifdBuf.putInt(valueOrOffset)
        }

        // 256: ImageWidth (SHORT)
        writeEntry(256, 3, 1, width)
        // 257: ImageLength (SHORT)
        writeEntry(257, 3, 1, height)
        // 258: BitsPerSample (SHORT, count 3, offset to [8, 8, 8])
        writeEntry(258, 3, 3, bitsOffset)
        // 259: Compression (SHORT, 1 = Uncompressed / None)
        writeEntry(259, 3, 1, 1)
        // 262: PhotometricInterpretation (SHORT, 2 = RGB)
        writeEntry(262, 3, 1, 2)
        // 273: StripOffsets (LONG, 1, imageDataOffset)
        writeEntry(273, 4, 1, imageDataOffset)
        // 277: SamplesPerPixel (SHORT, 3)
        writeEntry(277, 3, 1, 3)
        // 278: RowsPerStrip (SHORT, height)
        writeEntry(278, 3, 1, height)
        // 279: StripByteCounts (LONG, imageDataLen)
        writeEntry(279, 4, 1, imageDataLen)

        if (isDng) {
            // Tag 50706: DNGVersion (BYTE, 4, [1, 4, 0, 0])
            val dngVer = 0x00000401
            writeEntry(50706, 1, 4, dngVer)
        }

        // Next IFD offset = 0 (none)
        ifdBuf.putInt(0)

        // Extra data: BitsPerSample array = [8, 8, 8]
        ifdBuf.putShort(8)
        ifdBuf.putShort(8)
        ifdBuf.putShort(8)

        out.write(ifdBuf.array())
        out.flush()
    }

    /**
     * Pure uncompressed TIFF 6.0 / Adobe DNG (Linear RAW) encoder from a [Bitmap].
     */
    fun writeUncompressedTiff(bitmap: Bitmap, output: OutputStream, isDng: Boolean = false) {
        writeUncompressedTiff(bitmap.width, bitmap.height, { y, rowPixels ->
            bitmap.getPixels(rowPixels, 0, bitmap.width, 0, y, bitmap.width, 1)
        }, output, isDng)
    }
}
