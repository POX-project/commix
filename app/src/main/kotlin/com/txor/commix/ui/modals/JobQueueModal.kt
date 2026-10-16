package com.txor.commix.ui.modals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txor.commix.*
import com.txor.commix.ui.theme.AppleYellow
import com.txor.commix.ui.theme.CinemaGreen
import com.txor.commix.ui.theme.CinemaTextLight

/**
 * High-performance background processing job queue modal matching Flutter's
 * _showJobQueueModal and screen_installed.png.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobQueueModal(
    onDismiss: () -> Unit
) {
    val queue = ProcessingJobQueue.instance
    val jobs by queue.jobs.collectAsState()
    val isPaused by queue.isPaused.collectAsState()

    val activeCount = queue.activeJobCount
    val pendingCount = queue.pendingJobCount
    val completedCount = queue.completedJobCount

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1C1C1E),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .width(36.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(2.5.dp))
                    .background(Color(0x4DFFFFFF))
            )
        },
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp)
        ) {
            // Header Row: Icon + Title + Status + Done button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x33FFD60A))
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = "Queue",
                            tint = AppleYellow,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Processing Job Queue",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "$activeCount Active · $pendingCount Pending · $completedCount Done",
                            color = CinemaTextLight,
                            fontSize = 11.sp
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x26FFFFFF))
                        .clickable { onDismiss() }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Done",
                        color = AppleYellow,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Buttons Row: Pause/Resume + Clear Done
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Pause / Resume Button
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isPaused) Color(0x3330D158) else Color(0x26FFFFFF))
                        .clickable {
                            if (isPaused) queue.resumeAll() else queue.pauseAll()
                        }
                        .padding(vertical = 9.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = null,
                            tint = if (isPaused) CinemaGreen else Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isPaused) "Resume Queue" else "Pause Queue",
                            color = if (isPaused) CinemaGreen else Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }

                // Clear Done Button
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x1FFFFFFF))
                        .clickable { queue.clearCompleted() }
                        .padding(vertical = 9.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Clear Done",
                            color = Color.Gray,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action Button Row 2: Run Engine Demo
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x26FFD60A))
                    .border(0.8.dp, AppleYellow.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .clickable {
                        queue.enqueueJob(
                            title = "Engine Demo: Virtual Gimbal 60fps Stack",
                            type = ProcessingJobType.VIDEO_STABILIZATION,
                            priority = JobPriority.HIGH,
                            executionBlock = { updateProgress ->
                                updateProgress(0.25f, "Parsing Gyroflow IMU log")
                                kotlinx.coroutines.delay(200)
                                updateProgress(0.60f, "Running Kalman attitude filter")
                                kotlinx.coroutines.delay(200)
                                updateProgress(0.90f, "Applying quaternion smoothing")
                                kotlinx.coroutines.delay(200)
                                updateProgress(1.0f, "Completed")
                            }
                        )
                    }
                    .padding(vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.PlayCircle,
                        contentDescription = null,
                        tint = AppleYellow,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Run Engine Demo Job",
                        color = AppleYellow,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Job List or Empty State
            if (jobs.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Inbox,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No Jobs in Queue",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Background video stabilization, exposure stacking,\nand media exports will be processed here.",
                            color = CinemaTextLight,
                            fontSize = 11.5.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = 16.dp)
                ) {
                    items(jobs, key = { it.id }) { job ->
                        JobItemCard(job = job, onCancel = { queue.cancelJob(job.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun JobItemCard(
    job: ProcessingJob,
    onCancel: () -> Unit
) {
    val statusColor = when (job.status) {
        JobStatus.RUNNING -> AppleYellow
        JobStatus.PENDING -> Color(0xFF007AFF)
        JobStatus.PAUSED -> Color(0xFFFF9500)
        JobStatus.COMPLETED -> CinemaGreen
        JobStatus.FAILED -> Color(0xFFFF453A)
        JobStatus.CANCELLED -> Color.Gray
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x1AFFFFFF))
            .border(0.8.dp, Color(0x1FFFFFFF), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(statusColor.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = job.type.shortCode,
                            color = statusColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = job.title,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(statusColor.copy(alpha = 0.25f))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = job.status.displayLabel,
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Stage Description
            Text(
                text = job.currentStage,
                color = CinemaTextLight,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Progress Bar if running or active
            if (job.status.isActive) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { job.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp)),
                    color = AppleYellow,
                    trackColor = Color(0x33FFFFFF)
                )
            }

            // Cancel action if running or pending
            if (job.status.isActive) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Cancel",
                        color = Color(0xFFFF453A),
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                        modifier = Modifier.clickable { onCancel() }
                    )
                }
            }
        }
    }
}
