package com.txor.commix

import com.txor.commix.automation.AutomationEngine
import com.txor.commix.automation.KeyframeCurve
import com.txor.commix.automation.KeyframePoint
import com.txor.commix.automation.KeyframeTrack
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class KeyframeEngineAndAutomationTest {

    @Test
    fun testLinearInterpolation() {
        val track = KeyframeTrack(
            trackId = 0,
            points = listOf(
                KeyframePoint(0f, 1.0f, KeyframeCurve.Linear),
                KeyframePoint(10f, 3.0f, KeyframeCurve.Linear)
            )
        )
        // Midpoint at t=5s
        val mid = track.evaluate(5f)
        assertEquals(2.0f, mid, 0.001f)

        // Quarter at t=2.5s
        val quarter = track.evaluate(2.5f)
        assertEquals(1.5f, quarter, 0.001f)

        // Bounds clamping
        assertEquals(1.0f, track.evaluate(-1f), 0.001f)
        assertEquals(3.0f, track.evaluate(12f), 0.001f)
    }

    @Test
    fun testEaseInInterpolationStartsSlow() {
        val track = KeyframeTrack(
            trackId = 0,
            points = listOf(
                KeyframePoint(0f, 0f, KeyframeCurve.EaseIn),
                KeyframePoint(10f, 100f, KeyframeCurve.EaseIn)
            )
        )
        // At t=5 (u=0.5), factor = 0.25 -> value = 25
        val valAtMid = track.evaluate(5f)
        assertEquals(25.0f, valAtMid, 0.001f)
    }

    @Test
    fun testEaseOutInterpolationSlowsDownAtEnd() {
        val track = KeyframeTrack(
            trackId = 0,
            points = listOf(
                KeyframePoint(0f, 0f, KeyframeCurve.EaseOut),
                KeyframePoint(10f, 100f, KeyframeCurve.EaseOut)
            )
        )
        // At t=5 (u=0.5), factor = 1 - 0.25 = 0.75 -> value = 75
        val valAtMid = track.evaluate(5f)
        assertEquals(75.0f, valAtMid, 0.001f)
    }

    @Test
    fun testHoldCurveKeepsInitialValueUntilNextKeyframe() {
        val track = KeyframeTrack(
            trackId = 0,
            points = listOf(
                KeyframePoint(0f, 50f, KeyframeCurve.Hold),
                KeyframePoint(10f, 100f, KeyframeCurve.Hold)
            )
        )
        assertEquals(50f, track.evaluate(2f), 0.001f)
        assertEquals(50f, track.evaluate(5f), 0.001f)
        assertEquals(50f, track.evaluate(9.9f), 0.001f)
        assertEquals(100f, track.evaluate(10f), 0.001f)
    }

    @Test
    fun testAutomationAll5PresetsRegistered() {
        val presets = AutomationEngine.BUILT_IN_PRESETS
        assertEquals(5, presets.size)

        val ids = presets.map { it.id }.toSet()
        assertTrue(ids.contains("dolly_zoom"))
        assertTrue(ids.contains("sunrise_ramp"))
        assertTrue(ids.contains("golden_hour"))
        assertTrue(ids.contains("reveal_shot"))
        assertTrue(ids.contains("dramatic_rack"))

        val fallback = AutomationEngine.getPresetById("unknown_preset")
        assertEquals("dolly_zoom", fallback.id)
    }

    @Test
    fun testAutomationPresetDollyZoomCurves() {
        val preset = AutomationEngine.PRESET_DOLLY_ZOOM
        assertEquals(8.0f, preset.durationSeconds, 0.001f)

        // t=0s
        val v0 = preset.evaluate(0f)
        assertEquals(1.0, v0.zoom!!, 0.001)
        assertEquals(0.2f, v0.focusDiopters!!, 0.001f)

        // t=4s (midpoint of 8s EaseInOut: exactly halfway between 1.0 and 3.2 = 2.1)
        val vMid = preset.evaluate(4f)
        assertEquals(2.1, vMid.zoom!!, 0.01)
        assertEquals(0.5f, vMid.focusDiopters!!, 0.01f)

        // t=8s
        val vEnd = preset.evaluate(8f)
        assertEquals(3.2, vEnd.zoom!!, 0.001)
        assertEquals(0.8f, vEnd.focusDiopters!!, 0.001f)
    }

    @Test
    fun testAutomationPresetSunriseRampCurves() {
        val preset = AutomationEngine.PRESET_SUNRISE_RAMP
        assertEquals(20.0f, preset.durationSeconds, 0.001f)

        val v0 = preset.evaluate(0f)
        assertEquals(-1.5f, v0.ev!!, 0.001f)

        val vMid = preset.evaluate(10f)
        // Midpoint of EaseInOut between -1.5 and 1.0 = -0.25
        assertEquals(-0.25f, vMid.ev!!, 0.01f)

        val vEnd = preset.evaluate(20f)
        assertEquals(1.0f, vEnd.ev!!, 0.001f)
    }

    @Test
    fun testAutomationPresetGoldenHourWbDrift() {
        val preset = AutomationEngine.PRESET_GOLDEN_HOUR
        assertEquals(15.0f, preset.durationSeconds, 0.001f)

        val v0 = preset.evaluate(0f)
        assertEquals(3200, v0.wbKelvin!!)

        val vMid = preset.evaluate(7.5f)
        assertEquals(4400, vMid.wbKelvin!!)

        val vEnd = preset.evaluate(15f)
        assertEquals(5600, vEnd.wbKelvin!!)
    }

    @Test
    fun testAutomationPresetCinematicReveal() {
        val preset = AutomationEngine.PRESET_REVEAL_SHOT
        assertEquals(10.0f, preset.durationSeconds, 0.001f)

        val v0 = preset.evaluate(0f)
        assertEquals(4.0, v0.zoom!!, 0.001)
        assertEquals(0.1f, v0.focusDiopters!!, 0.001f)

        val vMid = preset.evaluate(5.0f)
        assertEquals(2.5, vMid.zoom!!, 0.01)
        assertEquals(0.5f, vMid.focusDiopters!!, 0.01f)

        val vEnd = preset.evaluate(10.0f)
        assertEquals(1.0, vEnd.zoom!!, 0.001)
        assertEquals(0.9f, vEnd.focusDiopters!!, 0.001f)
    }

    @Test
    fun testAutomationPresetDramaticRackFocusHold() {
        val preset = AutomationEngine.PRESET_DRAMATIC_RACK
        assertEquals(9.0f, preset.durationSeconds, 0.001f)

        // Hold segment 1: 0s to 3.5s stays at 0.2 D
        assertEquals(0.2f, preset.evaluate(0f).focusDiopters!!, 0.001f)
        assertEquals(0.2f, preset.evaluate(2.0f).focusDiopters!!, 0.001f)
        assertEquals(0.2f, preset.evaluate(3.5f).focusDiopters!!, 0.001f)

        // Transition segment: 3.5s to 5.5s pulls focus to 0.85 D
        val midTransition = preset.evaluate(4.5f).focusDiopters!!
        assertTrue("Mid transition should be between 0.2 and 0.85", midTransition > 0.2f && midTransition < 0.85f)
        assertEquals(0.525f, midTransition, 0.05f)

        // Hold segment 2: 5.5s to 9.0s stays at 0.85 D
        assertEquals(0.85f, preset.evaluate(5.5f).focusDiopters!!, 0.001f)
        assertEquals(0.85f, preset.evaluate(7.0f).focusDiopters!!, 0.001f)
        assertEquals(0.85f, preset.evaluate(9.0f).focusDiopters!!, 0.001f)
    }

    @Test
    fun testCustomMotionConfigCreation() {
        val custom = AutomationEngine.createCustomConfig(
            durationSec = 12.0,
            startZoom = 1.0,
            endZoom = 5.0,
            enableZoom = true,
            startEv = -1.0f,
            endEv = 1.0f,
            enableEv = true,
            startFocus = -1f,
            endFocus = -1f,
            enableFocus = false
        )

        assertEquals("custom_motion", custom.id)
        assertEquals(12.0f, custom.durationSeconds, 0.001f)
        assertNotNull(custom.zoomTrack)
        assertNotNull(custom.evTrack)
        assertNull(custom.focusTrack)

        val v0 = custom.evaluate(0f)
        assertEquals(1.0, v0.zoom!!, 0.001)
        assertEquals(-1.0f, v0.ev!!, 0.001f)
        assertNull(v0.focusDiopters)

        val vMid = custom.evaluate(6.0f)
        assertEquals(3.0, vMid.zoom!!, 0.01)
        assertEquals(0.0f, vMid.ev!!, 0.01f)

        val vEnd = custom.evaluate(12.0f)
        assertEquals(5.0, vEnd.zoom!!, 0.001)
        assertEquals(1.0f, vEnd.ev!!, 0.001f)
    }


    @Test
    fun testAll16ProControlTabsExist() {
        val expectedTabs = listOf(
            "ZOOM", "ISO", "SHUTTER", "ANGLE", "EV", "WB", "FOCUS", "CURVE",
            "GRAIN", "LUT", "PROCESS_ZERO", "AUDIO", "ND", "ANAMORPHIC", "AEB", "SCOPES"
        )
        val actualTabs = com.txor.commix.ui.components.ProControlTab.values().map { it.name }
        assertEquals(16, actualTabs.size)
        assertEquals(expectedTabs, actualTabs)
    }
}
