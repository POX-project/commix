package com.txor.commix

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

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

        queue.resumeAll()
        assertFalse(queue.isPaused.value)
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
        var finalStage = ""
        var finalProg = 0f

        val job = queue.enqueueJob(
            title = "Custom Worker Job",
            type = ProcessingJobType.MULTI_EXPOSURE_STACK,
            priority = JobPriority.HIGH
        ) { updateProgress ->
            updateProgress(0.5f, "Midway Stage")
            delay(50)
            updateProgress(1.0f, "Completed Stage")
        }

        // Wait for coroutine execution
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
        delay(20)
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
        val executionLog = java.util.concurrent.CopyOnWriteArrayList<String>()

        val jobA = queue.enqueueJob(
            title = "Job Alpha",
            type = ProcessingJobType.VIDEO_STABILIZATION
        ) { _ ->
            executionLog.add("AlphaStarted")
            delay(50)
            executionLog.add("AlphaFinished")
        }

        val jobB = queue.enqueueJob(
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
}
