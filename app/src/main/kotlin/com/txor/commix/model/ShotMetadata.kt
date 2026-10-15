package com.txor.commix.model

data class ExifOptions(
    val cameraInfo: Boolean = true,
    val location: Boolean = false,
    val userComment: Boolean = true,
    val lensSpecs: Boolean = true,
    val exposureSettings: Boolean = true,
    val dateTime: Boolean = true,
    val imageDescription: Boolean = true
)

data class ShotMetadata(
    val timestamp: Long = System.currentTimeMillis(),
    val exposureTimeNs: Long? = null,
    val iso: Int? = null,
    val aperture: Float? = null,
    val focalLength: Float? = null,
    val focalLength35mm: Int? = null,
    val focusDistance: Float? = null,
    val colorCorrectionGains: FloatArray? = null,
    val actualWidth: Int = 0,
    val actualHeight: Int = 0,
    val isHdr: Boolean = false,
    val isNightSight: Boolean = false,
    val isHiResMode: Boolean = false,
    val activeProfile: String = "standard",
    val activeMode: String = "PHOTO",
    val digitalZoom: Double = 1.0,
    val aspectRatio: String = "4:3",
    val enhanceMode: String = "none",
    val enhanceAlgo: String = "none",
    val enhanceIntensity: Float = 0f,
    val filmGrain: String = "off",
    val toneCurve: String = "neutral",
    val lutLook: String = "none",
    val slateRoll: String = "A",
    val slateScene: String = "1",
    val slateTake: String = "1",
    val compressionFormat: String = "jpeg",
    val compressionQuality: Int = 95,
    val gpsLatitude: Double? = null,
    val gpsLongitude: Double? = null,
    val gpsAltitude: Double? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ShotMetadata
        return timestamp == other.timestamp && actualWidth == other.actualWidth && actualHeight == other.actualHeight
    }

    override fun hashCode(): Int {
        var result = timestamp.hashCode()
        result = 31 * result + actualWidth
        result = 31 * result + actualHeight
        return result
    }
}
