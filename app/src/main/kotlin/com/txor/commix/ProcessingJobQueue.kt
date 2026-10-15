package com.txor.commix

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

enum class ProcessingJobType(val displayName: String, val shortCode: String) {
    VIDEO_STABILIZATION("Video Stabilization", "STAB"),
    MULTI_EXPOSURE_STACK("Multi-Exposure Stacking", "STACK"),
    SLOW_MO_INTERPOLATION("Slow-Motion Interpolation", "SLOMO"),
    SUPER_RES_SYNTHESIS("Super-Resolution Synthesis", "SRES"),
    MEDIA_EXPORT("Media Encoding & Export", "EXPRT"),
    RAW_DEVELOPMENT("Raw DNG Development", "RAW"),
    PANORAMA_STITCHING("Panorama & 360 Stitching", "PANO")
}

enum class JobStatus(val displayLabel: String) {
    PENDING("Queued"),
    RUNNING("Processing"),
    PAUSED("Paused"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    CANCELLED("Cancelled");

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED

    val isActive: Boolean
        get() = this == RUNNING || this == PENDING
}

enum class JobPriority {
    HIGH, NORMAL, LOW
}

data class ProcessingJob(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val type: ProcessingJobType,
    val status: JobStatus = JobStatus.PENDING,
    val priority: JobPriority = JobPriority.NORMAL,
    val progress: Float = 0.0f,
    val currentStage: String = "Queued in processing pool...",
    val etaSeconds: Double = 0.0,
    val sourcePath: String? = null,
    val outputPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val errorMessage: String? = null
)

/**
 * High-performance background processing job queue matching Flutter's ProcessingJobQueue.
 * Manages computational photo stacking, gyro stabilization, and media export asynchronously.
 */
class ProcessingJobQueue private constructor() {

    companion object {
        val instance: ProcessingJobQueue by lazy { ProcessingJobQueue() }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _jobs = MutableStateFlow<List<ProcessingJob>>(emptyList())
    val jobs: StateFlow<List<ProcessingJob>> = _jobs.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val jobExecutionBlocks = java.util.concurrent.ConcurrentHashMap<String, suspend (updateProgress: (Float, String) -> Unit) -> Unit>()
    private val runningCoroutines = java.util.concurrent.ConcurrentHashMap<String, Job>()

    var maxConcurrentJobs: Int = 2
    var autoQueueVideoStabilization: Boolean = true

    val activeJobCount: Int
        get() = _jobs.value.count { it.status == JobStatus.RUNNING }

    val pendingJobCount: Int
        get() = _jobs.value.count { it.status == JobStatus.PENDING }

    val completedJobCount: Int
        get() = _jobs.value.count { it.status == JobStatus.COMPLETED }

    val hasWorkInProgress: Boolean
        get() = _jobs.value.any { it.status.isActive }

    val overallProgress: Float
        get() {
            val active = _jobs.value.filter { it.status.isActive }
            if (active.isEmpty()) return 1.0f
            return active.map { it.progress }.average().toFloat().coerceIn(0.0f, 1.0f)
        }

    fun enqueueJob(
        title: String,
        type: ProcessingJobType,
        sourcePath: String? = null,
        outputPath: String? = null,
        priority: JobPriority = JobPriority.NORMAL,
        executionBlock: (suspend (updateProgress: (Float, String) -> Unit) -> Unit)? = null
    ): ProcessingJob {
        val job = ProcessingJob(
            title = title,
            type = type,
            sourcePath = sourcePath,
            outputPath = outputPath,
            priority = priority
        )

        if (executionBlock != null) {
            jobExecutionBlocks[job.id] = executionBlock
        }

        _jobs.update { listOf(job) + it }
        triggerNextJobs()
        return job
    }

    fun pauseAll() {
        _isPaused.value = true
        _jobs.update { list ->
            list.map { if (it.status == JobStatus.PENDING) it.copy(status = JobStatus.PAUSED) else it }
        }
    }

    fun resumeAll() {
        _isPaused.value = false
        _jobs.update { list ->
            list.map { if (it.status == JobStatus.PAUSED) it.copy(status = JobStatus.PENDING) else it }
        }
        triggerNextJobs()
    }

    fun clearCompleted() {
        _jobs.update { list -> list.filter { !it.status.isTerminal } }
    }

    fun clearAll() {
        runningCoroutines.values.forEach { it.cancel() }
        runningCoroutines.clear()
        jobExecutionBlocks.clear()
        _jobs.value = emptyList()
        maxConcurrentJobs = 2
        _isPaused.value = false
    }

    fun cancelJob(id: String) {
        runningCoroutines.remove(id)?.cancel()
        jobExecutionBlocks.remove(id)
        _jobs.update { list ->
            list.map { if (it.id == id && !it.status.isTerminal) it.copy(status = JobStatus.CANCELLED, currentStage = "Cancelled by user") else it }
        }
        triggerNextJobs()
    }

    private fun triggerNextJobs() {
        if (_isPaused.value) return

        val currentRunning = activeJobCount
        val availableSlots = (maxConcurrentJobs - currentRunning).coerceAtLeast(0)
        if (availableSlots <= 0) return

        val nextJobs = _jobs.value
            .filter { it.status == JobStatus.PENDING }
            .sortedBy { it.priority.ordinal }
            .take(availableSlots)

        for (target in nextJobs) {
            val customBlock = jobExecutionBlocks[target.id]
            startJobExecution(target.id, customBlock)
        }
    }

    private fun startJobExecution(
        jobId: String,
        customBlock: (suspend (updateProgress: (Float, String) -> Unit) -> Unit)?
    ) {
        _jobs.update { list ->
            list.map { if (it.id == jobId) it.copy(status = JobStatus.RUNNING, currentStage = "Starting pipeline worker...") else it }
        }

        val coroutineJob = scope.launch {
            try {
                if (customBlock != null) {
                    customBlock { prog, stage ->
                        _jobs.update { list ->
                            list.map { if (it.id == jobId && it.status == JobStatus.RUNNING) it.copy(progress = prog.coerceIn(0f, 1f), currentStage = stage) else it }
                        }
                    }
                } else {
                    val stages = listOf(
                        "Allocating direct buffers" to 0.25f,
                        "Executing SIMD NEON processing" to 0.6f,
                        "Writing container atom headers" to 0.9f,
                        "Syncing to filesystem" to 1.0f
                    )
                    for ((stageName, targetProgress) in stages) {
                        delay(200)
                        _jobs.update { list ->
                            list.map { if (it.id == jobId && it.status == JobStatus.RUNNING) it.copy(progress = targetProgress, currentStage = stageName) else it }
                        }
                    }
                }

                _jobs.update { list ->
                    list.map { if (it.id == jobId && it.status == JobStatus.RUNNING) it.copy(status = JobStatus.COMPLETED, progress = 1.0f, currentStage = "Finished") else it }
                }
            } catch (e: CancellationException) {
                _jobs.update { list ->
                    list.map { if (it.id == jobId) it.copy(status = JobStatus.CANCELLED, currentStage = "Cancelled") else it }
                }
            } catch (e: Exception) {
                _jobs.update { list ->
                    list.map { if (it.id == jobId) it.copy(status = JobStatus.FAILED, errorMessage = e.message ?: "Processing error", currentStage = "Failed") else it }
                }
            } finally {
                runningCoroutines.remove(jobId)
                jobExecutionBlocks.remove(jobId)
                triggerNextJobs()
            }
        }
        runningCoroutines[jobId] = coroutineJob
    }
}
