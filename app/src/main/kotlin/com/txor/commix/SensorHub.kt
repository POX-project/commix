package com.txor.commix

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.atan2
import kotlin.math.sqrt

data class ImuSample(
    val timestampNs: Long,
    val gyroX: Float,
    val gyroY: Float,
    val gyroZ: Float,
    val accelX: Float,
    val accelY: Float,
    val accelZ: Float
)

/**
 * SensorHub manages 6-axis IMU sampling synchronized to Camera2 frame timestamps,
 * providing data for real-time electronic stabilization and Gyroflow motion logging.
 */
class SensorHub(private val context: Context, private val onOrientationChanged: (Map<String, Double>) -> Unit) {
    private val TAG = "CommixSensorHub"
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private var rotationListener: SensorEventListener? = null
    private var imuListener: SensorEventListener? = null
    private var lastOrientationMs = 0L

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    // Current synchronized values
    @Volatile var currentPitchDeg = 0.0
    @Volatile var currentRollDeg = 0.0
    @Volatile var currentAzimuthDeg = 0.0

    // Gyroflow logging ring buffer
    val imuLogQueue = ConcurrentLinkedQueue<ImuSample>()
    private val queueSize = AtomicInteger(0)
    @Volatile var isLoggingImu = false

    private var lastGyroX = 0f
    private var lastGyroY = 0f
    private var lastGyroZ = 0f
    private var lastAccelX = 0f
    private var lastAccelY = 0f
    private var lastAccelZ = 0f

    fun startOrientationUpdates(): Boolean {
        val sm = sensorManager ?: return false
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: return false

        rotationListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null) return
                val now = System.currentTimeMillis()
                if (now - lastOrientationMs < 16) return
                lastOrientationMs = now

                if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    val az = Math.toDegrees(orientationAngles[0].toDouble())
                    val pi = Math.toDegrees(orientationAngles[1].toDouble())
                    val ro = Math.toDegrees(orientationAngles[2].toDouble())
                    currentAzimuthDeg = az
                    currentPitchDeg = pi
                    currentRollDeg = ro
                    onOrientationChanged(mapOf("pitch" to pi, "roll" to ro, "azimuth" to az))
                } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER || event.sensor.type == Sensor.TYPE_GRAVITY) {
                    val ax = event.values[0]
                    val ay = event.values[1]
                    val az = event.values[2]
                    val pi = Math.toDegrees(atan2(ay.toDouble(), sqrt(ax * ax + az * az).toDouble()))
                    val ro = Math.toDegrees(atan2(-ax.toDouble(), az.toDouble()))
                    currentPitchDeg = pi
                    currentRollDeg = ro
                    onOrientationChanged(mapOf("pitch" to pi, "roll" to ro, "azimuth" to 0.0))
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        return try {
            sm.registerListener(rotationListener, sensor, SensorManager.SENSOR_DELAY_GAME)
        } catch (_: Exception) {
            false
        }
    }

    fun stopOrientationUpdates() {
        rotationListener?.let { sensorManager?.unregisterListener(it) }
        rotationListener = null
    }

    fun startImuLogging() {
        imuLogQueue.clear()
        queueSize.set(0)
        isLoggingImu = true
        val sm = sensorManager ?: return
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        imuListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null || !isLoggingImu) return
                if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
                    lastGyroX = event.values[0]
                    lastGyroY = event.values[1]
                    lastGyroZ = event.values[2]
                    val sample = ImuSample(
                        timestampNs = event.timestamp,
                        gyroX = lastGyroX,
                        gyroY = lastGyroY,
                        gyroZ = lastGyroZ,
                        accelX = lastAccelX,
                        accelY = lastAccelY,
                        accelZ = lastAccelZ
                    )
                    imuLogQueue.add(sample)
                    if (queueSize.incrementAndGet() > 30000) {
                        if (imuLogQueue.poll() != null) {
                            queueSize.decrementAndGet()
                        }
                    }
                } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                    lastAccelX = event.values[0]
                    lastAccelY = event.values[1]
                    lastAccelZ = event.values[2]
                    val sample = ImuSample(
                        timestampNs = event.timestamp,
                        gyroX = lastGyroX,
                        gyroY = lastGyroY,
                        gyroZ = lastGyroZ,
                        accelX = lastAccelX,
                        accelY = lastAccelY,
                        accelZ = lastAccelZ
                    )
                    imuLogQueue.add(sample)
                    if (queueSize.incrementAndGet() > 30000) {
                        if (imuLogQueue.poll() != null) {
                            queueSize.decrementAndGet()
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        try {
            gyro?.let { sm.registerListener(imuListener, it, SensorManager.SENSOR_DELAY_FASTEST) }
        } catch (e: SecurityException) {
            try {
                gyro?.let { sm.registerListener(imuListener, it, SensorManager.SENSOR_DELAY_GAME) }
            } catch (_: Exception) {}
        } catch (_: Exception) {}

        try {
            accel?.let { sm.registerListener(imuListener, it, SensorManager.SENSOR_DELAY_FASTEST) }
        } catch (e: SecurityException) {
            try {
                accel?.let { sm.registerListener(imuListener, it, SensorManager.SENSOR_DELAY_GAME) }
            } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    fun stopImuLogging(): List<ImuSample> {
        isLoggingImu = false
        imuListener?.let { sensorManager?.unregisterListener(it) }
        imuListener = null
        val samples = imuLogQueue.toList()
        imuLogQueue.clear()
        queueSize.set(0)
        return samples
    }

    fun exportGyroflowGcsv(file: File, samples: List<ImuSample>) {
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { writer ->
            writer.write("GYROFLOW V1\n")
            writer.write("t,gx,gy,gz,ax,ay,az\n")
            val baseTs = if (samples.isNotEmpty()) samples.first().timestampNs else 0L
            for (s in samples) {
                val relTimeMs = (s.timestampNs - baseTs) / 1_000_000.0
                writer.write(
                    String.format(
                        java.util.Locale.US,
                        "%.3f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f\n",
                        relTimeMs, s.gyroX, s.gyroY, s.gyroZ, s.accelX, s.accelY, s.accelZ
                    )
                )
            }
        }
    }
}
