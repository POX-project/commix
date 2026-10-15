package com.txor.commix.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txor.commix.ui.theme.AppleYellow
import com.txor.commix.ui.theme.CinemaGreen
import kotlin.math.roundToInt

@Composable
fun FocusLoupe(
    currentFocusDiopters: Float,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(40f) }
    var magnification by remember { mutableFloatStateOf(2.0f) }

    val focusText = remember(currentFocusDiopters) {
        if (currentFocusDiopters < 0f) {
            "AF (AUTO FOCUS)"
        } else if (currentFocusDiopters <= 0.05f) {
            "∞ INF"
        } else {
            val meters = 1.0f / currentFocusDiopters
            if (meters < 1.0f) {
                "${(meters * 100).roundToInt()} cm (%.1f dpt)".format(currentFocusDiopters)
            } else {
                "%.2f m (%.1f dpt)".format(meters, currentFocusDiopters)
            }
        }
    }

    Box(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .width(160.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xDD141416))
            .border(1.5.dp, AppleYellow.copy(alpha = 0.85f), RoundedCornerShape(20.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX = (offsetX + dragAmount.x).coerceIn(-300f, 300f)
                    offsetY = (offsetY + dragAmount.y).coerceIn(-50f, 800f)
                }
            }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            // 1. Header Bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x33FFFFFF))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = AppleYellow,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${magnification.toInt()}x LOUPE",
                        color = AppleYellow,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp
                    )
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .clickable { onClose() }
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Loupe",
                        tint = Color.Gray,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            // 2. Concentric Reticle Window
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(95.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f

                    // Concentric inspection circles
                    drawCircle(Color(0x33FFFFFF), radius = 34.dp.toPx(), center = Offset(cx, cy), style = Stroke(0.8f))
                    drawCircle(Color(0x4DFFFFFF), radius = 18.dp.toPx(), center = Offset(cx, cy), style = Stroke(0.8f))

                    // Crosshair ticks
                    drawLine(CinemaGreen, Offset(cx - 10.dp.toPx(), cy), Offset(cx + 10.dp.toPx(), cy), strokeWidth = 1.2f)
                    drawLine(CinemaGreen, Offset(cx, cy - 10.dp.toPx()), Offset(cx, cy + 10.dp.toPx()), strokeWidth = 1.2f)
                }

                // Focus distance badge
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = focusText,
                        color = CinemaGreen,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 3. Magnification Selector Pills (2x, 3x, 4x)
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x22000000))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                listOf(2.0f, 3.0f, 4.0f).forEach { mag ->
                    val isSelected = (magnification == mag)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) AppleYellow else Color(0x22FFFFFF))
                            .clickable { magnification = mag }
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "${mag.toInt()}x",
                            color = if (isSelected) Color.Black else Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
