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
    private val droppedLogSamples = AtomicInteger(0)
    private var overflowWarned = false
    @Volatile var isLoggingImu = false

    private var lastGyroX = 0f
    private var lastGyroY = 0f
    private var lastGyroZ = 0f
    private var lastAccelX = 0f
    private var lastAccelY = 0f
    private var lastAccelZ = 0f
    private var hasAccelSample = false

    fun startOrientationUpdates(): Boolean {
        val sm = sensorManager ?: return false
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: return false

        rotationListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null) return

                if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
                    val gx = event.values[0]
                    val gy = event.values[1]
                    val gz = event.values[2]
                    if (com.txor.commix.bridge.NativeEngineBridge.isLoaded) {
                        com.txor.commix.bridge.NativeEngineBridge.nativePushBestShotVelocity(gx, gy, gz)
                    }
                    return
                }

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

        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (gyro != null) {
            try { sm.registerListener(rotationListener, gyro, SensorManager.SENSOR_DELAY_GAME) } catch (_: Exception) {}
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
        droppedLogSamples.set(0)
        overflowWarned = false
        hasAccelSample = false
        isLoggingImu = true

        val sm = sensorManager ?: return
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (gyro == null) {
            // Angular velocity is the Gyroflow spec's mandatory column; without a
            // gyroscope no usable .gcsv can be produced.
            Log.w(TAG, "Gyroflow logging unavailable: device has no TYPE_GYROSCOPE sensor")
        }

        imuListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null || !isLoggingImu) return
                when (event.sensor.type) {
                    Sensor.TYPE_GYROSCOPE -> {
                        lastGyroX = event.values[0]
                        lastGyroY = event.values[1]
                        lastGyroZ = event.values[2]
                        if (com.txor.commix.bridge.NativeEngineBridge.isLoaded) {
                            com.txor.commix.bridge.NativeEngineBridge
                                .nativePushBestShotVelocity(lastGyroX, lastGyroY, lastGyroZ)
                        }
                        // One log row per real gyroscope event: the row timestamp is
                        // the gyro sample's own hardware timestamp. The accelerometer
                        // triple rides along from the accelerometer stream below.
                        recordImuSample(event.timestamp)
                    }

                    Sensor.TYPE_ACCELEROMETER -> {
                        lastAccelX = event.values[0]
                        lastAccelY = event.values[1]
                        lastAccelZ = event.values[2]
                        hasAccelSample = true
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        try {
            gyro?.let { sm.registerListener(imuListener!!, it, SensorManager.SENSOR_DELAY_FASTEST) }
        } catch (e: SecurityException) {
            try {
                gyro?.let { sm.registerListener(imuListener!!, it, SensorManager.SENSOR_DELAY_GAME) }
            } catch (_: Exception) {}
        } catch (_: Exception) {}

        try {
            accel?.let { sm.registerListener(imuListener!!, it, SensorManager.SENSOR_DELAY_FASTEST) }
        } catch (e: SecurityException) {
            try {
                accel?.let { sm.registerListener(imuListener!!, it, SensorManager.SENSOR_DELAY_GAME) }
            } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    /**
     * Records one `.gcsv` row per gyroscope event. Accelerometer events only refresh
     * the latest accel triple, so rows never carry stale-halves double rows.
     */
    private fun recordImuSample(timestampNs: Long) {
        if (!hasAccelSample) {
            // No real accelerometer measurement yet — inventing values is not an
            // option, so the first microseconds are simply not logged.
            return
        }

        // Forward live sample to bare-metal C++20 stabilization ring buffer
        if (com.txor.commix.bridge.NativeEngineBridge.isLoaded) {
            com.txor.commix.bridge.NativeEngineBridge.nativePushImuSample(
                timestampNs,
                lastGyroX,
                lastGyroY,
                lastGyroZ,
                lastAccelX,
                lastAccelY,
                lastAccelZ
            )
        }

        val size = queueSize.get()
        if (size >= IMU_LOG_MAX_SAMPLES) {
            // Drop from the tail, never the head. The head holds the beginning of
            // the take needed for synchronization against the video start timestamp.
            if (droppedLogSamples.incrementAndGet() == 1 && !overflowWarned) {
                overflowWarned = true
                Log.w(TAG, "Gyroflow log buffer full ($IMU_LOG_MAX_SAMPLES samples); tail samples discarded to preserve video sync head")
            }
            return
        }

        val sample = ImuSample(
            timestampNs = timestampNs,
            gyroX = lastGyroX,
            gyroY = lastGyroY,
            gyroZ = lastGyroZ,
            accelX = lastAccelX,
            accelY = lastAccelY,
            accelZ = lastAccelZ
        )
        imuLogQueue.add(sample)
        queueSize.incrementAndGet()
    }

    fun stopImuLogging(): List<ImuSample> {
        isLoggingImu = false
        imuListener?.let { sensorManager?.unregisterListener(it) }
        imuListener = null
        val dropped = droppedLogSamples.get()
        if (dropped > 0) {
            Log.w(TAG, "Gyroflow log tail dropped $dropped samples due to capacity limit ($IMU_LOG_MAX_SAMPLES)")
        }
        val samples = imuLogQueue.toList()
        imuLogQueue.clear()
        queueSize.set(0)
        return samples
    }

    companion object {
        /**
         * Gyroflow `.gcsv` exporter — format per
         * https://docs.gyroflow.xyz/app/technical-details/gcsv-format
         *
         * Mandatory layout implemented here:
         *  1. `GYROFLOW IMU LOG`   (identifies the file as an IMU log)
         *  2. `version,1.3`        (format version)
         *  3. `id,commix`          (unique logger id; mandatory line 3)
         *  4. `orientation,YxZ`    (mandatory line 4; Android sensor axes for a
         *                           portrait camera — users can remap in Gyroflow)
         * The C++ mirror of this header lives in
         * app/src/main/cpp/stabilization_engine.cpp (keep both byte-identical).
         */
        const val GYROFLOW_HEADER = "GYROFLOW IMU LOG\n"
        const val GYROFLOW_VERSION = "version,1.3\n"
        const val GYROFLOW_ID = "id,commix\n"
        const val GYROFLOW_ORIENTATION = "orientation,YxZ\n"
        const val GYROFLOW_VENDOR = "vendor,commix\n"
        const val GYROFLOW_TIMESTAMP = "tscale,1\n"
        const val GYROFLOW_GSCALE = "gscale,1\n"
        const val GYROFLOW_ASCALE = "ascale,0.10197162129779283\n"
        const val GYROFLOW_CSV_HEADER = "t,gx,gy,gz,ax,ay,az\n"

        private const val IMU_LOG_MAX_SAMPLES = 30_000

        fun exportGyroflowGcsv(file: File, samples: List<ImuSample>, videoFilename: String? = null) {
            file.parentFile?.mkdirs()
            file.bufferedWriter().use { writer ->
                writer.write(GYROFLOW_HEADER)
                writer.write(GYROFLOW_VERSION)
                writer.write(GYROFLOW_ID)
                writer.write(GYROFLOW_ORIENTATION)
                writer.write(GYROFLOW_VENDOR)
                if (!videoFilename.isNullOrBlank()) {
                    writer.write("videofilename,$videoFilename\n")
                }
                // Android reports gyro in rad/s and accelerometer in m/s^2 — the
                // units Gyroflow expects — so the file declares unit scales and the
                // rows carry the sensor values verbatim.
                writer.write(GYROFLOW_TIMESTAMP)
                writer.write(GYROFLOW_GSCALE)
                writer.write(GYROFLOW_ASCALE)
                writer.write(GYROFLOW_CSV_HEADER)
                val baseTs = if (samples.isNotEmpty()) samples.first().timestampNs else 0L
                for (s in samples) {
                    val tSec = (s.timestampNs - baseTs) * 1e-9
                    writer.write(
                        String.format(
                            java.util.Locale.US,
                            "%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f\n",
                            tSec, s.gyroX, s.gyroY, s.gyroZ, s.accelX, s.accelY, s.accelZ
                        )
                    )
                }
            }
        }
    }

    fun exportGyroflowGcsv(file: File, samples: List<ImuSample>, videoFilename: String? = null) =
        Companion.exportGyroflowGcsv(file, samples, videoFilename)
}
