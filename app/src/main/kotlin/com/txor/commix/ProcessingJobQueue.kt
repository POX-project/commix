package com.txor.commix

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs

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

/**
 * Cooperative cancellation token passed to worker execution blocks.
 */
interface CancellationToken {
    val isCancelled: Boolean
    fun checkActive()
}

/**
 * Rich execution context for processing pipeline workers, providing cooperative cancellation
 * and throttled progress telemetry.
 */
interface JobExecutionContext : CancellationToken {
    val jobId: String
    fun updateProgress(progress: Float, stage: String, etaSeconds: Double? = null)
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
    val sequenceNumber: Long = 0L,
    val errorMessage: String? = null
)

/**
 * Thread-safe, high-performance background processing job queue matching Flutter's ProcessingJobQueue.
 * Manages computational photo stacking, gyro stabilization, and media export asynchronously with
 * priority-aware scheduling (High, Normal, Low), strict FIFO ordering within tiers, thread pool limits,
 * zero job drops, and cooperative cancellation tokens.
 */
class ProcessingJobQueue internal constructor(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {

    companion object {
        val instance: ProcessingJobQueue by lazy { ProcessingJobQueue() }
    }

    private val lock = ReentrantLock()
    private val sequenceCounter = AtomicLong(0L)

    private val _jobs = MutableStateFlow<List<ProcessingJob>>(emptyList())
    val jobs: StateFlow<List<ProcessingJob>> = _jobs.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val jobExecutionBlocks = ConcurrentHashMap<String, suspend (JobExecutionContext) -> Unit>()
    private val runningCoroutines = ConcurrentHashMap<String, Job>()
    private val cancellationTokens = ConcurrentHashMap<String, CancellationTokenImpl>()
    private val activeJobIds = mutableSetOf<String>()

    @Volatile
    var maxConcurrentJobs: Int = 2
        set(value) {
            val clamped = value.coerceIn(1, 16)
            field = clamped
            triggerNextJobs()
        }

    @Volatile
    var autoQueueVideoStabilization: Boolean = true

    fun shouldAutoQueueStabilization(): Boolean = autoQueueVideoStabilization

    val activeJobCount: Int
        get() = lock.withLock { activeJobIds.size }

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

    /**
     * Enqueues a job with a simple progress callback: (progress: Float, stage: String) -> Unit.
     */
    fun enqueueJob(
        title: String,
        type: ProcessingJobType,
        sourcePath: String? = null,
        outputPath: String? = null,
        priority: JobPriority = JobPriority.NORMAL,
        executionBlock: (suspend (updateProgress: (Float, String) -> Unit) -> Unit)? = null
    ): ProcessingJob {
        val wrappedBlock: (suspend (JobExecutionContext) -> Unit)? = executionBlock?.let { block ->
            { context ->
                block { prog, stage ->
                    context.updateProgress(prog, stage)
                }
            }
        }
        return enqueueJobInternal(
            title = title,
            type = type,
            sourcePath = sourcePath,
            outputPath = outputPath,
            priority = priority,
            block = wrappedBlock
        )
    }

    /**
     * Enqueues a job with a rich JobExecutionContext providing cooperative cancellation and ETA updates.
     */
    fun enqueueJobWithContext(
        title: String,
        type: ProcessingJobType,
        sourcePath: String? = null,
        outputPath: String? = null,
        priority: JobPriority = JobPriority.NORMAL,
        executionBlock: (suspend (context: JobExecutionContext) -> Unit)? = null
    ): ProcessingJob {
        return enqueueJobInternal(
            title = title,
            type = type,
            sourcePath = sourcePath,
            outputPath = outputPath,
            priority = priority,
            block = executionBlock
        )
    }

    private fun enqueueJobInternal(
        title: String,
        type: ProcessingJobType,
        sourcePath: String?,
        outputPath: String?,
        priority: JobPriority,
        block: (suspend (JobExecutionContext) -> Unit)?
    ): ProcessingJob {
        val paused = _isPaused.value
        val initialStatus = if (paused) JobStatus.PAUSED else JobStatus.PENDING
        val initialStage = if (paused) "Paused in queue" else "Queued in processing pool..."

        val job: ProcessingJob
        lock.withLock {
            val seq = sequenceCounter.incrementAndGet()
            job = ProcessingJob(
                title = title,
                type = type,
                status = initialStatus,
                priority = priority,
                currentStage = initialStage,
                sourcePath = sourcePath,
                outputPath = outputPath,
                sequenceNumber = seq
            )
            if (block != null) {
                jobExecutionBlocks[job.id] = block
            }
            // Prepend in the list for UI display (newest at top of recent history)
            _jobs.update { listOf(job) + it }
        }

        triggerNextJobs()
        return job
    }

    fun pauseAll() {
        lock.withLock {
            _isPaused.value = true
            _jobs.update { list ->
                list.map {
                    if (it.status == JobStatus.PENDING) {
                        it.copy(status = JobStatus.PAUSED, currentStage = "Paused in queue")
                    } else it
                }
            }
        }
    }

    fun resumeAll() {
        lock.withLock {
            _isPaused.value = false
            _jobs.update { list ->
                list.map {
                    if (it.status == JobStatus.PAUSED) {
                        it.copy(status = JobStatus.PENDING, currentStage = "Queued in processing pool...")
                    } else it
                }
            }
        }
        triggerNextJobs()
    }

    fun pauseJob(id: String) {
        lock.withLock {
            _jobs.update { list ->
                list.map {
                    if (it.id == id && it.status == JobStatus.PENDING) {
                        it.copy(status = JobStatus.PAUSED, currentStage = "Paused in queue")
                    } else it
                }
            }
        }
    }

    fun resumeJob(id: String) {
        lock.withLock {
            _jobs.update { list ->
                list.map {
                    if (it.id == id && it.status == JobStatus.PAUSED) {
                        it.copy(status = JobStatus.PENDING, currentStage = "Queued in processing pool...")
                    } else it
                }
            }
        }
        triggerNextJobs()
    }

    fun clearCompleted() {
        lock.withLock {
            _jobs.update { list -> list.filter { !it.status.isTerminal } }
        }
    }

    fun clearAll() {
        lock.withLock {
            // Signal cancellation tokens first
            cancellationTokens.values.forEach { it.cancel() }
            cancellationTokens.clear()

            // Cancel coroutines
            runningCoroutines.values.forEach { it.cancel() }
            runningCoroutines.clear()

            jobExecutionBlocks.clear()
            activeJobIds.clear()
            _jobs.value = emptyList()
            maxConcurrentJobs = 2
            _isPaused.value = false
        }
    }

    fun cancelJob(id: String) {
        lock.withLock {
            cancellationTokens.remove(id)?.cancel()
            runningCoroutines.remove(id)?.cancel()
            jobExecutionBlocks.remove(id)
            activeJobIds.remove(id)

            _jobs.update { list ->
                list.map {
                    if (it.id == id && !it.status.isTerminal) {
                        it.copy(status = JobStatus.CANCELLED, currentStage = "Cancelled by user")
                    } else it
                }
            }
        }
        triggerNextJobs()
    }

    private fun triggerNextJobs() {
        val jobsToStart = mutableListOf<Job>()

        lock.withLock {
            if (_isPaused.value) return

            val currentRunning = activeJobIds.size
            val availableSlots = (maxConcurrentJobs - currentRunning).coerceAtLeast(0)
            if (availableSlots <= 0) return

            // Sort pending jobs by:
            // 1. Priority (HIGH = 0, NORMAL = 1, LOW = 2)
            // 2. Sequence number ascending (Strict FIFO within priority tier)
            val candidateJobs = _jobs.value
                .filter { it.status == JobStatus.PENDING && !activeJobIds.contains(it.id) }
                .sortedWith(
                    compareBy<ProcessingJob> { it.priority.ordinal }
                        .thenBy { it.sequenceNumber }
                )
                .take(availableSlots)

            if (candidateJobs.isEmpty()) return

            val candidateIds = candidateJobs.map { it.id }.toSet()

            // StateFlow emission hygiene: atomically mark all candidate jobs as RUNNING in a single emission
            _jobs.update { list ->
                list.map {
                    if (candidateIds.contains(it.id) && it.status == JobStatus.PENDING) {
                        it.copy(status = JobStatus.RUNNING, currentStage = "Starting pipeline worker...")
                    } else it
                }
            }

            for (target in candidateJobs) {
                activeJobIds.add(target.id)
                val customBlock = jobExecutionBlocks[target.id] ?: defaultSimulatedBlock(target.id)
                val coroutineJob = createJobExecution(target.id, customBlock)
                jobsToStart.add(coroutineJob)
            }
        }

        // Launch outside the lock to avoid holding the lock during coroutine dispatch
        for (job in jobsToStart) {
            job.start()
        }
    }

    /**
     * Instantiates token, context, and lazy coroutine within the lock to eliminate race conditions
     * where a job could be cancelled before coroutine setup finishes.
     */
    private fun createJobExecution(
        jobId: String,
        block: suspend (JobExecutionContext) -> Unit
    ): Job {
        val token = CancellationTokenImpl()
        val context = JobExecutionContextImpl(
            jobId = jobId,
            token = token,
            onProgressUpdate = { prog, stage, eta ->
                reportProgress(jobId, prog, stage, eta)
            }
        )

        val coroutineJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                token.checkActive()
                block(context)

                // Ensure job is marked COMPLETED if not already terminal
                lock.withLock {
                    _jobs.update { list ->
                        list.map {
                            if (it.id == jobId && it.status == JobStatus.RUNNING) {
                                it.copy(status = JobStatus.COMPLETED, progress = 1.0f, currentStage = "Finished", etaSeconds = 0.0)
                            } else it
                        }
                    }
                }
            } catch (e: CancellationException) {
                lock.withLock {
                    _jobs.update { list ->
                        list.map {
                            if (it.id == jobId && it.status != JobStatus.CANCELLED) {
                                it.copy(status = JobStatus.CANCELLED, currentStage = "Cancelled by user")
                            } else it
                        }
                    }
                }
            } catch (t: Throwable) {
                // Catch all Throwables (including Errors) to ensure zero orphaned RUNNING slots
                lock.withLock {
                    _jobs.update { list ->
                        list.map {
                            if (it.id == jobId) {
                                it.copy(status = JobStatus.FAILED, errorMessage = t.message ?: "Processing error", currentStage = "Failed")
                            } else it
                        }
                    }
                }
            } finally {
                lock.withLock {
                    runningCoroutines.remove(jobId)
                    jobExecutionBlocks.remove(jobId)
                    cancellationTokens.remove(jobId)
                    activeJobIds.remove(jobId)
                }
                triggerNextJobs()
            }
        }

        cancellationTokens[jobId] = token
        runningCoroutines[jobId] = coroutineJob

        return coroutineJob
    }

    private fun reportProgress(jobId: String, progress: Float, stage: String, etaSeconds: Double?) {
        val clampedProgress = progress.coerceIn(0.0f, 1.0f)
        _jobs.update { list ->
            list.map {
                if (it.id == jobId && it.status == JobStatus.RUNNING) {
                    val targetEta = etaSeconds ?: it.etaSeconds
                    if (it.progress == clampedProgress && it.currentStage == stage && it.etaSeconds == targetEta) {
                        it
                    } else {
                        it.copy(
                            progress = clampedProgress,
                            currentStage = stage,
                            etaSeconds = targetEta
                        )
                    }
                } else it
            }
        }
    }

    private fun defaultSimulatedBlock(jobId: String): suspend (JobExecutionContext) -> Unit = { context ->
        val stages = listOf(
            "Allocating buffers" to 0.25f,
            "Processing frame data" to 0.60f,
            "Finalizing output" to 0.90f,
            "Syncing to filesystem" to 1.00f
        )
        for ((stageName, targetProgress) in stages) {
            context.checkActive()
            delay(200)
            context.updateProgress(targetProgress, stageName)
        }
    }

    private class CancellationTokenImpl : CancellationToken {
        @Volatile
        private var _isCancelled = false

        override val isCancelled: Boolean
            get() = _isCancelled

        fun cancel() {
            _isCancelled = true
        }

        override fun checkActive() {
            if (_isCancelled) {
                throw CancellationException("Job execution was cancelled via CancellationToken")
            }
        }
    }

    private class JobExecutionContextImpl(
        override val jobId: String,
        private val token: CancellationTokenImpl,
        private val onProgressUpdate: (Float, String, Double?) -> Unit
    ) : JobExecutionContext {

        private val startTimeMs = System.currentTimeMillis()
        private var lastEmitTimeMs = 0L
        private var lastProgress = -1.0f
        private var lastStage: String? = null

        override val isCancelled: Boolean
            get() = token.isCancelled

        override fun checkActive() {
            token.checkActive()
        }

        override fun updateProgress(progress: Float, stage: String, etaSeconds: Double?) {
            token.checkActive()
            val clamped = progress.coerceIn(0.0f, 1.0f)
            val now = System.currentTimeMillis()

            // Dynamic ETA estimation if not explicitly provided
            val computedEta = if (etaSeconds != null) {
                etaSeconds
            } else if (clamped in 0.02f..0.98f) {
                val elapsedMs = (now - startTimeMs).coerceAtLeast(1L)
                val totalEstMs = elapsedMs / clamped.toDouble()
                val remainingMs = totalEstMs - elapsedMs
                (remainingMs / 1000.0).coerceAtLeast(0.0)
            } else 0.0

            val stageChanged = stage != lastStage
            val timeElapsed = (now - lastEmitTimeMs) >= 16L
            val progressMoved = abs(clamped - lastProgress) >= 0.005f // At least 0.5% progress delta

            // Telemetry throttling:
            // 1. Always emit terminal 1.0 (completion) immediately
            // 2. Always emit on stage change immediately
            // 3. Otherwise throttle to UI frame budget (>= 16ms) and require measurable progress delta
            val shouldEmit = clamped >= 1.0f ||
                    stageChanged ||
                    (timeElapsed && progressMoved)

            if (shouldEmit) {
                lastEmitTimeMs = now
                lastProgress = clamped
                lastStage = stage
                onProgressUpdate(clamped, stage, computedEta)
            }
        }
    }
}
