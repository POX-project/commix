package com.txor.commix.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PanoramaHorizontal
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txor.commix.ui.theme.AppleYellow
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * GCam-style panoramic and 360 Photo Sphere guidance overlay.
 * Features gyro horizon leveling guide, center alignment reticle,
 * dynamic sweep dots, and spherical capture target tracking.
 */
@Composable
fun PanoramaGuidanceOverlay(
    sweepAngle: Float,
    progress: Float,
    rollDeg: Float,
    pitchDeg: Float,
    is360Sphere: Boolean,
    isCapturing: Boolean = false,
    capturedFrames: Int = 0,
    onToggle360: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLevel = abs(rollDeg) < 1.5f && abs(pitchDeg) < 2.0f
    val guideColor = if (isLevel) Color(0xFF34C759) else AppleYellow

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Box(modifier = modifier.fillMaxSize()) {
        // 1. Top Mode Badge & 360 Toggle Pill
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x99000000))
                    .border(0.8.dp, guideColor, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (is360Sphere) Icons.Default.Public else Icons.Default.PanoramaHorizontal,
                        contentDescription = null,
                        tint = guideColor,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (is360Sphere) "GCAM 360° PHOTO SPHERE" else "GCAM PANORAMA SWEEP",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.5.sp,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            if (isCapturing) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xCC000000))
                        .border(0.8.dp, AppleYellow, RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "$capturedFrames SLICES (${sweepAngle.roundToInt()}°)",
                        color = AppleYellow,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.5.sp
                    )
                }
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (is360Sphere) AppleYellow else Color(0x66000000))
                    .border(0.8.dp, AppleYellow, RoundedCornerShape(12.dp))
                    .clickable { onToggle360() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = if (is360Sphere) "360° ACTIVE" else "SWITCH 360°",
                    color = if (is360Sphere) Color.Black else AppleYellow,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
            }
        }

        // 2. Center Reticle & Horizontal Horizon Level Line
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val trackW = size.width * 0.75f

            // Level guideline across center
            drawLine(
                color = guideColor.copy(alpha = 0.7f),
                start = Offset(cx - trackW / 2f, cy),
                end = Offset(cx + trackW / 2f, cy),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )

            // Center Target Ring
            drawCircle(
                color = guideColor,
                radius = 16.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 2.5.dp.toPx())
            )
            drawCircle(
                color = guideColor.copy(alpha = pulseAlpha * 0.6f),
                radius = 4.dp.toPx(),
                center = Offset(cx, cy)
            )

            // Target alignment dots along the line
            val dotOffsets = listOf(-0.35f, -0.2f, 0.2f, 0.35f)
            dotOffsets.forEach { rel ->
                val dx = cx + trackW * rel
                drawCircle(
                    color = Color.White.copy(alpha = 0.85f),
                    radius = 5.dp.toPx(),
                    center = Offset(dx, cy)
                )
                drawCircle(
                    color = Color.Black.copy(alpha = 0.5f),
                    radius = 5.dp.toPx(),
                    center = Offset(dx, cy),
                    style = Stroke(width = 1.dp.toPx())
                )
            }

            // If 360 mode: draw upper and lower spherical capture nodes
            if (is360Sphere) {
                val sphereRadius = 60.dp.toPx()
                drawCircle(
                    color = AppleYellow.copy(alpha = 0.4f),
                    radius = sphereRadius,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f))
                )
                drawCircle(color = Color.White, radius = 4.dp.toPx(), center = Offset(cx, cy - sphereRadius))
                drawCircle(color = Color.White, radius = 4.dp.toPx(), center = Offset(cx, cy + sphereRadius))
            }
        }

        // 3. Bottom Guidance Pill & Sweep Progress
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xCC000000))
                    .border(0.8.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (!isCapturing) {
                        if (isLevel) "Tap shutter and pan steadily" else "Tilt phone to level with line (${String.format(Locale.US, "%+.1f°", rollDeg)})"
                    } else {
                        if (isLevel) "Move phone steadily horizontally" else "Tilt phone to level (${String.format(Locale.US, "%+.1f°", rollDeg)})"
                    },
                    color = if (isLevel) Color.White else AppleYellow,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp
                )
            }

            if (progress > 0f) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .width(180.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0x44FFFFFF))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .background(AppleYellow)
                    )
                }
            }
        }
    }
}
