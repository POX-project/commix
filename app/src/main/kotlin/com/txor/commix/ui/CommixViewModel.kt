package com.txor.commix.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.txor.commix.CaptureRequestParams
import com.txor.commix.PanoramaStitcher
import com.txor.commix.PipelineThroughputEngine
import com.txor.commix.automation.AutomationEngine
import com.txor.commix.automation.AutomationPresetConfig
import com.txor.commix.bridge.NativeEngineBridge
import com.txor.commix.data.CommixRepository
import com.txor.commix.model.CameraUiState
import com.txor.commix.model.ShotMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import kotlin.math.abs

class CommixViewModel(application: Application) : AndroidViewModel(application) {
    val repository = CommixRepository(application.applicationContext)
    val preferences = com.txor.commix.data.CommixPreferences(application.applicationContext)

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private var activeSurface: Surface? = null

    init {
        val cameras = repository.getProbedCameras()
        val defaultCam = cameras.firstOrNull { !it.isFront && !it.isMacro && !it.isDepth }
            ?: cameras.firstOrNull { !it.isFront }
            ?: cameras.firstOrNull()

        val initialMp = defaultCam?.sensorMp ?: 12.0
        val loaded = preferences.load(_uiState.value)
        _uiState.update { current ->
            loaded.copy(
                availableCameras = cameras,
                activeCameraId = defaultCam?.cameraId ?: loaded.activeCameraId,
                maxLensMp = initialMp,
                targetPhotoMp = if (loaded.targetPhotoMp > 0.0 && loaded.targetPhotoMp <= initialMp) loaded.targetPhotoMp else initialMp,
                hardwareMaxDigitalZoom = defaultCam?.maxDigitalZoom ?: 10.0,
                maxZoomRatio = 30.0
            )
        }

        repository.onPreviewSizeConfigured = { previewSize, aspect, mp ->
            _uiState.update { it.copy(
                previewWidth = previewSize.width,
                previewHeight = previewSize.height,
                previewAspectRatio = aspect,
                targetPhotoMp = if (it.targetPhotoMp <= 0.0 || it.targetPhotoMp == it.maxLensMp) mp else it.targetPhotoMp,
                maxLensMp = mp
            ) }
        }

        repository.onPhotoSaved = { uri ->
            _uiState.update { it.copy(recentThumbnailPath = uri.toString(), lastSavedUriString = uri.toString(), isCapturing = false) }
        }

        repository.onVideoSaved = { uri ->
            _uiState.update { it.copy(recentThumbnailPath = uri.toString(), lastSavedUriString = uri.toString(), isRecording = false, recordingSeconds = 0) }
        }

        repository.onPanoramaSliceCaptured = { bytes ->
            val az = _uiState.value.azimuthDeg
            panoramaSlices.add(bytes to az)
            _uiState.update { it.copy(panoramaCapturedFrames = panoramaSlices.size) }
        }

        repository.onOrientationChanged = { map ->
            val pitch = map["pitch"] ?: 0.0
            val roll = map["roll"] ?: 0.0
            val azimuth = map["azimuth"] ?: 0.0
            _uiState.update { it.copy(pitchDeg = pitch.toFloat(), rollDeg = roll.toFloat(), azimuthDeg = azimuth.toFloat()) }
            if (_uiState.value.isPanoramaCapturing) {
                processPanoramaOrientation(azimuth.toFloat(), pitch.toFloat(), roll.toFloat())
            }
        }

        repository.onError = { msg ->
            _uiState.update { it.copy(statusMessage = msg, isCapturing = false, isRecording = false, isPanoramaCapturing = false) }
        }

        startTelemetryPolling()

        repository.startBackgroundIdSpam { newCam ->
            viewModelScope.launch(Dispatchers.Main) {
                _uiState.update { current ->
                    if (current.availableCameras.none { it.cameraId == newCam.cameraId }) {
                        current.copy(availableCameras = current.availableCameras + newCam)
                    } else {
                        current
                    }
                }
            }
        }
    }

    private var videoTimerJob: kotlinx.coroutines.Job? = null
    private var timeLapseJob: kotlinx.coroutines.Job? = null
    private var automationJob: kotlinx.coroutines.Job? = null

    fun onSurfaceAvailable(surface: Surface) {
        activeSurface = surface
        repository.openCamera(_uiState.value.activeCameraId, surface) {
            _uiState.update { it.copy(ready = true) }
        }
    }

    fun onSurfaceDestroyed() {
        activeSurface = null
        repository.onSurfaceDestroyed()
    }

    private fun startTelemetryPolling() {
        viewModelScope.launch(Dispatchers.Default) {
            val scores = FloatArray(4)
            val floats = FloatArray(10)
            val luma = IntArray(256)
            val wave = FloatArray(64)
            val keyVals = FloatArray(6)

            while (isActive) {
                try {
                    if (!com.txor.commix.bridge.NativeTelemetryBridge.isLoaded) {
                        delay(500)
                        continue
                    }

                    // 1. Read live C++ seqlock telemetry (Scopes, Audio VU, IMU, Spot IRE)
                    val hasTelemetry = com.txor.commix.bridge.NativeTelemetryBridge.nativeReadTelemetry(floats, luma, wave)

                    // 2. Poll Best Shot Evaluation at 30Hz
                    val spot = if (hasTelemetry) floats[7] else 50.0f
                    val hiClip = if (hasTelemetry) floats[8] else 0.0f
                    val loClip = if (hasTelemetry) floats[9] else 0.0f
                    val shouldShoot = NativeEngineBridge.nativeEvaluateBestShot(spot, hiClip, loClip, scores)
                    val totalScore = scores[3]

                    _uiState.update { current ->
                        current.copy(
                            audioNorm = if (hasTelemetry) floats[0] else current.audioNorm,
                            audioDb = if (hasTelemetry) floats[1] else current.audioDb,
                            spotIre = if (hasTelemetry) floats[7] else current.spotIre,
                            hiClipPct = if (hasTelemetry) floats[8] else current.hiClipPct,
                            loClipPct = if (hasTelemetry) floats[9] else current.loClipPct,
                            lumaHistogram = if (hasTelemetry && (current.showHistogram || current.showScopes)) luma.clone() else current.lumaHistogram,
                            waveformCols = if (hasTelemetry && (current.showWaveform || current.showScopes)) wave.clone() else current.waveformCols,
                            bestShotScore = totalScore,
                            shouldShowShootNow = shouldShoot
                        )
                    }
                } catch (_: Throwable) {}

                delay(33) // ~30Hz
            }
        }
    }

    fun onModeSelected(mode: String) {
        val cur = _uiState.value
        val oldMode = cur.activeMode
        if (oldMode == mode) return

        val oldSettings = com.txor.commix.model.ModeSettings(
            iso = cur.iso,
            shutterNs = cur.shutterNs,
            evStops = cur.evStops,
            wbKelvin = cur.wbKelvin,
            focusDiopters = cur.focusDiopters,
            zoomRatio = cur.zoomRatio,
            toneCurve = cur.toneCurve,
            filmGrain = cur.filmGrain,
            isProcessZero = cur.isProcessZeroActive,
            showProControls = cur.showProControls
        )
        val updatedMap = cur.perModeSettings.toMutableMap()
        updatedMap[oldMode] = oldSettings

        val restored = updatedMap[mode]
        _uiState.update {
            it.copy(
                activeMode = mode,
                perModeSettings = updatedMap,
                iso = restored?.iso ?: 0,
                shutterNs = restored?.shutterNs ?: 0L,
                evStops = restored?.evStops ?: 0f,
                wbKelvin = restored?.wbKelvin ?: 0,
                focusDiopters = restored?.focusDiopters ?: -1f,
                zoomRatio = restored?.zoomRatio ?: 1.0,
                toneCurve = restored?.toneCurve ?: "neutral",
                filmGrain = restored?.filmGrain ?: "off",
                isProcessZeroActive = restored?.isProcessZero ?: false,
                showProControls = restored?.showProControls ?: (mode == "PRO")
            )
        }
        updateRepositoryParams()
    }

    fun onIsoChanged(iso: Int) {
        _uiState.update { it.copy(iso = iso) }
        updateRepositoryParams()
    }

    fun onShutterChanged(shutterNs: Long) {
        _uiState.update { it.copy(shutterNs = shutterNs) }
        updateRepositoryParams()
    }

    fun onEvChanged(ev: Float) {
        _uiState.update { it.copy(evStops = ev) }
        updateRepositoryParams()
    }

    fun onWbChanged(kelvin: Int) {
        _uiState.update { it.copy(wbKelvin = kelvin) }
        updateRepositoryParams()
    }

    fun onFocusChanged(diopters: Float) {
        _uiState.update { it.copy(focusDiopters = diopters) }
        updateRepositoryParams()
    }

    fun onZoomChanged(zoom: Double) {
        _uiState.update { it.copy(zoomRatio = zoom.coerceIn(1.0, it.maxZoomRatio)) }
        updateRepositoryParams()
    }

    fun onSwitchCameraTapped() {
        val cameras = _uiState.value.availableCameras
        if (cameras.isEmpty()) return

        val currentCam = cameras.find { it.cameraId == _uiState.value.activeCameraId }
        val nextCam = if (currentCam?.isFront == true) {
            cameras.firstOrNull { !it.isFront && !it.isMacro && !it.isDepth }
                ?: cameras.firstOrNull { !it.isFront }
                ?: cameras.first()
        } else {
            cameras.firstOrNull { it.isFront }
                ?: cameras[(cameras.indexOf(currentCam) + 1).coerceAtLeast(0) % cameras.size]
        }

        onSelectCamera(nextCam.cameraId)
    }

    fun onShutterTapped() {
        val s = _uiState.value

        when (s.activeMode) {
            "VIDEO", "CINEMATIC", "SLOW-MO" -> {
                if (s.isRecording) {
                    videoTimerJob?.cancel()
                    videoTimerJob = null
                    repository.stopVideoRecording()
                    _uiState.update { it.copy(isRecording = false) }
                } else {
                    val isSlowMo = (s.activeMode == "SLOW-MO")
                    val effectiveFps = if (isSlowMo) s.slowMoFps else s.videoFps
                    val effectiveFpsFloat = if (isSlowMo) s.slowMoFps.toDouble() else s.videoFpsFloat
                    repository.startVideoRecording(
                        res = s.videoResolution,
                        fps = effectiveFps,
                        bitrate = s.videoBitrateBps,
                        isVfDisabled = s.viewfinderDisabled,
                        targetFpsFloat = effectiveFpsFloat,
                        isSlowMotion = isSlowMo
                    )
                    _uiState.update { it.copy(isRecording = true, recordingSeconds = 0) }
                    videoTimerJob = viewModelScope.launch {
                        while (isActive) {
                            delay(1000)
                            _uiState.update { it.copy(recordingSeconds = it.recordingSeconds + 1) }
                        }
                    }
                }
            }
            "PANORAMA" -> {
                if (s.isPanoramaCapturing) {
                    finishPanoramaCapture()
                } else {
                    startPanoramaCapture()
                }
            }
            "NIGHT" -> {
                if (s.isCapturing) return
                _uiState.update { it.copy(isCapturing = true) }
                viewModelScope.launch {
                    delay(3000)
                    if (_uiState.value.isCapturing) {
                        _uiState.update { it.copy(isCapturing = false) }
                    }
                }
                val meta = createShotMetadata(s)
                repository.takeNightBurst(meta)
            }
            "TIME-LAPSE" -> {
                if (s.isRecording) {
                    timeLapseJob?.cancel()
                    timeLapseJob = null
                    _uiState.update { it.copy(isRecording = false, timeLapseFrames = 0) }
                } else {
                    _uiState.update { it.copy(isRecording = true, timeLapseFrames = 0) }
                    timeLapseJob = viewModelScope.launch {
                        while (isActive) {
                            val meta = createShotMetadata(_uiState.value)
                            repository.takePicture(meta)
                            _uiState.update { it.copy(timeLapseFrames = it.timeLapseFrames + 1) }
                            val intervalMs = (_uiState.value.timeLapseIntervalSec.toDouble() * 1000.0).toLong().coerceAtLeast(500L)
                            delay(intervalMs)
                        }
                    }
                }
            }
            "AUTOMATION" -> {
                if (s.isRecording || s.isAutomationRunning) {
                    stopAutomation()
                } else {
                    startPresetAutomation(s.automationPresetId, s.automationTriggerMode)
                }
            }
            else -> { // "PHOTO", "PRO"
                if (s.isCapturing) return
                _uiState.update { it.copy(isCapturing = true) }
                viewModelScope.launch {
                    delay(3000)
                    if (_uiState.value.isCapturing) {
                        _uiState.update { it.copy(isCapturing = false) }
                    }
                }
                val meta = createShotMetadata(s)
                repository.takePicture(meta)
            }
        }
    }

    private fun createShotMetadata(s: CameraUiState): ShotMetadata {
        return ShotMetadata(
            iso = if (s.iso > 0) s.iso else null,
            exposureTimeNs = if (s.shutterNs > 0) s.shutterNs else null,
            focusDistance = if (s.focusDiopters >= 0) s.focusDiopters else null,
            digitalZoom = s.zoomRatio,
            toneCurve = s.toneCurve,
            filmGrain = s.filmGrain,
            lutLook = s.lutLook,
            slateRoll = s.slateRoll,
            slateScene = s.slateScene,
            slateTake = s.slateTake,
            compressionFormat = s.photoFormat,
            compressionQuality = s.photoQuality
        )
    }

    fun toggleProControls() {
        _uiState.update { it.copy(showProControls = !it.showProControls) }
    }

    fun toggleHistogram() {
        _uiState.update { it.copy(showHistogram = !it.showHistogram) }
    }

    fun toggleWaveform() {
        _uiState.update { it.copy(showWaveform = !it.showWaveform) }
        preferences.save(_uiState.value)
    }

    fun toggleHorizon() {
        _uiState.update { it.copy(showHorizonLeveler = !it.showHorizonLeveler) }
        preferences.save(_uiState.value)
    }

    fun toggleGrid() {
        _uiState.update { it.copy(showGrid = !it.showGrid) }
        preferences.save(_uiState.value)
    }

    fun onToneCurveChanged(curve: String) {
        _uiState.update { it.copy(toneCurve = curve) }
        updateRepositoryParams()
    }

    fun onFilmGrainChanged(grain: String) {
        _uiState.update { it.copy(filmGrain = grain) }
        updateRepositoryParams()
    }

    fun onLutChanged(lut: String) {
        _uiState.update { it.copy(lutLook = lut) }
        updateRepositoryParams()
    }

    fun onFormatChanged(format: String, quality: Int) {
        _uiState.update { it.copy(photoFormat = format, photoQuality = quality) }
        preferences.save(_uiState.value)
    }

    private fun updateRepositoryParams() {
        val s = _uiState.value
        val params = CaptureRequestParams(
            iso = s.iso,
            expNs = s.shutterNs,
            evStops = s.evStops,
            evStep = s.evStep,
            focusDiopters = s.focusDiopters,
            wbKelvin = s.wbKelvin,
            zoom = s.zoomRatio,
            hardwareMaxDigitalZoom = s.hardwareMaxDigitalZoom,
            oisMode = s.oisMode,
            eisMode = s.eisMode,
            activeMode = s.activeMode,
            targetPhotoMp = s.targetPhotoMp,
            toneCurve = s.toneCurve,
            filmGrain = s.filmGrain,
            lutLook = s.lutLook,
            isProcessZero = s.isProcessZeroActive,
            processZeroIntensity = s.processZeroIntensity,
            flashMode = s.flashMode,
            isViewfinderDisabled = s.viewfinderDisabled
        )
        repository.exifOptions = com.txor.commix.model.ExifOptions(
            cameraInfo = s.exifCameraInfo,
            location = s.exifLocation,
            userComment = s.exifUserComment,
            lensSpecs = s.exifLensInfo,
            exposureSettings = s.exifExposureSettings,
            dateTime = s.exifDateTime,
            imageDescription = s.exifImageDescription
        )
        repository.updateParams(params)
    }


    fun onSelectCamera(cameraId: String) {
        if (_uiState.value.activeCameraId == cameraId) return
        val cam = _uiState.value.availableCameras.find { it.cameraId == cameraId }
        _uiState.update {
            it.copy(
                activeCameraId = cameraId,
                maxLensMp = cam?.sensorMp ?: it.maxLensMp,
                targetPhotoMp = cam?.sensorMp ?: it.targetPhotoMp,
                hardwareMaxDigitalZoom = cam?.maxDigitalZoom ?: 10.0,
                maxZoomRatio = if (it.spaceZoomLockEngaged) 100.0 else 30.0,
                ready = false
            )
        }
        activeSurface?.let { surface ->
            repository.openCamera(cameraId, surface) {
                _uiState.update { it.copy(ready = true) }
                updateRepositoryParams()
            }
        }
    }

    fun onSelectLensPill(cameraId: String, targetZoom: Double) {
        if (_uiState.value.activeCameraId != cameraId) {
            onSelectCamera(cameraId)
        }
        onZoomChanged(targetZoom)
    }

    fun onSelectLensPreset(type: String) {
        val cameras = _uiState.value.availableCameras
        when (type.lowercase()) {
            "0.6x", "15mm", "ultrawide", "ultra_wide" -> {
                val uw = cameras.firstOrNull { it.isUltraWide }
                if (uw != null) {
                    onSelectCamera(uw.cameraId)
                    onZoomChanged(1.0)
                } else {
                    onZoomChanged(1.0)
                }
            }
            "1x", "26mm", "wide" -> {
                val wide = cameras.firstOrNull { !it.isFront && !it.isMacro && !it.isDepth && !it.isUltraWide }
                    ?: cameras.firstOrNull { !it.isFront }
                if (wide != null) {
                    onSelectCamera(wide.cameraId)
                }
                onZoomChanged(1.0)
            }
            "2x", "52mm", "tele", "telephoto" -> {
                val tele = cameras.firstOrNull { it.isTelephoto }
                if (tele != null) {
                    onSelectCamera(tele.cameraId)
                    onZoomChanged(1.0)
                } else {
                    val wide = cameras.firstOrNull { !it.isFront && !it.isMacro && !it.isDepth && !it.isUltraWide }
                        ?: cameras.firstOrNull { !it.isFront }
                    if (wide != null && _uiState.value.activeCameraId != wide.cameraId) {
                        onSelectCamera(wide.cameraId)
                    }
                    onZoomChanged(2.0)
                }
            }
            "macro" -> {
                val macro = cameras.firstOrNull { it.isMacro }
                if (macro != null) {
                    onSelectCamera(macro.cameraId)
                    onZoomChanged(1.0)
                } else {
                    onZoomChanged(2.0)
                    onFocusChanged(10.0f)
                }
            }
        }
    }

    fun onProbeAllCameras() {
        val probed = repository.getProbedCameras()
        _uiState.update {
            it.copy(
                availableCameras = probed,
                statusMessage = "Probing IDs -10000..10000..."
            )
        }
        repository.startBackgroundIdSpam { newCam ->
            viewModelScope.launch(Dispatchers.Main) {
                _uiState.update { current ->
                    if (current.availableCameras.none { it.cameraId == newCam.cameraId }) {
                        current.copy(
                            availableCameras = current.availableCameras + newCam,
                            statusMessage = "Discovered Camera ID ${newCam.cameraId}!"
                        )
                    } else {
                        current
                    }
                }
            }
        }
    }

    fun onCycleFlash() {
        val nextFlash = when (_uiState.value.flashMode) {
            "off" -> "auto"
            "auto" -> "on"
            "on" -> "torch"
            else -> "off"
        }
        _uiState.update { it.copy(flashMode = nextFlash) }
        updateRepositoryParams()
        showStatus("Flash: ${nextFlash.uppercase()}")
    }

    fun onCycleEis() {
        val nextEis = when (_uiState.value.eisMode) {
            "off" -> "standard"
            "standard" -> "super_steady"
            "super_steady" -> "legend"
            else -> "off"
        }
        _uiState.update { it.copy(eisMode = nextEis) }
        updateRepositoryParams()
    }

    fun onToggleRaw() {
        val nextRaw = if (_uiState.value.photoFormat == "raw_dng") "jpeg" else "raw_dng"
        _uiState.update { it.copy(photoFormat = nextRaw) }
    }

    fun onUpdatePhotoMp(mp: Double) {
        _uiState.update { it.copy(targetPhotoMp = mp, isHiResActive = mp > 20.0) }
        updateRepositoryParams()
    }

    fun onTapFocus(normX: Float, normY: Float) {
        repository.triggerTapFocus(normX, normY)
    }

    fun onCancelFocus() {
        repository.cancelFocus()
    }

    fun onUpdateVideoFormat(resolution: String, fpsFloat: Double, bitrateBps: Int) {
        _uiState.update {
            it.copy(
                videoResolution = resolution,
                videoFpsFloat = fpsFloat,
                videoFps = fpsFloat.toInt(),
                videoBitrateBps = bitrateBps
            )
        }
        preferences.save(_uiState.value)
    }

    fun getSupportedVideoSizes(): List<String> {
        val deviceSizes = repository.getSupportedVideoSizes(_uiState.value.activeCameraId)
        return PipelineThroughputEngine.getStandardResolutions(deviceSizes)
    }

    fun onSetViewfinderDisabled(disabled: Boolean) {
        _uiState.update { it.copy(viewfinderDisabled = disabled) }
        preferences.save(_uiState.value)
        showStatus(if (disabled) "Cinema Headless (Blind VF) Active" else "Live Viewfinder Restored")
    }

    fun getSupportedFpsList(): List<Double> {
        return repository.getSupportedFpsList(_uiState.value.activeCameraId)
    }

    fun onCycleResolution() {
        val resolutions = getSupportedVideoSizes()
        val currentIdx = resolutions.indexOf(_uiState.value.videoResolution)
        val nextRes = resolutions[(currentIdx + 1).coerceAtLeast(0) % resolutions.size]
        val newMax = PipelineThroughputEngine.getMaxFpsForResolution(
            resolution = nextRes,
            isVfDisabled = _uiState.value.viewfinderDisabled
        )
        val clampedFps = if (_uiState.value.videoFpsFloat > newMax) newMax else _uiState.value.videoFpsFloat
        _uiState.update {
            it.copy(
                videoResolution = nextRes,
                videoFpsFloat = clampedFps,
                videoFps = clampedFps.toInt()
            )
        }
    }

    fun onCycleFps() {
        val fpsList = getSupportedFpsList()
        val currentFps = _uiState.value.videoFpsFloat
        val currentIdx = fpsList.indexOfFirst { kotlin.math.abs(it - currentFps) < 0.1 }
        val nextFps = fpsList[(currentIdx + 1).coerceAtLeast(0) % fpsList.size]
        _uiState.update { it.copy(videoFps = nextFps.toInt(), videoFpsFloat = nextFps) }
    }

    fun onShutterAngleChanged(angleDeg: Double) {
        val fps = if (_uiState.value.videoFpsFloat > 0) _uiState.value.videoFpsFloat else 30.0
        val ns = (1_000_000_000.0 * (angleDeg / 360.0) / fps).toLong()
        _uiState.update { it.copy(shutterAngleDeg = angleDeg, shutterNs = ns) }
        updateRepositoryParams()
    }

    fun onToggleShutter180Lock() {
        val newLocked = !_uiState.value.shutterAngleLocked
        _uiState.update { it.copy(shutterAngleLocked = newLocked) }
        if (newLocked) {
            onShutterAngleChanged(180.0)
            showStatus("180° Cinema Shutter Locked")
        } else {
            showStatus("Shutter Angle Unlocked")
        }
    }

    fun onNdFilterChanged(filter: String) {
        val evDrop = when (filter) {
            "nd2" -> -1.0f
            "nd4" -> -2.0f
            "nd8" -> -3.0f
            "nd16" -> -4.0f
            "nd64" -> -6.0f
            "nd1000" -> -10.0f
            else -> 0.0f
        }
        _uiState.update { it.copy(ndFilter = filter, evStops = evDrop) }
        updateRepositoryParams()
        showStatus("e-ND: ${filter.uppercase()} ($evDrop EV)")
    }

    fun onAnamorphicChanged(ratio: Float) {
        _uiState.update { it.copy(anamorphicRatio = ratio) }
        showStatus(if (ratio == 1.0f) "Anamorphic De-Squeeze: Off" else "Anamorphic De-Squeeze: ${ratio}×")
    }

    fun onAebChanged(enabled: Boolean, shots: Int, spreadEv: Float) {
        _uiState.update { it.copy(aebEnabled = enabled, aebShots = shots, aebSpreadEv = spreadEv) }
        showStatus(if (enabled) "AEB Active: $shots-Shot (±${spreadEv} EV)" else "AEB Disabled")
    }

    fun onAudioGainChanged(gainDb: Float) {
        _uiState.update { it.copy(audioGainDb = gainDb) }
        repository.audioEngine.setGain(gainDb)
        showStatus("Audio Mic Gain: ${gainDb.toInt()} dB")
    }

    fun onToggleAudioLowCut() {
        val next = !_uiState.value.audioLowCut
        _uiState.update { it.copy(audioLowCut = next) }
        repository.audioEngine.setLowCut(next)
        showStatus(if (next) "Audio Low Cut: 80Hz Active" else "Audio Low Cut: Off")
    }

    fun onToggleProcessZero() {
        val next = !_uiState.value.isProcessZeroActive
        _uiState.update { it.copy(isProcessZeroActive = next) }
        updateRepositoryParams()
        showStatus(if (next) "Process Zero: Computational Smoothing Bypassed" else "Standard Engine Active")
    }

    fun onProcessZeroIntensityChanged(intensity: Float) {
        _uiState.update { it.copy(processZeroIntensity = intensity) }
        updateRepositoryParams()
    }

    fun onSelectAutomationPreset(presetId: String) {
        _uiState.update { it.copy(automationPresetId = presetId) }
    }

    fun onToggleSpaceZoomLock() {
        val next = !_uiState.value.spaceZoomLockEngaged
        val maxZ = if (next) 100.0 else 30.0
        _uiState.update { it.copy(spaceZoomLockEngaged = next, maxZoomRatio = maxZ) }
        showStatus(if (next) "Space Zoom (100×) Stabilization Locked" else "Zoom Lock Released (30× Max)")
    }

    fun onToggleCleanViewfinder() {
        val next = !_uiState.value.cleanViewfinderMode
        _uiState.update { it.copy(cleanViewfinderMode = next) }
        preferences.save(_uiState.value)
        showStatus(if (next) "Clean Viewfinder Mode Active" else "Standard Overlays Visible")
    }

    fun onToggleViewfinderDisabled() {
        val next = !_uiState.value.viewfinderDisabled
        _uiState.update { it.copy(viewfinderDisabled = next) }
        preferences.save(_uiState.value)
        showStatus(if (next) "Viewfinder Stream Detached (Blind Mode)" else "Viewfinder Preview Active")
    }

    fun onSelectGridType(grid: String) {
        _uiState.update { it.copy(gridType = grid, showGrid = (grid != "off")) }
        preferences.save(_uiState.value)
    }

    fun onToggleHaptics() {
        val next = !_uiState.value.hapticsEnabled
        _uiState.update { it.copy(hapticsEnabled = next) }
        preferences.save(_uiState.value)
        showStatus(if (next) "Haptic Feedback Enabled" else "Haptics Disabled")
    }

    fun onToggleAnimations() {
        val next = !_uiState.value.animationsEnabled
        _uiState.update { it.copy(animationsEnabled = next) }
        preferences.save(_uiState.value)
    }

    fun onToggleStorageRemaining() {
        val next = !_uiState.value.showStorageRemaining
        _uiState.update { it.copy(showStorageRemaining = next) }
        preferences.save(_uiState.value)
    }

    fun onToggleAdaptivePerformance() {
        val next = !_uiState.value.adaptivePerformanceEnabled
        _uiState.update { it.copy(adaptivePerformanceEnabled = next) }
        preferences.save(_uiState.value)
        showStatus(if (next) "Adaptive Performance: Dynamic Tuning" else "Adaptive Performance: Standard")
    }

    fun onSetExifPreset(privacyMode: Boolean) {
        _uiState.update {
            it.copy(
                exifCameraInfo = true,
                exifLensInfo = true,
                exifExposureSettings = true,
                exifDateTime = true,
                exifLocation = !privacyMode,
                exifImageDescription = !privacyMode,
                exifUserComment = !privacyMode,
                exifColorGeometry = true
            )
        }
        preferences.save(_uiState.value)
        showStatus(if (privacyMode) "EXIF Privacy Mode: Geotags Stripped" else "EXIF: All Optical & Location Metadata Allowed")
    }

    fun onToggleExifLocation() {
        val next = !_uiState.value.exifLocation
        _uiState.update { it.copy(exifLocation = next) }
        preferences.save(_uiState.value)
    }

    fun onToggleExifCameraInfo() {
        val next = !_uiState.value.exifCameraInfo
        _uiState.update { it.copy(exifCameraInfo = next) }
        preferences.save(_uiState.value)
    }

    fun onToggleExifExposure() {
        val next = !_uiState.value.exifExposureSettings
        _uiState.update { it.copy(exifExposureSettings = next) }
        preferences.save(_uiState.value)
    }

    fun onToggleExifLensInfo() {
        val next = !_uiState.value.exifLensInfo
        _uiState.update { it.copy(exifLensInfo = next) }
        preferences.save(_uiState.value)
    }

    fun onToggleExifDateTime() {
        val next = !_uiState.value.exifDateTime
        _uiState.update { it.copy(exifDateTime = next) }
        preferences.save(_uiState.value)
    }

    fun onToggleExifImageDesc() {
        val next = !_uiState.value.exifImageDescription
        _uiState.update { it.copy(exifImageDescription = next) }
        preferences.save(_uiState.value)
    }

    fun onSetVideoCodec(codec: String) {
        _uiState.update { it.copy(videoCodec = codec) }
        preferences.save(_uiState.value)
        showStatus("Video Codec: ${codec.uppercase()}")
    }

    fun onSetEnhanceAlgo(algo: String) {
        _uiState.update { it.copy(photoEnhanceAlgo = algo) }
        preferences.save(_uiState.value)
        showStatus("Enhancement Model: ${algo.uppercase()}")
    }

    fun onToggleUncompressedAudio() {
        val next = !_uiState.value.uncompressedAudio
        _uiState.update { it.copy(uncompressedAudio = next) }
        preferences.save(_uiState.value)
        showStatus(if (next) "Audio: LPCM 48kHz Uncompressed" else "Audio: AAC Standard")
    }

    fun onSetDefaultShutterAngle(angle: String) {
        _uiState.update { it.copy(defaultShutterAngle = angle) }
        preferences.save(_uiState.value)
        showStatus("Default Shutter Angle: ${angle}°")
    }

    fun onSetCinemaMatteRatio(ratio: String) {
        _uiState.update { it.copy(cinemaMatteRatio = ratio) }
        preferences.save(_uiState.value)
        showStatus("Cinema Matte: $ratio")
    }

    fun onSetNightFrameCount(frames: Int) {
        _uiState.update { it.copy(nightFrameCount = frames) }
        preferences.save(_uiState.value)
        showStatus("Night Multi-Frame Burst: $frames Frames")
    }

    fun onToggleNightAstro() {
        val next = !_uiState.value.nightAstroAlignment
        _uiState.update { it.copy(nightAstroAlignment = next) }
        preferences.save(_uiState.value)
        showStatus(if (next) "Astro Star Tracking: Active" else "Astro Star Tracking: Off")
    }

    fun onSetNightTone(tone: String) {
        _uiState.update { it.copy(nightTone = tone) }
        preferences.save(_uiState.value)
        showStatus("Night Tone: ${tone.uppercase()}")
    }

    fun onSetSlowMoFps(fps: Int) {
        _uiState.update { it.copy(slowMoFps = fps) }
        preferences.save(_uiState.value)
        showStatus("Slow-Mo Readout: $fps FPS")
    }

    fun onSetTimeLapseInterval(sec: Int) {
        _uiState.update { it.copy(timeLapseIntervalSec = sec) }
        preferences.save(_uiState.value)
        showStatus("Time-Lapse Interval: ${sec}s")
    }

    fun onToggleFlagshipRemosaic() {
        val next = !_uiState.value.flagshipRemosaicPass
        _uiState.update { it.copy(flagshipRemosaicPass = next) }
        preferences.save(_uiState.value)
        showStatus(if (next) "MediaTek Imagiq Remosaic Pass Enabled" else "Remosaic Pass Bypassed")
    }

    fun onToggleFlagshipAuxWhitelist() {
        val next = !_uiState.value.flagshipAuxWhitelist
        _uiState.update { it.copy(flagshipAuxWhitelist = next) }
        preferences.save(_uiState.value)
        showStatus(if (next) "Qualcomm Aux Package Whitelist Active" else "Aux Whitelist Standard")
    }

    fun onSetAutomationTrigger(trigger: String) {
        _uiState.update { it.copy(automationTriggerMode = trigger) }
        preferences.save(_uiState.value)
        showStatus("Automation Trigger: ${trigger.uppercase()}")
    }

    fun onSetQueueWorkerThreads(threads: Int) {
        _uiState.update { it.copy(queueWorkerThreads = threads) }
        preferences.save(_uiState.value)
        showStatus("Job Queue Worker Cores: $threads")
    }

    fun onToggleQueueAutoStacking() {
        val next = !_uiState.value.queueAutoQueueStacking
        _uiState.update { it.copy(queueAutoQueueStacking = next) }
        preferences.save(_uiState.value)
        showStatus(if (next) "Background Job Queue Stacking Enabled" else "Synchronous Capture Active")
    }

    fun onToggle360SphereMode() {
        val next = !_uiState.value.is360SphereMode
        _uiState.update { it.copy(is360SphereMode = next) }
        preferences.save(_uiState.value)
        showStatus(if (next) "360° Photo Sphere Mode Active" else "Standard Panorama Mode")
    }

    private var startPanoramaAzimuth: Float = 0f
    private var startPanoramaPitch: Float = 0f
    private var lastSliceAzimuth: Float = 0f
    private val panoramaSlices = Collections.synchronizedList(mutableListOf<Pair<ByteArray, Float>>())

    fun onUpdatePanoramaProgress(sweepAngle: Float, progress: Float) {
        _uiState.update { it.copy(panoramaSweepAngle = sweepAngle, panoramaProgress = progress) }
    }

    fun startPanoramaCapture() {
        val s = _uiState.value
        panoramaSlices.clear()
        val currentAz = s.azimuthDeg
        val currentPi = s.pitchDeg
        startPanoramaAzimuth = currentAz
        startPanoramaPitch = currentPi
        lastSliceAzimuth = currentAz

        _uiState.update {
            it.copy(
                isPanoramaCapturing = true,
                panoramaSweepAngle = 0f,
                panoramaProgress = 0f,
                panoramaCapturedFrames = 0
            )
        }
        repository.startPanoramaCapture()

        // Capture initial frame 0 immediately
        val meta = createShotMetadata(s)
        repository.takePanoramaSlice(meta)

        showStatus(if (s.is360SphereMode) "Capturing 360° Sphere: Sweep full circle" else "Capturing Panorama: Pan horizontally along the line")
    }

    private fun processPanoramaOrientation(azimuth: Float, pitch: Float, roll: Float) {
        if (!_uiState.value.isPanoramaCapturing) return

        val is360 = _uiState.value.is360SphereMode
        val deltaFromStart = PanoramaStitcher.calculateWrappedDelta(azimuth, startPanoramaAzimuth)
        val sweepAngle = abs(deltaFromStart)
        val progress = PanoramaStitcher.calculateProgress(sweepAngle, is360)

        _uiState.update {
            it.copy(panoramaSweepAngle = sweepAngle, panoramaProgress = progress)
        }

        // Trigger slice capture every 15 degrees of angular sweep
        val deltaSinceLastSlice = abs(PanoramaStitcher.calculateWrappedDelta(azimuth, lastSliceAzimuth))
        if (deltaSinceLastSlice >= 15.0f) {
            lastSliceAzimuth = azimuth
            val meta = createShotMetadata(_uiState.value)
            repository.takePanoramaSlice(meta)
            CommixHaptics.tick(getApplication(), _uiState.value.hapticsEnabled)
        }

        // If sweep reaches 100%, automatically finish and stitch
        if (progress >= 1.0f) {
            finishPanoramaCapture()
        }
    }

    fun finishPanoramaCapture() {
        val isCapturing = _uiState.value.isPanoramaCapturing
        if (!isCapturing) return

        _uiState.update { it.copy(isPanoramaCapturing = false) }
        val repoSlices = repository.stopPanoramaCapture()
        val is360 = _uiState.value.is360SphereMode

        viewModelScope.launch(Dispatchers.Default) {
            val slicesToStitch = synchronized(panoramaSlices) {
                if (panoramaSlices.isNotEmpty()) {
                    ArrayList(panoramaSlices)
                } else {
                    repoSlices.mapIndexed { idx, bytes -> bytes to (idx * 15.0f) }
                }
            }

            if (slicesToStitch.isEmpty()) {
                _uiState.update { it.copy(panoramaProgress = 0f, panoramaSweepAngle = 0f, panoramaCapturedFrames = 0) }
                showStatus("No panorama frames captured")
                return@launch
            }

            showStatus("Stitching ${if (is360) "360° Photo Sphere" else "Panorama"} (${slicesToStitch.size} frames)...")

            try {
                val stitchedBytes = if (slicesToStitch.size == 1) {
                    slicesToStitch.first().first
                } else {
                    PanoramaStitcher.stitchByteArrays(
                        rawSlices = slicesToStitch,
                        is360 = is360
                    )
                }

                repository.savePanoramaImage(stitchedBytes, is360 = is360) {
                    showStatus(if (is360) "360° Photo Sphere saved" else "Panorama saved successfully")
                }
            } catch (e: Exception) {
                showStatus("Stitching failed: ${e.message}")
            } finally {
                _uiState.update { it.copy(panoramaProgress = 0f, panoramaSweepAngle = 0f, panoramaCapturedFrames = 0) }
            }
        }
    }

    fun startPresetAutomation(
        presetId: String = _uiState.value.automationPresetId,
        triggerMode: String = _uiState.value.automationTriggerMode
    ) {
        val config = AutomationEngine.getPresetById(presetId)
        _uiState.update { it.copy(automationPresetId = presetId, activeMode = "AUTOMATION") }
        startAutomationSequence(config, triggerMode)
    }

    fun startCustomAutomation(
        durationSec: Double,
        startZoom: Double,
        endZoom: Double,
        startEv: Float,
        endEv: Float,
        startFocus: Float,
        endFocus: Float,
        triggerMode: String = _uiState.value.automationTriggerMode
    ) {
        val config = AutomationEngine.createCustomConfig(
            durationSec = durationSec,
            startZoom = startZoom,
            endZoom = endZoom,
            enableZoom = (startZoom != endZoom),
            startEv = startEv,
            endEv = endEv,
            enableEv = (startEv != endEv),
            startFocus = startFocus,
            endFocus = endFocus,
            enableFocus = (startFocus >= 0f && endFocus >= 0f && startFocus != endFocus)
        )
        _uiState.update { it.copy(activeMode = "AUTOMATION") }
        startAutomationSequence(config, triggerMode)
    }

    fun stopAutomation() {
        automationJob?.cancel()
        automationJob = null
        videoTimerJob?.cancel()
        videoTimerJob = null
        AutomationEngine.stopNativeBridge()
        if (_uiState.value.isRecording) {
            repository.stopVideoRecording()
        }
        _uiState.update {
            it.copy(
                isRecording = false,
                isAutomationRunning = false,
                automationCountdownSec = 0,
                automationProgress = 0f
            )
        }
        showStatus("Automation Sequence Stopped")
    }

    private fun startAutomationSequence(config: AutomationPresetConfig, triggerMode: String) {
        automationJob?.cancel()
        automationJob = viewModelScope.launch(Dispatchers.Default) {
            val s = _uiState.value
            val countdownSeconds = when (triggerMode) {
                "timer_3s", "timer" -> 3
                "timer_5s" -> 5
                else -> 0
            }

            if (countdownSeconds > 0) {
                _uiState.update {
                    it.copy(
                        isAutomationRunning = true,
                        automationCountdownSec = countdownSeconds,
                        automationProgress = 0f,
                        automationElapsedSec = 0f,
                        automationDurationSec = config.durationSeconds
                    )
                }
                for (sec in countdownSeconds downTo 1) {
                    _uiState.update { it.copy(automationCountdownSec = sec) }
                    withContext(Dispatchers.Main) {
                        CommixHaptics.tick(getApplication(), s.hapticsEnabled)
                    }
                    delay(1000)
                }
            }

            _uiState.update {
                it.copy(
                    automationCountdownSec = 0,
                    isAutomationRunning = true,
                    isRecording = true,
                    recordingSeconds = 0,
                    automationProgress = 0f,
                    automationElapsedSec = 0f,
                    automationDurationSec = config.durationSeconds
                )
            }

            withContext(Dispatchers.Main) {
                CommixHaptics.heavy(getApplication(), s.hapticsEnabled)
                repository.startVideoRecording(
                    res = s.videoResolution,
                    fps = s.videoFps,
                    bitrate = s.videoBitrateBps,
                    isVfDisabled = s.viewfinderDisabled,
                    targetFpsFloat = s.videoFpsFloat
                )
            }

            AutomationEngine.programNativeBridge(config)

            videoTimerJob?.cancel()
            videoTimerJob = viewModelScope.launch {
                while (isActive) {
                    delay(1000)
                    _uiState.update { it.copy(recordingSeconds = it.recordingSeconds + 1) }
                }
            }

            val totalDurationMs = (config.durationSeconds * 1000f).toLong().coerceAtLeast(100L)
            val startMs = System.currentTimeMillis()

            try {
                while (isActive) {
                    val nowMs = System.currentTimeMillis()
                    val elapsedMs = nowMs - startMs
                    val elapsedSec = (elapsedMs / 1000f).coerceAtMost(config.durationSeconds)
                    val progress = (elapsedMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)

                    val values = config.evaluate(elapsedSec)

                    _uiState.update { cur ->
                        var updated = cur.copy(
                            automationProgress = progress,
                            automationElapsedSec = elapsedSec
                        )
                        values.zoom?.let { z ->
                            updated = updated.copy(zoomRatio = z.coerceIn(1.0, cur.maxZoomRatio))
                        }
                        values.ev?.let { ev ->
                            updated = updated.copy(evStops = ev)
                        }
                        values.focusDiopters?.let { foc ->
                            updated = updated.copy(focusDiopters = foc)
                        }
                        values.wbKelvin?.let { wb ->
                            updated = updated.copy(wbKelvin = wb)
                        }
                        updated
                    }

                    updateRepositoryParams()

                    if (elapsedMs >= totalDurationMs) {
                        break
                    }
                    delay(33) // ~30Hz smooth parameter update
                }
            } finally {
                withContext(NonCancellable) {
                    videoTimerJob?.cancel()
                    videoTimerJob = null
                    AutomationEngine.stopNativeBridge()
                    withContext(Dispatchers.Main) {
                        repository.stopVideoRecording()
                        CommixHaptics.heavy(getApplication(), _uiState.value.hapticsEnabled)
                    }
                    _uiState.update {
                        it.copy(
                            isRecording = false,
                            isAutomationRunning = false,
                            automationProgress = 1.0f,
                            automationElapsedSec = config.durationSeconds
                        )
                    }
                    showStatus("Automation Complete (${config.title})")
                }
            }
        }
    }

    fun runAutomation(
        durationSec: Double,
        startZoom: Double,
        endZoom: Double,
        startEv: Float,
        endEv: Float,
        startFocus: Float,
        endFocus: Float
    ) {
        startCustomAutomation(
            durationSec = durationSec,
            startZoom = startZoom,
            endZoom = endZoom,
            startEv = startEv,
            endEv = endEv,
            startFocus = startFocus,
            endFocus = endFocus
        )
    }

    fun openGallery(context: Context) {
        val lastUriStr = _uiState.value.lastSavedUriString
        val lastUri = if (lastUriStr != null) Uri.parse(lastUriStr) else null
        val intentList = mutableListOf<Intent>()
        if (lastUri != null) {
            intentList.add(Intent(Intent.ACTION_VIEW).apply {
                val isVid = lastUri.toString().contains("video") || lastUri.toString().endsWith(".mp4")
                setDataAndType(lastUri, if (isVid) "video/*" else "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        intentList.add(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_GALLERY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        intentList.add(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        intentList.add(Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        intentList.add(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })

        for (intent in intentList) {
            try {
                context.startActivity(intent)
                return
            } catch (_: Throwable) {}
        }
        showStatus("Could not find gallery app")
    }

    fun showStatus(message: String) {
        _uiState.update { it.copy(statusMessage = message) }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        repository.release()
    }
}
