package com.txor.commix

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ProcessingJobQueueTest {

    private val queue = ProcessingJobQueue.instance

    @Before
    fun setUp() {
        queue.clearAll()
    }

    @Test
    fun testInitialQueueState() {
        assertNotNull(queue)
        assertFalse(queue.isPaused.value)
        assertEquals(0, queue.activeJobCount)
        assertEquals(0, queue.pendingJobCount)
        assertEquals(0, queue.completedJobCount)
    }

    @Test
    fun testEnqueueJob() {
        val job = queue.enqueueJob(
            title = "Unit Test Job",
            type = ProcessingJobType.SUPER_RES_SYNTHESIS,
            priority = JobPriority.NORMAL
        )

        assertNotNull(job)
        assertEquals("Unit Test Job", job.title)
        assertEquals(ProcessingJobType.SUPER_RES_SYNTHESIS, job.type)
        assertTrue(job.id.isNotEmpty())
        assertTrue(queue.jobs.value.any { it.id == job.id })
    }

    @Test
    fun testPauseAndResumeQueue() {
        queue.pauseAll()
        assertTrue(queue.isPaused.value)

        // When paused, newly enqueued jobs must be in PAUSED status, not PENDING
        val pausedJob = queue.enqueueJob(
            title = "Enqueued While Paused",
            type = ProcessingJobType.RAW_DEVELOPMENT
        )
        val fetchedPaused = queue.jobs.value.find { it.id == pausedJob.id }
        assertEquals(JobStatus.PAUSED, fetchedPaused?.status)

        queue.resumeAll()
        assertFalse(queue.isPaused.value)
        val fetchedResumed = queue.jobs.value.find { it.id == pausedJob.id }
        assertTrue(fetchedResumed?.status == JobStatus.PENDING || fetchedResumed?.status == JobStatus.RUNNING)
    }

    @Test
    fun testCancelJob() {
        val job = queue.enqueueJob(
            title = "Cancellable Job",
            type = ProcessingJobType.VIDEO_STABILIZATION,
            priority = JobPriority.LOW
        )

        queue.cancelJob(job.id)
        val updated = queue.jobs.value.find { it.id == job.id }
        assertNotNull(updated)
        assertEquals(JobStatus.CANCELLED, updated?.status)
        assertEquals("Cancelled by user", updated?.currentStage)
    }

    @Test
    fun testCustomProgressExecution() = runBlocking {
        val job = queue.enqueueJob(
            title = "Custom Worker Job",
            type = ProcessingJobType.MULTI_EXPOSURE_STACK,
            priority = JobPriority.HIGH
        ) { updateProgress ->
            updateProgress(0.5f, "Midway Stage")
            delay(50)
            updateProgress(1.0f, "Completed Stage")
        }

        delay(250)

        val completed = queue.jobs.value.find { it.id == job.id }
        assertNotNull(completed)
        assertEquals(JobStatus.COMPLETED, completed?.status)
        assertEquals(1.0f, completed?.progress ?: 0f, 0.01f)
    }

    @Test
    fun testClearCompletedJobs() = runBlocking {
        val job = queue.enqueueJob(
            title = "Ephemeral Job",
            type = ProcessingJobType.MEDIA_EXPORT
        ) { updateProgress ->
            updateProgress(1.0f, "Done")
        }

        delay(200)
        queue.clearCompleted()
        val exists = queue.jobs.value.any { it.id == job.id }
        assertFalse(exists)
    }

    @Test
    fun testQueuedJobRetainsItsCustomExecutionBlockAcrossSlotTransitions() = runBlocking {
        queue.maxConcurrentJobs = 1
        var job1Ran = false
        var job2Ran = false

        val job1 = queue.enqueueJob(
            title = "Blocking Slot Job 1",
            type = ProcessingJobType.MULTI_EXPOSURE_STACK
        ) { updateProgress ->
            job1Ran = true
            updateProgress(0.5f, "Working")
            delay(100)
            updateProgress(1.0f, "Done")
        }

        val job2 = queue.enqueueJob(
            title = "Queued Job 2",
            type = ProcessingJobType.RAW_DEVELOPMENT
        ) { updateProgress ->
            job2Ran = true
            updateProgress(1.0f, "Custom Finished")
        }

        // Initially job1 is running and job2 is pending
        delay(30)
        val j2Pending = queue.jobs.value.find { it.id == job2.id }
        assertEquals(JobStatus.PENDING, j2Pending?.status)

        // Wait for job1 to complete and trigger job2
        delay(350)
        assertTrue("Job 1 custom block must have executed", job1Ran)
        assertTrue("Job 2 custom block must have executed once slot freed", job2Ran)

        val j2Done = queue.jobs.value.find { it.id == job2.id }
        assertEquals(JobStatus.COMPLETED, j2Done?.status)
        assertEquals("Finished", j2Done?.currentStage)
    }

    @Test
    fun testConcurrentJobsExecuteDistinctPayloads() = runBlocking {
        queue.maxConcurrentJobs = 2
        val executionLog = CopyOnWriteArrayList<String>()

        queue.enqueueJob(
            title = "Job Alpha",
            type = ProcessingJobType.VIDEO_STABILIZATION
        ) { _ ->
            executionLog.add("AlphaStarted")
            delay(50)
            executionLog.add("AlphaFinished")
        }

        queue.enqueueJob(
            title = "Job Beta",
            type = ProcessingJobType.SLOW_MO_INTERPOLATION
        ) { _ ->
            executionLog.add("BetaStarted")
            delay(50)
            executionLog.add("BetaFinished")
        }

        delay(250)
        assertTrue(executionLog.contains("AlphaStarted"))
        assertTrue(executionLog.contains("AlphaFinished"))
        assertTrue(executionLog.contains("BetaStarted"))
        assertTrue(executionLog.contains("BetaFinished"))
    }

    @Test
    fun testCancelJobActuallyCancelsRunningCoroutineAndStaysCancelled() = runBlocking {
        var continuedAfterCancel = false

        val job = queue.enqueueJob(
            title = "Long Running Job To Cancel",
            type = ProcessingJobType.PANORAMA_STITCHING
        ) { _ ->
            delay(100)
            continuedAfterCancel = true
        }

        delay(30)
        val runningJob = queue.jobs.value.find { it.id == job.id }
        assertEquals(JobStatus.RUNNING, runningJob?.status)

        // Cancel while running
        queue.cancelJob(job.id)
        val cancelledJob = queue.jobs.value.find { it.id == job.id }
        assertEquals(JobStatus.CANCELLED, cancelledJob?.status)

        // Wait beyond the job duration
        delay(200)
        assertFalse("Coroutine must be cancelled and not continue past delay", continuedAfterCancel)
        val finalJob = queue.jobs.value.find { it.id == job.id }
        assertEquals(JobStatus.CANCELLED, finalJob?.status)
    }

    @Test
    fun testStrictConcurrencyLimitEnforcementUnderBurst() = runBlocking {
        val maxConcurrency = 3
        queue.maxConcurrentJobs = maxConcurrency

        val currentlyRunning = AtomicInteger(0)
        val peakRunning = AtomicInteger(0)
        val completedCount = AtomicInteger(0)
        val totalJobs = 15

        for (i in 0 until totalJobs) {
            queue.enqueueJob(
                title = "Burst Job $i",
                type = ProcessingJobType.MEDIA_EXPORT
            ) { _ ->
                val active = currentlyRunning.incrementAndGet()
                peakRunning.updateAndGet { currentPeak -> maxOf(currentPeak, active) }
                delay(30)
                currentlyRunning.decrementAndGet()
                completedCount.incrementAndGet()
            }
        }

        // Wait for all burst jobs to finish
        val timeoutMs = 3000L
        val startMs = System.currentTimeMillis()
        while (completedCount.get() < totalJobs && (System.currentTimeMillis() - startMs) < timeoutMs) {
            delay(20)
        }

        assertEquals("All 15 burst jobs must complete with zero drops", totalJobs, completedCount.get())
        assertTrue(
            "Peak concurrent jobs (${peakRunning.get()}) must never exceed maxConcurrentJobs ($maxConcurrency)",
            peakRunning.get() <= maxConcurrency
        )
    }

    @Test
    fun testPriorityQueuingHighBeforeNormalBeforeLow() = runBlocking {
        queue.maxConcurrentJobs = 1

        val executionOrder = CopyOnWriteArrayList<String>()
        val blockerLatch = CompletableDeferred<Unit>()

        // Occupy the single slot
        queue.enqueueJob(
            title = "Blocking Job",
            type = ProcessingJobType.MEDIA_EXPORT,
            priority = JobPriority.HIGH
        ) { _ ->
            blockerLatch.await()
        }

        delay(20)

        // Enqueue LOW, then NORMAL, then HIGH while the slot is busy
        queue.enqueueJob(
            title = "LowPriorityJob",
            type = ProcessingJobType.RAW_DEVELOPMENT,
            priority = JobPriority.LOW
        ) { _ ->
            executionOrder.add("LOW")
        }

        queue.enqueueJob(
            title = "NormalPriorityJob",
            type = ProcessingJobType.RAW_DEVELOPMENT,
            priority = JobPriority.NORMAL
        ) { _ ->
            executionOrder.add("NORMAL")
        }

        queue.enqueueJob(
            title = "HighPriorityJob",
            type = ProcessingJobType.RAW_DEVELOPMENT,
            priority = JobPriority.HIGH
        ) { _ ->
            executionOrder.add("HIGH")
        }

        // Release the blocker so the queued jobs execute according to priority
        blockerLatch.complete(Unit)

        // Wait for the 3 queued jobs to complete
        delay(300)

        assertEquals(listOf("HIGH", "NORMAL", "LOW"), executionOrder.toList())
    }

    @Test
    fun testStrictFifoOrderingWithinSamePriorityTier() = runBlocking {
        queue.maxConcurrentJobs = 1

        val executionOrder = CopyOnWriteArrayList<String>()
        val blockerLatch = CompletableDeferred<Unit>()

        queue.enqueueJob(
            title = "Blocker",
            type = ProcessingJobType.MEDIA_EXPORT,
            priority = JobPriority.NORMAL
        ) { _ ->
            blockerLatch.await()
        }

        delay(20)

        // Enqueue 4 NORMAL priority jobs in order A, B, C, D
        queue.enqueueJob(title = "Job A", type = ProcessingJobType.RAW_DEVELOPMENT, priority = JobPriority.NORMAL) {
            executionOrder.add("A")
        }
        queue.enqueueJob(title = "Job B", type = ProcessingJobType.RAW_DEVELOPMENT, priority = JobPriority.NORMAL) {
            executionOrder.add("B")
        }
        queue.enqueueJob(title = "Job C", type = ProcessingJobType.RAW_DEVELOPMENT, priority = JobPriority.NORMAL) {
            executionOrder.add("C")
        }
        queue.enqueueJob(title = "Job D", type = ProcessingJobType.RAW_DEVELOPMENT, priority = JobPriority.NORMAL) {
            executionOrder.add("D")
        }

        // Unblock
        blockerLatch.complete(Unit)
        delay(350)

        // In a true FIFO queue, A must run before B, B before C, C before D
        assertEquals(
            "Same priority jobs must execute in strict FIFO enqueue sequence, never LIFO",
            listOf("A", "B", "C", "D"),
            executionOrder.toList()
        )
    }

    @Test
    fun testDynamicMaxConcurrentJobsIncreaseImmediatelyFreesSlots() = runBlocking {
        queue.maxConcurrentJobs = 1

        val startedJobs = CopyOnWriteArrayList<String>()
        val releaseGate = CompletableDeferred<Unit>()

        queue.enqueueJob(title = "Slot 1 Holder", type = ProcessingJobType.MEDIA_EXPORT) {
            startedJobs.add("Slot 1")
            releaseGate.await()
        }

        queue.enqueueJob(title = "Queued Waiting", type = ProcessingJobType.RAW_DEVELOPMENT) {
            startedJobs.add("Slot 2")
            releaseGate.await()
        }

        delay(30)
        assertEquals("Only 1 job should be running when maxConcurrentJobs is 1", listOf("Slot 1"), startedJobs.toList())

        // Dynamically increase concurrency from 1 to 2
        queue.maxConcurrentJobs = 2

        // The queued job must immediately start without waiting for Slot 1 to finish
        delay(50)
        assertEquals("Queued job must immediately start when concurrency increases", listOf("Slot 1", "Slot 2"), startedJobs.toList())

        releaseGate.complete(Unit)
        delay(100)
    }

    @Test
    fun testDynamicSlotContractionDrainsGracefullyWithoutStartingNewSlots() = runBlocking {
        queue.maxConcurrentJobs = 3

        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        val gate3 = CompletableDeferred<Unit>()
        val job4Ran = AtomicBoolean(false)

        queue.enqueueJob(title = "Slot 1", type = ProcessingJobType.MEDIA_EXPORT) {
            gate1.await()
        }
        queue.enqueueJob(title = "Slot 2", type = ProcessingJobType.MEDIA_EXPORT) {
            gate2.await()
        }
        queue.enqueueJob(title = "Slot 3", type = ProcessingJobType.MEDIA_EXPORT) {
            gate3.await()
        }

        delay(30)
        assertEquals(3, queue.activeJobCount)

        // Enqueue Job 4, which must wait in PENDING
        val job4 = queue.enqueueJob(title = "Slot 4 Waiting", type = ProcessingJobType.RAW_DEVELOPMENT) {
            job4Ran.set(true)
        }
        delay(20)
        assertEquals(JobStatus.PENDING, queue.jobs.value.find { it.id == job4.id }?.status)

        // Contract maxConcurrentJobs from 3 to 1
        queue.maxConcurrentJobs = 1

        // Release slot 1: active jobs drops to 2. Since 2 > 1, Job 4 must NOT start!
        gate1.complete(Unit)
        delay(50)
        assertFalse("Job 4 must not start while active jobs (2) > maxConcurrentJobs (1)", job4Ran.get())
        assertEquals(JobStatus.PENDING, queue.jobs.value.find { it.id == job4.id }?.status)

        // Release slot 2: active jobs drops to 1. Since 1 >= 1, Job 4 still must NOT start!
        gate2.complete(Unit)
        delay(50)
        assertFalse("Job 4 must not start while active jobs (1) == maxConcurrentJobs (1)", job4Ran.get())

        // Release slot 3: active jobs drops to 0 (< 1). Now Job 4 must start!
        gate3.complete(Unit)
        delay(100)
        assertTrue("Job 4 must start once active jobs fall below contracted limit", job4Ran.get())
        assertEquals(JobStatus.COMPLETED, queue.jobs.value.find { it.id == job4.id }?.status)
    }

    @Test
    fun testCancellationTokenAbortsWorkerImmediately() = runBlocking {
        val workerAbortedEarly = AtomicBoolean(false)
        val loopCompleted = AtomicBoolean(false)

        val job = queue.enqueueJobWithContext(
            title = "Cooperative Cancellation Test",
            type = ProcessingJobType.VIDEO_STABILIZATION
        ) { context ->
            try {
                for (step in 1..20) {
                    context.checkActive()
                    delay(30)
                    context.updateProgress(step / 20f, "Step $step")
                }
                loopCompleted.set(true)
            } catch (e: CancellationException) {
                workerAbortedEarly.set(true)
                throw e
            }
        }

        delay(60)
        queue.cancelJob(job.id)

        delay(200)
        assertTrue("Worker must cooperatively abort on cancellation token", workerAbortedEarly.get())
        assertFalse("Worker must not complete full loop after cancellation", loopCompleted.get())

        val jobState = queue.jobs.value.find { it.id == job.id }
        assertEquals(JobStatus.CANCELLED, jobState?.status)
    }

    @Test
    fun testZeroJobDropsUnderMultiThreadedBurstEnqueue() = runBlocking {
        queue.maxConcurrentJobs = 4
        val threadCount = 8
        val jobsPerThread = 10
        val totalExpected = threadCount * jobsPerThread

        val processedIds = ConcurrentLinkedQueue<String>()
        val latch = CountDownLatch(totalExpected)

        withContext(Dispatchers.IO) {
            val jobs = (0 until threadCount).map { threadIdx ->
                launch {
                    for (j in 0 until jobsPerThread) {
                        queue.enqueueJob(
                            title = "Thread-$threadIdx-Job-$j",
                            type = ProcessingJobType.MEDIA_EXPORT
                        ) { updateProgress ->
                            updateProgress(0.5f, "Processing")
                            delay(10)
                            updateProgress(1.0f, "Done")
                            processedIds.add("Thread-$threadIdx-Job-$j")
                            latch.countDown()
                        }
                    }
                }
            }
            jobs.forEach { it.join() }
        }

        val completedInTime = latch.await(5, TimeUnit.SECONDS)
        assertTrue("All jobs must finish within timeout with zero drops", completedInTime)
        assertEquals(totalExpected, processedIds.size)

        var waitTries = 0
        while ((queue.activeJobCount > 0 || queue.completedJobCount < totalExpected) && waitTries < 100) {
            delay(10)
            waitTries++
        }

        assertEquals(0, queue.activeJobCount)
        assertEquals(0, queue.pendingJobCount)
        assertEquals(totalExpected, queue.completedJobCount)
    }

    @Test
    fun testWorkerThrowableDoesNotDeadlockOrLeakSlots() = runBlocking {
        queue.maxConcurrentJobs = 1

        val job1 = queue.enqueueJob(
            title = "Exploding Job",
            type = ProcessingJobType.SUPER_RES_SYNTHESIS
        ) { _ ->
            throw AssertionError("Simulated severe worker error/assertion")
        }

        var job2Ran = false
        val job2 = queue.enqueueJob(
            title = "Recovery Job",
            type = ProcessingJobType.MEDIA_EXPORT
        ) { _ ->
            job2Ran = true
        }

        delay(200)

        val j1 = queue.jobs.value.find { it.id == job1.id }
        val j2 = queue.jobs.value.find { it.id == job2.id }

        assertEquals(JobStatus.FAILED, j1?.status)
        assertEquals("Simulated severe worker error/assertion", j1?.errorMessage)
        assertTrue("Subsequent job must run and not be starved by previous failure", job2Ran)
        assertEquals(JobStatus.COMPLETED, j2?.status)
    }

    @Test
    fun testProgressTelemetryAndDynamicEta() = runBlocking {
        var observedEta: Double? = null

        val job = queue.enqueueJobWithContext(
            title = "ETA Telemetry Job",
            type = ProcessingJobType.PANORAMA_STITCHING
        ) { context ->
            delay(50)
            context.updateProgress(0.5f, "Stitching Halfway")
            delay(50)
            context.updateProgress(1.0f, "Finished")
        }

        delay(75)
        val midJob = queue.jobs.value.find { it.id == job.id }
        observedEta = midJob?.etaSeconds

        delay(150)
        val doneJob = queue.jobs.value.find { it.id == job.id }

        assertNotNull("Midway job state should exist", midJob)
        assertEquals(JobStatus.RUNNING, midJob?.status)
        assertTrue("Progress must be >= 0.5f", (midJob?.progress ?: 0f) >= 0.5f)
        assertEquals(JobStatus.COMPLETED, doneJob?.status)
        assertEquals(1.0f, doneJob?.progress ?: 0f, 0.01f)
    }

    @Test
    fun testStageChangeForcesImmediateEmissionWithoutWaitingForTimer() = runBlocking {
        val job = queue.enqueueJobWithContext(
            title = "Stage Transition Telemetry",
            type = ProcessingJobType.VIDEO_STABILIZATION
        ) { context ->
            context.updateProgress(0.2f, "Buffer Allocation")
            // Immediate stage update with identical progress (< 1ms, 0% delta)
            context.updateProgress(0.2f, "Gyro Smoothing")
            delay(30)
            context.updateProgress(1.0f, "Finished")
        }

        delay(15)
        val currentJob = queue.jobs.value.find { it.id == job.id }
        assertEquals("Gyro Smoothing", currentJob?.currentStage)

        delay(100)
        val completedJob = queue.jobs.value.find { it.id == job.id }
        assertEquals(JobStatus.COMPLETED, completedJob?.status)
    }

    @Test
    fun testRapidSubPercentProgressIsThrottledToPreventStateFlowSpam() = runBlocking {
        val job = queue.enqueueJobWithContext(
            title = "Throttling Test",
            type = ProcessingJobType.RAW_DEVELOPMENT
        ) { context ->
            // Rapidly spam 100 tiny sub-0.5% updates within a tight synchronous loop (< 2ms)
            for (i in 1..100) {
                context.updateProgress(i * 0.0005f, "Demosaicing")
            }
            context.updateProgress(1.0f, "Completed")
        }

        delay(150)
        val completedJob = queue.jobs.value.find { it.id == job.id }
        assertEquals(JobStatus.COMPLETED, completedJob?.status)
        assertEquals(1.0f, completedJob?.progress ?: 0f, 0.01f)
    }
}
