package com.txor.commix.ui.modals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
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
import com.txor.commix.ui.theme.CinemaCardDark
import com.txor.commix.ui.theme.CinemaGreen
import com.txor.commix.ui.theme.CinemaTextLight

data class AutomationPreset(
    val id: String,
    val title: String,
    val description: String,
    val icon: String,
    val durationSeconds: Float,
    val applyPreset: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyframeEditorModal(
    activePresetId: String,
    onSelectPreset: (AutomationPreset) -> Unit,
    onArmShot: () -> Unit,
    onDismiss: () -> Unit
) {
    val presets = remember {
        listOf(
            AutomationPreset(
                id = "dolly_zoom",
                title = "Dolly Zoom (Vertigo)",
                description = "Smooth zoom in from 1.0x to 3.2x while pulling focus forward",
                icon = "🔍",
                durationSeconds = 8.0f,
                applyPreset = {
                    com.txor.commix.bridge.NativeEngineBridge.nativeKeyframeReset()
                    com.txor.commix.bridge.NativeEngineBridge.nativeKeyframeAddPoint(0, 0f, 1.0f, 3)
                    com.txor.commix.bridge.NativeEngineBridge.nativeKeyframeAddPoint(0, 8f, 3.2f, 3)
                    com.txor.commix.bridge.NativeEngineBridge.nativeKeyframeAddPoint(5, 0f, 0.2f, 3)
                    com.txor.commix.bridge.NativeEngineBridge.nativeKeyframeAddPoint(5, 8f, 0.8f, 3)
                    com.txor.commix.bridge.NativeEngineBridge.nativeKeyframeStart(8.0f)
                }
            ),
            AutomationPreset(
                id = "sunrise_ramp",
                title = "Sunrise EV Ramp",
                description = "Gradual exposure transition from -1.5 EV to +1.0 EV",
                icon = "🌅",
                durationSeconds = 20.0f,
                applyPreset = {
                    com.txor.commix.bridge.NativeEngineBridge.nativeKeyframeReset()
                    com.txor.commix.bridge.NativeEngineBridge.nativeKeyframeAddPoint(3, 0f, -1.5f, 3)
                    com.txor.commix.bridge.NativeEngineBridge.nativeKeyframeAddPoint(3, 20f, 1.0f, 3)
                    com.txor.commix.bridge.NativeEngineBridge.nativeKeyframeStart(20.0f)
                }
            ),
            AutomationPreset(
                id = "golden_hour",
                title = "Golden Hour WB Drift",
                description = "Smooth color temp drift from 3200K tungsten to 5600K daylight",
                icon = "✨",
                durationSeconds = 15.0f,
                applyPreset = {
                    com.txor.commix.bridge.NativeEngineBridge.nativeKeyframeReset()
                    com.txor.commix.bridge.NativeEngineBridge.nativeKeyframeAddPoint(4, 0f, 3200f, 1)
                    com.txor.commix.bridge.NativeEngineBridge.nativeKeyframeAddPoint(4, 15f, 5600f, 1)
                    com.txor.commix.bridge.NativeEngineBridge.nativeKeyframeStart(15.0f)
                }
            ),
            AutomationPreset(
                id = "reveal_shot",
                title = "Cinematic Reveal",
                description = "Start zoomed-in (4.0x) on subject, smoothly pull out to wide (1.0x)",
                icon = "🎬",
                durationSeconds = 10.0f,
                applyPreset = {
                    com.txor.commix.bridge.NativeEngineBridge.nativeKeyframeReset()
                    com.txor.commix.bridge.NativeEngineBridge.nativeKeyframeAddPoint(0, 0f, 4.0f, 3)
                    com.txor.commix.bridge.NativeEngineBridge.nativeKeyframeAddPoint(0, 10f, 1.0f, 3)
                    com.txor.commix.bridge.NativeEngineBridge.nativeKeyframeAddPoint(5, 0f, 0.1f, 3)
                    com.txor.commix.bridge.NativeEngineBridge.nativeKeyframeAddPoint(5, 10f, 0.9f, 3)
                    com.txor.commix.bridge.NativeEngineBridge.nativeKeyframeStart(10.0f)
                }
            ),
            AutomationPreset(
                id = "dramatic_rack",
                title = "Dramatic Rack Focus",
                description = "Snap rack focus from foreground subject to background with 2s hold",
                icon = "🎯",
                durationSeconds = 9.0f,
                applyPreset = {
                    com.txor.commix.bridge.NativeEngineBridge.nativeKeyframeReset()
                    com.txor.commix.bridge.NativeEngineBridge.nativeKeyframeAddPoint(5, 0f, 0.2f, 3)
                    com.txor.commix.bridge.NativeEngineBridge.nativeKeyframeAddPoint(5, 3.5f, 0.2f, 0)
                    com.txor.commix.bridge.NativeEngineBridge.nativeKeyframeAddPoint(5, 5.5f, 0.85f, 3)
                    com.txor.commix.bridge.NativeEngineBridge.nativeKeyframeAddPoint(5, 9.0f, 0.85f, 0)
                    com.txor.commix.bridge.NativeEngineBridge.nativeKeyframeStart(9.0f)
                }
            )
        )
    }

    var selectedPreset by remember {
        mutableStateOf(presets.find { it.id == activePresetId } ?: presets.first())
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = CinemaCardDark,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.Gray) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Text(
                    text = "CINEMATOGRAPHY AUTOMATION PRESETS",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = AppleYellow
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() }
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.Gray,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp)
            ) {
                items(presets) { preset ->
                    val isSelected = (preset.id == selectedPreset.id)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) Color(0x33FFD60A) else Color(0x1AFFFFFF))
                            .border(
                                width = if (isSelected) 1.2.dp else 0.8.dp,
                                color = if (isSelected) AppleYellow else Color(0x22FFFFFF),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                selectedPreset = preset
                                onSelectPreset(preset)
                            }
                            .padding(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(text = preset.icon, fontSize = 22.sp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = preset.title,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.5.sp,
                                        color = if (isSelected) AppleYellow else Color.White
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${preset.description} (${preset.durationSeconds.toInt()}s)",
                                        fontSize = 11.sp,
                                        color = CinemaTextLight
                                    )
                                }
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = AppleYellow,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Arm Shot Button
            Button(
                onClick = {
                    selectedPreset.applyPreset()
                    onArmShot()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = AppleYellow),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "ARM AUTOMATION SHOT",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
