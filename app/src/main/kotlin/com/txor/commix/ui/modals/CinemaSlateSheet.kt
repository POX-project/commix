package com.txor.commix.ui.modals

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txor.commix.ui.theme.AppleYellow
import com.txor.commix.ui.theme.CinemaCardDark
import com.txor.commix.ui.theme.CinemaTextLight
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CinemaSlateSheet(
    roll: String,
    scene: String,
    take: String,
    onPlayTone: () -> Unit,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val clapperAngle = remember { Animatable(-25f) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.Black
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = "CINEMA PRODUCTION SLATE",
                color = AppleYellow,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Clapper Stick
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .rotate(clapperAngle.value)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White)
                    .border(2.dp, Color.Black)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Slate Board Body
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(CinemaCardDark)
                    .border(2.dp, Color(0x33FFFFFF))
                    .padding(16.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    SlateBox("ROLL", roll, Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(8.dp))
                    SlateBox("SCENE", scene, Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(8.dp))
                    SlateBox("TAKE", take, Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "COMMIX PRO CINEMA CAMERA • 4K DCI • 24.000 FPS",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Clapper Action Button (Claps stick and emits 1kHz sync tone)
            Button(
                onClick = {
                    coroutineScope.launch {
                        onPlayTone()
                        clapperAngle.animateTo(0f, tween(80))
                        clapperAngle.animateTo(-25f, tween(150))
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = AppleYellow),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text(
                    text = "CLAP SLATE & SYNC AUDIO (1kHz)",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun SlateBox(title: String, value: String, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x1AFFFFFF))
            .padding(vertical = 12.dp)
    ) {
        Text(text = title, fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
        Text(text = value, fontSize = 22.sp, color = CinemaTextLight, fontWeight = FontWeight.ExtraBold)
    }
}
