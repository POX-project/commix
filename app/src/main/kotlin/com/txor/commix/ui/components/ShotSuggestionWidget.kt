package com.txor.commix.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.txor.commix.ui.theme.AppleYellow
import com.txor.commix.ui.theme.CinemaGreen
import kotlin.math.abs

/**
 * Samsung One UI-style Shot Suggestion composition widget matching Flutter's
 * shot_suggestion_widget.dart.
 */
@Composable
fun ShotSuggestionWidget(
    shouldShowShootNow: Boolean,
    bestShotScore: Float,
    rollDeg: Float,
    pitchDeg: Float,
    modifier: Modifier = Modifier
) {
    val isLevel = abs(rollDeg) <= 1.0f && abs(pitchDeg) <= 1.5f
    val isOptimal = shouldShowShootNow || (bestShotScore >= 0.75f && isLevel)

    val pulseScale = remember { Animatable(1.0f) }

    LaunchedEffect(isOptimal) {
        if (isOptimal) {
            pulseScale.animateTo(1.25f, tween(300, easing = FastOutSlowInEasing))
            pulseScale.animateTo(1.0f, tween(300, easing = FastOutSlowInEasing))
        }
    }

    val guideColor = if (isOptimal) AppleYellow else Color(0x66FFFFFF)
    val ringColor = if (isOptimal) AppleYellow else Color(0x40FFFFFF)

    Canvas(modifier = modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f

        // Center composition target circle (Samsung style)
        val baseRadius = 26f * pulseScale.value
        drawCircle(
            color = ringColor,
            radius = baseRadius,
            center = Offset(cx, cy),
            style = Stroke(width = if (isOptimal) 2.5f else 1.5f)
        )

        // Center target core dot
        drawCircle(
            color = guideColor,
            radius = if (isOptimal) 5f else 3.5f,
            center = Offset(cx, cy)
        )

        // Artificial horizon level bar (rotates smoothly with device roll)
        rotate(degrees = -rollDeg, pivot = Offset(cx, cy)) {
            val barHalfLen = 50f
            val barY = cy

            drawLine(
                color = guideColor,
                start = Offset(cx - barHalfLen, barY),
                end = Offset(cx - baseRadius - 6f, barY),
                strokeWidth = if (isOptimal) 2.5f else 1.5f
            )

            drawLine(
                color = guideColor,
                start = Offset(cx + baseRadius + 6f, barY),
                end = Offset(cx + barHalfLen, barY),
                strokeWidth = if (isOptimal) 2.5f else 1.5f
            )
        }
    }
}
