package com.txor.commix.bridge

import java.nio.ByteBuffer

/**
 * High-performance zero-copy JNI bridge to TelemetrySharedBuffer in libcommix_native.so.
 */
object NativeTelemetryBridge {
    val isLoaded: Boolean = try {
        System.loadLibrary("commix_native")
        true
    } catch (e: Throwable) {
        android.util.Log.e("Commix", "Failed to load libcommix_native.so", e)
        false
    }

    @JvmStatic
    external fun nativeGetBufferPointer(): Long

    @JvmStatic
    external fun nativeWriteAudio(norm: Float, db: Float, leftDb: Float, rightDb: Float)

    @JvmStatic
    external fun nativeWriteImu(pitch: Float, roll: Float, yaw: Float)

    @JvmStatic
    external fun nativeWriteLens(afDistance: Float, afState: Int)

    @JvmStatic
    external fun nativeComputeScopesDirect(
        y: ByteBuffer,
        u: ByteBuffer,
        v: ByteBuffer,
        width: Int,
        height: Int,
        yStride: Int,
        uvStride: Int,
        uvPixelStride: Int,
        step: Int
    )

    @JvmStatic
    external fun nativeReadTelemetry(
        outFloats: FloatArray,
        outLumaBins: IntArray,
        outWaveCols: FloatArray
    ): Boolean
}

