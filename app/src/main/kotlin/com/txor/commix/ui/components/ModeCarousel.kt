package com.txor.commix.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txor.commix.ui.theme.AppleYellow
import kotlinx.coroutines.launch

/**
 * 1:1 Apple Cinema Mode Carousel matching Flutter's lib/widgets/mode_carousel.dart
 * and screen_installed.png / screen_main_settings.png.
 */
@Composable
fun ModeCarousel(
    modes: List<String>,
    activeMode: String,
    onModeSelected: (String) -> Unit,
    hapticsEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val vibrator = remember { context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val activeIndex = modes.indexOf(activeMode).coerceAtLeast(0)

    LaunchedEffect(activeIndex) {
        listState.animateScrollToItem((activeIndex - 1).coerceAtLeast(0))
    }

    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(horizontal = 24.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        itemsIndexed(
            items = modes,
            key = { _, mode -> mode }
        ) { index, mode ->
            val isSelected = (mode == activeMode)
            val icon = getModeIcon(mode)

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (isSelected) Color(0x33FFD60A) else Color(0x15FFFFFF)
                    )
                    .border(
                        width = if (isSelected) 1.5.dp else 0.8.dp,
                        color = if (isSelected) AppleYellow else Color(0x22FFFFFF),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .clickable {
                        if (!isSelected) {
                            onModeSelected(mode)
                            if (hapticsEnabled) {
                                try {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                                    }
                                } catch (_: Throwable) {}
                            }
                            scope.launch {
                                listState.animateScrollToItem((index - 1).coerceAtLeast(0))
                            }
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = mode,
                        tint = if (isSelected) AppleYellow else Color(0xFF8E8E93),
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = mode,
                        color = if (isSelected) AppleYellow else Color.White,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Black else FontWeight.SemiBold,
                        letterSpacing = 0.6.sp
                    )
                }
            }
        }
    }
}

private fun getModeIcon(mode: String): ImageVector {
    return when (mode) {
        "NIGHT" -> Icons.Default.Bedtime
        "SLOW-MO" -> Icons.Default.Speed
        "VIDEO" -> Icons.Default.Videocam
        "CINEMATIC" -> Icons.Default.Movie
        "PHOTO" -> Icons.Default.CameraAlt
        "PRO" -> Icons.Default.Tune
        "TIME-LAPSE" -> Icons.Default.Timer
        "AUTOMATION" -> Icons.Default.ElectricBolt
        "PANORAMA" -> Icons.Default.PanoramaHorizontal
        else -> Icons.Default.CameraAlt
    }
}
