package com.txor.commix

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PanoramaAnd360EngineTest {

    @Test
    fun testWrappedDeltaRegularRotation() {
        val delta1 = PanoramaStitcher.calculateWrappedDelta(45f, 0f)
        assertEquals(45f, delta1, 0.001f)

        val delta2 = PanoramaStitcher.calculateWrappedDelta(0f, 45f)
        assertEquals(-45f, delta2, 0.001f)
    }

    @Test
    fun testWrappedDeltaAcrossCompassBoundary() {
        // Rotating from +175° to -175° is a clockwise rotation of +10°
        val cwDelta = PanoramaStitcher.calculateWrappedDelta(-175f, 175f)
        assertEquals(10f, cwDelta, 0.001f)

        // Rotating from -175° to +175° is a counter-clockwise rotation of -10°
        val ccwDelta = PanoramaStitcher.calculateWrappedDelta(175f, -175f)
        assertEquals(-10f, ccwDelta, 0.001f)

        // Small step near north: 359° to 1° (wrapped in 0..360 space)
        val northDelta = PanoramaStitcher.calculateWrappedDelta(1f, 359f)
        assertEquals(2f, northDelta, 0.001f)
    }

    @Test
    fun testProgressCalculationStandardPanorama() {
        assertEquals(0.0f, PanoramaStitcher.calculateProgress(0f, is360 = false), 0.001f)
        assertEquals(0.25f, PanoramaStitcher.calculateProgress(45f, is360 = false), 0.001f)
        assertEquals(0.5f, PanoramaStitcher.calculateProgress(90f, is360 = false), 0.001f)
        assertEquals(1.0f, PanoramaStitcher.calculateProgress(180f, is360 = false), 0.001f)
        // Coerced at 1.0f beyond 180°
        assertEquals(1.0f, PanoramaStitcher.calculateProgress(210f, is360 = false), 0.001f)
    }

    @Test
    fun testProgressCalculation360PhotoSphere() {
        assertEquals(0.0f, PanoramaStitcher.calculateProgress(0f, is360 = true), 0.001f)
        assertEquals(0.25f, PanoramaStitcher.calculateProgress(90f, is360 = true), 0.001f)
        assertEquals(0.5f, PanoramaStitcher.calculateProgress(180f, is360 = true), 0.001f)
        assertEquals(0.75f, PanoramaStitcher.calculateProgress(270f, is360 = true), 0.001f)
        assertEquals(1.0f, PanoramaStitcher.calculateProgress(360f, is360 = true), 0.001f)
        // Coerced at 1.0f beyond 360°
        assertEquals(1.0f, PanoramaStitcher.calculateProgress(390f, is360 = true), 0.001f)
    }

    @Test
    fun testGPanoXmlGeneration() {
        val xml = PanoramaStitcher.buildGPanoXml(4096, 2048)
        assertNotNull(xml)
        assertTrue(xml.contains("<GPano:UsePanoramaViewer>True</GPano:UsePanoramaViewer>"))
        assertTrue(xml.contains("<GPano:ProjectionType>equirectangular</GPano:ProjectionType>"))
        assertTrue(xml.contains("<GPano:FullPanoWidthPixels>4096</GPano:FullPanoWidthPixels>"))
        assertTrue(xml.contains("<GPano:FullPanoHeightPixels>2048</GPano:FullPanoHeightPixels>"))
        assertTrue(xml.contains("<GPano:CroppedAreaImageWidthPixels>4096</GPano:CroppedAreaImageWidthPixels>"))
        assertTrue(xml.contains("<GPano:CroppedAreaImageHeightPixels>2048</GPano:CroppedAreaImageHeightPixels>"))
        assertTrue(xml.contains("http://ns.google.com/photos/1.0/panorama/"))
    }

    @Test
    fun testGPhotoSphereXmpInjectionIntoJpeg() {
        // Mock minimal JPEG stream starting with SOI (0xFF, 0xD8) and mock tail
        val mockJpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xDB.toByte(), 0x00.toByte(), 0x04.toByte(), 0x01.toByte(), 0x02.toByte(),
            0xFF.toByte(), 0xD9.toByte()
        )

        val injected = PanoramaStitcher.injectGPhotoSphereXmp(mockJpeg, 1920, 960)
        assertNotNull(injected)
        assertTrue(injected.size > mockJpeg.size)

        // Verify SOI preserved at byte 0 and 1
        assertEquals(0xFF.toByte(), injected[0])
        assertEquals(0xD8.toByte(), injected[1])

        // Verify APP1 marker inserted at byte 2 and 3
        assertEquals(0xFF.toByte(), injected[2])
        assertEquals(0xE1.toByte(), injected[3])

        // Convert byte array to string to verify XMP presence
        val injectedStr = String(injected, Charsets.ISO_8859_1)
        assertTrue(injectedStr.contains("http://ns.adobe.com/xap/1.0/"))
        assertTrue(injectedStr.contains("GPano:UsePanoramaViewer"))
        assertTrue(injectedStr.contains("equirectangular"))
    }

    @Test
    fun testProcessingJobQueuePanoramaType() = runBlocking {
        val queue = ProcessingJobQueue.instance
        queue.resumeAll()

        val job = queue.enqueueJob(
            title = "Test Pano Stitching",
            type = ProcessingJobType.PANORAMA_STITCHING,
            priority = JobPriority.HIGH
        ) { updateProgress ->
            updateProgress(0.5f, "Aligning slices")
            delay(50)
            updateProgress(1.0f, "Stitching complete")
        }

        assertEquals(ProcessingJobType.PANORAMA_STITCHING, job.type)
        assertEquals("Panorama & 360 Stitching", job.type.displayName)
        assertEquals("PANO", job.type.shortCode)

        delay(200)
        val finishedJob = queue.jobs.value.find { it.id == job.id }
        assertNotNull(finishedJob)
        assertEquals(JobStatus.COMPLETED, finishedJob?.status)
    }
}
