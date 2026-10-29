package com.txor.commix

import com.txor.commix.CaptureRequestFactory.AeMode
import com.txor.commix.CaptureRequestFactory.ManualExposure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ManualExposurePlanTest {

    private fun resolve(
        iso: Int = 0,
        expNs: Long = 0L,
        flash: String = "off",
        sensitivity: IntRange? = null,
        exposure: LongRange? = null,
        lastIso: Int = 0,
        lastExp: Long = 0L
    ) = CaptureRequestFactory.resolveExposure(
        CaptureRequestParams(
            iso = iso, expNs = expNs, flashMode = flash,
            sensitivityRange = sensitivity, exposureTimeRangeNs = exposure,
            lastAutoIso = lastIso, lastAutoExposureTimeNs = lastExp
        )
    )

    @Test
    fun neitherManualIsAutoAeWithEv() {
        val plan = resolve()
        assertEquals(AeMode.ON, plan.aeMode)
        assertEquals(ManualExposure.NONE, plan.manual)
        assertNull(plan.sensitivity)
        assertNull(plan.exposureTimeNs)
    }

    @Test
    fun isoOnlyIsHonouredAndKeepsTheLastAeShutter() {
        val plan = resolve(iso = 800, lastExp = 8_000_000L, lastIso = 100)
        assertEquals(AeMode.OFF, plan.aeMode)
        assertEquals(ManualExposure.ISO_ONLY, plan.manual)
        assertEquals(800, plan.sensitivity)          // the ISO dial now actually does something
        assertEquals(8_000_000L, plan.exposureTimeNs)
        assertNull(plan.warning)
    }

    @Test
    fun shutterOnlyIsHonouredAndKeepsTheLastAeIso() {
        val plan = resolve(expNs = 20_000_000L, lastIso = 400, lastExp = 30_000_000L)
        assertEquals(AeMode.OFF, plan.aeMode)
        assertEquals(ManualExposure.SHUTTER_ONLY, plan.manual)
        assertEquals(20_000_000L, plan.exposureTimeNs)
        assertEquals(400, plan.sensitivity)
    }

    @Test
    fun bothManualSetsBothAndNoEv() {
        val plan = resolve(iso = 6400, expNs = 1_000_000_000L)
        assertEquals(ManualExposure.ISO_AND_SHUTTER, plan.manual)
        assertEquals(6400, plan.sensitivity)
        assertEquals(1_000_000_000L, plan.exposureTimeNs)
        assertNull(plan.evIndex)                     // EV is meaningless with AE off
        assertEquals(1_000_000_000L, plan.frameDurationNs) // frame duration must not beat exposure
    }

    @Test
    fun oneSidedManualFallsBackToTemplateDefaultsBeforeTheFirst3aResult() {
        val plan = resolve(iso = 200)
        assertEquals(200, plan.sensitivity)
        assertEquals(33_333_333L, plan.exposureTimeNs)
    }

    @Test
    fun outOfRangeValuesAreClampedAndReported() {
        val plan = resolve(iso = 999_999, expNs = 1L,
            sensitivity = 100..3200, exposure = 100_000L..1_200_000_000L)
        assertEquals(3200, plan.sensitivity)
        assertEquals(100_000L, plan.exposureTimeNs)
        assertNotNull(plan.warning)                   // never silently dropped
    }

    @Test
    fun flashOwnsAeAndTheOverrideIsReported() {
        val plan = resolve(iso = 800, expNs = 20_000_000L, flash = "on")
        assertEquals(AeMode.UNSET, plan.aeMode)
        assertNull(plan.sensitivity)
        assertNotNull(plan.warning)
    }

    @Test
    fun torchLeavesAeToTheFlashBlockWithoutWarningWhenNothingIsManual() {
        assertNull(resolve(flash = "torch").warning)
    }
}
