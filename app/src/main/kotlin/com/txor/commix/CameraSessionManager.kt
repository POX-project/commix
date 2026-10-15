package com.txor.commix

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.util.Log
import java.util.concurrent.atomic.AtomicReference

enum class CameraState {
    CLOSED,
    OPENING,
    OPENED,
    CONFIGURING,
    ACTIVE_PREVIEW,
    CAPTURING_STILL,
    RECORDING_VIDEO,
    CLOSING,
    ERROR
}

/**
 * Encapsulates Camera2 session lifecycle as a formal state machine.
 * Prevents race conditions during asynchronous camera open, configure, and close cycles.
 */
class CameraSessionManager {
    companion object {
        private const val TAG = "CameraSessionManager"
    }

    private val currentState = AtomicReference(CameraState.CLOSED)
    var activeCameraId: String? = null
    var activeDevice: CameraDevice? = null
    var activeSession: CameraCaptureSession? = null

    val state: CameraState
        get() = currentState.get()

    /**
     * Transition to target state if legal, returning true if transitioned.
     */
    fun transitionTo(newState: CameraState): Boolean {
        while (true) {
            val oldState = currentState.get()
            if (!isValidTransition(oldState, newState)) {
                Log.w(TAG, "Illegal state transition attempted: $oldState -> $newState")
                return false
            }
            if (currentState.compareAndSet(oldState, newState)) {
                Log.d(TAG, "State transition: $oldState -> $newState")
                return true
            }
        }
    }

    fun forceState(newState: CameraState) {
        val old = currentState.getAndSet(newState)
        Log.d(TAG, "Force state transition: $old -> $newState")
    }

    private fun isValidTransition(from: CameraState, to: CameraState): Boolean {
        if (to == CameraState.ERROR || to == CameraState.CLOSED) return true
        return when (from) {
            CameraState.CLOSED -> to == CameraState.OPENING
            CameraState.OPENING -> to == CameraState.OPENED || to == CameraState.CLOSED
            CameraState.OPENED -> to == CameraState.CONFIGURING || to == CameraState.CLOSING
            CameraState.CONFIGURING -> to == CameraState.ACTIVE_PREVIEW || to == CameraState.RECORDING_VIDEO || to == CameraState.CLOSING
            CameraState.ACTIVE_PREVIEW -> to == CameraState.CAPTURING_STILL || to == CameraState.RECORDING_VIDEO || to == CameraState.CONFIGURING || to == CameraState.OPENED || to == CameraState.CLOSING
            CameraState.CAPTURING_STILL -> to == CameraState.ACTIVE_PREVIEW || to == CameraState.CONFIGURING || to == CameraState.CLOSING || to == CameraState.CAPTURING_STILL || to == CameraState.RECORDING_VIDEO
            CameraState.RECORDING_VIDEO -> to == CameraState.ACTIVE_PREVIEW || to == CameraState.CONFIGURING || to == CameraState.CLOSING || to == CameraState.CAPTURING_STILL
            CameraState.CLOSING -> to == CameraState.CLOSED
            CameraState.ERROR -> to == CameraState.CLOSING || to == CameraState.CLOSED
        }
    }

    fun release() {
        forceState(CameraState.CLOSING)
        try {
            activeSession?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing session: ${e.message}")
        } finally {
            activeSession = null
        }

        try {
            activeDevice?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing device: ${e.message}")
        } finally {
            activeDevice = null
            activeCameraId = null
            forceState(CameraState.CLOSED)
        }
    }
}
