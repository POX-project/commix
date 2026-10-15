package com.txor.commix.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txor.commix.ui.theme.AppleYellow
import com.txor.commix.ui.theme.CinemaGreen
import com.txor.commix.ui.theme.RecordingRed
import java.util.Locale

@Composable
fun RecordingHud(
    isRecording: Boolean,
    seconds: Int,
    audioDb: Float = -24f,
    resolution: String = "1920x1080",
    fps: Double = 30.0,
    bitrateBps: Int = 50000000,
    remainingMinutes: Int = 165,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "rec_blink")
    val tallyAlpha by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "tally_alpha"
    )

    AnimatedVisibility(
        visible = isRecording,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Main Top Tally & Timecode Bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xDD000000))
                    .border(0.8.dp, RecordingRed.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                // Pulsing Red Tally Circle + "REC"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(RecordingRed.copy(alpha = tallyAlpha))
                    )
                    Text(
                        text = "REC",
                        color = RecordingRed,
                        fontWeight = FontWeight.Black,
                        fontSize = 11.sp,
                        letterSpacing = 0.5.sp
                    )
                }

                // SMPTE Timecode HH:MM:SS
                val hours = seconds / 3600
                val minutes = (seconds % 3600) / 60
                val secs = seconds % 60
                val timecode = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs)

                Text(
                    text = timecode,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.5.sp,
                    color = Color.White
                )

                // Stereo L / R Mini VU Meters
                val normDb = ((audioDb + 60f) / 60f).coerceIn(0f, 1f)
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.width(36.dp)
                ) {
                    VuChannelBar(normDb = normDb)
                    VuChannelBar(normDb = (normDb * 0.95f).coerceIn(0f, 1f))
                }
            }

            // Secondary Sub-Bar: Format, Bitrate, and Estimated Storage Remaining
            val resLabel = if (resolution.contains("3840")) "4K" else if (resolution.contains("1920")) "1080p" else "720p"
            val mbps = bitrateBps / 1_000_000
            val remHours = remainingMinutes / 60
            val remMins = remainingMinutes % 60
            val remLabel = if (remHours > 0) "${remHours}h ${remMins}m left" else "${remMins}m left"

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "$resLabel · ${fps.toInt()}fps · ${mbps}Mbps · $remLabel",
                    color = AppleYellow,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    letterSpacing = 0.3.sp
                )
            }
        }
    }
}

@Composable
private fun VuChannelBar(normDb: Float) {
    val barColor = if (normDb > 0.88f) RecordingRed
    else if (normDb > 0.7f) AppleYellow
    else CinemaGreen

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(1.5.dp))
            .background(Color(0x40FFFFFF))
            .drawBehind {
                val fillWidth = size.width * normDb.coerceIn(0f, 1f)
                drawRect(
                    color = barColor,
                    size = androidx.compose.ui.geometry.Size(fillWidth, size.height)
                )
            }
    )
}
