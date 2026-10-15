package com.txor.commix

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Manages device thermal envelope, ADPF performance hints, and thermal throttling callbacks.
 * Conforms to Android 10+ (API 29) Thermal APIs and Android 11+ (API 30) Headroom APIs.
 */
class ThermalManager(private val context: Context) {
    companion object {
        private const val TAG = "ThermalManager"
    }

    private val powerManager: PowerManager? by lazy {
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }

    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    var onThermalStateChanged: ((statusName: String, statusCode: Int) -> Unit)? = null

    /**
     * Start listening to OS thermal state changes
     */
    fun startMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
                    val statusName = when (status) {
                        PowerManager.THERMAL_STATUS_NONE -> "none"
                        PowerManager.THERMAL_STATUS_LIGHT -> "light"
                        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
                        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
                        else -> "unknown"
                    }
                    Log.i(TAG, "Thermal status changed: $statusName ($status)")
                    onThermalStateChanged?.invoke(statusName, status)
                }
                powerManager?.addThermalStatusListener(thermalListener!!)
                Log.d(TAG, "Thermal monitoring registered successfully.")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register thermal status listener: ${e.message}")
            }
        }
    }

    /**
     * Stop thermal monitoring
     */
    fun stopMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
            try {
                powerManager?.removeThermalStatusListener(thermalListener!!)
                thermalListener = null
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister thermal status listener: ${e.message}")
            }
        }
    }

    /**
     * Returns thermal headroom: 1.0 means severe throttling threshold reached.
     * Returns Float.NaN if unsupported.
     */
    fun getThermalHeadroom(forecastSeconds: Int = 10): Float {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                return powerManager?.getThermalHeadroom(forecastSeconds) ?: Float.NaN
            } catch (e: Exception) {
                Log.w(TAG, "getThermalHeadroom failed: ${e.message}")
            }
        }
        return Float.NaN
    }

    /**
     * Current thermal status code (0 = NONE to 6 = SHUTDOWN)
     */
    fun getCurrentThermalStatus(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
            } catch (e: Exception) {
                Log.w(TAG, "getCurrentThermalStatus failed: ${e.message}")
            }
        }
        return 0
    }
}
