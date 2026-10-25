package com.par9uet.jm.core.concurrent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundWorkTest {
    @Test fun maintenanceWaitsForAllVisibleWorkAndAQuietWindow() = runTest {
        val work = ForegroundWork()
        work.update("home", true)
        work.update("favorites", true)
        val waiting = async { work.awaitIdle() }
        runCurrent()
        work.update("home", false)
        advanceTimeBy(10_000); runCurrent()
        assertFalse(waiting.isCompleted)
        work.update("favorites", false)
        advanceTimeBy(749); runCurrent()
        assertFalse(waiting.isCompleted)
        advanceTimeBy(1); runCurrent()
        assertTrue(waiting.isCompleted)
    }

    @Test fun newVisibleRowsRestartTheQuietWindowAndDuplicateUpdatesDoNotLeakWork() = runTest {
        val work = ForegroundWork()
        val waiting = async { work.awaitIdle() }
        runCurrent()
        advanceTimeBy(500)
        work.update("cover", true)
        work.update("cover", true)
        runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertFalse(waiting.isCompleted)
        work.update("cover", false)
        advanceTimeBy(750); runCurrent()
        assertTrue(waiting.isCompleted)
    }
}
