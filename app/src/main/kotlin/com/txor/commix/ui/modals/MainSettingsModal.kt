package com.txor.commix.ui.modals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txor.commix.PhotoFormatEngine
import com.txor.commix.model.CameraUiState
import com.txor.commix.ui.CommixViewModel
import com.txor.commix.ui.theme.AppleYellow
import com.txor.commix.ui.theme.CinemaCardDark
import com.txor.commix.ui.theme.CinemaGreen
import com.txor.commix.ui.theme.CinemaTextLight
import java.util.Locale

/**
 * 1:1 Complete Main Camera Settings Modal matching Flutter's _showMainSettings
 * with all 11 mode and subsystem configuration tabs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainSettingsModal(
    state: CameraUiState,
    viewModel: CommixViewModel,
    onDismiss: () -> Unit
) {
    val tabs = listOf(
        "PHOTO", "VIDEO", "PRO", "CINEMATIC", "NIGHT", "SLOW-MO", "TIME-LAPSE", "FLAGSHIPS", "AUTOMATION", "QUEUE", "GENERAL"
    )
    var selectedTab by remember { mutableStateOf(if (state.activeMode in tabs) state.activeMode else "GENERAL") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xF0121214),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0x4DFFFFFF)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(horizontal = 16.dp)
        ) {
            // Header Bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Camera Settings",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color(0x26FFD60A))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = state.activeMode,
                            color = AppleYellow,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "Done",
                        color = AppleYellow,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Mode Tabs Bar
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                items(tabs) { tab ->
                    val isSelected = (tab == selectedTab)
                    val isCurrent = (tab == state.activeMode)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(19.dp))
                            .background(if (isSelected) Color(0xFF2C2C2E) else Color(0x14FFFFFF))
                            .border(
                                width = if (isSelected) 1.dp else 0.6.dp,
                                color = if (isSelected) AppleYellow else Color(0x1FFFFFFF),
                                shape = RoundedCornerShape(19.dp)
                            )
                            .clickable { selectedTab = tab }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isCurrent && !isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(5.dp)
                                        .clip(RoundedCornerShape(2.5.dp))
                                        .background(AppleYellow)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(
                                text = tab,
                                color = if (isSelected) AppleYellow else if (isCurrent) Color.White else Color.Gray,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Tab Content
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (selectedTab) {
                    "PHOTO" -> {
                        SettingsCard(title = "Photo Format & Compression") {
                            val isLossless = PhotoFormatEngine.isLossless(state.photoFormat, state.photoQuality)
                            val isZeroComp = PhotoFormatEngine.isZeroCompression(state.photoFormat)
                            val formatSubtitle = when {
                                isZeroComp -> "${state.photoFormat.uppercase()} · Zero Compression (Archival Exact)"
                                isLossless -> "${state.photoFormat.uppercase()} · 100% (Lossless Compressed)"
                                else -> "${state.photoFormat.uppercase()} · ${state.photoQuality}% Quality"
                            }
                            SettingsSegmentedRow(
                                title = "Standard Formats",
                                subtitle = formatSubtitle,
                                options = listOf(
                                    "jpeg" to "JPEG",
                                    "heic" to "HEIC",
                                    "webp" to "WEBP",
                                    "png" to "PNG"
                                ),
                                selected = state.photoFormat,
                                onSelected = { viewModel.onFormatChanged(it, state.photoQuality) }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            SettingsSegmentedRow(
                                title = "Uncompressed & Lossless (Zero Compression)",
                                subtitle = "Bit-exact pixel streams without compression algorithms",
                                options = listOf(
                                    "lossless" to "BMP (Raw)",
                                    "tiff" to "TIFF 6.0",
                                    "raw_dng" to "RAW DNG"
                                ),
                                selected = state.photoFormat,
                                onSelected = { viewModel.onFormatChanged(it, 100) }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            SettingsSliderRow(
                                title = "Compression Quality",
                                value = (if (isLossless) 100 else state.photoQuality).toFloat(),
                                range = 50f..100f,
                                label = if (isLossless) "100% (Lossless)" else "${state.photoQuality}%",
                                onValueChange = { viewModel.onFormatChanged(state.photoFormat, it.toInt()) }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            SettingsSegmentedRow(
                                title = "Enhancement Model",
                                subtitle = "Model: ${state.photoEnhanceAlgo.uppercase()}",
                                options = listOf("clarity" to "Clarity", "detail" to "Detail", "unsharp" to "Sharp", "super_res" to "AI Super"),
                                selected = state.photoEnhanceAlgo,
                                onSelected = { viewModel.onSetEnhanceAlgo(it) }
                            )
                        }

                        SettingsCard(title = "Computational Imaging") {
                            SettingsToggleRow(
                                title = "Process Zero (Zero Smoothing)",
                                subtitle = "Bypass ISP noise reduction & sharpening halos",
                                checked = state.isProcessZeroActive,
                                onCheckedChange = { viewModel.onToggleProcessZero() }
                            )
                            if (state.isProcessZeroActive) {
                                Spacer(modifier = Modifier.height(10.dp))
                                SettingsSliderRow(
                                    title = "Process Zero Intensity",
                                    value = state.processZeroIntensity,
                                    range = 0.0f..2.0f,
                                    label = String.format(Locale.US, "%.1f×", state.processZeroIntensity),
                                    onValueChange = { viewModel.onProcessZeroIntensityChanged(it) }
                                )
                            }
                        }
                    }

                    "VIDEO" -> {
                        SettingsCard(title = "Video Encoding & Bitrate") {
                            SettingsSegmentedRow(
                                title = "Video Codec",
                                subtitle = "Hardware AVC / HEVC / All-I Intra",
                                options = listOf("hevc" to "H.265 / HEVC", "h264" to "H.264", "all_i" to "All-I Intra"),
                                selected = state.videoCodec,
                                onSelected = { viewModel.onSetVideoCodec(it) }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0x1AFFFFFF))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Format Spec:", color = Color.Gray, fontSize = 11.sp)
                                Text(
                                    "${state.videoResolution} · ${state.videoFpsFloat.toInt()} FPS · ${state.videoBitrateBps / 1_000_000} Mbps",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        SettingsCard(title = "Audio Pipeline & Viewfinder") {
                            SettingsToggleRow(
                                title = "Broadcast Quality Audio (384kbps AAC LC)",
                                subtitle = "Studio broadcast high-bitrate audio stream",
                                checked = state.uncompressedAudio,
                                onCheckedChange = { viewModel.onToggleUncompressedAudio() }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            SettingsToggleRow(
                                title = "Audio Low Cut (80Hz High-Pass)",
                                subtitle = "Eliminate wind rumble & mechanical handling noise",
                                checked = state.audioLowCut,
                                onCheckedChange = { viewModel.onToggleAudioLowCut() }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            SettingsToggleRow(
                                title = "Disable Viewfinder Preview (Blind Mode)",
                                subtitle = "OLED blackout to unlock 100% cadence and 0 dropped frames",
                                checked = state.viewfinderDisabled,
                                onCheckedChange = { viewModel.onToggleViewfinderDisabled() }
                            )
                        }
                    }

                    "PRO" -> {
                        SettingsCard(title = "Pro Cinema Scopes & Assists") {
                            SettingsToggleRow(
                                title = "Luma Histogram (256-bin)",
                                subtitle = "Real-time luminance distribution & exposure zones",
                                checked = state.showHistogram,
                                onCheckedChange = { viewModel.toggleHistogram() }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            SettingsToggleRow(
                                title = "RGB Waveform Parade",
                                subtitle = "IRE calibration and channel clipping analysis",
                                checked = state.showWaveform,
                                onCheckedChange = { viewModel.toggleWaveform() }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            SettingsToggleRow(
                                title = "Chroma Vectorscope (Polar)",
                                subtitle = "Real-time hue and chroma saturation polar graticule",
                                checked = state.showVectorscope,
                                onCheckedChange = { viewModel.toggleVectorscope() }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            SettingsSegmentedRow(
                                title = "Default Shutter Angle",
                                subtitle = "Motion blur cadence preset",
                                options = listOf("180" to "180°", "144" to "144°", "172.8" to "172.8°", "360" to "360°"),
                                selected = state.defaultShutterAngle,
                                onSelected = { viewModel.onSetDefaultShutterAngle(it) }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            SettingsToggleRow(
                                title = "180° Cinema Shutter Lock",
                                subtitle = "Auto-link exposure duration strictly to video framerate",
                                checked = state.shutterAngleLocked,
                                onCheckedChange = { viewModel.onToggleShutter180Lock() }
                            )
                        }
                    }

                    "CINEMATIC" -> {
                        SettingsCard(title = "Cinematic Aspect Ratio & Optics") {
                            SettingsSegmentedRow(
                                title = "Framing Matte",
                                subtitle = "Cinema letterbox overlay",
                                options = listOf("2.39:1" to "2.39:1", "2.00:1" to "2.00:1", "1.85:1" to "1.85:1", "1.66:1" to "1.66:1", "16:9" to "16:9"),
                                selected = state.cinemaMatteRatio,
                                onSelected = { viewModel.onSetCinemaMatteRatio(it) }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            SettingsSegmentedRow(
                                title = "Anamorphic De-Squeeze",
                                subtitle = "Optical lens horizontal expansion",
                                options = listOf("1.0" to "Off", "1.33" to "1.33×", "1.55" to "1.55×", "1.75" to "1.75×", "2.0" to "2.0×"),
                                selected = state.anamorphicRatio.toString(),
                                onSelected = { viewModel.onAnamorphicChanged(it.toFloatOrNull() ?: 1.0f) }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            SettingsToggleRow(
                                title = "360° World Horizon Lock",
                                subtitle = "GoPro-grade IMU quaternion roll inversion",
                                checked = state.is360SphereMode,
                                onCheckedChange = { viewModel.onToggle360SphereMode() }
                            )
                        }
                    }

                    "NIGHT" -> {
                        SettingsCard(title = "Computational Night & Astrophotography") {
                            SettingsSegmentedRow(
                                title = "Multi-Frame Burst Stacking",
                                subtitle = "Align & merge RAW exposures",
                                options = listOf("4" to "4 Frames", "8" to "8 Frames", "12" to "12 Frames", "16" to "16 Frames"),
                                selected = state.nightFrameCount.toString(),
                                onSelected = { viewModel.onSetNightFrameCount(it.toIntOrNull() ?: 8) }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            SettingsToggleRow(
                                title = "Astro Alignment & Star Tracking",
                                subtitle = "IMU gyroscopic star field motion compensation",
                                checked = state.nightAstroAlignment,
                                onCheckedChange = { viewModel.onToggleNightAstro() }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            SettingsSegmentedRow(
                                title = "Night Tone Curve",
                                subtitle = "Black point calibration",
                                options = listOf("neutral" to "Neutral", "cold" to "Cold", "warm" to "Warm", "gold" to "Gold"),
                                selected = state.nightTone,
                                onSelected = { viewModel.onSetNightTone(it) }
                            )
                        }
                    }

                    "SLOW-MO" -> {
                        SettingsCard(title = "High Speed Slow-Motion") {
                            SettingsSegmentedRow(
                                title = "Sensor Readout FPS",
                                subtitle = "Hardware sensor high-speed rate",
                                options = listOf("120" to "120 FPS (4×)", "240" to "240 FPS (8×)"),
                                selected = state.slowMoFps.toString(),
                                onSelected = { viewModel.onSetSlowMoFps(it.toIntOrNull() ?: 120) }
                            )
                        }
                    }

                    "TIME-LAPSE" -> {
                        SettingsCard(title = "Time-Lapse & Motion Interval") {
                            SettingsSegmentedRow(
                                title = "Capture Interval",
                                subtitle = "Duration between successive frames",
                                options = listOf("1" to "1 sec", "2" to "2 sec", "5" to "5 sec", "10" to "10 sec", "30" to "30 sec"),
                                selected = state.timeLapseIntervalSec.toString(),
                                onSelected = { viewModel.onSetTimeLapseInterval(it.toIntOrNull() ?: 2) }
                            )
                        }
                    }

                    "FLAGSHIPS" -> {
                        SettingsCard(title = "Cinema Look & Color Science") {
                            SettingsSegmentedRow(
                                title = "Color Science Profile",
                                subtitle = "ASC CDL Grading Matrix",
                                options = listOf("none" to "Standard", "leica" to "Leica", "hasselblad" to "Hasselblad", "zeiss" to "Zeiss", "proraw" to "Apple"),
                                selected = state.lutLook,
                                onSelected = { viewModel.onLutChanged(it) }
                            )
                        }
                    }

                    "AUTOMATION" -> {
                        SettingsCard(title = "Motion & Parameter Automation") {
                            SettingsSegmentedRow(
                                title = "Trigger Mode",
                                subtitle = "Automation start event",
                                options = listOf("timer" to "Timer (3s)", "audio" to "Audio Clap", "gyro" to "Gyro Shake"),
                                selected = state.automationTriggerMode,
                                onSelected = { viewModel.onSetAutomationTrigger(it) }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            SettingsSegmentedRow(
                                title = "Motion Preset",
                                subtitle = "Cubic ease-in-out curve",
                                options = listOf("dolly_zoom" to "Dolly Zoom", "vertigo" to "Vertigo", "orbit_pan" to "Orbit Pan", "focus_ramp" to "Focus Ramp"),
                                selected = state.automationPresetId,
                                onSelected = { viewModel.onSelectAutomationPreset(it) }
                            )
                        }
                    }

                    "QUEUE" -> {
                        SettingsCard(title = "Processing Job Queue Engine") {
                            SettingsSegmentedRow(
                                title = "Worker Threads",
                                subtitle = "Concurrent background synthesis threads",
                                options = listOf("1" to "1 Core", "2" to "2 Cores", "4" to "4 Cores"),
                                selected = state.queueWorkerThreads.toString(),
                                onSelected = { viewModel.onSetQueueWorkerThreads(it.toIntOrNull() ?: 2) }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            SettingsToggleRow(
                                title = "Auto-Queue Multi-Exposure Stacking",
                                subtitle = "Defer heavy computation to background queue",
                                checked = state.queueAutoQueueStacking,
                                onCheckedChange = { viewModel.onToggleQueueAutoStacking() }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            val queue = com.txor.commix.ProcessingJobQueue.instance
                            val activeCount = queue.activeJobCount
                            val pendingCount = queue.pendingJobCount
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0x1AFFFFFF))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Queue Load:", color = Color.Gray, fontSize = 11.sp)
                                Text(
                                    "$activeCount Processing · $pendingCount Pending (${(queue.overallProgress * 100).toInt()}%)",
                                    color = if (activeCount > 0) AppleYellow else Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    "GENERAL" -> {
                        // 1. Viewfinder & Display
                        SettingsCard(title = "Viewfinder & Display") {
                            SettingsToggleRow(
                                title = "Disable Viewfinder Preview (Blackout)",
                                subtitle = "OLED battery-saving blackout (tap to wake)",
                                checked = state.viewfinderDisabled,
                                onCheckedChange = { viewModel.onToggleViewfinderDisabled() }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            SettingsToggleRow(
                                title = "Clean Viewfinder Mode",
                                subtitle = "Hide all non-essential HUD overlays & scopes",
                                checked = state.cleanViewfinderMode,
                                onCheckedChange = { viewModel.onToggleCleanViewfinder() }
                            )
                        }

                        // 2. Composition & Guides
                        SettingsCard(title = "Composition & Guides") {
                            SettingsSegmentedRow(
                                title = "Grid & Alignment",
                                subtitle = state.gridType.uppercase(),
                                options = listOf("off" to "Off", "3x3" to "3×3", "golden" to "Golden", "crosshair" to "Cross"),
                                selected = state.gridType,
                                onSelected = { viewModel.onSelectGridType(it) }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            SettingsToggleRow(
                                title = "Dual-Axis Horizon Leveler",
                                subtitle = "Roll & pitch visual level guides",
                                checked = state.showHorizonLeveler,
                                onCheckedChange = { viewModel.toggleHorizon() }
                            )
                        }

                        // 3. Haptics & Telemetry (1:1 with Flutter)
                        SettingsCard(title = "Haptics & Telemetry") {
                            SettingsToggleRow(
                                title = "Tactile Haptic Feedback",
                                subtitle = "Haptics for shutter, stops, and focus lock",
                                checked = state.hapticsEnabled,
                                onCheckedChange = { viewModel.onToggleHaptics() }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            SettingsToggleRow(
                                title = "Focus Ring Animations",
                                subtitle = "Visual reticle pulse and lock animation",
                                checked = state.animationsEnabled,
                                onCheckedChange = { viewModel.onToggleAnimations() }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            SettingsToggleRow(
                                title = "Storage & Battery Telemetry",
                                subtitle = "Live free disk and battery gauge in HUD",
                                checked = state.showStorageRemaining,
                                onCheckedChange = { viewModel.onToggleStorageRemaining() }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            SettingsToggleRow(
                                title = "Adaptive Performance Mode",
                                subtitle = "Auto-tune refresh rates on battery/thermal load",
                                checked = state.adaptivePerformanceEnabled,
                                onCheckedChange = { viewModel.onToggleAdaptivePerformance() }
                            )
                        }

                        // 4. EXIF & Privacy Engine (1:1 with Flutter)
                        SettingsCard(title = "EXIF & Privacy Engine") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { viewModel.onSetExifPreset(false) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x26FFFFFF)),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Allow All", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = { viewModel.onSetExifPreset(true) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FFD60A)),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Privacy Mode", color = AppleYellow, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            SettingsToggleRow(
                                title = "GPS Geotagging & Location",
                                subtitle = "Embed location coordinates in photos",
                                checked = state.exifLocation,
                                onCheckedChange = { viewModel.onToggleExifLocation() }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            SettingsToggleRow(
                                title = "Camera & Device Hardware",
                                subtitle = "Embed device make & model",
                                checked = state.exifCameraInfo,
                                onCheckedChange = { viewModel.onToggleExifCameraInfo() }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            SettingsToggleRow(
                                title = "Exposure & Shooting Data",
                                subtitle = "Embed ISO, shutter speed & aperture",
                                checked = state.exifExposureSettings,
                                onCheckedChange = { viewModel.onToggleExifExposure() }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            SettingsToggleRow(
                                title = "Lens & Optical Profile",
                                subtitle = "Embed focal length & lens model",
                                checked = state.exifLensInfo,
                                onCheckedChange = { viewModel.onToggleExifLensInfo() }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            SettingsToggleRow(
                                title = "Timestamp & Capture Time",
                                subtitle = "Embed date & exact capture timestamp",
                                checked = state.exifDateTime,
                                onCheckedChange = { viewModel.onToggleExifDateTime() }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            SettingsToggleRow(
                                title = "Image Description & Copyright",
                                subtitle = "Embed technical tags & copyright",
                                checked = state.exifImageDescription,
                                onCheckedChange = { viewModel.onToggleExifImageDesc() }
                            )
                        }

                        // 5. Flagship & Sensor Telemetry Card (1:1 with Flutter)
                        val activeCam = state.availableCameras.find { it.cameraId == state.activeCameraId }
                        SettingsCard(title = "Flagship & Sensor Telemetry") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0x1AFFFFFF))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "SENSOR: CAM ${state.activeCameraId} (${if (activeCam?.isFront == true) "FRONT" else "REAR"})",
                                        color = AppleYellow,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "MAX ${String.format(Locale.US, "%.1f", state.maxLensMp)} MP",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Optical Stabilization (OIS):", color = Color.Gray, fontSize = 10.sp)
                                    Text(
                                        if (activeCam?.hasOis == true) "Hardware VCM [HW]" else "Software Gyro Shift [SW]",
                                        color = if (activeCam?.hasOis == true) CinemaGreen else Color(0xFFFF9500),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Electronic Stabilization (EIS):", color = Color.Gray, fontSize = 10.sp)
                                    Text(
                                        if (state.eisMode == "legend") "EIS LEGEND 👑 [HW+SW]" else if (activeCam?.hasEis == true) "HAL Stream [HW]" else "Gyro Trajectory [SW]",
                                        color = CinemaGreen,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Dynamic Range (HDR):", color = Color.Gray, fontSize = 10.sp)
                                    Text(
                                        if (activeCam?.hasRaw == true) "Sensor Staggered [HW]" else "Exposure Fusion [SW]",
                                        color = if (activeCam?.hasRaw == true) CinemaGreen else Color(0xFFFF9500),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("RAW Bayer Stream:", color = Color.Gray, fontSize = 10.sp)
                                    Text(
                                        if (activeCam?.hasRaw == true) "Bayer CFA Stream [HW]" else "Lossless RGB [SW]",
                                        color = if (activeCam?.hasRaw == true) CinemaGreen else Color(0xFFFF9500),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1C1C1E))
            .padding(14.dp)
    ) {
        Text(
            text = title,
            color = AppleYellow,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 10.dp)
        )
        content()
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(text = subtitle, color = CinemaTextLight, fontSize = 10.sp, lineHeight = 13.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = AppleYellow,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0x33FFFFFF)
            )
        )
    }
}

@Composable
private fun SettingsSegmentedRow(
    title: String,
    subtitle: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelected: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(text = subtitle, color = AppleYellow, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x1AFFFFFF))
                .padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            options.forEach { (key, label) ->
                val isSel = (key == selected)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSel) AppleYellow else Color.Transparent)
                        .clickable { onSelected(key) }
                        .padding(vertical = 6.dp)
                ) {
                    Text(
                        text = label,
                        color = if (isSel) Color.Black else Color.White,
                        fontSize = 10.5.sp,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    label: String,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(text = label, color = AppleYellow, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = AppleYellow,
                activeTrackColor = AppleYellow,
                inactiveTrackColor = Color(0x33FFFFFF)
            )
        )
    }
}
