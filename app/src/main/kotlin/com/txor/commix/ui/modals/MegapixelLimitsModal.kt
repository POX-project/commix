package com.txor.commix.ui.modals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txor.commix.ui.theme.AppleYellow
import com.txor.commix.ui.theme.CinemaTextLight

/**
 * 1:1 Megapixel Limits Driver bottom sheet modal matching Flutter's
 * _showMpPicker and screen_settings_opened.png.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MegapixelLimitsModal(
    currentMp: Double,
    maxMp: Double = 64.0,
    isHiRes: Boolean = false,
    onMpSelected: (Double) -> Unit,
    onDismiss: () -> Unit
) {
    val minBound = 1.0f
    val maxBound = maxMp.toFloat().coerceAtLeast(minBound)
    var sliderValue by remember(currentMp, maxMp) { mutableFloatStateOf(currentMp.toFloat().coerceIn(minBound, maxBound)) }
    val presets = remember(maxMp) {
        val standard = listOf(5.0, 8.0, 12.0, 16.0, 24.0, 48.0, 64.0, 108.0, 200.0)
        (standard.filter { it < maxMp - 0.5 } + maxMp).distinct().sorted()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1C1C1E),
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header Row: Title + Tag + Done
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "MEGAPIXEL LIMITS DRIVER",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0x33FFD60A))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "MAX ${maxMp.toInt()}MP",
                            color = AppleYellow,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    }
                }

                Text(
                    text = "Done",
                    color = AppleYellow,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier.clickable { onDismiss() }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Subtitle 1: Hardware Sensor Presets
            Text(
                text = "Hardware Sensor Presets (Max: ${maxMp.toInt()} MP):",
                color = CinemaTextLight,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Presets Horizontal Row
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(presets) { preset ->
                    val isSel = kotlin.math.abs(sliderValue - preset.toFloat()) < 0.6f
                    val isPresetMax = kotlin.math.abs(preset - maxMp) < 0.5
                    val label = when {
                        isPresetMax -> "${preset.toInt()}MP MAX"
                        preset == 16.0 -> "16MP BINNED"
                        else -> "${preset.toInt()}MP"
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSel) {
                                    if (isPresetMax) AppleYellow else Color(0xFF007AFF)
                                } else Color(0x22FFFFFF)
                            )
                            .clickable {
                                sliderValue = preset.toFloat()
                                onMpSelected(preset)
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = label,
                            color = if (isSel && isPresetMax) Color.Black else Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Subtitle 2: Hardware Sensor Megapixel Stepper + Value display
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Hardware Sensor Megapixel Stepper:",
                    color = CinemaTextLight,
                    fontSize = 11.sp
                )
                Text(
                    text = String.format(java.util.Locale.US, "%.1f MP", sliderValue),
                    color = AppleYellow,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Stepper Slider from minBound to maxBound
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "${minBound.toInt()}M",
                    color = CinemaTextLight,
                    fontSize = 10.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Slider(
                    value = sliderValue.coerceIn(minBound, maxBound),
                    onValueChange = {
                        sliderValue = it
                    },
                    onValueChangeFinished = {
                        onMpSelected(sliderValue.toDouble())
                    },
                    valueRange = minBound..maxBound,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = AppleYellow,
                        inactiveTrackColor = Color(0x33FFFFFF)
                    ),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${maxMp.toInt().coerceAtLeast(1)}M",
                    color = CinemaTextLight,
                    fontSize = 10.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
