package com.txor.commix.model

import androidx.compose.runtime.Immutable
import com.txor.commix.CameraProbeResult

@Immutable
data class CameraUiState(
    val ready: Boolean = false,
    val activeMode: String = "PHOTO",
    val isRecording: Boolean = false,
    val recordingSeconds: Int = 0,
    val isCapturing: Boolean = false,
    val activeCameraId: String = "0",
    val availableCameras: List<CameraProbeResult> = emptyList(),

    // Pro Exposure Parameters
    val iso: Int = 0, // 0 = Auto
    val shutterNs: Long = 0L, // 0L = Auto
    val shutterAngleDeg: Double = 180.0,
    val evStops: Float = 0.0f,
    val evStep: Float = 0.1f,
    val wbKelvin: Int = 0, // 0 = Auto
    val focusDiopters: Float = -1.0f, // -1.0f = AF
    val zoomRatio: Double = 1.0,
    val maxZoomRatio: Double = 10.0,

    // Formats & Resolution
    val photoFormat: String = "jpeg",
    val photoQuality: Int = 95,
    val targetPhotoMp: Double = 0.0,
    val maxLensMp: Double = 0.0,
    val isHiResActive: Boolean = false,
    val videoResolution: String = "1920x1080",
    val videoFps: Int = 30,
    val videoFpsFloat: Double = 30.0,
    val videoBitrateBps: Int = 50000000,

    // Dynamic Preview & Viewport Geometry
    val previewWidth: Int = 1920,
    val previewHeight: Int = 1080,
    val previewAspectRatio: Float = 3f / 4f,

    // Optics & Stabilization
    val oisMode: String = "standard",
    val eisMode: String = "legend",
    val flashMode: String = "off",

    // Color Science & Process Zero
    val toneCurve: String = "neutral",
    val filmGrain: String = "off",
    val grainIntensity: Float = 1.0f,
    val lutLook: String = "none",
    val lutBlend: Float = 1.0f,
    val activeOemProfile: String = "standard",
    val isProcessZeroActive: Boolean = false,
    val processZeroIntensity: Float = 1.0f,

    // Advanced Cinema Optics & Rigging
    val shutterAngleLocked: Boolean = false,
    val wbTint: Float = 0.0f,
    val ndFilter: String = "off",
    val anamorphicRatio: Float = 1.0f,
    val aebEnabled: Boolean = false,
    val aebShots: Int = 3,
    val aebSpreadEv: Float = 1.0f,
    val audioGainDb: Float = 0.0f,
    val audioLowCut: Boolean = false,
    val spaceZoomLockEngaged: Boolean = false,
    val estimatedTimeRemainingMinutes: Int = 180,

    // Production Clapperboard Slate
    val slateRoll: String = "A",
    val slateScene: String = "1",
    val slateTake: String = "1",

    // UI & Scopes Toggles
    val showScopes: Boolean = false,
    val showHistogram: Boolean = false,
    val showWaveform: Boolean = false,
    val showRgbParade: Boolean = false,
    val showVectorscope: Boolean = false,
    val showHorizonLeveler: Boolean = false,
    val showGrid: Boolean = false,
    val showProControls: Boolean = false,
    val azimuthDeg: Float = 0.0f,
    val pitchDeg: Float = 0.0f,
    val rollDeg: Float = 0.0f,

    // Telemetry & Best Shot
    val lumaHistogram: IntArray = IntArray(256),
    val waveformCols: FloatArray = FloatArray(64),
    val audioDb: Float = -60f,
    val audioNorm: Float = 0f,
    val spotIre: Float = 50f,
    val hiClipPct: Float = 0f,
    val loClipPct: Float = 0f,
    val timeLapseFrames: Int = 0,
    val bestShotScore: Float = 0.0f,
    val shouldShowShootNow: Boolean = false,
    val thermalStatus: String = "none",
    val batteryPct: Int = 100,
    val isCharging: Boolean = false,
    val recentThumbnailPath: String? = null,
    val lastSavedUriString: String? = null,
    val automationPresetId: String = "dolly_zoom",
    val isAutomationRunning: Boolean = false,
    val automationProgress: Float = 0f,
    val automationDurationSec: Float = 10f,
    val automationElapsedSec: Float = 0f,
    val automationCountdownSec: Int = 0,
    val statusMessage: String? = null,

    // Additional Per-Mode & GCam Style Parity Fields
    val hardwareMaxDigitalZoom: Double = 10.0,
    val cleanViewfinderMode: Boolean = false,
    val viewfinderDisabled: Boolean = false,
    val gridType: String = "3x3",
    val panoramaSweepAngle: Float = 0f,
    val panoramaProgress: Float = 0f,
    val isPanoramaCapturing: Boolean = false,
    val panoramaCapturedFrames: Int = 0,
    val is360SphereMode: Boolean = false,
    val perModeSettings: Map<String, ModeSettings> = emptyMap(),

    // Universal Settings & System Preferences Parity (1:1 with Flutter)
    val hapticsEnabled: Boolean = true,
    val animationsEnabled: Boolean = true,
    val showStorageRemaining: Boolean = true,
    val adaptivePerformanceEnabled: Boolean = true,
    val exifCameraInfo: Boolean = true,
    val exifLensInfo: Boolean = true,
    val exifExposureSettings: Boolean = true,
    val exifDateTime: Boolean = true,
    val exifLocation: Boolean = false,
    val exifImageDescription: Boolean = true,
    val exifUserComment: Boolean = true,
    val exifColorGeometry: Boolean = true,
    val videoCodec: String = "hevc",
    val photoEnhanceAlgo: String = "clarity",
    val uncompressedAudio: Boolean = false,
    val defaultShutterAngle: String = "180",
    val cinemaMatteRatio: String = "2.39:1",
    val nightFrameCount: Int = 8,
    val nightAstroAlignment: Boolean = true,
    val nightTone: String = "neutral",
    val slowMoFps: Int = 120,
    val timeLapseIntervalSec: Int = 2,
    val flagshipRemosaicPass: Boolean = true,
    val flagshipAuxWhitelist: Boolean = true,
    val automationTriggerMode: String = "timer",
    val queueWorkerThreads: Int = 2,
    val queueAutoQueueStacking: Boolean = true
)

@Immutable
data class ModeSettings(
    val iso: Int = 0,
    val shutterNs: Long = 0L,
    val evStops: Float = 0f,
    val wbKelvin: Int = 0,
    val focusDiopters: Float = -1f,
    val zoomRatio: Double = 1.0,
    val toneCurve: String = "neutral",
    val filmGrain: String = "off",
    val isProcessZero: Boolean = false,
    val showProControls: Boolean = false
)

