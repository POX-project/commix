package com.txor.commix.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.txor.commix.ui.theme.*

/**
 * High-performance hardware-accelerated scopes, rule-of-thirds grid,
 * and pitch/roll horizon leveler matching Flutter's cinema_tools.dart.
 */
@Composable
fun ScopesOverlay(
    showWaveform: Boolean,
    showHistogram: Boolean,
    showHorizon: Boolean,
    showGrid: Boolean,
    rollDeg: Float = 0f,
    pitchDeg: Float = 0f,
    waveformCols: FloatArray = FloatArray(0),
    lumaHistogram: IntArray = IntArray(0),
    modifier: Modifier = Modifier
) {
    val wavePath = remember { Path() }
    val histPath = remember { Path() }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // 1. Rule of Thirds Grid
        if (showGrid) {
            val gridColor = Color(0x33FFFFFF)

            // Vertical grid lines
            drawLine(gridColor, Offset(w / 3f, 0f), Offset(w / 3f, h), strokeWidth = 1f)
            drawLine(gridColor, Offset(w * 2f / 3f, 0f), Offset(w * 2f / 3f, h), strokeWidth = 1f)

            // Horizontal grid lines
            drawLine(gridColor, Offset(0f, h / 3f), Offset(w, h / 3f), strokeWidth = 1f)
            drawLine(gridColor, Offset(0f, h * 2f / 3f), Offset(w, h * 2f / 3f), strokeWidth = 1f)
        }

        // 2. Center Reticle & Horizon Leveler (matching screen_main_settings.png)
        val cx = w / 2f
        val cy = h / 2f

        val isLevel = kotlin.math.abs(rollDeg) <= 0.5f && kotlin.math.abs(pitchDeg) <= 0.5f
        val reticleColor = if (showHorizon && isLevel) CinemaGreen else Color(0x80FFFFFF)

        // Center reticle ring
        val ringRadius = 40f
        drawCircle(
            color = reticleColor,
            radius = ringRadius,
            center = Offset(cx, cy),
            style = Stroke(width = 1.5f)
        )

        // Center reticle dot
        drawCircle(
            color = reticleColor,
            radius = 3.5f,
            center = Offset(cx, cy)
        )

        // Subtle horizontal crosshair bars
        rotate(degrees = if (showHorizon) rollDeg else 0f, pivot = Offset(cx, cy)) {
            drawLine(
                color = reticleColor,
                start = Offset(cx - 70f, cy),
                end = Offset(cx - ringRadius, cy),
                strokeWidth = 1.5f
            )
            drawLine(
                color = reticleColor,
                start = Offset(cx + ringRadius, cy),
                end = Offset(cx + 70f, cy),
                strokeWidth = 1.5f
            )
        }

        if (showHorizon) {
            // Pitch ladder ticks
            val clampedPitch = pitchDeg.coerceIn(-30f, 30f)
            val pitchOffset = clampedPitch * 3f
            val ladderY = cy - pitchOffset

            drawLine(Color(0x66FFFFFF), Offset(cx - 20f, ladderY - 15f), Offset(cx + 20f, ladderY - 15f), strokeWidth = 1f)
            drawLine(Color(0x99FFFFFF), Offset(cx - 30f, ladderY), Offset(cx + 30f, ladderY), strokeWidth = 1.5f)
            drawLine(Color(0x66FFFFFF), Offset(cx - 20f, ladderY + 15f), Offset(cx + 20f, ladderY + 15f), strokeWidth = 1f)
        }

        // 3. Broadcast Waveform Scope (Bottom left mini overlay)
        if (showWaveform) {
            val scopeW = 160f
            val scopeH = 90f
            val scopeX = 16f
            val scopeY = h - scopeH - 120f

            drawRect(
                color = Color(0x99000000),
                topLeft = Offset(scopeX, scopeY),
                size = Size(scopeW, scopeH)
            )

            // Reference IRE lines
            drawLine(Color(0x55FFFFFF), Offset(scopeX, scopeY), Offset(scopeX + scopeW, scopeY), strokeWidth = 1f)
            drawLine(Color(0x33FFFFFF), Offset(scopeX, scopeY + scopeH * 0.5f), Offset(scopeX + scopeW, scopeY + scopeH * 0.5f), strokeWidth = 1f)
            drawLine(Color(0x55FFFFFF), Offset(scopeX, scopeY + scopeH), Offset(scopeX + scopeW, scopeY + scopeH), strokeWidth = 1f)

            wavePath.reset()
            val colCount = if (waveformCols.isNotEmpty()) waveformCols.size else 64
            val startIre = if (waveformCols.isNotEmpty()) waveformCols[0].coerceIn(0f, 100f) else 50f
            wavePath.moveTo(scopeX, scopeY + scopeH * (1f - startIre / 100f))
            for (i in 0 until colCount) {
                val ire = if (waveformCols.isNotEmpty()) waveformCols[i].coerceIn(0f, 100f) else (50f + 15f * kotlin.math.sin(i.toDouble() * 0.3).toFloat())
                val px = scopeX + (i.toFloat() / (colCount - 1).coerceAtLeast(1)) * scopeW
                val py = scopeY + scopeH * (1f - ire / 100f)
                wavePath.lineTo(px, py)
            }
            drawPath(wavePath, color = CinemaGreen, style = Stroke(width = 1.5f))
        }

        // 4. Luma Histogram Scope (Top right corner)
        if (showHistogram) {
            val histW = 140f
            val histH = 70f
            val histX = w - histW - 16f
            val histY = 70f

            drawRect(
                color = Color(0x99000000),
                topLeft = Offset(histX, histY),
                size = Size(histW, histH)
            )

            histPath.reset()
            histPath.moveTo(histX, histY + histH)
            var maxCount = 1
            if (lumaHistogram.isNotEmpty()) {
                for (b in lumaHistogram) {
                    if (b > maxCount) maxCount = b
                }
            }
            val steps = 32
            val binStep = (if (lumaHistogram.isNotEmpty()) lumaHistogram.size else 256) / steps
            for (i in 0 until steps) {
                var binSum = 0
                if (lumaHistogram.isNotEmpty()) {
                    for (k in 0 until binStep) {
                        val idx = i * binStep + k
                        if (idx < lumaHistogram.size) binSum += lumaHistogram[idx]
                    }
                }
                val avgBin = binSum.toFloat() / binStep.coerceAtLeast(1)
                val normalizedHeight = if (lumaHistogram.isNotEmpty()) {
                    (avgBin / maxCount.toFloat()).coerceIn(0.02f, 1f)
                } else {
                    val diff = (i - 12) / 6.0
                    (0.8f * kotlin.math.exp(-diff * diff).toFloat())
                }
                val hx = histX + (i.toFloat() / (steps - 1)) * histW
                val hy = histY + histH - (histH * normalizedHeight)
                histPath.lineTo(hx, hy)
            }
            histPath.lineTo(histX + histW, histY + histH)
            histPath.close()
            drawPath(histPath, color = Color(0x66FFCC00))
            drawPath(histPath, color = AppleYellow, style = Stroke(width = 1.5f))
        }
    }
}

