package com.txor.commix.ui.modals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txor.commix.CameraProbeResult
import com.txor.commix.ui.theme.AppleYellow
import com.txor.commix.ui.theme.CinemaTextLight

/**
 * 1:1 Hardware Lens Engine bottom sheet modal matching Flutter's
 * _showLensPicker and screen_lens_opened.png.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardwareLensModal(
    activeCameraId: String,
    cameras: List<CameraProbeResult>,
    onSelectCamera: (String) -> Unit,
    onProbeAll: () -> Unit,
    onDismiss: () -> Unit
) {
    var manualIdText by remember { mutableStateOf(activeCameraId) }
    var isProbing by remember { mutableStateOf(false) }

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
            // Header Row: Icon + Title + Active ID Pill + Done button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Hexagon,
                        contentDescription = "Lens Engine",
                        tint = AppleYellow,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "HARDWARE LENS ENGINE",
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
                            text = "ACTIVE ID: $activeCameraId",
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

            Spacer(modifier = Modifier.height(12.dp))

            // Spam & Probe Button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2C2C2E))
                    .clickable {
                        isProbing = true
                        onProbeAll()
                        isProbing = false
                    }
                    .padding(vertical = 10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isProbing) {
                        CircularProgressIndicator(
                            color = AppleYellow,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = "Spam",
                            tint = AppleYellow,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isProbing) "Spamming IDs -10000..10000..." else "⚡ Spam & Probe All Camera IDs (-10000..10000)",
                        color = AppleYellow,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.5.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Subtitle
            Text(
                text = "Detected Camera Sensors (${cameras.size} found):",
                color = CinemaTextLight,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // List of Camera Sensors
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 260.dp)
            ) {
                items(cameras, key = { it.cameraId }) { cam ->
                    val isSelected = cam.cameraId == activeCameraId
                    val facingStr = if (cam.isFront) "Front" else "Back"
                    val fl = cam.focalLengths.firstOrNull() ?: 26f
                    val roleStr = when {
                        cam.isMacro -> "macro"
                        cam.isUltraWide -> "ultra_wide"
                        cam.isTelephoto -> "telephoto"
                        else -> "wide"
                    }
                    val lensName = when {
                        cam.isFront -> "Front Camera"
                        cam.isMacro -> "Macro Lens"
                        cam.isUltraWide -> "Ultra-Wide Lens"
                        cam.isTelephoto -> "Telephoto Lens"
                        else -> "Wide Main Lens"
                    }
                    val title = "$lensName [ID ${cam.cameraId}]"

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) Color(0x33FFD60A) else Color(0x1AFFFFFF))
                            .border(
                                width = if (isSelected) 1.5.dp else 0.8.dp,
                                color = if (isSelected) AppleYellow else Color(0x26FFFFFF),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = if (cam.isFront) Icons.Default.Person else Icons.Default.CameraAlt,
                                contentDescription = null,
                                tint = if (isSelected) AppleYellow else Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = title,
                                    color = if (isSelected) AppleYellow else Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "$facingStr · ${fl.toInt()}mm · ${String.format(java.util.Locale.US, "%.1f", cam.sensorMp)} MP · $roleStr",
                                    color = CinemaTextLight,
                                    fontSize = 10.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(AppleYellow)
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "ACTIVE",
                                        color = Color.Black,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0x33FFFFFF))
                                        .clickable { onSelectCamera(cam.cameraId) }
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "SWITCH",
                                        color = Color.White,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Footer: Force Camera ID input + Direct Switch button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x14FFFFFF))
                    .border(0.6.dp, Color(0x26FFFFFF), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Force Camera ID:",
                        color = CinemaTextLight,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .width(44.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x22FFFFFF))
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        BasicTextField(
                            value = manualIdText,
                            onValueChange = { manualIdText = it.trim() },
                            textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            cursorBrush = SolidColor(AppleYellow)
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(AppleYellow)
                            .clickable {
                                if (manualIdText.isNotEmpty()) {
                                    onSelectCamera(manualIdText)
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "Direct Switch",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp
                        )
                    }
                }
            }
        }
    }
}
