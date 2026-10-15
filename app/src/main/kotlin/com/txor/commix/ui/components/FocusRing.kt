package com.txor.commix.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.txor.commix.ui.theme.AppleYellow
import kotlin.math.roundToInt

@Composable
fun FocusRing(
    x: Float,
    y: Float,
    evStops: Float,
    onEvChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val scale = remember { Animatable(1.4f) }

    LaunchedEffect(x, y) {
        scale.snapTo(1.4f)
        scale.animateTo(
            targetValue = 1.0f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
        )
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val halfSizePx = with(density) { 40.dp.roundToPx() }

    val currentOnEvChanged by rememberUpdatedState(onEvChanged)
    var accumulatedEv by remember(x, y) { mutableFloatStateOf(evStops) }

    Box(
        modifier = modifier
            .offset { IntOffset((x - halfSizePx).roundToInt(), (y - halfSizePx).roundToInt()) }
            .size(80.dp)
            .pointerInput(x, y) {
                detectVerticalDragGestures { _, dragAmount ->
                    accumulatedEv = (accumulatedEv - (dragAmount / 50.0f)).coerceIn(-2.0f, 2.0f)
                    currentOnEvChanged(accumulatedEv)
                }
            }
    ) {
        Canvas(modifier = Modifier.size(80.dp)) {
            val radius = 35f * scale.value
            val center = Offset(size.width / 2f, size.height / 2f)

            // Outer focus circle / square
            drawCircle(
                color = AppleYellow,
                radius = radius,
                center = center,
                style = Stroke(width = 2f)
            )

            // Small 4 corner indicator ticks
            val tickLen = 8f
            drawLine(AppleYellow, Offset(center.x - radius, center.y), Offset(center.x - radius + tickLen, center.y), 2f)
            drawLine(AppleYellow, Offset(center.x + radius, center.y), Offset(center.x + radius - tickLen, center.y), 2f)
            drawLine(AppleYellow, Offset(center.x, center.y - radius), Offset(center.x, center.y - radius + tickLen), 2f)
            drawLine(AppleYellow, Offset(center.x, center.y + radius), Offset(center.x, center.y + radius - tickLen), 2f)

            // EV bias slider line and sun icon on the right
            val sunX = center.x + radius + 18f
            val sunY = center.y - (evStops / 2.0f) * radius

            drawLine(
                color = Color(0x66FFCC00),
                start = Offset(sunX, center.y - radius),
                end = Offset(sunX, center.y + radius),
                strokeWidth = 1.5f
            )

            // Sun icon (small circle with rays)
            drawCircle(
                color = AppleYellow,
                radius = 5f,
                center = Offset(sunX, sunY)
            )
        }
    }
}
