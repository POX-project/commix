package com.txor.commix.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txor.commix.ui.theme.AppleYellow

/**
 * 1:1 Kotlin port of Flutter's CinemaMatteOverlay in lib/cinema_tools.dart.
 * Renders professional Hollywood letterbox / pillarbox matte bars for:
 * 2.39:1 (Scope), 2.00:1 (Univisium), 1.85:1 (Flat), 16:9, and 4:3.
 */
@Composable
fun CinemaMatteOverlay(
    ratio: String = "2.39:1",
    opacity: Float = 0.88f,
    modifier: Modifier = Modifier
) {
    if (ratio == "off") return

    val targetAspect = when (ratio) {
        "2.39:1" -> 2.39f
        "2.00:1" -> 2.00f
        "1.85:1" -> 1.85f
        "1.66:1" -> 1.66f
        "16:9" -> 16f / 9f
        "4:3" -> 4f / 3f
        else -> 2.39f
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
    ) {
        val w = maxWidth
        val h = maxHeight
        if (w <= 0.dp || h <= 0.dp) return@BoxWithConstraints

        val currentAspect = w.value / h.value

        if (currentAspect < targetAspect) {
            // Screen is taller than target -> Letterbox (Top & Bottom bars)
            val activeH = (w.value / targetAspect).dp
            val barH = ((h - activeH) / 2f).coerceAtLeast(0.dp)

            Column(modifier = Modifier.fillMaxSize()) {
                // Top Cinema Matte Bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barH)
                        .background(Color.Black.copy(alpha = opacity)),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        modifier = Modifier
                            .padding(bottom = 6.dp)
                            .background(Color(0x33FFFFFF), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "$ratio ANAMORPHIC SCOPE",
                            color = AppleYellow,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                // Transparent Active Cinema Aperture Window
                Spacer(modifier = Modifier.weight(1f))

                // Bottom Cinema Matte Bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barH)
                        .background(Color.Black.copy(alpha = opacity))
                )
            }
        } else {
            // Screen is wider than target -> Pillarbox (Left & Right bars)
            val activeW = (h.value * targetAspect).dp
            val barW = ((w - activeW) / 2f).coerceAtLeast(0.dp)

            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(barW)
                        .background(Color.Black.copy(alpha = opacity))
                )
                Spacer(modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(barW)
                        .background(Color.Black.copy(alpha = opacity))
                )
            }
        }
    }
}
