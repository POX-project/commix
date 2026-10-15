package com.txor.commix

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class VideoFormatEngineTest {

    @Test
    fun testBroadcastFpsStandardsListCompleteness() {
        val standards = BroadcastFpsStandard.standards
        assertEquals(13, standards.size)

        // Verify key cinema, broadcast, and HFR standards
        assertEquals("23.98", standards[0].label)
        assertEquals(24, standards[0].integerEquivalent)
        assertEquals(41708333L, standards[0].frameDurationNs)

        assertEquals("24", standards[1].label)
        assertEquals(24.0, standards[1].fps, 0.0001)

        assertEquals("25", standards[2].label)
        assertEquals(25.0, standards[2].fps, 0.0001)

        assertEquals("29.97", standards[3].label)
        assertEquals(30, standards[3].integerEquivalent)

        assertEquals("30", standards[4].label)
        assertEquals(30.0, standards[4].fps, 0.0001)

        assertEquals("59.94", standards[7].label)
        assertEquals(60, standards[7].integerEquivalent)

        assertEquals("60", standards[8].label)
        assertEquals(60.0, standards[8].fps, 0.0001)

        assertEquals("120", standards[9].label)
        assertEquals(120.0, standards[9].fps, 0.0001)

        assertEquals("240", standards[10].label)
        assertEquals(240.0, standards[10].fps, 0.0001)

        assertEquals("480", standards[11].label)
        assertEquals(480.0, standards[11].fps, 0.0001)

        assertEquals("960", standards[12].label)
        assertEquals(960.0, standards[12].fps, 0.0001)
    }

    @Test
    fun testFindClosestStandard() {
        val s24 = BroadcastFpsStandard.findClosestStandard(23.95)
        assertNotNull(s24)
        assertEquals("23.98", s24?.label)

        val s60 = BroadcastFpsStandard.findClosestStandard(59.9)
        assertNotNull(s60)
        assertEquals("59.94", s60?.label)

        // With strict tolerance
        val strictMatch = BroadcastFpsStandard.findClosestStandard(40.0, tolerance = 0.5)
        assertNull("40.0 fps is not near any broadcast standard with 0.5 tolerance", strictMatch)
    }

    @Test
    fun testMagneticFpsSnapperSnapsWithinThreshold() {
        // 24.3 is within 0.85 of 24.0
        val snapResult24 = MagneticFpsSnapper.snap(24.3, snapThreshold = 0.85)
        assertTrue(snapResult24.didSnap)
        assertEquals(24.0, snapResult24.fps, 0.001)
        assertEquals("24", snapResult24.matchedStandard?.label)

        // 29.8 is within 0.85 of 29.97002997
        val snapResult30 = MagneticFpsSnapper.snap(29.8, snapThreshold = 0.85)
        assertTrue(snapResult30.didSnap)
        assertEquals(29.97002997, snapResult30.fps, 0.001)

        // 35.0 is far away from both 30.0 and 48.0 -> should not snap
        val noSnap = MagneticFpsSnapper.snap(35.0, snapThreshold = 0.85)
        assertFalse(noSnap.didSnap)
        assertEquals(35.0, noSnap.fps, 0.001)
        assertNull(noSnap.matchedStandard)
    }

    @Test
    fun testCalculateSensorMaxFps() {
        // 33333333 ns corresponds to ~30 FPS
        val fps30 = MagneticFpsSnapper.calculateSensorMaxFps(33333333L)
        assertEquals(30.0, fps30, 0.01)

        // 16666667 ns corresponds to ~60 FPS
        val fps60 = MagneticFpsSnapper.calculateSensorMaxFps(16666667L)
        assertEquals(60.0, fps60, 0.01)

        // 0 ns fallback
        val fallback = MagneticFpsSnapper.calculateSensorMaxFps(0L)
        assertEquals(30.0, fallback, 0.001)
    }

    @Test
    fun testVideoQualityProfilesAndStorageCalculations() {
        val eco = VideoQualityProfile.profiles.find { it.label == "Broadcast Eco" }
        assertNotNull(eco)
        assertEquals(25_000_000, eco?.bitrateBps)

        // 25 Mbps = 25,000,000 / 8 bytes/sec * 60 sec / (1024 * 1024) = ~178.81 MB/min
        val mbPerMin = eco?.megaBytesPerMinute ?: 0.0
        assertTrue("Expected ~178 MB/min, got $mbPerMin", mbPerMin in 170.0..185.0)

        // 10 minutes estimation
        val tenMin = eco?.estimateStorageMb(10) ?: 0
        assertTrue(tenMin in 1700..1850)

        // Snapping from bitrate
        val snapped = VideoQualityProfile.fromBitrate(48_000_000)
        assertEquals("High Quality", snapped.label)
        assertEquals(50_000_000, snapped.bitrateBps)
    }

    @Test
    fun testResolutionParsing() {
        val r4k = PipelineThroughputEngine.parseResolution("3840x2160")
        assertEquals(3840, r4k.first)
        assertEquals(2160, r4k.second)

        val r1080 = PipelineThroughputEngine.parseResolution("1080p (FHD)")
        assertEquals(1920, r1080.first)
        assertEquals(1080, r1080.second)

        val r720 = PipelineThroughputEngine.parseResolution("720p HD")
        assertEquals(1280, r720.first)
        assertEquals(720, r720.second)

        val rCustom = PipelineThroughputEngine.parseResolution("2560x1440")
        assertEquals(2560, rCustom.first)
        assertEquals(1440, rCustom.second)
    }

    @Test
    fun testPipelineThroughputOptimalZone() {
        // Standard 1080p @ 30fps is well within standard dual-stream limits (380 Mpx/s)
        val eval = PipelineThroughputEngine.evaluate("1920x1080", 30.0)
        assertEquals(PipelineZone.OPTIMAL, eval.zone)
        assertTrue(eval.currentMegaPixelsPerSecond < eval.maxDualStreamMpxPerSecond)
        assertFalse(eval.requiresBlindVf)
    }

    @Test
    fun testPipelineThroughputUnsupported4kHighFps() {
        // 4K @ 60fps on a hardware capped at 30fps should report UNSUPPORTED
        val eval = PipelineThroughputEngine.evaluate(
            resolution = "3840x2160",
            fps = 60.0,
            hardwareMaxFps = 30.0
        )
        assertEquals(PipelineZone.UNSUPPORTED, eval.zone)
        assertFalse(eval.canWorkWithVfDisabled)
        assertTrue(eval.overloadPercentage >= 100.0)
    }

    @Test
    fun testPipelineThroughputBlindVfWorkMitigation() {
        // Scenario where total dual-stream throughput exceeds 380 Mpx/s,
        // but single-stream recording throughput is below 550 Mpx/s
        val eval = PipelineThroughputEngine.evaluate(
            resolution = "1920x1080",
            fps = 160.0,
            hardwareMaxFps = 240.0,
            isVfDisabled = false,
            maxDualStreamMpx = 380.0,
            maxSingleStreamMpx = 550.0
        )
        assertEquals(PipelineZone.BLIND_VF_WORK, eval.zone)
        assertTrue(eval.canWorkWithVfDisabled)
        assertTrue(eval.requiresBlindVf)

        // When blind viewfinder is engaged (isVfDisabled = true), current Mpx drops
        val evalBlind = PipelineThroughputEngine.evaluate(
            resolution = "1920x1080",
            fps = 160.0,
            hardwareMaxFps = 240.0,
            isVfDisabled = true,
            maxDualStreamMpx = 380.0,
            maxSingleStreamMpx = 550.0
        )
        assertTrue(evalBlind.currentMegaPixelsPerSecond < eval.currentMegaPixelsPerSecond)
    }

    @Test
    fun testGetMaxFpsForResolutionAlgorithmic() {
        val fps4k = PipelineThroughputEngine.getMaxFpsForResolution("3840x2160", isVfDisabled = false)
        assertTrue("Expected 4K max between 30 and 40 FPS, got $fps4k", fps4k in 30.0..40.0)

        // With Viewfinder Disabled, 100% of single-stream ISP is unlocked
        val fps4kSolo = PipelineThroughputEngine.getMaxFpsForResolution("3840x2160", isVfDisabled = true)
        assertTrue("Expected 4K solo pass between 60 and 70 FPS, got $fps4kSolo", fps4kSolo in 60.0..70.0)

        val fps1080 = PipelineThroughputEngine.getMaxFpsForResolution("1920x1080", isVfDisabled = false)
        assertTrue("Expected 1080p max >= 120 FPS, got $fps1080", fps1080 >= 120.0)

        val fps720 = PipelineThroughputEngine.getMaxFpsForResolution("1280x720", isVfDisabled = false)
        assertTrue("Expected 720p max >= 240 FPS, got $fps720", fps720 >= 240.0)

        // Custom sensor duration (e.g. 16666667 ns = 60 fps)
        val fpsCustomSensor = PipelineThroughputEngine.getMaxFpsForResolution("1920x1080", sensorMinFrameDurationNs = 16666667L)
        assertEquals(60.0, fpsCustomSensor, 0.01)
    }

    @Test
    fun testResolutionDetailAndStandardsDetection() {
        val detail64 = PipelineThroughputEngine.getResolutionDetail("9248x6936")
        assertTrue(detail64.standardName.contains("64MP FULL SENSOR"))
        assertTrue(detail64.isRecognizedStandard)
        assertEquals(64.1, detail64.megapixels, 0.2)

        val detail4k = PipelineThroughputEngine.getResolutionDetail("3840x2160")
        assertEquals("4K UHD BROADCAST (16:9)", detail4k.standardName)
        assertTrue(detail4k.isRecognizedStandard)
        assertEquals(8.29, detail4k.megapixels, 0.05)

        val detail1080 = PipelineThroughputEngine.getResolutionDetail("1920x1080")
        assertEquals("1080p FULL HD (16:9)", detail1080.standardName)
        assertTrue(detail1080.isRecognizedStandard)

        val detail16 = PipelineThroughputEngine.getResolutionDetail("4624x3472")
        assertEquals("16MP 4:3 SENSOR BIN", detail16.standardName)
        assertTrue(detail16.isRecognizedStandard)
    }

    @Test
    fun testDynamicSensorCeilings64mpAnd16mp() {
        // Full sensor 64MP mode (9248x6936 = 64.1 Mpx) has a dynamic readout ceiling of 1.0 - 2.0 FPS
        val fps64mp = PipelineThroughputEngine.getMaxFpsForResolution("9248x6936", isVfDisabled = false)
        assertTrue("Expected 64MP limit between 1.0 and 3.0 FPS, got $fps64mp", fps64mp in 1.0..3.0)

        val fps64mpSolo = PipelineThroughputEngine.getMaxFpsForResolution("9248x6936", isVfDisabled = true)
        assertTrue("Expected 64MP solo limit between 1.5 and 3.0 FPS, got $fps64mpSolo", fps64mpSolo in 1.5..3.0)
        assertTrue("Solo pass should unlock equal or higher FPS than dual-stream", fps64mpSolo >= fps64mp)

        // Native 16MP binned sensor mode (4624x3472 = 16.0 Mpx)
        val fps16mp = PipelineThroughputEngine.getMaxFpsForResolution("4624x3472")
        assertTrue("Expected 16MP limit <= 24.0 FPS, got $fps16mp", fps16mp <= 24.0)
    }

    @Test
    fun testDynamicFpsChipsSelection() {
        // For ultra-high resolution (e.g. maxFps = 2.0), chips should offer 1.0, 1.5, 2.0
        val chips64mp = PipelineThroughputEngine.getDynamicFpsChipsForResolution(2.0)
        assertTrue(chips64mp.contains(1.0))
        assertTrue(chips64mp.contains(1.5))
        assertTrue(chips64mp.contains(2.0))
        assertFalse(chips64mp.contains(24.0))

        // For standard 1080p (maxFps = 240.0), chips should contain broadcast standards
        val chips1080p = PipelineThroughputEngine.getDynamicFpsChipsForResolution(240.0)
        assertTrue(chips1080p.contains(24.0))
        assertTrue(chips1080p.contains(30.0))
        assertTrue(chips1080p.contains(60.0))
    }

    @Test
    fun testGetStandardResolutionsOrderAndInclusion() {
        val list = PipelineThroughputEngine.getStandardResolutions(listOf("9248x6936", "4624x3472", "1920x1080", "640x480"))
        assertTrue(list.contains("9248x6936"))
        assertTrue(list.contains("4624x3472"))
        assertTrue(list.contains("3840x2160"))
        assertTrue(list.contains("1920x1080"))
        assertTrue(list.contains("1280x720"))
        assertTrue(list.contains("640x480"))
        // Sorted descending by pixel area: 64MP first, then 16MP, 4K, 1080p, 720p, 480p
        assertEquals("9248x6936", list[0])
        assertEquals("4624x3472", list[1])
        assertEquals("3840x2160", list[2])
        assertEquals("1920x1080", list[3])
        assertEquals("1280x720", list[4])
    }

    @Test
    fun testDeduplicationOfNearIdenticalResolutions() {
        // 4640x3472 (16.11 MP) and 4624x3472 (16.05 MP) are within 0.3% of each other
        val list = PipelineThroughputEngine.getStandardResolutions(listOf("4640x3472", "4624x3472", "1920x1080"))
        assertEquals(1, list.count { it.contains("3472") })
        assertTrue(list.contains("4640x3472") || list.contains("4624x3472"))
    }

    @Test
    fun testCalculateResolutionForMpAcrossFullRange() {
        // 64.1 MP full sensor mode (4:3)
        val (w64, h64) = PipelineThroughputEngine.calculateResolutionForMp(64.1)
        assertEquals(9248, w64)
        assertEquals(6936, h64) // Canonical 64MP Samsung GW1 full sensor mode

        // 16.0 MP binned mode (4:3)
        val (w16, h16) = PipelineThroughputEngine.calculateResolutionForMp(16.0)
        assertEquals(0, w16 % 16)
        assertEquals(0, h16 % 16)
        assertEquals(4624, w16)
        assertEquals(3472, h16)

        // 8.3 MP 4K UHD mode (16:9)
        val (w4k, h4k) = PipelineThroughputEngine.calculateResolutionForMp(8.3)
        assertEquals(0, w4k % 16)
        assertEquals(0, h4k % 16)
        assertEquals(3840, w4k)
        assertEquals(2160, h4k)

        // 2.07 MP 1080p FHD mode (16:9)
        val (w1080, h1080) = PipelineThroughputEngine.calculateResolutionForMp(2.07)
        assertEquals(1920, w1080)
        assertEquals(1080, h1080)

        // 0.92 MP 720p HD mode (16:9)
        val (w720, h720) = PipelineThroughputEngine.calculateResolutionForMp(0.92)
        assertEquals(0, w720 % 16)
        assertEquals(0, h720 % 16)
        assertEquals(1280, w720)
        assertEquals(720, h720)

        // Arbitrary float values between 16.1 MP and 64 MP (e.g. 24.5 MP, 36.0 MP, 48.0 MP)
        val testMpList = listOf(17.5, 20.0, 24.5, 30.0, 36.5, 42.0, 48.0, 50.0, 55.5)
        for (mp in testMpList) {
            val (w, h) = PipelineThroughputEngine.calculateResolutionForMp(mp)
            assertEquals("Width $w must be multiple of 16", 0, w % 16)
            assertEquals("Height $h must be multiple of 16", 0, h % 16)
            val actualMp = (w.toDouble() * h.toDouble()) / 1_000_000.0
            assertTrue("Calculated MP $actualMp must be within 1.5% of target $mp", abs(actualMp - mp) / mp < 0.015)
        }
    }

    @Test
    fun testContinuousAlgorithmicFpsFor64MpAndCustomMp() {
        // 64MP @ 9248x6936 should yield ~1.0 - 1.7 FPS
        val maxFpsWithVf = PipelineThroughputEngine.getMaxFpsForResolution("9248x6936", isVfDisabled = false)
        val maxFpsSoloPass = PipelineThroughputEngine.getMaxFpsForResolution("9248x6936", isVfDisabled = true)
        assertTrue(maxFpsWithVf in 1.0..1.2)
        assertTrue(maxFpsSoloPass in 1.5..1.8)
        assertTrue("Blind VF must unlock higher throughput than dual-stream", maxFpsSoloPass > maxFpsWithVf)

        // Custom 30MP mode (6320x4736)
        val customFpsWithVf = PipelineThroughputEngine.getMaxFpsForResolution("6320x4736", isVfDisabled = false)
        val customFpsSolo = PipelineThroughputEngine.getMaxFpsForResolution("6320x4736", isVfDisabled = true)
        assertTrue("Custom 30MP FPS must be >= 1.0", customFpsWithVf >= 1.0)
        assertTrue("Solo pass must be >= dual stream", customFpsSolo >= customFpsWithVf)
    }
}

