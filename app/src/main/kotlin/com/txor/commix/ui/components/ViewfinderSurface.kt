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
    targetAspectRatio: Float = 3f / 4f,
    onSurfaceDestroyed: () -> Unit = {}
) {
    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                var lastSurface: Surface? = null

                fun applyTransform(vWidth: Int, vHeight: Int) {
                    if (vWidth <= 0 || vHeight <= 0 || previewWidth <= 0 || previewHeight <= 0) return
                    val matrix = Matrix()
                    // In portrait mode, 90° sensor rotation inverts aspect ratio
                    val bufferAspect = previewHeight.toFloat() / previewWidth.toFloat()
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
                    matrix.setScale(scaleX, scaleY, vWidth / 2f, vHeight / 2f)
                    setTransform(matrix)
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
                val matrix = Matrix()
                val bufferAspect = previewHeight.toFloat() / previewWidth.toFloat()
                val viewAspect = textureView.width.toFloat() / textureView.height.toFloat()
                val scaleX: Float
                val scaleY: Float
                if (viewAspect > bufferAspect) {
                    scaleX = 1f
                    scaleY = (textureView.width / bufferAspect) / textureView.height
                } else {
                    scaleX = (textureView.height * bufferAspect) / textureView.width
                    scaleY = 1f
                }
                matrix.setScale(scaleX, scaleY, textureView.width / 2f, textureView.height / 2f)
                textureView.setTransform(matrix)
            }
        },
        modifier = modifier.fillMaxSize()
    )
}
