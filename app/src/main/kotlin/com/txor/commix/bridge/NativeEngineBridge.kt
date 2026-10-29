package com.txor.commix.bridge

import java.nio.ByteBuffer

/**
 * JNI bridge for bare-metal C++20 image processing, computational photography,
 * stabilization, and color science in libcommix_native.so.
 */
object NativeEngineBridge {
    val isLoaded: Boolean = NativeTelemetryBridge.isLoaded

    @JvmStatic
    external fun nativeInitEngine(): Boolean

    @JvmStatic
    external fun nativeProcessZeroDirect(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        channels: Int,
        curvePreset: Int,
        grainPreset: Int,
        grainIntensity: Float
    ): Boolean

    @JvmStatic
    external fun nativeApplyUnsharpMaskDirect(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        channels: Int,
        amount: Float,
        radius: Float
    )

    @JvmStatic
    external fun nativeApplyCdlDirect(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        channels: Int,
        slopeR: Float, slopeG: Float, slopeB: Float,
        offsetR: Float, offsetG: Float, offsetB: Float,
        powerR: Float, powerG: Float, powerB: Float,
        saturation: Float
    )

    @JvmStatic
    external fun nativeStartImuLogging()

    @JvmStatic
    external fun nativeStopAndExportGyroflow(
        outputPath: String,
        cameraName: String,
        frameRate: Double
    ): Boolean

    @JvmStatic
    external fun nativePushImuSample(
        timestampNs: Long,
        gx: Float,
        gy: Float,
        gz: Float,
        ax: Float,
        ay: Float,
        az: Float
    )

    @JvmStatic
    external fun nativePushBestShotVelocity(gx: Float, gy: Float, gz: Float)

    @JvmStatic
    external fun nativeEvaluateBestShot(
        spotIre: Float,
        hiClipPct: Float,
        loClipPct: Float,
        outScores: FloatArray
    ): Boolean

    @JvmStatic
    external fun nativeStackFramesDirect(
        frameBuffers: Array<ByteBuffer>,
        frameCount: Int,
        width: Int,
        height: Int,
        channels: Int,
        outBuffer: ByteBuffer,
        deepShadowBoost: Boolean
    ): Boolean

    @JvmStatic
    external fun nativeKeyframeReset()

    @JvmStatic
    external fun nativeKeyframeAddPoint(
        trackId: Int,
        timeSec: Float,
        value: Float,
        curve: Int
    )

    @JvmStatic
    external fun nativeKeyframeStart(durationSec: Float)

    @JvmStatic
    external fun nativeKeyframeStop()

    @JvmStatic
    external fun nativeKeyframeTick(
        dt: Float,
        outValues: FloatArray
    ): Boolean
}
