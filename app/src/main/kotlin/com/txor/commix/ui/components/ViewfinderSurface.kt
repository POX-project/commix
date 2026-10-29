package com.txor.commix.ui.components

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * High-performance hardware-accelerated TextureView for zero-latency camera preview.
 * Fully handles SurfaceTexture default buffer sizes, Matrix transformations, and
 * lifecycle state transitions (foreground/background/resume) to guarantee that
 * previews are never distorted and the viewport never freezes when leaving and returning to the app.
 */
@Composable
fun ViewfinderSurface(
    onSurfaceReady: (Surface, SurfaceTexture) -> Unit,
    modifier: Modifier = Modifier,
    previewWidth: Int = 1920,
    previewHeight: Int = 1080,
    sensorOrientation: Int = 90,
    anamorphicDeSqueeze: Float = 1.0f,
    targetAspectRatio: Float = 3f / 4f,
    onSurfaceDestroyed: () -> Unit = {}
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    var currentSurface by remember { mutableStateOf<Surface?>(null) }

    fun buildTransform(vWidth: Int, vHeight: Int): Matrix {
        val transformMatrix = Matrix()
        if (vWidth <= 0 || vHeight <= 0 || previewWidth <= 0 || previewHeight <= 0) return transformMatrix
        val centerX = vWidth / 2f
        val centerY = vHeight / 2f

        // Camera2's SurfaceTexture automatically applies the sensor-orientation
        // transform in hardware to deliver upright frames to TextureView.
        // We scale the buffer to match the container viewport aspect ratio (portrait: previewHeight / previewWidth).
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
        transformMatrix.setScale(scaleX, scaleY, centerX, centerY)

        if (anamorphicDeSqueeze > 1.0f) {
            transformMatrix.postScale(1.0f, 1.0f / anamorphicDeSqueeze, centerX, centerY)
        }

        return transformMatrix
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    val tv = textureViewRef
                    if (tv != null && tv.isAvailable) {
                        val st = tv.surfaceTexture
                        if (st != null) {
                            st.setDefaultBufferSize(previewWidth, previewHeight)
                            tv.setTransform(buildTransform(tv.width, tv.height))
                            currentSurface?.release()
                            val surf = Surface(st)
                            currentSurface = surf
                            onSurfaceReady(surf, st)
                        }
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    onSurfaceDestroyed()
                    currentSurface?.release()
                    currentSurface = null
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            currentSurface?.release()
            currentSurface = null
        }
    }

    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                textureViewRef = this

                fun applyTransform(vWidth: Int, vHeight: Int) {
                    setTransform(buildTransform(vWidth, vHeight))
                }

                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                        st.setDefaultBufferSize(previewWidth, previewHeight)
                        applyTransform(width, height)
                        currentSurface?.release()
                        val surface = Surface(st)
                        currentSurface = surface
                        onSurfaceReady(surface, st)
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                        st.setDefaultBufferSize(previewWidth, previewHeight)
                        applyTransform(width, height)
                    }

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        onSurfaceDestroyed()
                        currentSurface?.release()
                        currentSurface = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                        // Frame rendered
                    }
                }
            }
        },
        update = { textureView ->
            textureViewRef = textureView
            if (textureView.isAvailable && textureView.width > 0 && textureView.height > 0) {
                textureView.surfaceTexture?.setDefaultBufferSize(previewWidth, previewHeight)
                textureView.setTransform(buildTransform(textureView.width, textureView.height))
            }
        },
        modifier = modifier.fillMaxSize().clipToBounds()
    )
}
