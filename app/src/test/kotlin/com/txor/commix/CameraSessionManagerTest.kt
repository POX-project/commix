package com.txor.commix

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CameraSessionManagerTest {

    private lateinit var sessionManager: CameraSessionManager

    @Before
    fun setUp() {
        sessionManager = CameraSessionManager()
    }

    @Test
    fun testInitialStateIsClosed() {
        assertEquals(CameraState.CLOSED, sessionManager.state)
    }

    @Test
    fun testStandardCameraLifecycleTransitions() {
        // CLOSED -> OPENING
        assertTrue(sessionManager.transitionTo(CameraState.OPENING))
        assertEquals(CameraState.OPENING, sessionManager.state)

        // OPENING -> OPENED
        assertTrue(sessionManager.transitionTo(CameraState.OPENED))
        assertEquals(CameraState.OPENED, sessionManager.state)

        // OPENED -> CONFIGURING
        assertTrue(sessionManager.transitionTo(CameraState.CONFIGURING))
        assertEquals(CameraState.CONFIGURING, sessionManager.state)

        // CONFIGURING -> ACTIVE_PREVIEW
        assertTrue(sessionManager.transitionTo(CameraState.ACTIVE_PREVIEW))
        assertEquals(CameraState.ACTIVE_PREVIEW, sessionManager.state)
    }

    @Test
    fun testStillCaptureTransitions() {
        sessionManager.forceState(CameraState.ACTIVE_PREVIEW)

        // ACTIVE_PREVIEW -> CAPTURING_STILL
        assertTrue(sessionManager.transitionTo(CameraState.CAPTURING_STILL))
        assertEquals(CameraState.CAPTURING_STILL, sessionManager.state)

        // CAPTURING_STILL -> ACTIVE_PREVIEW
        assertTrue(sessionManager.transitionTo(CameraState.ACTIVE_PREVIEW))
        assertEquals(CameraState.ACTIVE_PREVIEW, sessionManager.state)
    }

    @Test
    fun testVideoRecordingTransitions() {
        sessionManager.forceState(CameraState.ACTIVE_PREVIEW)

        // ACTIVE_PREVIEW -> RECORDING_VIDEO
        assertTrue(sessionManager.transitionTo(CameraState.RECORDING_VIDEO))
        assertEquals(CameraState.RECORDING_VIDEO, sessionManager.state)

        // RECORDING_VIDEO -> CONFIGURING (when stopping and restoring preview session)
        assertTrue(sessionManager.transitionTo(CameraState.CONFIGURING))
        assertEquals(CameraState.CONFIGURING, sessionManager.state)

        // CONFIGURING -> ACTIVE_PREVIEW
        assertTrue(sessionManager.transitionTo(CameraState.ACTIVE_PREVIEW))
        assertEquals(CameraState.ACTIVE_PREVIEW, sessionManager.state)
    }

    @Test
    fun testDirectVideoConfiguringTransitions() {
        sessionManager.forceState(CameraState.CONFIGURING)

        // CONFIGURING -> RECORDING_VIDEO (when configuring video recording session)
        assertTrue(sessionManager.transitionTo(CameraState.RECORDING_VIDEO))
        assertEquals(CameraState.RECORDING_VIDEO, sessionManager.state)
    }

    @Test
    fun testSurfaceDestructionTransitions() {
        sessionManager.forceState(CameraState.ACTIVE_PREVIEW)

        // ACTIVE_PREVIEW -> OPENED (when preview surface is destroyed)
        assertTrue(sessionManager.transitionTo(CameraState.OPENED))
        assertEquals(CameraState.OPENED, sessionManager.state)

        // OPENED -> CONFIGURING (when new surface is provided)
        assertTrue(sessionManager.transitionTo(CameraState.CONFIGURING))
        assertEquals(CameraState.CONFIGURING, sessionManager.state)
    }

    @Test
    fun testIllegalTransitionsAreBlocked() {
        // CLOSED -> ACTIVE_PREVIEW is illegal
        assertFalse(sessionManager.transitionTo(CameraState.ACTIVE_PREVIEW))
        assertEquals(CameraState.CLOSED, sessionManager.state)

        // CLOSED -> CAPTURING_STILL is illegal
        assertFalse(sessionManager.transitionTo(CameraState.CAPTURING_STILL))
        assertEquals(CameraState.CLOSED, sessionManager.state)

        // CLOSED -> RECORDING_VIDEO is illegal
        assertFalse(sessionManager.transitionTo(CameraState.RECORDING_VIDEO))
        assertEquals(CameraState.CLOSED, sessionManager.state)

        // OPENING -> ACTIVE_PREVIEW is illegal (must go through OPENED)
        sessionManager.forceState(CameraState.OPENING)
        assertFalse(sessionManager.transitionTo(CameraState.ACTIVE_PREVIEW))
        assertEquals(CameraState.OPENING, sessionManager.state)

        // RECORDING_VIDEO -> CAPTURING_STILL is illegal
        sessionManager.forceState(CameraState.RECORDING_VIDEO)
        assertFalse(sessionManager.transitionTo(CameraState.CAPTURING_STILL))
        assertEquals(CameraState.RECORDING_VIDEO, sessionManager.state)
    }

    @Test
    fun testTransitionToErrorOrClosedAlwaysAllowed() {
        sessionManager.forceState(CameraState.ACTIVE_PREVIEW)
        assertTrue(sessionManager.transitionTo(CameraState.ERROR))
        assertEquals(CameraState.ERROR, sessionManager.state)

        sessionManager.forceState(CameraState.RECORDING_VIDEO)
        assertTrue(sessionManager.transitionTo(CameraState.CLOSED))
        assertEquals(CameraState.CLOSED, sessionManager.state)
    }

    @Test
    fun testReleaseLifecycle() {
        sessionManager.forceState(CameraState.ACTIVE_PREVIEW)
        sessionManager.activeCameraId = "0"
        sessionManager.release()

        assertEquals(CameraState.CLOSED, sessionManager.state)
        assertNull(sessionManager.activeCameraId)
        assertNull(sessionManager.activeDevice)
        assertNull(sessionManager.activeSession)
    }
}
