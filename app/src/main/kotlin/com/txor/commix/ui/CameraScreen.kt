package com.txor.commix.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.provider.MediaStore
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import java.util.Locale
import com.txor.commix.ProcessingJobQueue
import com.txor.commix.ui.components.*
import com.txor.commix.ui.modals.CinemaSlateSheet
import com.txor.commix.ui.modals.HardwareLensModal
import com.txor.commix.ui.modals.JobQueueModal
import com.txor.commix.ui.modals.KeyframeEditorModal
import com.txor.commix.ui.modals.MegapixelLimitsModal
import com.txor.commix.ui.modals.ProVideoFormatModal
import com.txor.commix.ui.modals.MainSettingsModal
import com.txor.commix.ui.modals.AutomationModal
import com.txor.commix.PipelineThroughputEngine
import com.txor.commix.ui.theme.*

/**
 * 1:1 Apple Cinema / Commix Pro Camera Screen in Jetpack Compose
 * matching screen_installed.png, screen_main_settings.png, and screen_lens_opened.png.
 */
@Composable
fun CameraScreen(
    viewModel: CommixViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val queue = ProcessingJobQueue.instance
    val queueJobs by queue.jobs.collectAsState()

    val context = LocalContext.current
    var showFormatModal by remember { mutableStateOf(false) }
    var showLensModal by remember { mutableStateOf(false) }
    var showMpModal by remember { mutableStateOf(false) }
    var showJobQueueModal by remember { mutableStateOf(false) }
    var showSlateSheet by remember { mutableStateOf(false) }
    var showKeyframeModal by remember { mutableStateOf(false) }
    var showFocusLoupe by remember { mutableStateOf(false) }
    var showMainSettings by remember { mutableStateOf(false) }
    var showAutomationModal by remember { mutableStateOf(false) }
    var cleanViewfinderMode by remember { mutableStateOf(false) }
    val isCleanActive = cleanViewfinderMode || state.cleanViewfinderMode
    var activeProTab by remember { mutableStateOf(ProControlTab.ISO) }

    // Tap to focus reticle state
    var tapFocusPoint by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var isFocusLocked by remember { mutableStateOf(false) }
    var vfWidth by remember { mutableFloatStateOf(1f) }
    var vfHeight by remember { mutableFloatStateOf(1f) }

    val currentMaxZoom by rememberUpdatedState(state.maxZoomRatio)
    var gestureAccumulatedZoom by remember { mutableDoubleStateOf(state.zoomRatio) }
    LaunchedEffect(state.zoomRatio) {
        gestureAccumulatedZoom = state.zoomRatio
    }

    LaunchedEffect(tapFocusPoint, isFocusLocked) {
        if (tapFocusPoint != null && !isFocusLocked) {
            kotlinx.coroutines.delay(3500)
            tapFocusPoint = null
            viewModel.onCancelFocus()
        }
    }

    LaunchedEffect(state.activeCameraId, state.activeMode) {
        tapFocusPoint = null
        isFocusLocked = false
        viewModel.onCancelFocus()
    }

    val modes = listOf("NIGHT", "SLOW-MO", "VIDEO", "CINEMATIC", "PHOTO", "PRO", "TIME-LAPSE", "PANORAMA", "AUTOMATION")
    val isVideoMode = state.activeMode in listOf("VIDEO", "CINEMATIC", "SLOW-MO", "TIME-LAPSE", "AUTOMATION")

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CinemaBlack)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(CinemaBlack)
        ) {
        // -------------------------------------------------------------
        // 1. TOP BAR CONTROLS (Height ~50dp)
        // -------------------------------------------------------------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 12.dp)
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            // Left Group: Flash Toggle & Action Mode / Shield Indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Flash Circle Button
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (state.flashMode != "off") Color(0x33FFD60A) else Color(0x33000000))
                        .clickable { viewModel.onCycleFlash() }
                ) {
                    Icon(
                        imageVector = if (state.flashMode == "off") Icons.Default.FlashOff else Icons.Default.FlashOn,
                        contentDescription = "Flash",
                        tint = if (state.flashMode != "off") AppleYellow else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Job Queue Icon Button
                val activeJobs = queue.activeJobCount
                val hasQueueWork = queue.hasWorkInProgress

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (hasQueueWork) Color(0x33FFD60A) else Color(0x33000000))
                        .clickable { showJobQueueModal = true }
                ) {
                    if (activeJobs > 0) {
                        CircularProgressIndicator(
                            color = AppleYellow,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = "Job Queue",
                            tint = if (hasQueueWork) AppleYellow else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Yellow Shield / Action Mode Badge (in Video Mode)
                if (isVideoMode) {
                    val isEisActive = state.eisMode != "off"
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (isEisActive) AppleYellow else Color(0x33000000))
                            .border(
                                0.8.dp,
                                if (isEisActive) AppleYellow else Color(0x33FFFFFF),
                                CircleShape
                            )
                            .clickable { viewModel.onCycleEis() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Action Mode EIS",
                            tint = if (isEisActive) Color.Black else Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Center Group: Apple Expandable Chevron & Status Pill
            if (isVideoMode) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Expand",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0x99000000))
                            .border(0.8.dp, Color(0x33FFFFFF), RoundedCornerShape(14.dp))
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = CinemaGreen,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Mode: ${state.activeMode}",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(44.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0x22000000))
                        .clickable { viewModel.toggleProControls() }
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Expand",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Right Group: Video Format / RAW & MP Badges / Settings Sliders
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isVideoMode) {
                    // Video Resolution & FPS Pill (e.g. 64MP · 2 or 1080p · 24)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0x33000000))
                            .border(0.8.dp, Color(0x33FFFFFF), RoundedCornerShape(14.dp))
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        CommixHaptics.click(context, state.hapticsEnabled)
                                        showFormatModal = true
                                    },
                                    onLongPress = {
                                        CommixHaptics.heavy(context, state.hapticsEnabled)
                                        viewModel.onCycleResolution()
                                    }
                                )
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        val (pw, ph) = PipelineThroughputEngine.parseResolution(state.videoResolution)
                        val pmp = (pw.toLong() * ph.toLong()) / 1_000_000.0
                        val resPillLabel = when {
                            pmp >= 50.0 -> String.format(java.util.Locale.US, "%.0fMP", pmp)
                            pmp >= 10.0 -> String.format(java.util.Locale.US, "%.0fMP", pmp)
                            state.videoResolution.contains("3840") || state.videoResolution.contains("4K") -> "4K"
                            state.videoResolution.contains("1920") || state.videoResolution.contains("1080") -> "1080p"
                            state.videoResolution.contains("1280") || state.videoResolution.contains("720") -> "720p"
                            pmp >= 1.0 -> String.format(java.util.Locale.US, "%.1fMP", pmp)
                            else -> state.videoResolution
                        }
                        val fpsPillLabel = if (state.videoFpsFloat % 1.0 == 0.0) {
                            "${state.videoFpsFloat.toInt()}"
                        } else {
                            String.format(java.util.Locale.US, "%.1f", state.videoFpsFloat)
                        }
                        Text(
                            text = "$resPillLabel · $fpsPillLabel",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp,
                            letterSpacing = 0.3.sp
                        )
                    }
                } else {
                    // RAW Toggle Badge
                    val isRawActive = state.photoFormat.contains("raw")
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isRawActive) AppleYellow else Color(0x33000000))
                            .border(
                                0.8.dp,
                                if (isRawActive) AppleYellow else Color(0x33FFFFFF),
                                RoundedCornerShape(14.dp)
                            )
                            .clickable {
                                CommixHaptics.click(context, state.hapticsEnabled)
                                viewModel.onToggleRaw()
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "RAW",
                            color = if (isRawActive) Color.Black else Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.5.sp,
                            letterSpacing = 0.3.sp
                        )
                    }

                    // Megapixel Pill Badge (e.g. 64.1MP)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0x33000000))
                            .border(1.2.dp, AppleYellow, RoundedCornerShape(14.dp))
                            .clickable {
                                CommixHaptics.click(context, state.hapticsEnabled)
                                showMpModal = true
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = String.format(java.util.Locale.US, "%.1fMP", state.targetPhotoMp),
                            color = AppleYellow,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.5.sp,
                            letterSpacing = 0.3.sp
                        )
                    }
                }

                // Settings 3-Sliders Circle Button
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0x33000000))
                        .clickable {
                            CommixHaptics.click(context, state.hapticsEnabled)
                            viewModel.toggleProControls()
                        }
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Pro Controls",
                        tint = if (state.showProControls) AppleYellow else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Overall Settings Gear Button
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0x33000000))
                        .clickable {
                            CommixHaptics.click(context, state.hapticsEnabled)
                            showMainSettings = true
                        }
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Camera Settings",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // -------------------------------------------------------------
        // 2. LIVE VIEWFINDER VIEWPORT (Expanded with exact mode aspect ratio & 24dp rounded corners)
        // -------------------------------------------------------------
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val isVideoOrCinema = state.activeMode in listOf("VIDEO", "SLOW-MO", "TIME-LAPSE", "CINEMATIC", "AUTOMATION")
            val targetRatio = if (state.previewAspectRatio > 0f) state.previewAspectRatio else {
                if (isVideoOrCinema) 9f / 16f else 3f / 4f
            }

            val containerRatio = maxWidth / maxHeight
            val vfModifier = if (containerRatio > targetRatio) {
                Modifier.fillMaxHeight().aspectRatio(targetRatio)
            } else {
                Modifier.fillMaxWidth().aspectRatio(targetRatio)
            }

            Box(
                modifier = vfModifier
                    .background(Color.Black)
                    .onGloballyPositioned { coords ->
                        vfWidth = coords.size.width.toFloat().coerceAtLeast(1f)
                        vfHeight = coords.size.height.toFloat().coerceAtLeast(1f)
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            val target = (gestureAccumulatedZoom * zoom).coerceIn(1.0, currentMaxZoom)
                            gestureAccumulatedZoom = target
                            viewModel.onZoomChanged(target)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                showFocusLoupe = !showFocusLoupe
                            },
                            onTap = { offset ->
                                tapFocusPoint = Pair(offset.x, offset.y)
                                isFocusLocked = false
                                val normX = (offset.x / vfWidth).coerceIn(0f, 1f)
                                val normY = (offset.y / vfHeight).coerceIn(0f, 1f)
                                viewModel.onTapFocus(normX, normY)
                            },
                            onLongPress = { offset ->
                                tapFocusPoint = Pair(offset.x, offset.y)
                                isFocusLocked = true
                                val normX = (offset.x / vfWidth).coerceIn(0f, 1f)
                                val normY = (offset.y / vfHeight).coerceIn(0f, 1f)
                                viewModel.onTapFocus(normX, normY)
                            }
                        )
                    }
            ) {
                val previewW = state.previewWidth
                val previewH = state.previewHeight
                ViewfinderSurface(
                    onSurfaceReady = { surface ->
                        viewModel.onSurfaceAvailable(surface)
                    },
                    onSurfaceDestroyed = {
                        viewModel.onSurfaceDestroyed()
                    },
                    previewWidth = previewW,
                    previewHeight = previewH,
                    sensorOrientation = state.sensorOrientation,
                    anamorphicDeSqueeze = state.anamorphicRatio,
                    targetAspectRatio = targetRatio,
                    modifier = Modifier.graphicsLayer {
                        val hwMax = state.hardwareMaxDigitalZoom.coerceAtLeast(1.0)
                        val digScale = if (state.zoomRatio > hwMax) {
                            (state.zoomRatio / hwMax).toFloat().coerceIn(1f, 30f)
                        } else 1f
                        scaleX = digScale
                        scaleY = digScale
                    }
                )

                // Cinema Anamorphic Letterbox Matte Overlay
                if (state.activeMode == "CINEMATIC" || (state.cinemaMatteRatio.isNotEmpty() && state.cinemaMatteRatio != "off")) {
                    val matte = if (state.activeMode == "CINEMATIC") state.cinemaMatteRatio.ifEmpty { "2.39:1" } else state.cinemaMatteRatio
                    CinemaMatteOverlay(ratio = matte)
                }

                // GCam-Style Panorama & 360 Degree Viewfinder Overlay
                if (state.activeMode == "PANORAMA") {
                    PanoramaGuidanceOverlay(
                        sweepAngle = state.panoramaSweepAngle,
                        progress = state.panoramaProgress,
                        rollDeg = state.rollDeg,
                        pitchDeg = state.pitchDeg,
                        is360Sphere = state.is360SphereMode,
                        isCapturing = state.isPanoramaCapturing,
                        capturedFrames = state.panoramaCapturedFrames,
                        onToggle360 = { viewModel.onToggle360SphereMode() }
                    )
                }

                // Blind Viewfinder / Cinema Headless Mode (active during recording)
                if (state.viewfinderDisabled && state.isRecording) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .clickable { viewModel.onToggleViewfinderDisabled() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (state.isRecording) {
                            // Cinema Headless OLED Monitor matching Flutter 1:1
                            val m = (state.recordingSeconds / 60).toString().padStart(2, '0')
                            val s = (state.recordingSeconds % 60).toString().padStart(2, '0')
                            val timeStr = "$m:$s"
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(Color(0x33FF9500))
                                        .border(1.2.dp, Color(0xFFFF9500), RoundedCornerShape(20.dp))
                                        .padding(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Bolt, contentDescription = null, tint = Color(0xFFFF9500), modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            "CINEMA BLIND VF · 100% ISP DEDICATED",
                                            color = Color(0xFFFF9500),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            letterSpacing = 0.6.sp
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(20.dp))

                                Text(
                                    text = timeStr,
                                    color = Color.White,
                                    fontSize = 52.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 2.sp
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                val dispRes = when {
                                    state.videoResolution.contains("3840") -> "4K"
                                    state.videoResolution.contains("1920") -> "1080p"
                                    state.videoResolution.contains("1280") -> "720p"
                                    else -> state.videoResolution
                                }
                                Text(
                                    text = "$dispRes · ${String.format(java.util.Locale.US, "%.1f", state.videoFpsFloat)} FPS · ${state.videoBitrateBps / 1_000_000} Mbps",
                                    color = AppleYellow,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )

                                Spacer(modifier = Modifier.height(22.dp))

                                // Real-time Audio VU meter
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth(0.85f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0x22FFFFFF))
                                        .border(0.8.dp, Color(0x22FFFFFF), RoundedCornerShape(10.dp))
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Text("AUDIO", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(Color(0x22FFFFFF))
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(state.audioNorm.coerceIn(0.01f, 1f))
                                                .fillMaxHeight()
                                                .background(if (state.audioNorm > 0.85f) Color(0xFFFF3B30) else Color(0xFF34C759))
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "${state.audioDb.toInt()} dB",
                                        color = Color.Gray,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    "Viewfinder preview detached to guarantee zero dropped frames.\n100% throughput dedicated to hardware encoder.",
                                    color = Color.Gray,
                                    fontSize = 10.sp,
                                    lineHeight = 13.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = AppleYellow, modifier = Modifier.size(36.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("VIEWFINDER DETACHED (BLIND MODE)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("Tap anywhere to restore live preview", color = Color.Gray, fontSize = 10.sp)
                            }
                        }
                    }
                }

                // Automation Mode HUD & Overlays
                if (state.activeMode == "AUTOMATION") {
                    // 1. Countdown Overlay (3s / 5s timer)
                    if (state.automationCountdownSec > 0) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0x88000000))
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(100.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xCC000000))
                                        .border(3.dp, AppleYellow, CircleShape)
                                ) {
                                    Text(
                                        text = "${state.automationCountdownSec}",
                                        color = AppleYellow,
                                        fontSize = 54.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "STARTING AUTOMATION IN...",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }

                    // 2. Live Automation Motion HUD during sequence execution
                    if ((state.isAutomationRunning || state.isRecording) && state.automationCountdownSec == 0) {
                        val preset = com.txor.commix.automation.AutomationEngine.getPresetById(state.automationPresetId)
                        val title = if (state.automationPresetId == "custom_motion") "CUSTOM MOTION" else preset.title
                        val elapsed = state.automationElapsedSec.toInt()
                        val total = state.automationDurationSec.toInt()
                        val elapsedMin = elapsed / 60
                        val elapsedSec = elapsed % 60
                        val totalMin = total / 60
                        val totalSec = total % 60
                        val timeStr = String.format(Locale.US, "%02d:%02d / %02d:%02d", elapsedMin, elapsedSec, totalMin, totalSec)
                        val pct = (state.automationProgress * 100).toInt()

                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 14.dp, start = 14.dp, end = 14.dp)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xEE121214))
                                .border(1.5.dp, AppleYellow, RoundedCornerShape(16.dp))
                                .padding(12.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.ElectricBolt,
                                            contentDescription = null,
                                            tint = AppleYellow,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = title.uppercase(),
                                            color = AppleYellow,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.5.sp
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = timeStr,
                                            color = Color.White,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 11.sp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(AppleYellow)
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "$pct%",
                                                color = Color.Black,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                }

                                // Progress Bar
                                LinearProgressIndicator(
                                    progress = { state.automationProgress },
                                    color = AppleYellow,
                                    trackColor = Color(0x33E5A93C),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                )

                                // Live Parameter Badges
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    val zText = String.format(Locale.US, "%.1f×", state.zoomRatio)
                                    val evText = String.format(Locale.US, "%+.1f", state.evStops)
                                    val focText = if (state.focusDiopters >= 0f) String.format(Locale.US, "%.2f D", state.focusDiopters) else "AF"
                                    val wbText = if (state.wbKelvin > 0) "${state.wbKelvin}K" else "AWB"

                                    AutomationParamBadge(label = "ZOOM", value = zText, modifier = Modifier.weight(1f))
                                    AutomationParamBadge(label = "EV", value = evText, modifier = Modifier.weight(1f))
                                    AutomationParamBadge(label = "FOCUS", value = focText, modifier = Modifier.weight(1f))
                                    AutomationParamBadge(label = "WB", value = wbText, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }

                    // 3. Idle Quick Action Pill
                    if (!state.isAutomationRunning && !state.isRecording) {
                        val preset = com.txor.commix.automation.AutomationEngine.getPresetById(state.automationPresetId)
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 16.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xCC000000))
                                .border(1.dp, AppleYellow, RoundedCornerShape(14.dp))
                                .clickable { showAutomationModal = true }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ElectricBolt, contentDescription = null, tint = AppleYellow, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "AUTOMATION: ${preset.title.uppercase()} (${preset.durationSeconds.toInt()}s)",
                                    color = AppleYellow,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                // Ultra Zoom AI Engine Active Badge
                if (state.zoomRatio >= 3.0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x665856D6))
                            .border(0.8.dp, Color(0xFF5856D6), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = Color(0xFFBF5AF2),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = String.format(java.util.Locale.US, "ULTRA ZOOM AI ENGINE ACTIVE · %.1f×", state.zoomRatio),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                letterSpacing = 0.4.sp
                            )
                        }
                    }
                }

                // Scopes, Rule-of-Thirds Grid, and Center Reticle
                ScopesOverlay(
                    showWaveform = state.showWaveform,
                    showHistogram = state.showHistogram,
                    showHorizon = state.showHorizonLeveler,
                    showGrid = state.showGrid,
                    rollDeg = state.rollDeg,
                    pitchDeg = state.pitchDeg,
                    waveformCols = state.waveformCols,
                    lumaHistogram = state.lumaHistogram
                )

                // Samsung One UI Composition & Horizon Suggestion
                if (!isCleanActive && !state.isRecording) {
                    ShotSuggestionWidget(
                        shouldShowShootNow = state.shouldShowShootNow,
                        bestShotScore = state.bestShotScore,
                        rollDeg = state.rollDeg,
                        pitchDeg = state.pitchDeg
                    )
                }

                // Interactive Focus Loupe
                if (showFocusLoupe) {
                    FocusLoupe(
                        currentFocusDiopters = state.focusDiopters,
                        onClose = { showFocusLoupe = false },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 48.dp, end = 12.dp)
                    )
                }

                // Tap Focus Reticle
                tapFocusPoint?.let { (fx, fy) ->
                    FocusRing(
                        x = fx,
                        y = fy,
                        evStops = state.evStops,
                        onEvChanged = { viewModel.onEvChanged(it) }
                    )
                }

                // Recording HUD with timecode and audio VU meter
                if (state.isRecording) {
                    RecordingHud(
                        isRecording = state.isRecording,
                        seconds = state.recordingSeconds,
                        audioDb = state.audioDb,
                        resolution = state.videoResolution,
                        fps = state.videoFpsFloat,
                        bitrateBps = state.videoBitrateBps,
                        remainingMinutes = state.estimatedTimeRemainingMinutes,
                        showStorageRemaining = state.showStorageRemaining,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp)
                    )
                }


                // Clean View Exit Pill (Top-Left corner of viewfinder)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 14.dp, top = 14.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x99000000))
                        .border(0.7.dp, Color(0x40FFFFFF), RoundedCornerShape(12.dp))
                        .clickable {
                            val next = !isCleanActive
                            cleanViewfinderMode = next
                            if (state.cleanViewfinderMode != next) viewModel.onToggleCleanViewfinder()
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = "Clean View",
                            tint = AppleYellow,
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (isCleanActive) "CLEAN VIEW (TAP TO EXIT)" else "CLEAN VIEW",
                            color = Color.White,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp
                        )
                    }
                }

                // Job Queue HUD Pill (top-right of viewfinder overlay when work in progress)
                if (!isCleanActive && queue.hasWorkInProgress) {
                    val qActive = queue.activeJobCount
                    val qUnfinished = qActive + queue.pendingJobCount
                    val qPct = (queue.overallProgress * 100).toInt()

                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 14.dp, top = 14.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xCC000000))
                            .border(0.8.dp, AppleYellow, RoundedCornerShape(12.dp))
                            .clickable { showJobQueueModal = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (qActive > 0) {
                                CircularProgressIndicator(
                                    color = AppleYellow,
                                    modifier = Modifier.size(10.dp),
                                    strokeWidth = 1.5.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Layers,
                                    contentDescription = null,
                                    tint = AppleYellow,
                                    modifier = Modifier.size(11.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "QUEUE: $qUnfinished ($qPct%)",
                                color = Color.White,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }

                // "SHOOT NOW!" Best Shot AI Cue
                androidx.compose.animation.AnimatedVisibility(
                    visible = state.shouldShowShootNow,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut(),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(bottom = 80.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(AppleYellow)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "SHOOT NOW! (PEAK STILLNESS)",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }

                // ---------------------------------------------------------
                // Floating Lens Selector Pill (inside bottom of Viewfinder)
                // Complete Quad-Lens Driver: 0.6x (15mm), 1x (26mm), 2x (52mm), Macro
                // ---------------------------------------------------------
                if (!isCleanActive) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp, start = 8.dp, end = 8.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color(0x99000000))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            val activeCam = state.availableCameras.find { it.cameraId == state.activeCameraId }
                            val isFrontActive = activeCam?.isFront == true
                            val zoom = state.zoomRatio

                            data class DynamicLensPill(
                                val key: String,
                                val label: String,
                                val isSelected: Boolean,
                                val onClick: () -> Unit
                            )

                            val pills = mutableListOf<DynamicLensPill>()

                            if (isFrontActive) {
                                val frontCams = state.availableCameras.filter { it.isFront }
                                if (frontCams.size <= 1) {
                                    val is1x = zoom < 1.4
                                    val is1_4x = zoom >= 1.4
                                    pills.add(DynamicLensPill("1x", "1x", is1x) { viewModel.onZoomChanged(1.0) })
                                    if (state.hardwareMaxDigitalZoom >= 1.5) {
                                        pills.add(DynamicLensPill("1.4x", "1.4x", is1_4x) { viewModel.onZoomChanged(1.4) })
                                    }
                                } else {
                                    frontCams.forEach { fCam ->
                                        val isSel = (fCam.cameraId == state.activeCameraId)
                                        val label = if (fCam.isUltraWide) "0.7x" else "1x"
                                        pills.add(DynamicLensPill(fCam.cameraId, label, isSel) {
                                            viewModel.onSelectLensPill(fCam.cameraId, 1.0)
                                        })
                                    }
                                }
                            } else {
                                val rearCams = state.availableCameras.filter { !it.isFront && !it.isDepth }
                                val mainCam = rearCams.firstOrNull { !it.isMacro && !it.isUltraWide && !it.isTelephoto }
                                    ?: rearCams.firstOrNull()
                                val mainFocal = mainCam?.focalLengths?.firstOrNull()?.takeIf { it > 0f } ?: 26f

                                val hasPhysicalTele = rearCams.any { it.isTelephoto }

                                // 1. Ultra-wide physical sensors
                                rearCams.filter { it.isUltraWide }.forEach { uwCam ->
                                    val uwFocal = uwCam.focalLengths.firstOrNull()?.takeIf { it > 0f } ?: 14f
                                    val factor = (uwFocal / mainFocal).coerceIn(0.4f, 0.8f)
                                    val label = String.format(java.util.Locale.US, "%.1fx", factor)
                                    val isSel = (state.activeCameraId == uwCam.cameraId)
                                    pills.add(DynamicLensPill(uwCam.cameraId, label, isSel) {
                                        viewModel.onSelectLensPill(uwCam.cameraId, 1.0)
                                    })
                                }

                                // 2. Main wide sensor (1x)
                                if (mainCam != null) {
                                    val is1xSel = (state.activeCameraId == mainCam.cameraId) && (zoom < 1.6)
                                    pills.add(DynamicLensPill(mainCam.cameraId, "1x", is1xSel) {
                                        viewModel.onSelectLensPill(mainCam.cameraId, 1.0)
                                    })
                                }

                                // 3. Telephoto physical sensors OR 2x digital zoom step on main sensor
                                if (hasPhysicalTele) {
                                    rearCams.filter { it.isTelephoto }.forEach { teleCam ->
                                        val teleFocal = teleCam.focalLengths.firstOrNull()?.takeIf { it > 0f } ?: 52f
                                        val factor = (teleFocal / mainFocal).coerceAtLeast(1.5f)
                                        val label = String.format(java.util.Locale.US, "%.0fx", factor)
                                        val isSel = (state.activeCameraId == teleCam.cameraId)
                                        pills.add(DynamicLensPill(teleCam.cameraId, label, isSel) {
                                            viewModel.onSelectLensPill(teleCam.cameraId, 1.0)
                                        })
                                    }
                                } else if (mainCam != null && state.hardwareMaxDigitalZoom >= 2.0) {
                                    val is2xSel = (state.activeCameraId == mainCam.cameraId) && (zoom >= 1.6)
                                    pills.add(DynamicLensPill("main_2x", "2x", is2xSel) {
                                        viewModel.onSelectLensPill(mainCam.cameraId, 2.0)
                                    })
                                }

                                // 4. Dedicated Macro physical sensor (excluding main camera)
                                rearCams.filter { it.isMacro && it.cameraId != mainCam?.cameraId }.forEach { macroCam ->
                                    val isSel = (state.activeCameraId == macroCam.cameraId)
                                    pills.add(DynamicLensPill(macroCam.cameraId, "Macro", isSel) {
                                        viewModel.onSelectLensPill(macroCam.cameraId, 1.0)
                                    })
                                }
                            }

                            // Render 4 Quad-Lens Buttons with overflow protection
                            pills.forEach { pill ->
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .padding(horizontal = 2.dp)
                                        .height(36.dp)
                                        .defaultMinSize(minWidth = 36.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(if (pill.isSelected) Color(0x61000000) else Color.Transparent)
                                        .then(
                                            if (pill.isSelected) Modifier.border(1.2.dp, AppleYellow.copy(alpha = 0.8f), RoundedCornerShape(18.dp))
                                            else Modifier
                                        )
                                        .clickable {
                                            CommixHaptics.click(context, state.hapticsEnabled)
                                            pill.onClick()
                                        }
                                        .padding(horizontal = 8.dp)
                                ) {
                                    Text(
                                        text = pill.label,
                                        color = if (pill.isSelected) AppleYellow else Color.White,
                                        fontWeight = if (pill.isSelected) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = if (pill.label.length >= 5) 11.5.sp else 13.sp,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            // LENS Chooser & Sensor Prober button [:: LENS]
                            Box(
                                modifier = Modifier
                                    .padding(start = 4.dp, end = 2.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0x26FFD60A))
                                    .border(0.8.dp, AppleYellow.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                                    .clickable {
                                        CommixHaptics.click(context, state.hapticsEnabled)
                                        showLensModal = true
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Hexagon,
                                        contentDescription = "LENS",
                                        tint = AppleYellow,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "LENS",
                                        color = AppleYellow,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.5.sp,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // 3. BOTTOM CONTROLS CONTAINER (Pure Black background)
        // -------------------------------------------------------------
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .background(CinemaBlack)
                .padding(bottom = 36.dp + navBottom, top = 4.dp)
        ) {
            // Pro Controls Expandable Drawer
            AnimatedVisibility(visible = state.showProControls) {
                ProSettingsCards(
                    activeTab = activeProTab,
                    onTabSelected = { activeProTab = it },
                    currentIso = state.iso,
                    onIsoSelected = { viewModel.onIsoChanged(it) },
                    currentShutterNs = state.shutterNs,
                    onShutterSelected = { viewModel.onShutterChanged(it) },
                    currentShutterAngle = state.shutterAngleDeg,
                    shutterAngleLocked = state.shutterAngleLocked,
                    onShutterAngleSelected = { viewModel.onShutterAngleChanged(it) },
                    onToggleShutter180Lock = { viewModel.onToggleShutter180Lock() },
                    videoFps = state.videoFps,
                    currentEv = state.evStops,
                    onEvSelected = { viewModel.onEvChanged(it) },
                    currentWb = state.wbKelvin,
                    onWbSelected = { viewModel.onWbChanged(it) },
                    currentFocus = state.focusDiopters,
                    onFocusSelected = { viewModel.onFocusChanged(it) },
                    currentZoom = state.zoomRatio,
                    maxZoom = state.maxZoomRatio,
                    onZoomSelected = { viewModel.onZoomChanged(it) },
                    spaceZoomLock = state.spaceZoomLockEngaged,
                    onToggleSpaceZoomLock = { viewModel.onToggleSpaceZoomLock() },
                    currentCurve = state.toneCurve,
                    onCurveSelected = { viewModel.onToneCurveChanged(it) },
                    currentGrain = state.filmGrain,
                    onGrainSelected = { viewModel.onFilmGrainChanged(it) },
                    currentLut = state.lutLook,
                    onLutSelected = { viewModel.onLutChanged(it) },
                    currentNd = state.ndFilter,
                    onNdSelected = { viewModel.onNdFilterChanged(it) },
                    currentAnamorphic = state.anamorphicRatio,
                    onAnamorphicSelected = { viewModel.onAnamorphicChanged(it) },
                    aebEnabled = state.aebEnabled,
                    aebShots = state.aebShots,
                    aebSpreadEv = state.aebSpreadEv,
                    onAebSelected = { en, shots, sp -> viewModel.onAebChanged(en, shots, sp) },
                    currentAudioGain = state.audioGainDb,
                    onAudioGainChanged = { viewModel.onAudioGainChanged(it) },
                    audioLowCut = state.audioLowCut,
                    onToggleAudioLowCut = { viewModel.onToggleAudioLowCut() },
                    isProcessZero = state.isProcessZeroActive,
                    onToggleProcessZero = { viewModel.onToggleProcessZero() },
                    processZeroIntensity = state.processZeroIntensity,
                    onProcessZeroIntensityChanged = { viewModel.onProcessZeroIntensityChanged(it) },
                    activeCamera = state.availableCameras.find { it.cameraId == state.activeCameraId },
                    showHistogram = state.showHistogram,
                    onToggleHistogram = { viewModel.toggleHistogram() },
                    showWaveform = state.showWaveform,
                    onToggleWaveform = { viewModel.toggleWaveform() },
                    showHorizon = state.showHorizonLeveler,
                    onToggleHorizon = { viewModel.toggleHorizon() },
                    showGrid = state.showGrid,
                    onToggleGrid = { viewModel.toggleGrid() },
                    onOpenSlate = { showSlateSheet = true },
                    onOpenKeyframePresets = { showKeyframeModal = true }
                )
            }


            // Tactile Rotary Dial for EV (active when EV tab is selected)
            if (!isCleanActive && state.showProControls && activeProTab == ProControlTab.EV) {
                val activeCam = state.availableCameras.find { it.cameraId == state.activeCameraId }
                val evStep = activeCam?.evStep ?: 0.1f
                val minEv = if (activeCam?.evRange != null) activeCam.evRange.lower * evStep else -3.0f
                val maxEv = if (activeCam?.evRange != null) activeCam.evRange.upper * evStep else 3.0f

                RotaryDial(
                    value = state.evStops,
                    minValue = minEv,
                    maxValue = maxEv,
                    step = evStep,
                    label = "EV COMPENSATION",
                    valueFormatter = { String.format(java.util.Locale.US, "%+.1f EV", it) },
                    onValueChanged = { viewModel.onEvChanged(it) },
                    hapticsEnabled = state.hapticsEnabled
                )
            }

            // Horizontal Pill Mode Carousel (NIGHT, SLOW-MO, VIDEO, CINEMATIC, PHOTO, PRO...)
            ModeCarousel(
                modes = modes,
                activeMode = state.activeMode,
                onModeSelected = { viewModel.onModeSelected(it) },
                hapticsEnabled = state.hapticsEnabled
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Shutter Row: Gallery Rounded Square, Dual-Ring Shutter, Camera Switcher
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
            ) {
                // Left: Gallery Thumbnail Button (50x50, 10dp radius matching Flutter)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x1AFFFFFF))
                        .clickable {
                            CommixHaptics.click(context, state.hapticsEnabled)
                            viewModel.openGallery(context)
                        }
                ) {
                    Icon(
                        imageVector = Icons.Default.Collections,
                        contentDescription = "Gallery",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Center: Dual-Ring Shutter Button (76x76 with morphing recording state matching Flutter)
                val isPanoCapturing = state.activeMode == "PANORAMA" && state.isPanoramaCapturing
                val isRecordingOrPano = state.isRecording || isPanoCapturing
                val outerRingColor = if (isPanoCapturing) AppleYellow else if (isVideoMode) RecordingRed else Color.White
                val innerCircleColor = if (isPanoCapturing) AppleYellow else if (isVideoMode) RecordingRed else Color.White

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(76.dp)
                        .border(4.dp, outerRingColor, CircleShape)
                        .clickable {
                            CommixHaptics.heavy(context, state.hapticsEnabled)
                            viewModel.onShutterTapped()
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (isRecordingOrPano) 28.dp else 62.dp)
                            .clip(RoundedCornerShape(if (isRecordingOrPano) 6.dp else 31.dp))
                            .background(innerCircleColor)
                    )
                }

                // Right: Camera Switcher Button (50x50, circle, matching Flutter)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(Color(0x1AFFFFFF))
                        .clickable {
                            CommixHaptics.click(context, state.hapticsEnabled)
                            viewModel.onSwitchCameraTapped()
                        }
                ) {
                    Icon(
                        imageVector = Icons.Default.FlipCameraAndroid,
                        contentDescription = "Switch Camera",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // -------------------------------------------------------------
        // 4. BOTTOM SHEET MODALS
        // -------------------------------------------------------------
        if (showLensModal) {
            HardwareLensModal(
                activeCameraId = state.activeCameraId,
                cameras = state.availableCameras,
                onSelectCamera = { id ->
                    viewModel.onSelectCamera(id)
                    showLensModal = false
                },
                onProbeAll = { viewModel.onProbeAllCameras() },
                onDismiss = { showLensModal = false }
            )
        }

        if (showMpModal) {
            MegapixelLimitsModal(
                currentMp = state.targetPhotoMp,
                maxMp = state.maxLensMp,
                isHiRes = state.isHiResActive,
                onMpSelected = { mp -> viewModel.onUpdatePhotoMp(mp) },
                onDismiss = { showMpModal = false }
            )
        }

        if (showJobQueueModal) {
            JobQueueModal(
                onDismiss = { showJobQueueModal = false }
            )
        }

        if (showFormatModal) {
            ProVideoFormatModal(
                currentResolution = state.videoResolution,
                currentFpsFloat = state.videoFpsFloat,
                currentBitrateBps = state.videoBitrateBps,
                isViewfinderDisabled = state.viewfinderDisabled,
                availableResolutions = viewModel.getSupportedVideoSizes(),
                availableFpsList = viewModel.getSupportedFpsList(),
                hapticsEnabled = state.hapticsEnabled,
                onResolutionSelected = { res ->
                    val newMax = PipelineThroughputEngine.getMaxFpsForResolution(
                        resolution = res,
                        isVfDisabled = state.viewfinderDisabled
                    )
                    val clampedFps = if (state.videoFpsFloat > newMax) newMax else state.videoFpsFloat
                    viewModel.onUpdateVideoFormat(res, clampedFps, state.videoBitrateBps)
                },
                onFpsSelected = { fps ->
                    viewModel.onUpdateVideoFormat(state.videoResolution, fps, state.videoBitrateBps)
                },
                onBitrateSelected = { br ->
                    viewModel.onUpdateVideoFormat(state.videoResolution, state.videoFpsFloat, br)
                },
                onToggleDisableVf = { disabled ->
                    viewModel.onSetViewfinderDisabled(disabled)
                    val newMax = PipelineThroughputEngine.getMaxFpsForResolution(
                        resolution = state.videoResolution,
                        isVfDisabled = disabled
                    )
                    if (state.videoFpsFloat > newMax) {
                        viewModel.onUpdateVideoFormat(state.videoResolution, newMax, state.videoBitrateBps)
                    }
                },
                onDismiss = { showFormatModal = false }
            )
        }

        if (showSlateSheet) {
            CinemaSlateSheet(
                roll = state.slateRoll,
                scene = state.slateScene,
                take = state.slateTake,
                onPlayTone = { viewModel.repository.audioEngine.playSlateTone() },
                onDismiss = { showSlateSheet = false }
            )
        }

        if (showKeyframeModal) {
            KeyframeEditorModal(
                activePresetId = state.automationPresetId,
                onSelectPreset = { preset ->
                    viewModel.onSelectAutomationPreset(preset.id)
                    viewModel.showStatus("Selected: ${preset.title}")
                },
                onArmShot = {
                    viewModel.onModeSelected("AUTOMATION")
                    viewModel.showStatus("Automation Armed — Tap Shutter to Begin")
                },
                onDismiss = { showKeyframeModal = false }
            )
        }

        if (showMainSettings) {
            MainSettingsModal(
                state = state,
                viewModel = viewModel,
                onDismiss = { showMainSettings = false }
            )
        }

        if (showAutomationModal) {
            AutomationModal(
                activePresetId = state.automationPresetId,
                activeTriggerMode = state.automationTriggerMode,
                maxZoom = state.maxZoomRatio,
                onSelectPreset = { presetId ->
                    viewModel.onSelectAutomationPreset(presetId)
                },
                onStartPreset = { presetId, trigger ->
                    viewModel.onSelectAutomationPreset(presetId)
                    viewModel.onSetAutomationTrigger(trigger)
                    viewModel.startPresetAutomation(presetId, trigger)
                    showAutomationModal = false
                },
                onStartCustom = { dur, sz, ez, sev, eev, sfoc, efoc, trigger ->
                    viewModel.onSetAutomationTrigger(trigger)
                    viewModel.startCustomAutomation(dur, sz, ez, sev, eev, sfoc, efoc, trigger)
                    showAutomationModal = false
                },
                onDismiss = { showAutomationModal = false }
            )
        }
    }

    // Floating status toast pill
    StatusPill(
        message = state.statusMessage,
        onDismiss = { viewModel.clearStatusMessage() },
        modifier = Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(top = 54.dp)
    )
}
}

@Composable
private fun AutomationParamBadge(label: String, value: String, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x55000000))
            .border(0.5.dp, Color(0x33FFFFFF), RoundedCornerShape(6.dp))
            .padding(vertical = 4.dp, horizontal = 2.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = label, color = Color.Gray, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(1.dp))
            Text(text = value, color = Color.White, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
        }
    }
}
