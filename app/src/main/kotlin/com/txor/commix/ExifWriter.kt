package com.txor.commix

import android.os.Build
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.txor.commix.model.ExifOptions
import com.txor.commix.model.ShotMetadata
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * ExifWriter embeds broadcast-grade cinema and camera metadata into saved JPEG/HEIC/DNG files.
 */
object ExifWriter {
    private const val TAG = "ExifWriter"

    fun writeMetadata(
        file: File,
        orientationDeg: Int,
        meta: ShotMetadata?,
        opts: ExifOptions
    ) {
        try {
            val exif = ExifInterface(file.absolutePath)
            applyExif(exif, orientationDeg, meta, opts)
            exif.saveAttributes()
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing EXIF to ${file.absolutePath}: ${e.message}", e)
        }
    }

    private fun applyExif(
        exif: ExifInterface,
        orientationDeg: Int,
        meta: ShotMetadata?,
        opts: ExifOptions
    ) {
        val exifOrientation = when (orientationDeg) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90.toString()
            180 -> ExifInterface.ORIENTATION_ROTATE_180.toString()
            270 -> ExifInterface.ORIENTATION_ROTATE_270.toString()
            else -> ExifInterface.ORIENTATION_NORMAL.toString()
        }
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation)

        if (opts.cameraInfo) {
            exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
            exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Commix Pro Cinema Camera")
        }

        if (opts.userComment) {
            val yr = Calendar.getInstance().get(Calendar.YEAR)
            exif.setAttribute(ExifInterface.TAG_ARTIST, "Commix Cinema")
            exif.setAttribute(ExifInterface.TAG_COPYRIGHT, "Commix Pro Camera © $yr")
        }

        if (opts.dateTime) {
            val date = meta?.timestamp?.let { Date(it) } ?: Date()
            val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            val dateStr = sdf.format(date)
            exif.setAttribute(ExifInterface.TAG_DATETIME, dateStr)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateStr)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateStr)
        }

        if (opts.imageDescription) {
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "Commix Cinema Shot")
        }

        if (meta != null) {
            if (meta.actualWidth > 0 && meta.actualHeight > 0) {
                exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, meta.actualWidth.toString())
                exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, meta.actualHeight.toString())
                exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, meta.actualWidth.toString())
                exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, meta.actualHeight.toString())
            }

            if (opts.exposureSettings) {
                meta.iso?.let { isoNumber ->
                    exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, isoNumber.toString())
                    @Suppress("DEPRECATION")
                    exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, isoNumber.toString())
                }

                meta.exposureTimeNs?.let { expNs ->
                    val expSec = expNs.toDouble() / 1_000_000_000.0
                    if (expSec > 0) {
                        val expStr = if (expSec < 1.0) {
                            val denom = (1.0 / expSec).roundToInt()
                            "1/$denom"
                        } else {
                            String.format(Locale.US, "%.3f", expSec)
                        }
                        exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, expStr)
                    }
                }

                meta.aperture?.let { fNumber ->
                    if (fNumber > 0) {
                        exif.setAttribute(ExifInterface.TAG_F_NUMBER, String.format(Locale.US, "%.2f", fNumber))
                    }
                }
            }

            if (opts.lensSpecs) {
                meta.focalLength?.let { fl ->
                    exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, String.format(Locale.US, "%.2f", fl))
                }
                meta.focalLength35mm?.let { fl35 ->
                    exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, fl35.toString())
                }
            }

            // GPS embedding
            if (opts.location && meta.gpsLatitude != null && meta.gpsLongitude != null) {
                exif.setLatLong(meta.gpsLatitude!!, meta.gpsLongitude!!)
                meta.gpsAltitude?.let { exif.setAltitude(it) }
            }
        }
    }
}
