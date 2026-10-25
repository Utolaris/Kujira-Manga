package com.par9uet.jm.coil

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoverFetchSchedulerTest {
    @Test fun coldScrollDoesNotStartDownloadsButCachedCoversStillLoad() = runTest {
        val scheduler = CoverFetchScheduler()
        val gate = CoverWorkGate(scrolling = true)
        var requests = 0
        val pending = List(100) { async { scheduler.fetch(gate, cached = false) { requests++ } } }
        runCurrent()
        assertEquals(0, requests)
        assertEquals("disk", scheduler.fetch(gate, cached = true) { "disk" })
        gate.setVisibility(true, true, scrolling = false)
        pending.forEach { it.await() }
        assertEquals(100, requests)
    }

    @Test fun coldDownloadsAndCacheWritesShareATwoRequestLimit() = runTest {
        val scheduler = CoverFetchScheduler()
        val release = CompletableDeferred<Unit>()
        var active = 0
        var peak = 0
        val pending = List(40) {
            launch {
                scheduler.fetch(CoverWorkGate(), cached = false) {
                    active++
                    peak = maxOf(peak, active)
                    try { release.await() } finally { active-- }
                }
            }
        }
        runCurrent()
        assertEquals(2, peak)
        release.complete(Unit)
        pending.forEach { it.join() }
        assertEquals(2, peak)
        assertEquals(0, active)
    }

    @Test fun queuedDownloadsRecheckVisibilityAndScrollingWithoutHoldingPermits() = runTest {
        val scheduler = CoverFetchScheduler(maxParallelism = 1)
        val release = CompletableDeferred<Unit>()
        val running = launch { scheduler.fetch(CoverWorkGate(), false) { release.await() } }
        runCurrent()
        val gate = CoverWorkGate()
        val queued = async { scheduler.fetch(gate, false) { "visible" } }
        runCurrent()
        gate.setVisibility(false, true)
        release.complete(Unit)
        running.join()
        runCurrent()
        assertFalse(queued.isCompleted)
        gate.setVisibility(true, true, scrolling = true)
        runCurrent()
        assertFalse(queued.isCompleted)
        assertEquals("cache", scheduler.fetch(gate, true) { "cache" })
        gate.setVisibility(true, true, scrolling = false)
        assertEquals("visible", queued.await())
    }

    @Test fun cancellationAndFailureReleaseTheFetchBudget() = runTest {
        val scheduler = CoverFetchScheduler(maxParallelism = 1)
        val running = launch { scheduler.fetch(CoverWorkGate(), false) { awaitCancellation() } }
        runCurrent()
        running.cancel(); running.join()
        val failure = IllegalStateException("network")
        org.junit.Assert.assertSame(failure,
            runCatching { scheduler.fetch(CoverWorkGate(), false) { throw failure } }.exceptionOrNull())
        assertEquals("next", scheduler.fetch(CoverWorkGate(), false) { "next" })
    }
}
