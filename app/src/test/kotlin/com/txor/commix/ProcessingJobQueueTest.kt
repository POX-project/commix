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
        queue.clearCompleted()
        queue.resumeAll()
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
}
