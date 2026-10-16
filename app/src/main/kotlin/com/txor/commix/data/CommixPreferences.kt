package com.txor.commix.data

import android.content.Context
import android.content.SharedPreferences
import com.txor.commix.model.CameraUiState

/**
 * 1:1 Complete Persistent Settings Manager matching Flutter's SharedPreferences
 * and app preferences pipeline (_loadAppPreferences / _saveAppPreferences / _loadExifConfig / _saveExifConfig).
 */
class CommixPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "commix_preferences"

        // Universal Display & Guides
        private const val KEY_VIEWFINDER_DISABLED = "viewfinderDisabled"
        private const val KEY_CLEAN_VIEWFINDER = "cleanViewfinderMode"
        private const val KEY_GRID_TYPE = "gridType"
        private const val KEY_HORIZON_LEVELER = "horizonLevelerEnabled"

        // Haptics & System
        private const val KEY_HAPTICS = "hapticsEnabled"
        private const val KEY_ANIMATIONS = "animationsEnabled"
        private const val KEY_SHOW_STORAGE = "showStorageRemaining"
        private const val KEY_ADAPTIVE_PERFORMANCE = "adaptivePerformanceEnabled"

        // EXIF & Privacy
        private const val KEY_EXIF_CAMERA_INFO = "exifCameraInfo"
        private const val KEY_EXIF_LENS_INFO = "exifLensInfo"
        private const val KEY_EXIF_EXPOSURE = "exifExposureSettings"
        private const val KEY_EXIF_DATETIME = "exifDateTime"
        private const val KEY_EXIF_LOCATION = "exifLocation"
        private const val KEY_EXIF_IMAGE_DESC = "exifImageDescription"
        private const val KEY_EXIF_USER_COMMENT = "exifUserComment"
        private const val KEY_EXIF_COLOR_GEOM = "exifColorGeometry"

        // Formats & Pipeline
        private const val KEY_PHOTO_FORMAT = "photoFormat"
        private const val KEY_PHOTO_QUALITY = "photoQuality"
        private const val KEY_PHOTO_ENHANCE_ALGO = "photoEnhanceAlgo"
        private const val KEY_PROCESS_ZERO = "processZeroEnabled"
        private const val KEY_PROCESS_ZERO_INTENSITY = "processZeroIntensity"
        private const val KEY_VIDEO_CODEC = "videoCodec"
        private const val KEY_VIDEO_RESOLUTION = "videoResolution"
        private const val KEY_VIDEO_FPS = "videoFpsFloat"
        private const val KEY_VIDEO_BITRATE = "videoBitrateBps"
        private const val KEY_AUDIO_LOW_CUT = "audioLowCut"
        private const val KEY_UNCOMPRESSED_AUDIO = "uncompressedAudio"
        private const val KEY_EIS_MODE = "eisMode"

        // Cinema & Optics
        private const val KEY_CINEMA_MATTE = "cinemaMatteRatio"
        private const val KEY_DEFAULT_SHUTTER_ANGLE = "defaultShutterAngle"
        private const val KEY_NIGHT_FRAME_COUNT = "nightFrameCount"
        private const val KEY_NIGHT_ASTRO = "nightAstroAlignment"
        private const val KEY_NIGHT_TONE = "nightTone"
        private const val KEY_SLOW_MO_FPS = "slowMoFps"
        private const val KEY_TIMELAPSE_INTERVAL = "timeLapseIntervalSec"
        private const val KEY_AUTOMATION_TRIGGER = "automationTriggerMode"
        private const val KEY_QUEUE_THREADS = "queueWorkerThreads"
        private const val KEY_QUEUE_AUTO_STACKING = "queueAutoQueueStacking"
    }

    fun load(defaultState: CameraUiState): CameraUiState {
        return defaultState.copy(
            viewfinderDisabled = prefs.getBoolean(KEY_VIEWFINDER_DISABLED, defaultState.viewfinderDisabled),
            cleanViewfinderMode = prefs.getBoolean(KEY_CLEAN_VIEWFINDER, defaultState.cleanViewfinderMode),
            gridType = prefs.getString(KEY_GRID_TYPE, defaultState.gridType) ?: defaultState.gridType,
            showHorizonLeveler = prefs.getBoolean(KEY_HORIZON_LEVELER, defaultState.showHorizonLeveler),

            hapticsEnabled = prefs.getBoolean(KEY_HAPTICS, defaultState.hapticsEnabled),
            animationsEnabled = prefs.getBoolean(KEY_ANIMATIONS, defaultState.animationsEnabled),
            showStorageRemaining = prefs.getBoolean(KEY_SHOW_STORAGE, defaultState.showStorageRemaining),
            adaptivePerformanceEnabled = prefs.getBoolean(KEY_ADAPTIVE_PERFORMANCE, defaultState.adaptivePerformanceEnabled),

            exifCameraInfo = prefs.getBoolean(KEY_EXIF_CAMERA_INFO, defaultState.exifCameraInfo),
            exifLensInfo = prefs.getBoolean(KEY_EXIF_LENS_INFO, defaultState.exifLensInfo),
            exifExposureSettings = prefs.getBoolean(KEY_EXIF_EXPOSURE, defaultState.exifExposureSettings),
            exifDateTime = prefs.getBoolean(KEY_EXIF_DATETIME, defaultState.exifDateTime),
            exifLocation = prefs.getBoolean(KEY_EXIF_LOCATION, defaultState.exifLocation),
            exifImageDescription = prefs.getBoolean(KEY_EXIF_IMAGE_DESC, defaultState.exifImageDescription),
            exifUserComment = prefs.getBoolean(KEY_EXIF_USER_COMMENT, defaultState.exifUserComment),
            exifColorGeometry = prefs.getBoolean(KEY_EXIF_COLOR_GEOM, defaultState.exifColorGeometry),

            photoFormat = prefs.getString(KEY_PHOTO_FORMAT, defaultState.photoFormat) ?: defaultState.photoFormat,
            photoQuality = prefs.getInt(KEY_PHOTO_QUALITY, defaultState.photoQuality),
            photoEnhanceAlgo = prefs.getString(KEY_PHOTO_ENHANCE_ALGO, defaultState.photoEnhanceAlgo) ?: defaultState.photoEnhanceAlgo,
            isProcessZeroActive = prefs.getBoolean(KEY_PROCESS_ZERO, defaultState.isProcessZeroActive),
            processZeroIntensity = prefs.getFloat(KEY_PROCESS_ZERO_INTENSITY, defaultState.processZeroIntensity),

            videoCodec = prefs.getString(KEY_VIDEO_CODEC, defaultState.videoCodec) ?: defaultState.videoCodec,
            videoResolution = prefs.getString(KEY_VIDEO_RESOLUTION, defaultState.videoResolution) ?: defaultState.videoResolution,
            videoFpsFloat = prefs.getFloat(KEY_VIDEO_FPS, defaultState.videoFpsFloat.toFloat()).toDouble(),
            videoFps = prefs.getFloat(KEY_VIDEO_FPS, defaultState.videoFpsFloat.toFloat()).toInt(),
            videoBitrateBps = prefs.getInt(KEY_VIDEO_BITRATE, defaultState.videoBitrateBps),
            audioLowCut = prefs.getBoolean(KEY_AUDIO_LOW_CUT, defaultState.audioLowCut),
            uncompressedAudio = prefs.getBoolean(KEY_UNCOMPRESSED_AUDIO, defaultState.uncompressedAudio),
            eisMode = prefs.getString(KEY_EIS_MODE, defaultState.eisMode) ?: defaultState.eisMode,

            cinemaMatteRatio = prefs.getString(KEY_CINEMA_MATTE, defaultState.cinemaMatteRatio) ?: defaultState.cinemaMatteRatio,
            defaultShutterAngle = prefs.getString(KEY_DEFAULT_SHUTTER_ANGLE, defaultState.defaultShutterAngle) ?: defaultState.defaultShutterAngle,
            nightFrameCount = prefs.getInt(KEY_NIGHT_FRAME_COUNT, defaultState.nightFrameCount),
            nightAstroAlignment = prefs.getBoolean(KEY_NIGHT_ASTRO, defaultState.nightAstroAlignment),
            nightTone = prefs.getString(KEY_NIGHT_TONE, defaultState.nightTone) ?: defaultState.nightTone,
            slowMoFps = prefs.getInt(KEY_SLOW_MO_FPS, defaultState.slowMoFps),
            timeLapseIntervalSec = prefs.getInt(KEY_TIMELAPSE_INTERVAL, defaultState.timeLapseIntervalSec),
            automationTriggerMode = prefs.getString(KEY_AUTOMATION_TRIGGER, defaultState.automationTriggerMode) ?: defaultState.automationTriggerMode,
            queueWorkerThreads = prefs.getInt(KEY_QUEUE_THREADS, defaultState.queueWorkerThreads),
            queueAutoQueueStacking = prefs.getBoolean(KEY_QUEUE_AUTO_STACKING, defaultState.queueAutoQueueStacking)
        )
    }

    fun save(state: CameraUiState) {
        prefs.edit()
            .putBoolean(KEY_VIEWFINDER_DISABLED, state.viewfinderDisabled)
            .putBoolean(KEY_CLEAN_VIEWFINDER, state.cleanViewfinderMode)
            .putString(KEY_GRID_TYPE, state.gridType)
            .putBoolean(KEY_HORIZON_LEVELER, state.showHorizonLeveler)

            .putBoolean(KEY_HAPTICS, state.hapticsEnabled)
            .putBoolean(KEY_ANIMATIONS, state.animationsEnabled)
            .putBoolean(KEY_SHOW_STORAGE, state.showStorageRemaining)
            .putBoolean(KEY_ADAPTIVE_PERFORMANCE, state.adaptivePerformanceEnabled)

            .putBoolean(KEY_EXIF_CAMERA_INFO, state.exifCameraInfo)
            .putBoolean(KEY_EXIF_LENS_INFO, state.exifLensInfo)
            .putBoolean(KEY_EXIF_EXPOSURE, state.exifExposureSettings)
            .putBoolean(KEY_EXIF_DATETIME, state.exifDateTime)
            .putBoolean(KEY_EXIF_LOCATION, state.exifLocation)
            .putBoolean(KEY_EXIF_IMAGE_DESC, state.exifImageDescription)
            .putBoolean(KEY_EXIF_USER_COMMENT, state.exifUserComment)
            .putBoolean(KEY_EXIF_COLOR_GEOM, state.exifColorGeometry)

            .putString(KEY_PHOTO_FORMAT, state.photoFormat)
            .putInt(KEY_PHOTO_QUALITY, state.photoQuality)
            .putString(KEY_PHOTO_ENHANCE_ALGO, state.photoEnhanceAlgo)
            .putBoolean(KEY_PROCESS_ZERO, state.isProcessZeroActive)
            .putFloat(KEY_PROCESS_ZERO_INTENSITY, state.processZeroIntensity)

            .putString(KEY_VIDEO_CODEC, state.videoCodec)
            .putString(KEY_VIDEO_RESOLUTION, state.videoResolution)
            .putFloat(KEY_VIDEO_FPS, state.videoFpsFloat.toFloat())
            .putInt(KEY_VIDEO_BITRATE, state.videoBitrateBps)
            .putBoolean(KEY_AUDIO_LOW_CUT, state.audioLowCut)
            .putBoolean(KEY_UNCOMPRESSED_AUDIO, state.uncompressedAudio)
            .putString(KEY_EIS_MODE, state.eisMode)

            .putString(KEY_CINEMA_MATTE, state.cinemaMatteRatio)
            .putString(KEY_DEFAULT_SHUTTER_ANGLE, state.defaultShutterAngle)
            .putInt(KEY_NIGHT_FRAME_COUNT, state.nightFrameCount)
            .putBoolean(KEY_NIGHT_ASTRO, state.nightAstroAlignment)
            .putString(KEY_NIGHT_TONE, state.nightTone)
            .putInt(KEY_SLOW_MO_FPS, state.slowMoFps)
            .putInt(KEY_TIMELAPSE_INTERVAL, state.timeLapseIntervalSec)
            .putString(KEY_AUTOMATION_TRIGGER, state.automationTriggerMode)
            .putInt(KEY_QUEUE_THREADS, state.queueWorkerThreads)
            .putBoolean(KEY_QUEUE_AUTO_STACKING, state.queueAutoQueueStacking)
            .apply()
    }
}
