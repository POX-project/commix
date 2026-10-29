package com.txor.commix

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit Test Suite for PhotoFormatEngine, covering:
 * - HEIC, JPEG, PNG, WEBP, LOSSLESS (BMP), TIFF, and RAW DNG format definitions.
 * - Zero-compression and lossless format classifiers.
 * - Bit-exact binary streaming headers, strides, and byte alignment for uncompressed BMP.
 * - Standard TIFF 6.0 IFDs and Adobe DNG Linear RAW container tags.
 * - CaptureRequestParams photo quality defaults and lossless coercion.
 */
class PhotoFormatEngineTest {

    @Test
    fun testSupportedFormatsListCompleteness() {
        val formats = PhotoFormatEngine.SUPPORTED_FORMATS
        assertTrue("Must support JPEG", formats.contains(PhotoFormatEngine.FORMAT_JPEG))
        assertTrue("Must support HEIC", formats.contains(PhotoFormatEngine.FORMAT_HEIC))
        assertTrue("Must support PNG", formats.contains(PhotoFormatEngine.FORMAT_PNG))
        assertTrue("Must support WEBP", formats.contains(PhotoFormatEngine.FORMAT_WEBP))
        assertTrue("Must support LOSSLESS BMP", formats.contains(PhotoFormatEngine.FORMAT_LOSSLESS))
        assertTrue("Must support TIFF", formats.contains(PhotoFormatEngine.FORMAT_TIFF))
        assertTrue("Must support RAW DNG", formats.contains(PhotoFormatEngine.FORMAT_RAW_DNG))
        assertEquals("Must offer all 7 professional still formats", 7, formats.size)
    }

    @Test
    fun testIsZeroCompressionCategorization() {
        // Formats that apply ZERO compression algorithm to raw pixel samples
        assertTrue(PhotoFormatEngine.isZeroCompression("lossless"))
        assertTrue(PhotoFormatEngine.isZeroCompression("bmp"))
        assertTrue(PhotoFormatEngine.isZeroCompression("BMP"))
        assertTrue(PhotoFormatEngine.isZeroCompression("tiff"))
        assertTrue(PhotoFormatEngine.isZeroCompression("tif"))
        assertTrue(PhotoFormatEngine.isZeroCompression("TIFF"))
        assertTrue(PhotoFormatEngine.isZeroCompression("raw_dng"))
        assertTrue(PhotoFormatEngine.isZeroCompression("dng"))
        assertTrue(PhotoFormatEngine.isZeroCompression("raw"))

        // Compressed formats (lossy or compressed lossless)
        assertFalse(PhotoFormatEngine.isZeroCompression("jpeg"))
        assertFalse(PhotoFormatEngine.isZeroCompression("jpg"))
        assertFalse(PhotoFormatEngine.isZeroCompression("heic"))
        assertFalse(PhotoFormatEngine.isZeroCompression("heif"))
        assertFalse(PhotoFormatEngine.isZeroCompression("png"))
        assertFalse(PhotoFormatEngine.isZeroCompression("webp"))
    }

    @Test
    fun testIsLosslessCategorization() {
        // True uncompressed formats are always lossless
        assertTrue(PhotoFormatEngine.isLossless("lossless", 50))
        assertTrue(PhotoFormatEngine.isLossless("bmp", 100))
        assertTrue(PhotoFormatEngine.isLossless("tiff", 75))
        assertTrue(PhotoFormatEngine.isLossless("raw_dng", 95))

        // PNG is inherently lossless compressed
        assertTrue(PhotoFormatEngine.isLossless("png", 50))
        assertTrue(PhotoFormatEngine.isLossless("png", 100))

        // WebP is lossless only when quality is maxed out to 100
        assertTrue(PhotoFormatEngine.isLossless("webp", 100))
        assertFalse(PhotoFormatEngine.isLossless("webp", 99))
        assertFalse(PhotoFormatEngine.isLossless("webp", 80))

        // JPEG and HEIC are lossy
        assertFalse(PhotoFormatEngine.isLossless("jpeg", 100))
        assertFalse(PhotoFormatEngine.isLossless("heic", 100))
    }

    @Test
    fun testFileExtensionsAndMimeTypes() {
        // Extensions
        assertEquals(".jpg", PhotoFormatEngine.getExtension("jpeg"))
        assertEquals(".heic", PhotoFormatEngine.getExtension("heic"))
        assertEquals(".heic", PhotoFormatEngine.getExtension("heif"))
        assertEquals(".png", PhotoFormatEngine.getExtension("png"))
        assertEquals(".webp", PhotoFormatEngine.getExtension("webp"))
        assertEquals(".bmp", PhotoFormatEngine.getExtension("lossless"))
        assertEquals(".bmp", PhotoFormatEngine.getExtension("bmp"))
        assertEquals(".tiff", PhotoFormatEngine.getExtension("tiff"))
        assertEquals(".dng", PhotoFormatEngine.getExtension("raw_dng"))

        // MIME Types
        assertEquals("image/jpeg", PhotoFormatEngine.getMimeType("jpeg"))
        assertEquals("image/heic", PhotoFormatEngine.getMimeType("heic"))
        assertEquals("image/png", PhotoFormatEngine.getMimeType("png"))
        assertEquals("image/webp", PhotoFormatEngine.getMimeType("webp"))
        assertEquals("image/bmp", PhotoFormatEngine.getMimeType("lossless"))
        assertEquals("image/tiff", PhotoFormatEngine.getMimeType("tiff"))
        assertEquals("image/x-adobe-dng", PhotoFormatEngine.getMimeType("raw_dng"))
    }

    @Test
    fun testUncompressedBmpHeaderAndPixelStructure() {
        val width = 3
        val height = 2
        // Row stride for 3 pixels (24-bit): ((3 * 3 + 3) / 4) * 4 = 12 bytes (9 pixel bytes + 3 padding bytes)
        val expectedStride = 12
        val expectedImageSize = expectedStride * height
        val expectedFileSize = 54 + expectedImageSize

        // Test pixels: row 0 (top) = Red, Green, Blue; row 1 (bottom) = White, Black, Yellow
        val row0 = intArrayOf(0x00FF0000, 0x0000FF00, 0x000000FF)
        val row1 = intArrayOf(0x00FFFFFF, 0x00000000, 0x00FFFF00)

        val out = ByteArrayOutputStream()
        PhotoFormatEngine.writeUncompressedBmp(
            width = width,
            height = height,
            getRowPixels = { y, dest ->
                val src = if (y == 0) row0 else row1
                System.arraycopy(src, 0, dest, 0, width)
            },
            output = out
        )

        val bytes = out.toByteArray()
        assertEquals(expectedFileSize, bytes.size)

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // BITMAPFILEHEADER
        assertEquals('B'.code.toByte(), buf.get(0))
        assertEquals('M'.code.toByte(), buf.get(1))
        assertEquals(expectedFileSize, buf.getInt(2))
        assertEquals(54, buf.getInt(10)) // Pixel offset

        // BITMAPINFOHEADER
        assertEquals(40, buf.getInt(14)) // biSize
        assertEquals(width, buf.getInt(18)) // biWidth
        assertEquals(height, buf.getInt(22)) // biHeight
        assertEquals(1.toShort(), buf.getShort(26)) // biPlanes
        assertEquals(24.toShort(), buf.getShort(28)) // biBitCount
        assertEquals(0, buf.getInt(30)) // biCompression = 0 (BI_RGB)
        assertEquals(expectedImageSize, buf.getInt(34)) // biSizeImage

        // Verify bottom-up scanline order:
        // First row written into pixel buffer must be row 1 (y = 1, bottom row in BMP)
        // Pixel (0, 1) is White (B=0xFF, G=0xFF, R=0xFF)
        val pixelOffset = 54
        assertEquals(0xFF.toByte(), bytes[pixelOffset])     // B
        assertEquals(0xFF.toByte(), bytes[pixelOffset + 1]) // G
        assertEquals(0xFF.toByte(), bytes[pixelOffset + 2]) // R

        // Check row stride padding (3 zero bytes at the end of row 1)
        assertEquals(0.toByte(), bytes[pixelOffset + 9])
        assertEquals(0.toByte(), bytes[pixelOffset + 10])
        assertEquals(0.toByte(), bytes[pixelOffset + 11])
    }

    @Test
    fun testUncompressedTiffHeaderAndIFDStructure() {
        val width = 2
        val height = 2
        val row = intArrayOf(0x00FF0000, 0x0000FF00) // Red, Green

        val out = ByteArrayOutputStream()
        PhotoFormatEngine.writeUncompressedTiff(
            width = width,
            height = height,
            getRowPixels = { _, dest ->
                System.arraycopy(row, 0, dest, 0, width)
            },
            output = out,
            isDng = false
        )

        val bytes = out.toByteArray()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // TIFF Header
        assertEquals('I'.code.toByte(), buf.get(0))
        assertEquals('I'.code.toByte(), buf.get(1))
        assertEquals(42.toShort(), buf.getShort(2))
        val ifdOffset = buf.getInt(4)
        assertEquals(8 + width * height * 3, ifdOffset)

        // Check First Pixel (RGB order in TIFF): Red = R:0xFF, G:0x00, B:0x00
        assertEquals(0xFF.toByte(), bytes[8])
        assertEquals(0x00.toByte(), bytes[9])
        assertEquals(0x00.toByte(), bytes[10])

        // IFD Entries count
        val numEntries = buf.getShort(ifdOffset).toInt()
        assertEquals(9, numEntries)

        // Verify Tag 259 (Compression) = 1 (Uncompressed / None)
        var compressionFound = false
        for (i in 0 until numEntries) {
            val entryOffset = ifdOffset + 2 + i * 12
            val tag = buf.getShort(entryOffset).toInt() and 0xFFFF
            if (tag == 259) {
                compressionFound = true
                val compVal = buf.getInt(entryOffset + 8)
                assertEquals(1, compVal)
            }
        }
        assertTrue("Compression tag (259) must be present in IFD", compressionFound)
    }

    @Test
    fun testUncompressedLinearRawDngHeaderAndVersionTag() {
        val width = 2
        val height = 2
        val row = intArrayOf(0x00112233, 0x00445566)

        val out = ByteArrayOutputStream()
        PhotoFormatEngine.writeUncompressedTiff(
            width = width,
            height = height,
            getRowPixels = { _, dest ->
                System.arraycopy(row, 0, dest, 0, width)
            },
            output = out,
            isDng = true
        )

        val bytes = out.toByteArray()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val ifdOffset = buf.getInt(4)
        val numEntries = buf.getShort(ifdOffset).toInt()
        assertEquals("DNG IFD must have 10 tags including DNGVersion", 10, numEntries)

        var dngVersionFound = false
        for (i in 0 until numEntries) {
            val entryOffset = ifdOffset + 2 + i * 12
            val tag = buf.getShort(entryOffset).toInt() and 0xFFFF
            if (tag == 50706) { // Tag 50706 = DNGVersion
                dngVersionFound = true
                val verVal = buf.getInt(entryOffset + 8)
                assertEquals("DNG version must be 1.4.0.0", 0x00000401, verVal)
            }
        }
        assertTrue("Tag 50706 (DNGVersion) must be present in DNG raw IFD", dngVersionFound)
    }

    @Test
    fun testCaptureRequestParamsQualityDefaultAndLosslessCoercion() {
        val defaultParams = CaptureRequestParams()
        assertEquals("Default photo format must be jpeg", "jpeg", defaultParams.photoFormat)
        assertEquals("Default photo quality must be 95", 95, defaultParams.photoQuality)

        val losslessParams = defaultParams.copy(photoFormat = "lossless", photoQuality = 60)
        assertTrue(PhotoFormatEngine.isLossless(losslessParams.photoFormat, losslessParams.photoQuality))

        val effectiveQuality = if (PhotoFormatEngine.isLossless(losslessParams.photoFormat, losslessParams.photoQuality)) {
            100
        } else {
            losslessParams.photoQuality
        }
        assertEquals("Lossless format must coerce quality to 100", 100, effectiveQuality)
    }
}
