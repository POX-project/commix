package com.txor.commix.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txor.commix.ui.theme.AppleYellow
import com.txor.commix.ui.theme.CinemaTextLight
import kotlin.math.roundToInt

@Composable
fun RotaryDial(
    value: Float,
    minValue: Float,
    maxValue: Float,
    step: Float,
    label: String,
    valueFormatter: (Float) -> String,
    onValueChanged: (Float) -> Unit,
    hapticsEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val vibrator = remember { context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator }
    var dragAccumulator by remember { mutableFloatStateOf(0f) }
    val currentValue by rememberUpdatedState(value)
    val currentMin by rememberUpdatedState(minValue)
    val currentMax by rememberUpdatedState(maxValue)
    val currentStep by rememberUpdatedState(step)
    val onValueChangeState by rememberUpdatedState(onValueChanged)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, dragAmount ->
                        dragAccumulator += dragAmount
                        val stepDistance = 25f // pixels per tick
                        if (kotlin.math.abs(dragAccumulator) >= stepDistance) {
                            val steps = (dragAccumulator / stepDistance).toInt()
                            dragAccumulator -= steps * stepDistance
                            val next = (currentValue + steps * currentStep).coerceIn(currentMin, currentMax)
                            if (next != currentValue) {
                                onValueChangeState(next)
                                // Mechanical tick haptic feedback
                                if (hapticsEnabled) {
                                    try {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                            vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                                        }
                                    } catch (_: Throwable) {}
                                }
                            }
                        }
                    }
                )
            }
    ) {
        // Label and current value display
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(bottom = 6.dp)
        ) {
            Text(
                text = "$label: ",
                color = Color.Gray,
                fontSize = 12.sp
            )
            Text(
                text = valueFormatter(value),
                color = AppleYellow,
                fontSize = 13.sp
            )
        }

        // Tactile thumbwheel ruler canvas
        Canvas(modifier = Modifier.fillMaxWidth().height(36.dp)) {
            val w = size.width
            val h = size.height
            val cx = w / 2f

            val totalTicks = 21
            val tickSpacing = w / totalTicks

            for (i in -10..10) {
                val tickX = cx + (i * tickSpacing)
                val isMajor = (i % 3 == 0)
                val tickHeight = if (i == 0) h * 0.9f else if (isMajor) h * 0.65f else h * 0.40f
                val tickColor = if (i == 0) AppleYellow else if (isMajor) CinemaTextLight.copy(alpha = 0.8f) else Color.DarkGray

                drawLine(
                    color = tickColor,
                    start = Offset(tickX, h - tickHeight),
                    end = Offset(tickX, h),
                    strokeWidth = if (i == 0) 2.5f else 1.5f
                )
            }
        }
    }
}
