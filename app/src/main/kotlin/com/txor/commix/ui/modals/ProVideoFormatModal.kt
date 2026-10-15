package com.txor.commix.ui.modals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import com.txor.commix.BroadcastFpsStandard
import com.txor.commix.MagneticFpsSnapper
import com.txor.commix.PipelineThroughputEngine
import com.txor.commix.PipelineZone
import com.txor.commix.VideoQualityProfile
import com.txor.commix.ui.theme.AppleYellow
import com.txor.commix.ui.theme.CinemaCardDark
import com.txor.commix.ui.theme.CinemaTextLight
import java.util.Locale

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import kotlin.math.abs
import kotlin.math.round

import androidx.compose.ui.platform.LocalContext
import com.txor.commix.ui.CommixHaptics

/**
 * 1:1 Complete Port of Flutter's widgets/pro_video_format_modal.dart
 * Precision Broadcast Standards, Dynamic Factor-Based Sensor/Encoder Ceilings,
 * 4-Zone Pipeline Throughput Monitor, and Cinema Headless (Blind VF) Support.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProVideoFormatModal(
    currentResolution: String,
    currentFpsFloat: Double,
    currentBitrateBps: Int,
    isViewfinderDisabled: Boolean = false,
    availableResolutions: List<String> = emptyList(),
    availableFpsList: List<Double> = emptyList(),
    hapticsEnabled: Boolean = true,
    onResolutionSelected: (String) -> Unit,
    onFpsSelected: (Double) -> Unit,
    onBitrateSelected: (Int) -> Unit,
    onToggleDisableVf: (Boolean) -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val allResolutions = remember(availableResolutions) {
        PipelineThroughputEngine.getStandardResolutions(availableResolutions)
    }

    val maxFps = remember(currentResolution, isViewfinderDisabled) {
        PipelineThroughputEngine.getMaxFpsForResolution(
            resolution = currentResolution,
            isVfDisabled = isViewfinderDisabled
        )
    }

    val throughput = remember(currentResolution, currentFpsFloat, isViewfinderDisabled, maxFps) {
        PipelineThroughputEngine.evaluate(
            resolution = currentResolution,
            fps = currentFpsFloat,
            isVfDisabled = isViewfinderDisabled,
            hardwareMaxFps = maxFps
        )
    }

    val zoneColor = when (throughput.zone) {
        PipelineZone.OPTIMAL -> Color(0xFF34C759)
        PipelineZone.RENDER_LAG -> Color(0xFFFFD60A)
        PipelineZone.BLIND_VF_WORK -> Color(0xFFFF9500)
        PipelineZone.UNSUPPORTED -> Color(0xFFFF3B30)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = CinemaCardDark,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.Gray) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "PRO VIDEO RESOLUTION & FRAME RATE",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp,
                        color = Color.White,
                        letterSpacing = 0.8.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Precision Broadcast Standards with Real Pipeline Limits",
                        color = Color.Gray,
                        fontSize = 10.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(AppleYellow)
                        .clickable {
                            CommixHaptics.click(context, hapticsEnabled)
                            onDismiss()
                        }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "DONE",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 1. PIPELINE THROUGHPUT & OVERLOAD MONITOR
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PIPELINE THROUGHPUT & OVERLOAD LIMITS",
                    color = Color.Gray,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "%.1f Mpx/s · %.0f%% Load",
                        throughput.currentMegaPixelsPerSecond,
                        throughput.overloadPercentage
                    ),
                    color = zoneColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 4-Zone Segmented Meter Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0x33000000))
                    .border(0.8.dp, Color(0x22FFFFFF), RoundedCornerShape(5.dp))
            ) {
                // Background Segment Guides
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(0.40f).fillMaxHeight().background(Color(0x2234C759)))
                    Box(modifier = Modifier.weight(0.25f).fillMaxHeight().background(Color(0x22FFD60A)))
                    Box(modifier = Modifier.weight(0.25f).fillMaxHeight().background(Color(0x22FF9500)))
                    Box(modifier = Modifier.weight(0.10f).fillMaxHeight().background(Color(0x22FF3B30)))
                }
                // Live Progress Fill
                val fillFraction = (throughput.overloadPercentage / 100.0).toFloat().coerceIn(0.05f, 1.0f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fillFraction)
                        .fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(
                                listOf(zoneColor.copy(alpha = 0.6f), zoneColor)
                            )
                        )
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Segment Legend
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("🟢 OPTIMAL (≤60)", color = Color.Gray, fontSize = 8.sp)
                Text("🟡 VF LAG (60-90)", color = Color.Gray, fontSize = 8.sp)
                Text("🟠 BLIND VF (90-240)", color = Color.Gray, fontSize = 8.sp)
                Text("🔴 LIMIT", color = Color.Gray, fontSize = 8.sp)
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Live Operational Zone Status Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(zoneColor.copy(alpha = 0.12f))
                    .border(1.dp, zoneColor.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = throughput.statusTitle,
                            color = zoneColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            letterSpacing = 0.3.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = throughput.statusDescription,
                        color = Color.White,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = throughput.mitigationAction,
                        color = Color.LightGray,
                        fontSize = 9.5.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. VIEWFINDER PIPELINE MODE & ISP SOLO PASS (Always accessible)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isViewfinderDisabled) Color(0x33FF9500) else Color(0x18FFFFFF))
                    .border(
                        1.dp,
                        if (isViewfinderDisabled) Color(0xFFFF9500) else Color(0x22FFFFFF),
                        RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Viewfinder Mode: ${if (isViewfinderDisabled) "Headless Cinema" else "Dual-Stream Live"}",
                                color = if (isViewfinderDisabled) Color(0xFFFF9500) else Color.White,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isViewfinderDisabled) Color(0xFFFF9500) else Color(0xFF34C759))
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (isViewfinderDisabled) "⚡ 100% ISP BOOST" else "● DUAL STREAM",
                                    color = Color.Black,
                                    fontSize = 7.5.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = if (isViewfinderDisabled) {
                                "Viewfinder preview detached during recording. 100% of ISP bandwidth allocated to encoder (unlocks higher FPS: 64MP @ 1.7 FPS, 4K @ 60 FPS, 16MP @ 22 FPS) with 0 dropped frames."
                            } else {
                                "Live viewfinder preview active at 60 FPS. ISP bandwidth is shared between preview and encoder streams."
                            },
                            color = Color.LightGray,
                            fontSize = 9.sp,
                            lineHeight = 12.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Switch(
                        checked = isViewfinderDisabled,
                        onCheckedChange = {
                            CommixHaptics.click(context, hapticsEnabled)
                            onToggleDisableVf(it)
                            val newMax = PipelineThroughputEngine.getMaxFpsForResolution(
                                currentResolution,
                                isVfDisabled = it
                            )
                            if (currentFpsFloat > newMax) {
                                onFpsSelected(newMax)
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFFFF9500),
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = Color(0x44FFFFFF)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 3. MEGAPIXEL SLIDER & REAL SENSOR RESOLUTION
            val snapPoints = remember(allResolutions) {
                allResolutions.map { res ->
                    PipelineThroughputEngine.getResolutionDetail(res)
                }.sortedBy { it.megapixels }
            }
            val minMp = snapPoints.firstOrNull()?.megapixels?.toFloat() ?: 0.3f
            val maxMp = snapPoints.lastOrNull()?.megapixels?.toFloat() ?: 64.0f

            val currentDetail = remember(currentResolution) {
                PipelineThroughputEngine.getResolutionDetail(currentResolution)
            }

            var mpSliderValue by remember(currentResolution) {
                mutableFloatStateOf(currentDetail.megapixels.toFloat())
            }

            val snapThreshold = 0.35f
            val closestSnap = remember(mpSliderValue, snapPoints) {
                snapPoints.minByOrNull { abs(it.megapixels - mpSliderValue) }
            }
            val isNearSnap = closestSnap != null && abs(closestSnap.megapixels - mpSliderValue) <= snapThreshold
            val displayedDetail = remember(mpSliderValue, isNearSnap, closestSnap) {
                if (isNearSnap) {
                    closestSnap!!
                } else {
                    val (cw, ch) = PipelineThroughputEngine.calculateResolutionForMp(mpSliderValue.toDouble())
                    PipelineThroughputEngine.ResolutionDetail(
                        resolution = "${cw}x${ch}",
                        width = cw,
                        height = ch,
                        megapixels = mpSliderValue.toDouble(),
                        standardName = String.format(Locale.US, "%.1fMP CUSTOM SENSOR", mpSliderValue),
                        isRecognizedStandard = false
                    )
                }
            }

            val displayedMaxFps = PipelineThroughputEngine.getMaxFpsForResolution(
                displayedDetail.resolution,
                isVfDisabled = isViewfinderDisabled
            )
            val displayedEval = PipelineThroughputEngine.evaluate(
                resolution = displayedDetail.resolution,
                fps = minOf(currentFpsFloat, displayedMaxFps),
                isVfDisabled = isViewfinderDisabled,
                hardwareMaxFps = displayedMaxFps
            )

            val displayedZoneColor = when (displayedEval.zone) {
                PipelineZone.OPTIMAL -> Color(0xFF34C759)
                PipelineZone.RENDER_LAG -> Color(0xFFFFD60A)
                PipelineZone.BLIND_VF_WORK -> Color(0xFFFF9500)
                PipelineZone.UNSUPPORTED -> Color(0xFFFF3B30)
            }
            val displayedZoneBadge = when (displayedEval.zone) {
                PipelineZone.OPTIMAL -> "● OPTIMAL"
                PipelineZone.RENDER_LAG -> "▲ VF LOAD"
                PipelineZone.BLIND_VF_WORK -> "⚡ VF OFF"
                PipelineZone.UNSUPPORTED -> "✕ LIMIT"
            }

            // Header Text: CAPTURE RESOLUTION (MP SLIDER)
            Text(
                text = "CAPTURE RESOLUTION (MEGAPIXEL SENSOR SLIDER)",
                fontSize = 10.sp,
                color = Color.Gray,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            // Dynamic Readout Header: Large MP, Standard Mode Badge, Exact Dimensions
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    val mpText = String.format(Locale.US, if (displayedDetail.megapixels >= 10.0) "%.1f" else "%.2f", displayedDetail.megapixels)
                    Text(
                        text = mpText,
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "MP",
                        color = AppleYellow,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${displayedDetail.width}×${displayedDetail.height}",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }

                // Standard / Sensor Mode Badge ("whenever you go on standard one it tells")
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (displayedDetail.isRecognizedStandard) Color(0x3334C759) else Color(0x33FF9500)
                        )
                        .border(
                            0.8.dp,
                            if (displayedDetail.isRecognizedStandard) Color(0xFF34C759) else Color(0xFFFF9500),
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = displayedDetail.standardName,
                        color = if (displayedDetail.isRecognizedStandard) Color(0xFF34C759) else Color(0xFFFF9500),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Subline: Algorithmic Max Capable FPS & Zone
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Algorithmic Limit: Max ${if (displayedMaxFps % 1.0 == 0.0) "${displayedMaxFps.toInt()}" else String.format(Locale.US, "%.1f", displayedMaxFps)} FPS",
                    color = Color.LightGray,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = displayedZoneBadge,
                    color = displayedZoneColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Continuous Megapixel Slider with Magnetic Snapping
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Text(
                    text = String.format(Locale.US, "%.1fM", minMp),
                    color = Color.Gray,
                    fontSize = 10.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Slider(
                    value = mpSliderValue.coerceIn(minMp, maxMp),
                    onValueChange = { newMp ->
                        mpSliderValue = newMp
                        val near = snapPoints.minByOrNull { abs(it.megapixels - newMp) }
                        if (near != null && abs(near.megapixels - newMp) <= snapThreshold) {
                            if (near.resolution != currentResolution) {
                                CommixHaptics.tick(context, hapticsEnabled)
                                onResolutionSelected(near.resolution)
                                val newMax = PipelineThroughputEngine.getMaxFpsForResolution(
                                    near.resolution,
                                    isVfDisabled = isViewfinderDisabled
                                )
                                if (currentFpsFloat > newMax) {
                                    onFpsSelected(newMax)
                                }
                            }
                        }
                    },
                    onValueChangeFinished = {
                        val near = snapPoints.minByOrNull { abs(it.megapixels - mpSliderValue) }
                        if (near != null && abs(near.megapixels - mpSliderValue) <= snapThreshold) {
                            mpSliderValue = near.megapixels.toFloat()
                            onResolutionSelected(near.resolution)
                            val newMax = PipelineThroughputEngine.getMaxFpsForResolution(
                                near.resolution,
                                isVfDisabled = isViewfinderDisabled
                            )
                            if (currentFpsFloat > newMax) {
                                onFpsSelected(newMax)
                            }
                        } else {
                            val (cw, ch) = PipelineThroughputEngine.calculateResolutionForMp(mpSliderValue.toDouble())
                            val customRes = "${cw}x${ch}"
                            onResolutionSelected(customRes)
                            val newMax = PipelineThroughputEngine.getMaxFpsForResolution(
                                customRes,
                                isVfDisabled = isViewfinderDisabled
                            )
                            if (currentFpsFloat > newMax) {
                                onFpsSelected(newMax)
                            }
                        }
                        CommixHaptics.click(context, hapticsEnabled)
                    },
                    valueRange = minMp..maxMp,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = AppleYellow,
                        inactiveTrackColor = Color(0x33FFFFFF)
                    ),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = String.format(Locale.US, "%.1fM", maxMp),
                    color = Color.Gray,
                    fontSize = 10.sp
                )
            }

            // Quick Resolution Snap Chips (From Highest MP to Lowest)
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                items(snapPoints.size) { idx ->
                    val snap = snapPoints[snapPoints.size - 1 - idx]
                    val isSelected = (snap.resolution == currentResolution)
                    val chipMaxFps = PipelineThroughputEngine.getMaxFpsForResolution(
                        snap.resolution,
                        isVfDisabled = isViewfinderDisabled
                    )
                    val evalFps = if (isSelected) currentFpsFloat else minOf(currentFpsFloat, chipMaxFps)
                    val chipEval = PipelineThroughputEngine.evaluate(
                        resolution = snap.resolution,
                        fps = evalFps,
                        isVfDisabled = isViewfinderDisabled,
                        hardwareMaxFps = chipMaxFps
                    )

                    val chipZoneColor = when (chipEval.zone) {
                        PipelineZone.OPTIMAL -> Color(0xFF34C759)
                        PipelineZone.RENDER_LAG -> Color(0xFFFFD60A)
                        PipelineZone.BLIND_VF_WORK -> Color(0xFFFF9500)
                        PipelineZone.UNSUPPORTED -> Color(0xFFFF3B30)
                    }

                    val chipZoneBadge = when (chipEval.zone) {
                        PipelineZone.OPTIMAL -> "● OPTIMAL"
                        PipelineZone.RENDER_LAG -> "▲ VF LOAD"
                        PipelineZone.BLIND_VF_WORK -> "⚡ VF OFF"
                        PipelineZone.UNSUPPORTED -> "✕ LIMIT"
                    }

                    val shortLabel = when {
                        snap.megapixels >= 50.0 -> String.format(Locale.US, "%.0fMP UHD", snap.megapixels)
                        snap.resolution.contains("4096") -> "4K DCI"
                        snap.resolution.contains("3840") || snap.resolution.contains("4K") -> "4K UHD"
                        snap.resolution.contains("2560") || snap.resolution.contains("1440") -> "1440p QHD"
                        snap.resolution.contains("1920") || snap.resolution.contains("1080") -> "1080p FHD"
                        snap.resolution.contains("1280") || snap.resolution.contains("720") -> "720p HD"
                        snap.resolution.contains("640") || snap.resolution.contains("480") -> "480p SD"
                        snap.megapixels >= 10.0 -> String.format(Locale.US, "%.0fMP", snap.megapixels)
                        snap.megapixels >= 1.0 -> String.format(Locale.US, "%.1fMP", snap.megapixels)
                        else -> snap.resolution
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) AppleYellow else Color(0x22FFFFFF))
                            .border(
                                1.dp,
                                if (isSelected) AppleYellow else chipZoneColor.copy(alpha = 0.5f),
                                RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                CommixHaptics.click(context, hapticsEnabled)
                                onResolutionSelected(snap.resolution)
                                mpSliderValue = snap.megapixels.toFloat()
                                val newMax = PipelineThroughputEngine.getMaxFpsForResolution(
                                    snap.resolution,
                                    isVfDisabled = isViewfinderDisabled
                                )
                                if (currentFpsFloat > newMax) {
                                    onFpsSelected(newMax)
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = shortLabel,
                                color = if (isSelected) Color.Black else CinemaTextLight,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.5.sp
                            )
                            Text(
                                text = "${snap.width}x${snap.height}",
                                color = if (isSelected) Color(0xFF333333) else Color.Gray,
                                fontSize = 8.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Max: ${if (chipMaxFps % 1.0 == 0.0) "${chipMaxFps.toInt()}" else String.format(Locale.US, "%.1f", chipMaxFps)} FPS",
                                color = if (isSelected) Color(0xFF222222) else Color(0xFFBBBBBB),
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = chipZoneBadge,
                                color = if (isSelected) Color(0xFF111111) else chipZoneColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 8.sp
                            )
                        }
                    }
                }
            }

            // 3. PRECISION FLOAT FRAME RATE READOUT & BADGE
            val matchedStandard = BroadcastFpsStandard.findClosestStandard(currentFpsFloat, tolerance = 0.05)
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = String.format(Locale.US, if (currentFpsFloat % 1.0 == 0.0) "%.1f" else "%.2f", currentFpsFloat),
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "FPS",
                        color = AppleYellow,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (matchedStandard != null) Color(0x3334C759) else Color(0x33FF9500)
                        )
                        .border(
                            0.8.dp,
                            if (matchedStandard != null) Color(0xFF34C759) else Color(0xFFFF9500),
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = matchedStandard?.standardName ?: "CUSTOM FLOAT PACE",
                        color = if (matchedStandard != null) Color(0xFF34C759) else Color(0xFFFF9500),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Continuous Precision Float Slider with Magnetic Snapping
            var fpsSliderValue by remember(currentFpsFloat) { mutableFloatStateOf(currentFpsFloat.toFloat()) }
            val minFps = 1.0f
            val maxFpsFloat = maxFps.toFloat().coerceAtLeast(1.0f)
            val sliderMin = if (maxFpsFloat <= 1.0f) 0.5f else minFps
            val sliderMax = maxFpsFloat

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Text(
                    text = String.format(Locale.US, if (sliderMin % 1f == 0f) "%.0f" else "%.1f", sliderMin),
                    color = Color.Gray,
                    fontSize = 10.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Slider(
                    value = fpsSliderValue.coerceIn(sliderMin, sliderMax),
                    onValueChange = { newFps ->
                        fpsSliderValue = newFps
                        onFpsSelected(round(newFps.toDouble() * 100.0) / 100.0)
                    },
                    onValueChangeFinished = {
                        CommixHaptics.tick(context, hapticsEnabled)
                        val snapResult = MagneticFpsSnapper.snap(
                            fpsSliderValue.toDouble(),
                            snapThreshold = 0.85
                        )
                        val chosenFps = if (snapResult.didSnap) {
                            snapResult.fps
                        } else {
                            round(fpsSliderValue.toDouble() * 10.0) / 10.0
                        }
                        onFpsSelected(chosenFps)
                    },
                    valueRange = sliderMin..sliderMax,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = AppleYellow,
                        inactiveTrackColor = Color(0x33FFFFFF)
                    ),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = String.format(Locale.US, if (sliderMax % 1f == 0f) "%.0f" else "%.1f", sliderMax),
                    color = Color.Gray,
                    fontSize = 10.sp
                )
            }

            // Dynamic Sensor & Broadcast FPS Snap Chips
            val dynamicFpsList = remember(maxFps) {
                PipelineThroughputEngine.getDynamicFpsChipsForResolution(maxFps)
            }
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp)
            ) {
                items(dynamicFpsList.size) { idx ->
                    val fpsVal = dynamicFpsList[idx]
                    val isSelected = (abs(fpsVal - currentFpsFloat) < 0.05)
                    val isExceedingHardware = fpsVal > (maxFps + 0.05)

                    val chipEval = PipelineThroughputEngine.evaluate(
                        resolution = currentResolution,
                        fps = fpsVal,
                        isVfDisabled = isViewfinderDisabled,
                        hardwareMaxFps = maxFps
                    )

                    val chipColor = when {
                        isExceedingHardware -> Color(0x44FF3B30)
                        chipEval.zone == PipelineZone.BLIND_VF_WORK -> Color(0x44FF9500)
                        else -> Color(0x22FFFFFF)
                    }

                    val chipLabel = if (fpsVal % 1.0 == 0.0) {
                        "${fpsVal.toInt()}"
                    } else {
                        String.format(Locale.US, "%.2f", fpsVal)
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) AppleYellow else chipColor)
                            .clickable {
                                CommixHaptics.click(context, hapticsEnabled)
                                onFpsSelected(fpsVal)
                            }
                            .padding(horizontal = 9.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = chipLabel,
                            color = if (isSelected) Color.Black else if (isExceedingHardware) Color(0xFFFF6961) else CinemaTextLight,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // 4. BITRATE & STORAGE FOOTPRINT
            val activeProfile = VideoQualityProfile.fromBitrate(currentBitrateBps)
            Text(
                text = "ENCODER BITRATE: ${activeProfile.label} (${activeProfile.formattedEstimate(1)})",
                fontSize = 10.sp,
                color = Color.Gray,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                VideoQualityProfile.profiles.forEach { profile ->
                    val isSelected = (profile.bitrateBps == currentBitrateBps)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) AppleYellow else Color(0x22FFFFFF))
                            .clickable {
                                CommixHaptics.click(context, hapticsEnabled)
                                onBitrateSelected(profile.bitrateBps)
                            }
                            .padding(vertical = 9.dp)
                    ) {
                        Text(
                            text = "${profile.bitrateBps / 1_000_000}M",
                            color = if (isSelected) Color.Black else CinemaTextLight,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}
