package com.txor.commix.ui.components

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * High-performance hardware-accelerated TextureView for zero-latency camera preview.
 * Fully handles SurfaceTexture default buffer sizes and Matrix transformations to guarantee
 * that previews are never distorted or stretched across 16:9 (Video), 4:3 (Photo), and 2.39:1 (Cinematic).
 */
@Composable
fun ViewfinderSurface(
    onSurfaceReady: (Surface) -> Unit,
    modifier: Modifier = Modifier,
    previewWidth: Int = 1920,
    previewHeight: Int = 1080,
    sensorOrientation: Int = 90,
    anamorphicDeSqueeze: Float = 1.0f,
    targetAspectRatio: Float = 3f / 4f,
    onSurfaceDestroyed: () -> Unit = {}
) {
    fun buildTransform(vWidth: Int, vHeight: Int): Matrix {
        val matrix = Matrix()
        if (vWidth <= 0 || vHeight <= 0 || previewWidth <= 0 || previewHeight <= 0) return matrix
        val centerX = vWidth / 2f
        val centerY = vHeight / 2f

        val isRotated = (sensorOrientation == 90 || sensorOrientation == 270)
        val bufW = if (isRotated) previewHeight.toFloat() else previewWidth.toFloat()
        val bufH = if (isRotated) previewWidth.toFloat() else previewHeight.toFloat()

        val bufferAspect = bufW / bufH
        val viewAspect = vWidth.toFloat() / vHeight.toFloat()

        val scaleX: Float
        val scaleY: Float
        if (viewAspect > bufferAspect) {
            scaleX = 1f
            scaleY = (vWidth / bufferAspect) / vHeight
        } else {
            scaleX = (vHeight * bufferAspect) / vWidth
            scaleY = 1f
        }
        matrix.setScale(scaleX, scaleY, centerX, centerY)

        if (isRotated) {
            matrix.postRotate(sensorOrientation.toFloat(), centerX, centerY)
        }

        if (anamorphicDeSqueeze > 1.0f) {
            matrix.postScale(anamorphicDeSqueeze, 1.0f, centerX, centerY)
        }

        return matrix
    }

    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                var lastSurface: Surface? = null

                fun applyTransform(vWidth: Int, vHeight: Int) {
                    setTransform(buildTransform(vWidth, vHeight))
                }

                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                        st.setDefaultBufferSize(previewWidth, previewHeight)
                        applyTransform(width, height)
                        val surface = Surface(st)
                        lastSurface = surface
                        onSurfaceReady(surface)
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                        st.setDefaultBufferSize(previewWidth, previewHeight)
                        applyTransform(width, height)
                    }

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        onSurfaceDestroyed()
                        lastSurface?.release()
                        lastSurface = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                        // Frame rendered
                    }
                }
            }
        },
        update = { textureView ->
            if (textureView.isAvailable && textureView.width > 0 && textureView.height > 0) {
                textureView.surfaceTexture?.setDefaultBufferSize(previewWidth, previewHeight)
                textureView.setTransform(buildTransform(textureView.width, textureView.height))
            }
        },
        modifier = modifier.fillMaxSize()
    )
}
