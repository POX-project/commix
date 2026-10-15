package com.txor.commix.data

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import com.txor.commix.*
import com.txor.commix.bridge.NativeEngineBridge
import com.txor.commix.bridge.NativeTelemetryBridge
import com.txor.commix.model.ExifOptions
import com.txor.commix.model.ShotMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*

class CommixRepository(private val context: Context) {
    companion object {
        private const val TAG = "CommixRepository"
    }

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val sessionManager = CameraSessionManager()
    val audioEngine = AudioEngine(context)
    val thermalManager = ThermalManager(context)
    val quirks = DeviceQuirksManager

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var previewSurface: Surface? = null
    private var analysisReader: ImageReader? = null
    private var stillReader: ImageReader? = null
    private var videoRecorder: CommixRecordingEngine? = null
    private var activeVideoFile: File? = null
    private var activeRecordingFps: Double = 30.0
    @Volatile var isRecordingVideo = false
        private set

    private var nightBurstCollector = Collections.synchronizedList(mutableListOf<ByteArray>())
    @Volatile private var isNightBurstActive = false

    private var panoramaSliceCollector = Collections.synchronizedList(mutableListOf<ByteArray>())
    @Volatile var isPanoramaActive = false
        private set

    private var activeCharacteristics: CameraCharacteristics? = null
    private var currentParams = CaptureRequestParams()
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    var onPhotoSaved: ((Uri) -> Unit)? = null
    var onVideoSaved: ((Uri) -> Unit)? = null
    var onPanoramaSliceCaptured: ((ByteArray) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onPreviewSizeConfigured: ((Size, Float, Double) -> Unit)? = null
    var exifOptions: ExifOptions = ExifOptions()


    val sensorHub = SensorHub(context) { map ->
        val pitch = map["pitch"] ?: 0.0
        val roll = map["roll"] ?: 0.0
        val az = map["azimuth"] ?: 0.0
        NativeTelemetryBridge.nativeWriteImu(pitch.toFloat(), roll.toFloat(), az.toFloat())
        onOrientationChanged?.invoke(map)
    }

    var onOrientationChanged: ((Map<String, Double>) -> Unit)? = null

    init {
        startCameraThread()
        thermalManager.onThermalStateChanged = { statusName, statusCode ->
            if (statusCode >= android.os.PowerManager.THERMAL_STATUS_SEVERE) {
                Log.w(TAG, "Thermal throttling engaged ($statusName).")
            }
        }
        thermalManager.startMonitoring()
        sensorHub.startOrientationUpdates()
        NativeEngineBridge.nativeInitEngine()
    }

    private fun startCameraThread() {
        if (cameraThread == null) {
            cameraThread = HandlerThread("CommixCameraThread").apply {
                start()
                cameraHandler = Handler(looper)
            }
        }
    }

    private var cachedProbedCameras: List<CameraProbeResult>? = null

    fun getProbedCameras(): List<CameraProbeResult> {
        return cachedProbedCameras ?: LensProbeService.probeCameraResults(cameraManager).also {
            cachedProbedCameras = it
        }
    }

    fun startBackgroundIdSpam(onDiscovered: (CameraProbeResult) -> Unit) {
        val known = (cachedProbedCameras ?: emptyList()).map { it.cameraId }.toSet()
        LensProbeService.startBackgroundIdSpam(cameraManager, known) { newCam ->
            synchronized(this) {
                val list = (cachedProbedCameras ?: emptyList()).toMutableList()
                if (list.none { it.cameraId == newCam.cameraId }) {
                    list.add(newCam)
                    cachedProbedCameras = list
                }
            }
            onDiscovered(newCam)
        }
    }

    fun getSupportedVideoSizes(cameraId: String? = null): List<String> {
        val id = cameraId ?: sessionManager.activeCameraId ?: "0"
        val c = try { cameraManager.getCameraCharacteristics(id) } catch (_: Exception) { return listOf("1920x1080", "1280x720") }
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        val allSizes = mutableSetOf<android.util.Size>()

        // 1. Gather all HAL-reported stream configurations
        if (map != null) {
            map.getOutputSizes(android.media.MediaRecorder::class.java)?.let { allSizes.addAll(it) }
            map.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.let { allSizes.addAll(it) }
            map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.let { allSizes.addAll(it) }
            map.getOutputSizes(android.graphics.ImageFormat.JPEG)?.let { allSizes.addAll(it) }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    map.getHighResolutionOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.let { allSizes.addAll(it) }
                    map.getHighResolutionOutputSizes(android.graphics.ImageFormat.JPEG)?.let { allSizes.addAll(it) }
                }
            } catch (_: Throwable) {}
        }

        // 2. Probed camera sensor sizes (including 64MP 9248x6936, 16MP 4624x3472, vendor super-resolution, remosaic)
        val probed = getProbedCameras().find { it.cameraId == id }
        if (probed != null) {
            allSizes.addAll(probed.supportedJpegSizes)
            allSizes.add(probed.hiResJpegSize)
            allSizes.add(probed.standardJpegSize)
        }

        // 3. Android 12+ Maximum Resolution Configuration Map
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val maxMap = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                maxMap?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.let { allSizes.addAll(it) }
                maxMap?.getOutputSizes(android.graphics.ImageFormat.JPEG)?.let { allSizes.addAll(it) }
            } catch (_: Throwable) {}
        }

        // 4. Sensor physical active array and pixel array bounds
        val activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)

        val maxSensorW = maxOf(
            activeArray?.width() ?: 0,
            pixelArray?.width ?: 0,
            probed?.hiResJpegSize?.width ?: 0,
            allSizes.maxOfOrNull { it.width } ?: 1920
        )
        val maxSensorH = maxOf(
            activeArray?.height() ?: 0,
            pixelArray?.height ?: 0,
            probed?.hiResJpegSize?.height ?: 0,
            allSizes.maxOfOrNull { it.height } ?: 1080
        )

        // 5. Sensor full native resolution and binned modes
        if (maxSensorW > 0 && maxSensorH > 0) {
            allSizes.add(android.util.Size(maxSensorW, maxSensorH))
            // 4:3 native binned mode (e.g. 4624x3472 for 64MP)
            allSizes.add(android.util.Size(maxSensorW / 2, maxSensorH / 2))
            // 16:9 sensor crop (e.g. 4624x2600)
            val h16_9 = (((maxSensorW / 2).toLong() * 9 / 16 / 2) * 2).toInt()
            if (h16_9 > 0) {
                allSizes.add(android.util.Size(maxSensorW / 2, h16_9))
            }
        }

        // 6. Universal video standards that physically fit inside this sensor
        val candidateStandards = listOf(
            android.util.Size(4096, 2160), // 4K DCI
            android.util.Size(3840, 2160), // 4K UHD
            android.util.Size(2560, 1440), // 1440p QHD
            android.util.Size(1920, 1080), // 1080p FHD
            android.util.Size(1280, 720),  // 720p HD
            android.util.Size(720, 480),   // 480p SD
            android.util.Size(640, 480)    // VGA
        )
        for (std in candidateStandards) {
            if (std.width <= maxSensorW && std.height <= maxSensorH) {
                allSizes.add(std)
            }
        }

        // Filter valid dimensions within sensor bounds and sort descending by pixel area
        val rawList = allSizes
            .filter { it.width in 640..maxSensorW && it.height in 480..maxSensorH && it.width % 2 == 0 && it.height % 2 == 0 }
            .sortedByDescending { it.width.toLong() * it.height.toLong() }

        // Deduplicate near-identical sensor modes (within 2% pixel area difference to avoid duplicates like 4640x3472 vs 4624x3472)
        val deduplicated = mutableListOf<String>()
        for (s in rawList) {
            val key = "${s.width}x${s.height}"
            val pixels = s.width.toLong() * s.height.toLong()
            val hasNear = deduplicated.any { existing ->
                val p = existing.split('x')
                val ew = p.getOrNull(0)?.toLongOrNull() ?: 0L
                val eh = p.getOrNull(1)?.toLongOrNull() ?: 0L
                val epixels = ew * eh
                kotlin.math.abs(pixels - epixels).toDouble() / pixels.toDouble() < 0.02
            }
            if (!hasNear) {
                deduplicated.add(key)
            }
        }

        return if (deduplicated.isNotEmpty()) deduplicated else listOf("1920x1080", "1280x720")
    }

    fun getSupportedFpsList(cameraId: String? = null): List<Double> {
        val id = cameraId ?: sessionManager.activeCameraId ?: "0"
        val c = try { cameraManager.getCameraCharacteristics(id) } catch (_: Exception) { return listOf(24.0, 30.0, 60.0) }
        val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val fpsSet = ranges.map { it.upper.toDouble() }.toMutableList()
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        try {
            map?.highSpeedVideoFpsRanges?.forEach { hsRange ->
                fpsSet.add(hsRange.upper.toDouble())
            }
        } catch (_: Exception) {}
        val sorted = fpsSet.distinct().sorted()
        return if (sorted.isNotEmpty()) sorted else listOf(24.0, 30.0, 60.0)
    }

    fun triggerTapFocus(normX: Float, normY: Float) {
        val session = sessionManager.activeSession ?: return
        val builder = previewRequestBuilder ?: return
        val characteristics = activeCharacteristics ?: return
        val handler = cameraHandler ?: return
        val surface = previewSurface
        if (surface == null || !surface.isValid) return

        val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: Rect(0, 0, 1920, 1080)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        val (sensorX, sensorY) = when (sensorOrientation) {
            90 -> Pair(normY, 1f - normX)
            270 -> Pair(1f - normY, normX)
            180 -> Pair(1f - normX, 1f - normY)
            else -> Pair(normX, normY)
        }

        val centerX = (activeArray.left + sensorX * activeArray.width()).toInt().coerceIn(activeArray.left, activeArray.right)
        val centerY = (activeArray.top + sensorY * activeArray.height()).toInt().coerceIn(activeArray.top, activeArray.bottom)

        val halfAreaWidth = (activeArray.width() * 0.06f).toInt().coerceAtLeast(10)
        val halfAreaHeight = (activeArray.height() * 0.06f).toInt().coerceAtLeast(10)

        val meteringRect = Rect(
            (centerX - halfAreaWidth).coerceAtLeast(activeArray.left),
            (centerY - halfAreaHeight).coerceAtLeast(activeArray.top),
            (centerX + halfAreaWidth).coerceAtMost(activeArray.right),
            (centerY + halfAreaHeight).coerceAtMost(activeArray.bottom)
        )

        val weight = MeteringRectangle.METERING_WEIGHT_MAX
        val meteringArea = arrayOf(MeteringRectangle(meteringRect, weight))

        val maxAfRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        val maxAeRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0

        if (maxAfRegions > 0) {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, meteringArea)
        }
        if (maxAeRegions > 0) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, meteringArea)
        }

        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)

        try {
            session.capture(builder.build(), null, handler)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            if (previewSurface?.isValid == true) {
                session.setRepeatingRequest(builder.build(), null, handler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed triggering tap focus: ${e.message}")
        }
    }

    fun cancelFocus() {
        val session = sessionManager.activeSession ?: return
        val builder = previewRequestBuilder ?: return
        val handler = cameraHandler ?: return
        val surface = previewSurface
        if (surface == null || !surface.isValid) return

        val isVideo = currentParams.activeMode in listOf("VIDEO", "CINEMATIC", "SLOW-MO")
        val afMode = if (isVideo) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE

        builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)

        try {
            session.capture(builder.build(), null, handler)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            if (previewSurface?.isValid == true) {
                session.setRepeatingRequest(builder.build(), null, handler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed canceling tap focus: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun onSurfaceDestroyed() {
        previewSurface = null
        try {
            sessionManager.activeSession?.stopRepeating()
        } catch (_: Exception) {}
        if (sessionManager.state == CameraState.ACTIVE_PREVIEW) {
            sessionManager.transitionTo(CameraState.OPENED)
        }
    }

    fun openCamera(cameraId: String, surface: Surface, onReady: () -> Unit) {
        val handler = cameraHandler ?: return
        previewSurface = surface

        val existingDevice = sessionManager.activeDevice
        if (sessionManager.activeCameraId == cameraId && existingDevice != null) {
            Log.d(TAG, "Camera $cameraId device already open, reconfiguring session for surface.")
            configureSession(existingDevice, surface, onReady)
            return
        }

        handler.post {
            try {
                if (sessionManager.state != CameraState.CLOSED) {
                    sessionManager.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing previous camera: ${e.message}")
            }

            // Yield briefly to let cameraserver cleanly finalize hardware disconnect
            try { Thread.sleep(75) } catch (_: Exception) {}

            sessionManager.transitionTo(CameraState.OPENING)
            activeCharacteristics = try {
                cameraManager.getCameraCharacteristics(cameraId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed getting characteristics for camera $cameraId", e)
                sessionManager.forceState(CameraState.ERROR)
                onError?.invoke("Failed getting characteristics for camera $cameraId")
                return@post
            }

            try {
                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        sessionManager.activeDevice = camera
                        sessionManager.activeCameraId = cameraId
                        sessionManager.transitionTo(CameraState.OPENED)
                        configureSession(camera, surface, onReady)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        sessionManager.release()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.e(TAG, "Camera open error: $error")
                        try { camera.close() } catch (_: Exception) {}
                        sessionManager.release()
                        onError?.invoke("Camera hardware error ($error)")
                    }
                }, handler)
            } catch (e: Exception) {
                Log.e(TAG, "Exception opening camera: ${e.message}", e)
                sessionManager.forceState(CameraState.ERROR)
                onError?.invoke("Failed to open camera: ${e.message}")
            }
        }
    }

    private fun configureSession(camera: CameraDevice, surface: Surface, onReady: () -> Unit) {
        val handler = cameraHandler ?: return
        sessionManager.transitionTo(CameraState.CONFIGURING)

        val map = activeCharacteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        // Setup Still Capture ImageReader with actual supported JPEG size
        stillReader?.close()
        val probed = getProbedCameras().find { it.cameraId == (sessionManager.activeCameraId ?: camera.id) }
        val supportedSizes = probed?.supportedJpegSizes?.takeIf { it.isNotEmpty() }
            ?: map?.getOutputSizes(ImageFormat.JPEG)?.toList()
            ?: emptyList()
        val targetMpPixels = currentParams.targetPhotoMp * 1_000_000.0
        val targetJpeg = if (targetMpPixels > 0.0) {
            supportedSizes.minByOrNull { kotlin.math.abs((it.width.toDouble() * it.height.toDouble()) - targetMpPixels) }
                ?: supportedSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
                ?: Size(1920, 1080)
        } else {
            supportedSizes.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: Size(1920, 1080)
        }

        stillReader = ImageReader.newInstance(targetJpeg.width, targetJpeg.height, ImageFormat.JPEG, 8).apply {
            setOnImageAvailableListener({ reader ->
                val img = try { reader.acquireNextImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                val bytes = try {
                    val buffer = img.planes[0].buffer
                    val b = ByteArray(buffer.remaining())
                    buffer.get(b)
                    b
                } finally {
                    img.close()
                }

                if (isNightBurstActive) {
                    synchronized(nightBurstCollector) {
                        nightBurstCollector.add(bytes)
                    }
                } else if (isPanoramaActive) {
                    synchronized(panoramaSliceCollector) {
                        panoramaSliceCollector.add(bytes)
                    }
                    onPanoramaSliceCaptured?.invoke(bytes)
                } else {
                    ioScope.launch {
                        val processed = processToneAndGrain(bytes)
                        saveStillBytes(processed, isNight = false)
                    }
                }
            }, handler)
        }

        // Setup YUV analysis reader for real-time scopes
        analysisReader?.close()
        analysisReader = null
        val yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
        val chosenYuv = yuvSizes.filter { it.width in 160..640 && it.height in 90..480 }
            .minByOrNull { it.width * it.height } ?: Size(320, 240)

        try {
            analysisReader = ImageReader.newInstance(chosenYuv.width, chosenYuv.height, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val img = try { reader.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                    try {
                        processAnalysisFrame(img)
                    } finally {
                        img.close()
                    }
                }, handler)
            }
        } catch (e: Exception) {
            Log.w(TAG, "analysisReader creation failed: ${e.message}")
        }

        // Dynamically compute preview resolution matching active capture aspect ratio
        val isVideo = currentParams.activeMode in listOf("VIDEO", "CINEMATIC", "SLOW-MO", "TIME-LAPSE")
        val videoParts = currentParams.videoResolution.split("x")
        val videoW = videoParts.firstOrNull()?.toIntOrNull() ?: 1920
        val videoH = videoParts.lastOrNull()?.toIntOrNull() ?: 1080

        val captureW = if (isVideo) videoW else targetJpeg.width
        val captureH = if (isVideo) videoH else targetJpeg.height
        val targetAspect = captureW.toFloat() / captureH.toFloat()

        val stSizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.toList() ?: emptyList()
        val matchingStSizes = stSizes.filter { size ->
            val r = size.width.toFloat() / size.height.toFloat()
            kotlin.math.abs(r - targetAspect) < 0.03f
        }

        val optimalPreview = matchingStSizes.filter { it.width <= 2048 && it.height <= 1536 }
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: matchingStSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: stSizes.minByOrNull { size ->
                val r = size.width.toFloat() / size.height.toFloat()
                kotlin.math.abs(r - targetAspect)
            }
            ?: Size(if (isVideo) 1920 else 1440, 1080)

        val portraitAspect = optimalPreview.height.toFloat() / optimalPreview.width.toFloat()
        val actualPhotoMp = (targetJpeg.width.toDouble() * targetJpeg.height.toDouble()) / 1_000_000.0

        onPreviewSizeConfigured?.invoke(optimalPreview, portraitAspect, actualPhotoMp)

        val sessionSurfaces = mutableListOf<Surface>()
        sessionSurfaces.add(surface)
        stillReader?.surface?.let { sessionSurfaces.add(it) }
        analysisReader?.surface?.let { sessionSurfaces.add(it) }

        createCaptureSessionWithFallback(camera, surface, sessionSurfaces, onReady, allowFallback = true)
    }

    private fun createCaptureSessionWithFallback(
        camera: CameraDevice,
        surface: Surface,
        surfaces: List<Surface>,
        onReady: () -> Unit,
        allowFallback: Boolean
    ) {

        val handler = cameraHandler ?: return
        try {
            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (sessionManager.activeDevice == null || surface != previewSurface || !surface.isValid) {
                        try { session.close() } catch (_: Exception) {}
                        sessionManager.transitionTo(CameraState.CLOSED)
                        return
                    }
                    sessionManager.activeSession = session
                    sessionManager.transitionTo(CameraState.ACTIVE_PREVIEW)

                    val isVideo = currentParams.activeMode in listOf("VIDEO", "CINEMATIC", "SLOW-MO", "TIME-LAPSE")
                    val template = if (isVideo) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                    val builder = camera.createCaptureRequest(template).apply {
                        addTarget(surface)
                        val aSurf = analysisReader?.surface
                        if (aSurf != null && aSurf.isValid && surfaces.contains(aSurf)) {
                            addTarget(aSurf)
                        }
                    }
                    previewRequestBuilder = builder
                    applyCaptureParams()

                    try {
                        if (previewSurface?.isValid == true) {
                            session.setRepeatingRequest(builder.build(), null, handler)
                            onReady()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start preview request: ${e.message}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.w(TAG, "Session configuration failed")
                    try { session.close() } catch (_: Exception) {}
                    val currentAnalysis = analysisReader
                    if (allowFallback && currentAnalysis != null) {
                        val surfToRemove = currentAnalysis.surface
                        Log.i(TAG, "Retrying session configuration with minimal surfaces")
                        try { currentAnalysis.close() } catch (_: Exception) {}
                        analysisReader = null
                        val fallbackSurfaces = surfaces.filter { it != surfToRemove }
                        createCaptureSessionWithFallback(camera, surface, fallbackSurfaces, onReady, allowFallback = false)
                        return
                    }
                    sessionManager.forceState(CameraState.ERROR)
                    onError?.invoke("Capture session configuration failed")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed creating capture session: ${e.message}", e)
            val currentAnalysis = analysisReader
            if (allowFallback && currentAnalysis != null) {
                val surfToRemove = currentAnalysis.surface
                try { currentAnalysis.close() } catch (_: Exception) {}
                analysisReader = null
                val fallbackSurfaces = surfaces.filter { it != surfToRemove }
                createCaptureSessionWithFallback(camera, surface, fallbackSurfaces, onReady, allowFallback = false)
            } else {
                sessionManager.forceState(CameraState.ERROR)
            }
        }
    }

    private fun processAnalysisFrame(image: Image) {
        if (!NativeTelemetryBridge.isLoaded) return
        try {
            val planes = image.planes
            if (planes.size < 3) return

            val yBuf = planes[0].buffer
            val uBuf = planes[1].buffer
            val vBuf = planes[2].buffer

            NativeTelemetryBridge.nativeComputeScopesDirect(
                yBuf, uBuf, vBuf,
                image.width, image.height,
                planes[0].rowStride,
                planes[1].rowStride,
                planes[1].pixelStride,
                2 // Step size
            )
        } catch (_: Throwable) {}
    }

    fun updateParams(params: CaptureRequestParams) {
        val oldMp = currentParams.targetPhotoMp
        val enriched = params.copy(
            evRange = params.evRange ?: activeCharacteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE),
            zoomRatioRange = params.zoomRatioRange ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activeCharacteristics?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            } else null,
            activeRect = params.activeRect ?: activeCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
            hasOisHardware = activeCharacteristics?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.any {
                it == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
            } == true
        )
        val oldMode = currentParams.activeMode
        val oldVideoRes = currentParams.videoResolution
        currentParams = enriched
        applyCaptureParams()

        val needsReconfig = (oldMp != enriched.targetPhotoMp || oldMode != enriched.activeMode || oldVideoRes != enriched.videoResolution) && !isRecordingVideo

        if (needsReconfig) {
            val camera = sessionManager.activeDevice
            val surface = previewSurface
            if (camera != null && surface != null && surface.isValid) {
                val currentW = stillReader?.width ?: 0
                val currentH = stillReader?.height ?: 0
                val probed = getProbedCameras().find { it.cameraId == (sessionManager.activeCameraId ?: camera.id) }
                val supportedSizes = probed?.supportedJpegSizes?.takeIf { it.isNotEmpty() }
                    ?: activeCharacteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?.getOutputSizes(ImageFormat.JPEG)?.toList()
                    ?: emptyList()
                val targetPixels = enriched.targetPhotoMp * 1_000_000.0
                val newJpeg = if (targetPixels > 0.0) {
                    supportedSizes.minByOrNull { kotlin.math.abs((it.width.toDouble() * it.height.toDouble()) - targetPixels) }
                        ?: supportedSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
                        ?: Size(1920, 1080)
                } else {
                    supportedSizes.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: Size(1920, 1080)
                }

                if (newJpeg.width != currentW || newJpeg.height != currentH || oldMode != enriched.activeMode || oldVideoRes != enriched.videoResolution) {
                    configureSession(camera, surface) {}
                }
            }
        }
    }

    private val captureParamsRunnable = Runnable {
        val session = sessionManager.activeSession ?: return@Runnable
        val builder = previewRequestBuilder ?: return@Runnable
        val handler = cameraHandler ?: return@Runnable
        val surface = previewSurface
        if (surface == null || !surface.isValid) return@Runnable

        CaptureRequestFactory.applyControls(builder, currentParams)
        try {
            session.setRepeatingRequest(builder.build(), null, handler)
        } catch (e: Exception) {
            Log.w(TAG, "Error applying capture controls: ${e.message}")
        }
    }

    private fun applyCaptureParams() {
        val handler = cameraHandler ?: return
        handler.removeCallbacks(captureParamsRunnable)
        handler.post(captureParamsRunnable)
    }

    fun startVideoRecording(
        res: String,
        fps: Int,
        bitrate: Int,
        isVfDisabled: Boolean = currentParams.isViewfinderDisabled,
        targetFpsFloat: Double = fps.toDouble(),
        isSlowMotion: Boolean = false,
        playbackFps: Double = 30.0
    ) {
        if (isRecordingVideo) return
        val camera = sessionManager.activeDevice ?: return
        val pSurf = previewSurface ?: return
        val handler = cameraHandler ?: return

        val dims = res.split("x")
        val reqW = dims.getOrNull(0)?.toIntOrNull() ?: 1920
        val reqH = dims.getOrNull(1)?.toIntOrNull() ?: 1080

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val videoDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Commix").apply { mkdirs() }
        val vidFile = File(videoDir, "COMMIX_VID_$timeStamp.mp4")
        activeVideoFile = vidFile

        val effectiveFps = if (quirks.strict4k30FpsCap && (reqW >= 3840 || reqH >= 2160)) {
            fps.coerceAtMost(30)
        } else {
            fps.coerceAtLeast(1)
        }

        // Query camera-supported surface output sizes (MediaRecorder / SurfaceTexture)
        val c = activeCharacteristics ?: try {
            cameraManager.getCameraCharacteristics(sessionManager.activeCameraId ?: "0")
        } catch (_: Exception) { null }
        val map = c?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val validCamSizes = map?.getOutputSizes(android.media.MediaRecorder::class.java)?.toList()
            ?: map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.toList()
            ?: listOf(Size(1920, 1080))

        // Hardware video encoder bounding box: 4K UHD ceiling (3840x2160 landscape or 2160x3840 portrait)
        val maxBoxW = if (reqW >= reqH) 3840 else 2160
        val maxBoxH = if (reqW >= reqH) 2160 else 3840
        val reqAspect = reqW.toDouble() / reqH.toDouble()

        val caps = c?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val hasHighSpeed = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)
        val hsSizes = map?.highSpeedVideoSizes?.toList() ?: emptyList()

        val targetFps = effectiveFps.coerceAtLeast(1)
        val shouldUseHighSpeed = hasHighSpeed && (targetFps >= 60 || isSlowMotion) && hsSizes.isNotEmpty()

        val (bestCamSize, bestHsRange) = if (shouldUseHighSpeed) {
            val candidateHsSizes = if (reqW <= maxBoxW && reqH <= maxBoxH && hsSizes.any { it.width == reqW && it.height == reqH }) {
                listOf(Size(reqW, reqH))
            } else {
                val aspectMatched = hsSizes.filter {
                    kotlin.math.abs((it.width.toDouble() / it.height.toDouble()) - reqAspect) < 0.08
                }
                aspectMatched.sortedByDescending { it.width.toLong() * it.height.toLong() }
                    .ifEmpty { hsSizes.sortedByDescending { it.width.toLong() * it.height.toLong() } }
            }
            val hsSize = candidateHsSizes.firstOrNull() ?: Size(1280, 720)
            val hsRanges = try { map?.getHighSpeedVideoFpsRangesFor(hsSize)?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
            val chosenRange = hsRanges.filter { it.upper <= targetFps }.maxByOrNull { it.upper }
                ?: hsRanges.maxByOrNull { it.upper }
            Pair(hsSize, chosenRange)
        } else {
            val encoderFittingSizes = validCamSizes.filter { it.width <= maxBoxW && it.height <= maxBoxH }
            val size = if (reqW <= maxBoxW && reqH <= maxBoxH && validCamSizes.any { it.width == reqW && it.height == reqH }) {
                Size(reqW, reqH)
            } else {
                val aspectMatched = encoderFittingSizes.filter {
                    kotlin.math.abs((it.width.toDouble() / it.height.toDouble()) - reqAspect) < 0.08
                }
                aspectMatched.maxByOrNull { it.width.toLong() * it.height.toLong() }
                    ?: encoderFittingSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
                    ?: Size(1920, 1080)
            }
            Pair(size, null)
        }

        try {
            val recorder = CommixRecordingEngine(
                outputFile = vidFile,
                width = bestCamSize.width,
                height = bestCamSize.height,
                targetFps = targetFpsFloat,
                bitrateBps = bitrate,
                isSlowMotion = isSlowMotion,
                playbackFps = playbackFps
            )
            recorder.start()
            val recSurface = recorder.surface ?: throw IllegalStateException("Encoder surface is null")
            videoRecorder = recorder
            activeRecordingFps = targetFpsFloat
            isRecordingVideo = true

            val soloPass = isVfDisabled || currentParams.isViewfinderDisabled
            val recordSurfaces = if (soloPass) {
                listOf(recSurface)
            } else {
                listOf(pSurf, recSurface)
            }

            val stateCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (!isRecordingVideo || sessionManager.activeDevice == null || !recSurface.isValid) {
                        try { session.close() } catch (_: Exception) {}
                        return
                    }
                    sessionManager.activeSession = session
                    sessionManager.transitionTo(CameraState.RECORDING_VIDEO)

                    val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        if (!soloPass && pSurf.isValid) {
                            addTarget(pSurf)
                        }
                        addTarget(recSurface)
                    }

                    if (session is CameraConstrainedHighSpeedCaptureSession && bestHsRange != null) {
                        try {
                            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestHsRange)
                        } catch (_: Exception) {}
                        previewRequestBuilder = builder
                        applyCaptureParams()

                        try {
                            val requestList = session.createHighSpeedRequestList(builder.build())
                            session.setRepeatingBurst(requestList, null, handler)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error setting high-speed repeating burst: ${e.message}")
                        }
                    } else {
                        // Standard session pacing: AE target FPS clamp & sensor frame duration
                        val fpsRanges = activeCharacteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                        val bestRange = if (targetFps >= 30) {
                            fpsRanges?.maxByOrNull { it.upper }
                        } else {
                            fpsRanges?.filter { it.upper <= maxOf(targetFps, 15) }?.maxByOrNull { it.upper }
                                ?: fpsRanges?.minByOrNull { kotlin.math.abs(it.upper - targetFps) }
                        }
                        if (bestRange != null) {
                            try {
                                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestRange)
                            } catch (_: Exception) {}
                        }
                        try {
                            val frameDurationNs = 1_000_000_000L / targetFps
                            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs)
                        } catch (_: Exception) {}

                        previewRequestBuilder = builder
                        applyCaptureParams()

                        try {
                            session.setRepeatingRequest(builder.build(), null, handler)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error setting recording repeating request: ${e.message}")
                        }
                    }

                    audioEngine.startAudioMonitoring()
                    NativeEngineBridge.nativeStartImuLogging()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Video recording session configuration failed")
                    isRecordingVideo = false
                    try { videoRecorder?.stop() } catch (_: Exception) {}
                    videoRecorder = null
                    val dev = sessionManager.activeDevice
                    val ps = previewSurface
                    if (dev != null && ps != null && ps.isValid) {
                        configureSession(dev, ps) {}
                    } else {
                        sessionManager.forceState(CameraState.ERROR)
                    }
                    onError?.invoke("Failed to configure video recording session")
                }
            }

            if (bestHsRange != null) {
                try {
                    camera.createConstrainedHighSpeedCaptureSession(recordSurfaces, stateCallback, handler)
                } catch (e: Exception) {
                    Log.w(TAG, "High-speed session creation failed (${e.message}), falling back to standard session")
                    camera.createCaptureSession(recordSurfaces, stateCallback, handler)
                }
            } else {
                camera.createCaptureSession(recordSurfaces, stateCallback, handler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startVideoRecording error: ${e.message}", e)
            isRecordingVideo = false
            try { videoRecorder?.stop() } catch (_: Exception) {}
            videoRecorder = null
            onError?.invoke("Failed to start video recording: ${e.message}")
        }
    }

    fun stopVideoRecording() {
        if (!isRecordingVideo) return
        isRecordingVideo = false
        audioEngine.stopAudioMonitoring()

        try {
            sessionManager.activeSession?.stopRepeating()
            sessionManager.activeSession?.abortCaptures()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping repeating during video stop: ${e.message}")
        }

        val camera = sessionManager.activeDevice
        val pSurf = previewSurface
        val vidFile = activeVideoFile

        try {
            videoRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping video recorder: ${e.message}")
        }
        videoRecorder = null

        if (vidFile != null) {
            val csvFile = File(vidFile.parentFile, vidFile.nameWithoutExtension + ".gcsv")
            try {
                NativeEngineBridge.nativeStopAndExportGyroflow(
                    csvFile.absolutePath,
                    sessionManager.activeCameraId ?: "0",
                    activeRecordingFps
                )
            } catch (e: Exception) {
                Log.w(TAG, "Gyroflow export error: ${e.message}")
            }

            saveVideoToMediaStore(vidFile)
        }

        if (camera != null && pSurf != null) {
            configureSession(camera, pSurf) {}
        }
    }

    private fun saveVideoToMediaStore(file: File) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Commix")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            try {
                resolver.openOutputStream(uri)?.use { os ->
                    file.inputStream().use { it.copyTo(os) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                ProcessingJobQueue.instance.enqueueJob(
                    title = "Gyroflow Stabilization & MP4 Export (${file.name})",
                    type = ProcessingJobType.VIDEO_STABILIZATION,
                    sourcePath = file.absolutePath,
                    outputPath = file.name,
                    priority = JobPriority.HIGH
                )
                onVideoSaved?.invoke(uri)
            } catch (e: Exception) {
                Log.e(TAG, "Failed writing video to MediaStore", e)
            }
        }
    }

    fun takePicture(metadata: ShotMetadata) {
        val session = sessionManager.activeSession
        val device = sessionManager.activeDevice
        val reader = stillReader
        val handler = cameraHandler

        if (session == null || device == null || reader == null || handler == null || !reader.surface.isValid || sessionManager.state != CameraState.ACTIVE_PREVIEW) {
            Log.w(TAG, "Cannot take picture: camera hardware or reader not ready (state=${sessionManager.state})")
            onError?.invoke("Camera not ready for capture")
            return
        }
        sessionManager.transitionTo(CameraState.CAPTURING_STILL)

        try {
            val sensorOrientation = activeCharacteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                CaptureRequestFactory.applyControls(this, currentParams)
                set(CaptureRequest.JPEG_QUALITY, metadata.compressionQuality.toByte())
                set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
            }

            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    sessionManager.transitionTo(CameraState.ACTIVE_PREVIEW)
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    sessionManager.transitionTo(CameraState.ACTIVE_PREVIEW)
                    onError?.invoke("Still capture failed")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during takePicture: ${e.message}")
            sessionManager.transitionTo(CameraState.ACTIVE_PREVIEW)
            onError?.invoke("Exception during takePicture: ${e.message}")
        }
    }

    fun startPanoramaCapture() {
        isPanoramaActive = true
        synchronized(panoramaSliceCollector) {
            panoramaSliceCollector.clear()
        }
    }

    fun takePanoramaSlice(metadata: ShotMetadata) {
        val session = sessionManager.activeSession
        val device = sessionManager.activeDevice
        val reader = stillReader
        val handler = cameraHandler

        if (session == null || device == null || reader == null || handler == null || !reader.surface.isValid || sessionManager.state != CameraState.ACTIVE_PREVIEW) {
            Log.w(TAG, "Cannot take panorama slice: camera hardware or reader not ready (state=${sessionManager.state})")
            return
        }

        try {
            val sensorOrientation = activeCharacteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                CaptureRequestFactory.applyControls(this, currentParams)
                set(CaptureRequest.JPEG_QUALITY, metadata.compressionQuality.toByte())
                set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
            }

            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    Log.w(TAG, "Panorama slice capture failed: reason=${failure.reason}")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during takePanoramaSlice: ${e.message}")
        }
    }

    fun stopPanoramaCapture(): List<ByteArray> {
        isPanoramaActive = false
        return synchronized(panoramaSliceCollector) {
            val copy = ArrayList(panoramaSliceCollector)
            panoramaSliceCollector.clear()
            copy
        }
    }

    fun takeNightBurst(metadata: ShotMetadata) {
        val session = sessionManager.activeSession ?: return
        val device = sessionManager.activeDevice ?: return
        val reader = stillReader ?: return
        val handler = cameraHandler ?: return

        if (!reader.surface.isValid) return
        if (sessionManager.state != CameraState.ACTIVE_PREVIEW) return
        sessionManager.transitionTo(CameraState.CAPTURING_STILL)
        isNightBurstActive = true
        synchronized(nightBurstCollector) { nightBurstCollector.clear() }

        val sensorOrientation = activeCharacteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val burstCount = 6
        val burstRequests = mutableListOf<CaptureRequest>()
        for (i in 0 until burstCount) {
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                CaptureRequestFactory.applyControls(this, currentParams)
                set(CaptureRequest.JPEG_QUALITY, metadata.compressionQuality.toByte())
                set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
            }
            burstRequests.add(req.build())
        }

        try {
            session.captureBurst(burstRequests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureSequenceCompleted(
                    session: CameraCaptureSession,
                    sequenceId: Int,
                    frameNumber: Long
                ) {
                    processNightBurst()
                    sessionManager.transitionTo(CameraState.ACTIVE_PREVIEW)
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    sessionManager.transitionTo(CameraState.ACTIVE_PREVIEW)
                    isNightBurstActive = false
                    onError?.invoke("Night burst capture sequence failed")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Night burst capture failed: ${e.message}")
            sessionManager.transitionTo(CameraState.ACTIVE_PREVIEW)
            isNightBurstActive = false
            onError?.invoke("Night burst capture failed: ${e.message}")
        }
    }

    private fun processNightBurst() {
        val frames = synchronized(nightBurstCollector) {
            val list = nightBurstCollector.toList()
            nightBurstCollector.clear()
            list
        }
        isNightBurstActive = false
        if (frames.isEmpty()) return

        try {
            if (frames.size < 2 || !NativeEngineBridge.isLoaded) {
                saveStillBytes(frames.first(), isNight = true)
                return
            }

            val frameBuffers = mutableListOf<ByteBuffer>()
            var width = 0
            var height = 0

            for (bytes in frames) {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                if (width == 0) {
                    width = bmp.width
                    height = bmp.height
                }
                if (bmp.width == width && bmp.height == height) {
                    val buf = ByteBuffer.allocateDirect(width * height * 4)
                    bmp.copyPixelsToBuffer(buf)
                    buf.rewind()
                    frameBuffers.add(buf)
                }
                bmp.recycle()
            }

            if (frameBuffers.size < 2) {
                saveStillBytes(frames.first(), isNight = true)
                return
            }

            val outBuf = ByteBuffer.allocateDirect(width * height * 4)
            val success = NativeEngineBridge.nativeStackFramesDirect(
                frameBuffers.toTypedArray(),
                frameBuffers.size,
                width,
                height,
                4,
                outBuf,
                true // deepShadowBoost
            )

            if (success) {
                outBuf.rewind()
                val compositeBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                compositeBitmap.copyPixelsFromBuffer(outBuf)
                val stream = ByteArrayOutputStream()
                compositeBitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                compositeBitmap.recycle()
                saveStillBytes(stream.toByteArray(), isNight = true)
            } else {
                saveStillBytes(frames.first(), isNight = true)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Night burst processing failed: ${t.message}", t)
            if (frames.isNotEmpty()) {
                saveStillBytes(frames.first(), isNight = true)
            }
        }
    }

    private fun saveStillImage(image: Image) {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        if (isNightBurstActive) {
            synchronized(nightBurstCollector) {
                nightBurstCollector.add(bytes)
            }
            return
        }

        if (isPanoramaActive) {
            synchronized(panoramaSliceCollector) {
                panoramaSliceCollector.add(bytes)
            }
            onPanoramaSliceCaptured?.invoke(bytes)
            return
        }

        val processedBytes = processToneAndGrain(bytes)
        saveStillBytes(processedBytes, isNight = false)
    }

    private fun processToneAndGrain(jpegBytes: ByteArray): ByteArray {
        val curve = currentParams.toneCurve
        val grain = currentParams.filmGrain
        if (curve == "neutral" && grain == "off") return jpegBytes
        if (!NativeEngineBridge.isLoaded) return jpegBytes

        try {
            val opts = BitmapFactory.Options().apply { inMutable = true }
            val mutableBmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts) ?: return jpegBytes
            val w = mutableBmp.width
            val h = mutableBmp.height

            val directBuf = ByteBuffer.allocateDirect(w * h * 4)
            mutableBmp.copyPixelsToBuffer(directBuf)
            directBuf.rewind()

            val curveId = when (curve) {
                "linear" -> 0
                "film_print" -> 1
                "cine_d" -> 2
                "neutral" -> 3
                else -> 3
            }
            val grainId = when (grain) {
                "35mm" -> 1
                "16mm" -> 2
                "8mm" -> 3
                else -> 0
            }

            NativeEngineBridge.nativeProcessZeroDirect(directBuf, w, h, 4, curveId, grainId, 1.0f)
            directBuf.rewind()
            mutableBmp.copyPixelsFromBuffer(directBuf)

            val stream = ByteArrayOutputStream()
            mutableBmp.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            mutableBmp.recycle()
            return stream.toByteArray()
        } catch (t: Throwable) {
            Log.e(TAG, "processToneAndGrain error: ${t.message}")
            return jpegBytes
        }
    }

    private fun saveStillBytes(bytes: ByteArray, isNight: Boolean = false) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val prefix = if (isNight) "COMMIX_NIGHT_" else "COMMIX_"
        val fileName = "$prefix$timeStamp.jpg"

        val tempFile = File(context.cacheDir, "temp_$fileName")
        try {
            tempFile.writeBytes(bytes)
            val orientation = activeCharacteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val meta = ShotMetadata(
                timestamp = System.currentTimeMillis(),
                iso = currentParams.iso.takeIf { it > 0 },
                exposureTimeNs = currentParams.expNs.takeIf { it > 0L },
                activeMode = currentParams.activeMode
            )
            ExifWriter.writeMetadata(tempFile, orientation, meta, exifOptions)
        } catch (e: Exception) {
            Log.w(TAG, "Failed embedding EXIF into temp file: ${e.message}")
        }

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Commix")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

        if (uri != null) {
            try {
                resolver.openOutputStream(uri)?.use { os ->
                    if (tempFile.exists()) {
                        tempFile.inputStream().use { it.copyTo(os) }
                    } else {
                        os.write(bytes)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                val jobType = if (isNight) ProcessingJobType.MULTI_EXPOSURE_STACK else ProcessingJobType.SUPER_RES_SYNTHESIS
                ProcessingJobQueue.instance.enqueueJob(
                    title = if (isNight) "Night Burst HDR Fusion ($fileName)" else "Tone Curve & Grain Synthesis ($fileName)",
                    type = jobType,
                    outputPath = fileName,
                    priority = JobPriority.NORMAL
                )
                onPhotoSaved?.invoke(uri)
            } catch (e: Exception) {
                Log.e(TAG, "Failed writing image to MediaStore", e)
                resolver.delete(uri, null, null)
            } finally {
                tempFile.delete()
            }
        } else {
            tempFile.delete()
        }
    }

    fun savePanoramaImage(bytes: ByteArray, is360: Boolean, onSaved: ((Uri) -> Unit)? = null) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val prefix = if (is360) "COMMIX_360_" else "COMMIX_PANO_"
        val fileName = "$prefix$timeStamp.jpg"

        val tempFile = File(context.cacheDir, "temp_$fileName")
        try {
            tempFile.writeBytes(bytes)
            val orientation = activeCharacteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val meta = ShotMetadata(
                timestamp = System.currentTimeMillis(),
                iso = currentParams.iso.takeIf { it > 0 },
                exposureTimeNs = currentParams.expNs.takeIf { it > 0L },
                activeMode = "PANORAMA"
            )
            ExifWriter.writeMetadata(tempFile, orientation, meta, exifOptions)
        } catch (e: Exception) {
            Log.w(TAG, "Failed embedding EXIF into panorama temp file: ${e.message}")
        }

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Commix")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

        if (uri != null) {
            try {
                resolver.openOutputStream(uri)?.use { os ->
                    if (tempFile.exists()) {
                        tempFile.inputStream().use { it.copyTo(os) }
                    } else {
                        os.write(bytes)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                ProcessingJobQueue.instance.enqueueJob(
                    title = if (is360) "360° Photo Sphere Stitching ($fileName)" else "Panorama Sweep Stitching ($fileName)",
                    type = ProcessingJobType.PANORAMA_STITCHING,
                    outputPath = fileName,
                    priority = JobPriority.HIGH
                )
                onPhotoSaved?.invoke(uri)
                onSaved?.invoke(uri)
            } catch (e: Exception) {
                Log.e(TAG, "Failed writing panorama image to MediaStore", e)
                resolver.delete(uri, null, null)
            } finally {
                tempFile.delete()
            }
        } else {
            tempFile.delete()
        }
    }

    fun release() {
        if (isRecordingVideo) {
            stopVideoRecording()
        }
        sensorHub.stopOrientationUpdates()
        thermalManager.stopMonitoring()
        audioEngine.release()
        sessionManager.release()
        analysisReader?.close()
        analysisReader = null
        stillReader?.close()
        stillReader = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }
}

