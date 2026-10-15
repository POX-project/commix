package com.txor.commix.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
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
import com.txor.commix.ui.theme.CinemaTextLight
import java.util.Locale

enum class ProControlTab {
    ZOOM, ISO, SHUTTER, ANGLE, EV, WB, FOCUS, CURVE, GRAIN, LUT, PROCESS_ZERO, AUDIO, ND, ANAMORPHIC, AEB, SCOPES
}

@Composable
fun ProSettingsCards(
    activeTab: ProControlTab,
    onTabSelected: (ProControlTab) -> Unit,
    currentIso: Int,
    onIsoSelected: (Int) -> Unit,
    currentShutterNs: Long,
    onShutterSelected: (Long) -> Unit,
    currentShutterAngle: Double = 180.0,
    shutterAngleLocked: Boolean = false,
    onShutterAngleSelected: (Double) -> Unit = {},
    onToggleShutter180Lock: () -> Unit = {},
    videoFps: Int = 30,
    currentEv: Float,
    onEvSelected: (Float) -> Unit,
    currentWb: Int,
    onWbSelected: (Int) -> Unit,
    currentFocus: Float,
    onFocusSelected: (Float) -> Unit,
    currentZoom: Double = 1.0,
    maxZoom: Double = 10.0,
    onZoomSelected: (Double) -> Unit = {},
    spaceZoomLock: Boolean = false,
    onToggleSpaceZoomLock: () -> Unit = {},
    currentCurve: String,
    onCurveSelected: (String) -> Unit,
    currentGrain: String,
    onGrainSelected: (String) -> Unit,
    currentLut: String = "none",
    onLutSelected: (String) -> Unit = {},
    currentNd: String = "off",
    onNdSelected: (String) -> Unit = {},
    currentAnamorphic: Float = 1.0f,
    onAnamorphicSelected: (Float) -> Unit = {},
    aebEnabled: Boolean = false,
    aebShots: Int = 3,
    aebSpreadEv: Float = 1.0f,
    onAebSelected: (Boolean, Int, Float) -> Unit = { _, _, _ -> },
    currentAudioGain: Float = 0.0f,
    onAudioGainChanged: (Float) -> Unit = {},
    audioLowCut: Boolean = false,
    onToggleAudioLowCut: () -> Unit = {},
    isProcessZero: Boolean = false,
    onToggleProcessZero: () -> Unit = {},
    processZeroIntensity: Float = 1.0f,
    onProcessZeroIntensityChanged: (Float) -> Unit = {},
    activeCamera: com.txor.commix.CameraProbeResult? = null,
    showHistogram: Boolean = false,
    onToggleHistogram: () -> Unit = {},
    showWaveform: Boolean = false,
    onToggleWaveform: () -> Unit = {},
    showHorizon: Boolean = false,
    onToggleHorizon: () -> Unit = {},
    showGrid: Boolean = false,
    onToggleGrid: () -> Unit = {},
    onOpenSlate: () -> Unit = {},
    onOpenKeyframePresets: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(CinemaCardDark.copy(alpha = 0.95f))
            .padding(vertical = 10.dp)
    ) {
        // Horizontal tab selectors
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
        ) {
            items(ProControlTab.values()) { tab ->
                val isSelected = (tab == activeTab)
                val tabLabel = when (tab) {
                    ProControlTab.PROCESS_ZERO -> "P0"
                    ProControlTab.SHUTTER -> "S"
                    ProControlTab.FOCUS -> "F"
                    else -> tab.name
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) AppleYellow else Color(0x22FFFFFF))
                        .clickable { onTabSelected(tab) }
                        .padding(horizontal = 11.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = tabLabel,
                        color = if (isSelected) Color.Black else CinemaTextLight,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Active control values row
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            when (activeTab) {
                ProControlTab.ZOOM -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val zoomSteps = listOf(
                            0.5 to "13mm (0.5×)",
                            1.0 to "24mm (1.0×)",
                            1.5 to "35mm (1.5×)",
                            2.0 to "48mm (2.0×)",
                            3.0 to "72mm (3.0×)",
                            5.0 to "120mm (5.0×)",
                            10.0 to "240mm (10×)"
                        ).filter { it.first <= maxZoom || it.first == 1.0 }

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(zoomSteps) { (z, label) ->
                                val isSel = kotlin.math.abs(currentZoom - z) < 0.15
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSel) AppleYellow else Color(0x1AFFFFFF))
                                        .clickable { onZoomSelected(z) }
                                        .padding(horizontal = 9.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isSel) Color.Black else CinemaTextLight,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            if (currentZoom >= 10.0) {
                                item {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (spaceZoomLock) AppleYellow else Color(0x33FFD60A))
                                            .border(0.8.dp, AppleYellow, RoundedCornerShape(6.dp))
                                            .clickable { onToggleSpaceZoomLock() }
                                            .padding(horizontal = 9.dp, vertical = 5.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Lock,
                                                contentDescription = null,
                                                tint = if (spaceZoomLock) Color.Black else AppleYellow,
                                                modifier = Modifier.size(11.dp)
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(
                                                text = "ZOOM LOCK",
                                                color = if (spaceZoomLock) Color.Black else AppleYellow,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Continuous Logarithmic Zoom Slider
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = String.format(Locale.US, "%.1f×", currentZoom),
                                color = AppleYellow,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.width(42.dp)
                            )
                            Slider(
                                value = currentZoom.toFloat(),
                                onValueChange = { onZoomSelected(it.toDouble()) },
                                valueRange = 1.0f..maxZoom.toFloat().coerceAtLeast(1.0f),
                                colors = SliderDefaults.colors(
                                    thumbColor = AppleYellow,
                                    activeTrackColor = AppleYellow,
                                    inactiveTrackColor = Color(0x33FFFFFF)
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                ProControlTab.ISO -> {
                    val isoRange = activeCamera?.isoRange
                    val minIso = (isoRange?.lower ?: 50).toFloat()
                    val maxIso = (isoRange?.upper ?: 6400).toFloat()
                    val baseIsos = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 12800)
                    val validIsos = if (isoRange != null) {
                        listOf(0) + baseIsos.filter { it in isoRange.lower..isoRange.upper }
                    } else {
                        listOf(0, 50, 100, 200, 400, 800, 1600, 3200, 6400)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (currentIso == 0) "ISO: AUTO" else "ISO: $currentIso",
                                color = AppleYellow,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                            Text(
                                text = "${minIso.toInt()} - ${maxIso.toInt()}",
                                color = CinemaTextLight,
                                fontSize = 10.sp
                            )
                        }
                        PillRow(items = validIsos, selected = currentIso, formatter = { if (it == 0) "AUTO" else "$it" }, onSelected = onIsoSelected)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "SLIDER",
                                color = CinemaTextLight,
                                fontSize = 10.sp,
                                modifier = Modifier.width(42.dp)
                            )
                            var sliderIso by remember(currentIso) { mutableFloatStateOf(if (currentIso == 0) minIso else currentIso.toFloat()) }
                            Slider(
                                value = sliderIso.coerceIn(minIso, maxIso),
                                onValueChange = { sliderIso = it },
                                onValueChangeFinished = { onIsoSelected(sliderIso.toInt()) },
                                valueRange = minIso..maxIso,
                                colors = SliderDefaults.colors(
                                    thumbColor = AppleYellow,
                                    activeTrackColor = AppleYellow,
                                    inactiveTrackColor = Color(0x33FFFFFF)
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                ProControlTab.SHUTTER -> {
                    val expRange = activeCamera?.expRange
                    val minExpNs = expRange?.lower ?: 125000L // 1/8000s
                    val maxExpNs = expRange?.upper ?: 1000000000L // 1s
                    val baseShutters = listOf(125000L, 250000L, 500000L, 1000000L, 2000000L, 4000000L, 8000000L, 16666666L, 33333333L, 50000000L, 100000000L, 250000000L, 500000000L, 1000000000L)
                    val validShutters = if (expRange != null) {
                        listOf(0L) + baseShutters.filter { it in expRange.lower..expRange.upper }
                    } else {
                        listOf(0L) + baseShutters
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (shutterAngleLocked) AppleYellow else Color(0x22FFFFFF))
                                    .clickable { onToggleShutter180Lock() }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = if (shutterAngleLocked) Color.Black else Color.White,
                                        modifier = Modifier.size(10.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "180° LOCK",
                                        color = if (shutterAngleLocked) Color.Black else Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                            val liveAngle = if (currentShutterNs > 0 && videoFps > 0) {
                                (360.0 * (currentShutterNs / 1_000_000_000.0) * videoFps).coerceIn(0.0, 360.0)
                            } else 180.0
                            Text(
                                text = if (currentShutterNs == 0L) "SHUTTER: AUTO" else "SHUTTER: 1/${(1_000_000_000.0 / currentShutterNs).toInt()}s (${String.format(Locale.US, "%.0f°", liveAngle)})",
                                color = AppleYellow,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        }
                        PillRow(items = validShutters, selected = currentShutterNs, formatter = { if (it == 0L) "AUTO" else "1/${(1_000_000_000.0 / it).toInt()}s" }, onSelected = onShutterSelected)
                        
                        // Logarithmic continuous shutter slider
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "SLIDER",
                                color = CinemaTextLight,
                                fontSize = 10.sp,
                                modifier = Modifier.width(42.dp)
                            )
                            val minLog = kotlin.math.ln(minExpNs.toDouble())
                            val maxLog = kotlin.math.ln(maxExpNs.toDouble())
                            val curLog = if (currentShutterNs > 0L) kotlin.math.ln(currentShutterNs.toDouble().coerceIn(minExpNs.toDouble(), maxExpNs.toDouble())) else kotlin.math.ln(16666666.0)
                            var sliderShutterLog by remember(currentShutterNs) { mutableFloatStateOf(curLog.toFloat()) }
                            Slider(
                                value = sliderShutterLog.coerceIn(minLog.toFloat(), maxLog.toFloat()),
                                onValueChange = { sliderShutterLog = it },
                                onValueChangeFinished = {
                                    val ns = kotlin.math.exp(sliderShutterLog.toDouble()).toLong()
                                    onShutterSelected(ns)
                                },
                                valueRange = minLog.toFloat()..maxLog.toFloat(),
                                colors = SliderDefaults.colors(
                                    thumbColor = AppleYellow,
                                    activeTrackColor = AppleYellow,
                                    inactiveTrackColor = Color(0x33FFFFFF)
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                ProControlTab.ANGLE -> {
                    val angles = listOf(45.0, 90.0, 144.0, 172.8, 180.0, 270.0, 360.0)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(angles) { a ->
                                val isSel = kotlin.math.abs(currentShutterAngle - a) < 0.5
                                val den = (360.0 * kotlin.math.max(1, videoFps) / a).toInt()
                                val label = when (a) {
                                    144.0 -> "144° (60Hz)"
                                    172.8 -> "172.8° (50Hz)"
                                    else -> "${a.toInt()}° (1/$den)"
                                }
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSel) AppleYellow else Color(0x1AFFFFFF))
                                        .clickable { onShutterAngleSelected(a) }
                                        .padding(horizontal = 9.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isSel) Color.Black else CinemaTextLight,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val curDen = (360.0 * kotlin.math.max(1, videoFps) / currentShutterAngle).toInt()
                            Text(
                                text = String.format(Locale.US, "%.1f° (1/%d)", currentShutterAngle, curDen),
                                color = AppleYellow,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                modifier = Modifier.width(90.dp)
                            )
                            Slider(
                                value = currentShutterAngle.toFloat(),
                                onValueChange = { onShutterAngleSelected(it.toDouble()) },
                                valueRange = 10f..360f,
                                colors = SliderDefaults.colors(
                                    thumbColor = AppleYellow,
                                    activeTrackColor = AppleYellow,
                                    inactiveTrackColor = Color(0x33FFFFFF)
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                ProControlTab.EV -> {
                    val evRange = activeCamera?.evRange
                    val evStep = activeCamera?.evStep ?: 0.1f
                    val minEv = (evRange?.lower ?: -30) * evStep
                    val maxEv = (evRange?.upper ?: 30) * evStep
                    val validEvs = listOf(-3.0f, -2.0f, -1.5f, -1.0f, -0.5f, 0.0f, 0.5f, 1.0f, 1.5f, 2.0f, 3.0f).filter { it in minEv..maxEv }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = String.format(Locale.US, "EXPOSURE: %+.1f EV", currentEv),
                                color = AppleYellow,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                            Text(
                                text = String.format(Locale.US, "%.1f to %+.1f", minEv, maxEv),
                                color = CinemaTextLight,
                                fontSize = 10.sp
                            )
                        }
                        PillRow(items = validEvs, selected = currentEv, formatter = { String.format(Locale.US, "%+.1f", it) }, onSelected = onEvSelected)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "SLIDER",
                                color = CinemaTextLight,
                                fontSize = 10.sp,
                                modifier = Modifier.width(42.dp)
                            )
                            var sliderEv by remember(currentEv) { mutableFloatStateOf(currentEv) }
                            Slider(
                                value = sliderEv.coerceIn(minEv, maxEv),
                                onValueChange = { sliderEv = it },
                                onValueChangeFinished = { onEvSelected(sliderEv) },
                                valueRange = minEv..maxEv,
                                colors = SliderDefaults.colors(
                                    thumbColor = AppleYellow,
                                    activeTrackColor = AppleYellow,
                                    inactiveTrackColor = Color(0x33FFFFFF)
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                ProControlTab.WB -> {
                    val wbs = listOf(0, 2800, 3200, 4000, 5000, 5600, 6500, 7500)
                    val wbLabels = mapOf(
                        0 to "AUTO",
                        2800 to "2800K",
                        3200 to "3200K Tungsten",
                        4000 to "4000K Fluor",
                        5000 to "5000K Day",
                        5600 to "5600K Sun",
                        6500 to "6500K Cloud",
                        7500 to "7500K Shade"
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (currentWb == 0) "WHITE BALANCE: AUTO" else "WHITE BALANCE: ${currentWb}K",
                                color = AppleYellow,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                            Text(
                                text = "2000K - 10000K",
                                color = CinemaTextLight,
                                fontSize = 10.sp
                            )
                        }
                        PillRow(items = wbs, selected = currentWb, formatter = { wbLabels[it] ?: "${it}K" }, onSelected = onWbSelected)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "SLIDER",
                                color = CinemaTextLight,
                                fontSize = 10.sp,
                                modifier = Modifier.width(42.dp)
                            )
                            var sliderWb by remember(currentWb) { mutableFloatStateOf(if (currentWb == 0) 5600f else currentWb.toFloat()) }
                            Slider(
                                value = sliderWb.coerceIn(2000f, 10000f),
                                onValueChange = { sliderWb = it },
                                onValueChangeFinished = { onWbSelected(sliderWb.toInt()) },
                                valueRange = 2000f..10000f,
                                colors = SliderDefaults.colors(
                                    thumbColor = AppleYellow,
                                    activeTrackColor = AppleYellow,
                                    inactiveTrackColor = Color(0x33FFFFFF)
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                ProControlTab.FOCUS -> {
                    val maxD = (activeCamera?.focusMaxDiopters ?: 10.0f).coerceAtLeast(1.0f)
                    val validFocuses = listOf(-1.0f, 0.0f, (maxD * 0.1f), (maxD * 0.2f), (maxD * 0.5f), (maxD * 0.8f), maxD)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val focusDesc = if (currentFocus < 0f) "AUTO AF"
                            else if (currentFocus <= 0.05f) "∞ INFINITY"
                            else {
                                val m = 1.0f / currentFocus
                                if (m < 1.0f) String.format(Locale.US, "%.0f cm (%.1f D)", m * 100f, currentFocus)
                                else String.format(Locale.US, "%.2f m (%.1f D)", m, currentFocus)
                            }
                            Text(
                                text = "FOCUS: $focusDesc",
                                color = AppleYellow,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                            Text(
                                text = "0.0 - ${String.format(Locale.US, "%.1f D", maxD)}",
                                color = CinemaTextLight,
                                fontSize = 10.sp
                            )
                        }
                        PillRow(
                            items = validFocuses,
                            selected = currentFocus,
                            formatter = {
                                if (it < 0f) "AF"
                                else if (it <= 0.05f) "∞ INF"
                                else {
                                    val m = 1.0f / it
                                    if (m < 1.0f) String.format(Locale.US, "%.0fcm", m * 100f)
                                    else String.format(Locale.US, "%.1fm", m)
                                }
                            },
                            onSelected = onFocusSelected
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "MANUAL",
                                color = CinemaTextLight,
                                fontSize = 10.sp,
                                modifier = Modifier.width(42.dp)
                            )
                            var sliderFocus by remember(currentFocus) { mutableFloatStateOf(if (currentFocus < 0f) 0f else currentFocus) }
                            Slider(
                                value = sliderFocus.coerceIn(0f, maxD),
                                onValueChange = { sliderFocus = it },
                                onValueChangeFinished = { onFocusSelected(sliderFocus) },
                                valueRange = 0f..maxD,
                                colors = SliderDefaults.colors(
                                    thumbColor = AppleYellow,
                                    activeTrackColor = AppleYellow,
                                    inactiveTrackColor = Color(0x33FFFFFF)
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                ProControlTab.CURVE -> {
                    val curves = listOf("neutral", "film_print", "cine_d", "linear", "arri_logc", "rec709", "slog3")
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "TONE CURVE: ${currentCurve.replace("_", " ").uppercase()}",
                                color = AppleYellow,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                        PillRow(items = curves, selected = currentCurve, formatter = { it.replace("_", " ").uppercase() }, onSelected = onCurveSelected)
                    }
                }

                ProControlTab.GRAIN -> {
                    val grains = listOf("off", "35mm", "16mm", "8mm")
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "FILM GRAIN: ${currentGrain.uppercase()}",
                                color = AppleYellow,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                        PillRow(items = grains, selected = currentGrain, formatter = { it.uppercase() }, onSelected = onGrainSelected)
                    }
                }

                ProControlTab.LUT -> {
                    val luts = listOf("none", "rec709", "slog3", "apple_log", "kodak_2383", "fuji_eterna", "bleach_bypass", "monochrome")
                    PillRow(items = luts, selected = currentLut, formatter = { it.replace("_", " ").uppercase() }, onSelected = onLutSelected)
                }

                ProControlTab.PROCESS_ZERO -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text(
                                    text = "PROCESS ZERO (ANTI-COMPUTATIONAL)",
                                    color = AppleYellow,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "Zero smoothing • Pure demosaic • Emulsion grain",
                                    color = CinemaTextLight,
                                    fontSize = 10.sp
                                )
                            }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isProcessZero) AppleYellow else Color(0x22FFFFFF))
                                    .clickable { onToggleProcessZero() }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = if (isProcessZero) "ACTIVE" else "ENABLE",
                                    color = if (isProcessZero) Color.Black else Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        if (isProcessZero) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = String.format(Locale.US, "INTENSITY: %.0f%%", processZeroIntensity * 100f),
                                    color = AppleYellow,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    modifier = Modifier.width(105.dp)
                                )
                                Slider(
                                    value = processZeroIntensity,
                                    onValueChange = onProcessZeroIntensityChanged,
                                    valueRange = 0.1f..1.0f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = AppleYellow,
                                        activeTrackColor = AppleYellow,
                                        inactiveTrackColor = Color(0x33FFFFFF)
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                ProControlTab.AUDIO -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AppleYellow)
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = null,
                                        tint = Color.Black,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Studio Mic 48kHz",
                                        color = Color.Black,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (audioLowCut) AppleYellow else Color(0x22FFFFFF))
                                    .clickable { onToggleAudioLowCut() }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "Low Cut 80Hz",
                                    color = if (audioLowCut) Color.Black else Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0x22FFFFFF))
                                    .clickable { onAudioGainChanged(0.0f) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "0 dB Reset",
                                    color = Color.White,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = String.format(Locale.US, "GAIN: %+.0fdB", currentAudioGain),
                                color = AppleYellow,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                modifier = Modifier.width(85.dp)
                            )
                            Slider(
                                value = currentAudioGain,
                                onValueChange = onAudioGainChanged,
                                valueRange = -12f..18f,
                                colors = SliderDefaults.colors(
                                    thumbColor = AppleYellow,
                                    activeTrackColor = AppleYellow,
                                    inactiveTrackColor = Color(0x33FFFFFF)
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                ProControlTab.ND -> {
                    val nds = listOf("off", "nd2", "nd4", "nd8", "nd16", "nd64", "nd1000")
                    val ndLabels = mapOf(
                        "off" to "Clear",
                        "nd2" to "ND2 (-1 EV)",
                        "nd4" to "ND4 (-2 EV)",
                        "nd8" to "ND8 (-3 EV)",
                        "nd16" to "ND16 (-4 EV)",
                        "nd64" to "ND64 (-6 EV)",
                        "nd1000" to "ND1000 (-10 EV)"
                    )
                    PillRow(items = nds, selected = currentNd, formatter = { ndLabels[it] ?: it.uppercase() }, onSelected = onNdSelected)
                }

                ProControlTab.ANAMORPHIC -> {
                    val anamorphicFactors = listOf(1.0f, 1.25f, 1.33f, 1.5f, 1.8f, 2.0f)
                    PillRow(
                        items = anamorphicFactors,
                        selected = currentAnamorphic,
                        formatter = { if (it == 1.0f) "1.0× (OFF)" else "${it}×" },
                        onSelected = onAnamorphicSelected
                    )
                }

                ProControlTab.AEB -> {
                    val aebOpts = listOf(
                        Triple(false, 3, 1.0f) to "OFF",
                        Triple(true, 3, 1.0f) to "3-Shot (±1 EV)",
                        Triple(true, 3, 2.0f) to "3-Shot (±2 EV)",
                        Triple(true, 5, 2.0f) to "5-Shot (±2 EV)"
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(aebOpts) { (opt, label) ->
                            val isSel = (!opt.first && !aebEnabled) || (aebEnabled && aebShots == opt.second && kotlin.math.abs(aebSpreadEv - opt.third) < 0.1f)
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSel) AppleYellow else Color(0x1AFFFFFF))
                                    .clickable { onAebSelected(opt.first, opt.second, opt.third) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSel) Color.Black else CinemaTextLight,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                ProControlTab.SCOPES -> {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        item { ScopeTogglePill(name = "HISTOGRAM", active = showHistogram, onToggle = onToggleHistogram) }
                        item { ScopeTogglePill(name = "WAVEFORM", active = showWaveform, onToggle = onToggleWaveform) }
                        item { ScopeTogglePill(name = "HORIZON", active = showHorizon, onToggle = onToggleHorizon) }
                        item { ScopeTogglePill(name = "GRID", active = showGrid, onToggle = onToggleGrid) }
                        item { ScopeTogglePill(name = "SLATE", active = false, onToggle = onOpenSlate) }
                        item { ScopeTogglePill(name = "AUTOMATION", active = false, onToggle = onOpenKeyframePresets) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScopeTogglePill(name: String, active: Boolean, onToggle: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) AppleYellow.copy(alpha = 0.25f) else Color(0x1AFFFFFF))
            .clickable { onToggle() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = name,
            color = if (active) AppleYellow else CinemaTextLight,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun <T> PillRow(
    items: List<T>,
    selected: T,
    formatter: (T) -> String,
    onSelected: (T) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(items) { item ->
            val isSelected = (item == selected)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSelected) AppleYellow else Color(0x1AFFFFFF))
                    .clickable { onSelected(item) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = formatter(item),
                    color = if (isSelected) Color.Black else CinemaTextLight,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 11.5.sp
                )
            }
        }
    }
}
