package com.txor.commix.ui.modals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import com.txor.commix.automation.AutomationEngine
import com.txor.commix.automation.AutomationPresetConfig
import com.txor.commix.ui.theme.AppleYellow
import com.txor.commix.ui.theme.CinemaCardDark
import com.txor.commix.ui.theme.CinemaTextLight
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationModal(
    activePresetId: String = "dolly_zoom",
    activeTriggerMode: String = "timer_3s",
    maxZoom: Double = 30.0,
    onSelectPreset: (String) -> Unit = {},
    onStartPreset: (presetId: String, triggerMode: String) -> Unit = { _, _ -> },
    onStartCustom: (durationSec: Double, startZoom: Double, endZoom: Double, startEv: Float, endEv: Float, startFocus: Float, endFocus: Float, triggerMode: String) -> Unit = { _, _, _, _, _, _, _, _ -> },
    onStartAutomation: ((durationSec: Double, startZoom: Double, endZoom: Double, startEv: Float, endEv: Float, startFocus: Float, endFocus: Float) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Presets, 1: Custom
    var selectedPresetId by remember { mutableStateOf(activePresetId) }
    var triggerMode by remember { mutableStateOf(activeTriggerMode) }

    // Custom controls state
    var durationSec by remember { mutableFloatStateOf(10.0f) }
    var startZoom by remember { mutableFloatStateOf(1.0f) }
    var endZoom by remember { mutableFloatStateOf(3.5f) }
    var enableZoomRamp by remember { mutableStateOf(true) }

    var startEv by remember { mutableFloatStateOf(0.0f) }
    var endEv by remember { mutableFloatStateOf(0.0f) }
    var enableEvRamp by remember { mutableStateOf(false) }

    var startFocus by remember { mutableFloatStateOf(1.0f) }
    var endFocus by remember { mutableFloatStateOf(5.0f) }
    var enableFocusRamp by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xF0121214),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0x4DFFFFFF)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(horizontal = 18.dp, vertical = 6.dp)
        ) {
            // Header Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
            ) {
                Text(
                    text = "MOTION AUTOMATION CONTROL",
                    color = AppleYellow,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onDismiss) {
                    Text("Close", color = Color.White, fontSize = 14.sp)
                }
            }

            // Top Tab Selector (Presets vs Custom)
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color(0xFF1C1C1E),
                contentColor = AppleYellow,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            "BUILT-IN PRESETS",
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 12.sp,
                            color = if (selectedTab == 0) AppleYellow else Color.Gray
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            "CUSTOM MOTION",
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 12.sp,
                            color = if (selectedTab == 1) AppleYellow else Color.Gray
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Body Area
            Box(modifier = Modifier.weight(1f)) {
                if (selectedTab == 0) {
                    // Presets List
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(AutomationEngine.BUILT_IN_PRESETS) { preset ->
                            val isSelected = (preset.id == selectedPresetId)
                            PresetCard(
                                preset = preset,
                                isSelected = isSelected,
                                onSelect = {
                                    selectedPresetId = preset.id
                                    onSelectPreset(preset.id)
                                }
                            )
                        }
                    }
                } else {
                    // Custom Motion Controls
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Duration Card
                        AutomationCard(title = "Sequence Duration: ${durationSec.toInt()}s") {
                            Slider(
                                value = durationSec,
                                onValueChange = { durationSec = it },
                                valueRange = 3f..30f,
                                steps = 26,
                                colors = SliderDefaults.colors(thumbColor = AppleYellow, activeTrackColor = AppleYellow)
                            )
                        }

                        // Zoom Ramp Card
                        AutomationCard(title = "Zoom Ramping Track") {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = if (enableZoomRamp) String.format(Locale.US, "%.1f× → %.1f×", startZoom, endZoom) else "Disabled",
                                    color = if (enableZoomRamp) AppleYellow else Color.Gray,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = enableZoomRamp,
                                    onCheckedChange = { enableZoomRamp = it },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = AppleYellow)
                                )
                            }
                            if (enableZoomRamp) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(text = "Start Zoom: ${String.format(Locale.US, "%.1f×", startZoom)}", color = CinemaTextLight, fontSize = 10.sp)
                                Slider(
                                    value = startZoom,
                                    onValueChange = { startZoom = it },
                                    valueRange = 1.0f..maxZoom.toFloat().coerceAtLeast(10f),
                                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AppleYellow)
                                )
                                Text(text = "End Zoom: ${String.format(Locale.US, "%.1f×", endZoom)}", color = CinemaTextLight, fontSize = 10.sp)
                                Slider(
                                    value = endZoom,
                                    onValueChange = { endZoom = it },
                                    valueRange = 1.0f..maxZoom.toFloat().coerceAtLeast(10f),
                                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AppleYellow)
                                )
                            }
                        }

                        // EV Ramp Card
                        AutomationCard(title = "Exposure Compensation Track") {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = if (enableEvRamp) String.format(Locale.US, "%+.1f EV → %+.1f EV", startEv, endEv) else "Disabled",
                                    color = if (enableEvRamp) AppleYellow else Color.Gray,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = enableEvRamp,
                                    onCheckedChange = { enableEvRamp = it },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = AppleYellow)
                                )
                            }
                            if (enableEvRamp) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(text = "Start EV: ${String.format(Locale.US, "%+.1f", startEv)}", color = CinemaTextLight, fontSize = 10.sp)
                                Slider(
                                    value = startEv,
                                    onValueChange = { startEv = it },
                                    valueRange = -3.0f..3.0f,
                                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AppleYellow)
                                )
                                Text(text = "End EV: ${String.format(Locale.US, "%+.1f", endEv)}", color = CinemaTextLight, fontSize = 10.sp)
                                Slider(
                                    value = endEv,
                                    onValueChange = { endEv = it },
                                    valueRange = -3.0f..3.0f,
                                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AppleYellow)
                                )
                            }
                        }

                        // Focus Ramp Card
                        AutomationCard(title = "Focus Pull Track") {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = if (enableFocusRamp) String.format(Locale.US, "%.1f D → %.1f D", startFocus, endFocus) else "Disabled",
                                    color = if (enableFocusRamp) AppleYellow else Color.Gray,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = enableFocusRamp,
                                    onCheckedChange = { enableFocusRamp = it },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = AppleYellow)
                                )
                            }
                            if (enableFocusRamp) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(text = "Near Focus (Start): ${String.format(Locale.US, "%.1f D", startFocus)}", color = CinemaTextLight, fontSize = 10.sp)
                                Slider(
                                    value = startFocus,
                                    onValueChange = { startFocus = it },
                                    valueRange = 0.0f..10.0f,
                                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AppleYellow)
                                )
                                Text(text = "Far Focus (End): ${String.format(Locale.US, "%.1f D", endFocus)}", color = CinemaTextLight, fontSize = 10.sp)
                                Slider(
                                    value = endFocus,
                                    onValueChange = { endFocus = it },
                                    valueRange = 0.0f..10.0f,
                                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AppleYellow)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Trigger Selector
            AutomationCard(title = "Trigger Mode") {
                val triggers = listOf(
                    "timer_3s" to "3s Countdown",
                    "timer_5s" to "5s Countdown",
                    "instant" to "Instant Start"
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    triggers.forEach { (key, label) ->
                        val isSel = (key == triggerMode)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) AppleYellow else Color(0x1AFFFFFF))
                                .clickable { triggerMode = key }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                text = label,
                                color = if (isSel) Color.Black else Color.White,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Button
            val activePreset = AutomationEngine.getPresetById(selectedPresetId)
            val buttonText = if (selectedTab == 0) {
                "EXECUTE ${activePreset.title.uppercase()} (${activePreset.durationSeconds.toInt()}s)"
            } else {
                "START CUSTOM MOTION (${durationSec.toInt()}s)"
            }

            Button(
                onClick = {
                    if (selectedTab == 0) {
                        onStartPreset(selectedPresetId, triggerMode)
                    } else {
                        if (onStartAutomation != null) {
                            onStartAutomation(
                                durationSec.toDouble(),
                                if (enableZoomRamp) startZoom.toDouble() else 1.0,
                                if (enableZoomRamp) endZoom.toDouble() else 1.0,
                                if (enableEvRamp) startEv else 0f,
                                if (enableEvRamp) endEv else 0f,
                                if (enableFocusRamp) startFocus else -1f,
                                if (enableFocusRamp) endFocus else -1f
                            )
                        }
                        onStartCustom(
                            durationSec.toDouble(),
                            if (enableZoomRamp) startZoom.toDouble() else 1.0,
                            if (enableZoomRamp) endZoom.toDouble() else 1.0,
                            if (enableEvRamp) startEv else 0f,
                            if (enableEvRamp) endEv else 0f,
                            if (enableFocusRamp) startFocus else -1f,
                            if (enableFocusRamp) endFocus else -1f,
                            triggerMode
                        )
                    }
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = AppleYellow),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = buttonText,
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.5.sp
                )
            }
        }
    }
}

@Composable
private fun PresetCard(
    preset: AutomationPresetConfig,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) Color(0xFF2C2C2E) else Color(0xFF1C1C1E))
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) AppleYellow else Color(0x22FFFFFF),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onSelect() }
            .padding(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = preset.icon, fontSize = 22.sp, modifier = Modifier.padding(end = 12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = preset.title,
                        color = if (isSelected) AppleYellow else Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0x33E5A93C))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${preset.durationSeconds.toInt()}s",
                            color = AppleYellow,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = preset.description,
                    color = CinemaTextLight,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = AppleYellow,
                    modifier = Modifier
                        .size(20.dp)
                        .padding(start = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun AutomationCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1C1C1E))
            .padding(12.dp)
    ) {
        Text(text = title, color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}
