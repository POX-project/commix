package com.txor.commix.ui.components

/**
 * Production cinema matte geometry.
 *
 * Maps a matte ratio label (as persisted in `CameraUiState.cinemaMatteRatio`) to the target
 * aperture aspect ratio rendered by [CinemaMatteOverlay]. Unknown labels fall back to Scope.
 */
object CinemaMatte {
    const val DEFAULT_RATIO: String = "2.39:1"
    const val DEFAULT_ASPECT: Float = 2.39f

    fun aspectFor(ratio: String): Float = when (ratio) {
        "2.39:1" -> 2.39f
        "2.00:1" -> 2.00f
        "1.85:1" -> 1.85f
        "1.66:1" -> 1.66f
        "16:9" -> 16f / 9f
        "4:3" -> 4f / 3f
        "1:1" -> 1.0f
        else -> DEFAULT_ASPECT
    }
}
