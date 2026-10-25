package com.par9uet.jm.coil

import coil.decode.DataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoverWorkGateTest {
    @Test fun diskCacheDecodesWhileScrollingWithoutWaitingForAStop() = runTest {
        val gate = CoverWorkGate(scrolling = true)
        val scheduler = CoverDecodeScheduler(maxParallelism = 1)
        val decoded = async { scheduler.decode(gate, DataSource.DISK) { "cached" } }
        runCurrent()
        assertTrue(decoded.isCompleted)
        assertEquals("cached", decoded.await())
        assertFalse(gate.isDecodeReady()) // Network work still waits on the very same gate.
    }

    @Test fun scrollingAllowsFetchButDefersDecodeWithoutHoldingAPermit() = runTest {
        val gate = CoverWorkGate(scrolling = true)
        gate.awaitReady()
        val scheduler = CoverDecodeScheduler(maxParallelism = 1)
        val pending = async { scheduler.decode(gate) { "scrolled" } }
        runCurrent()
        assertFalse(pending.isCompleted)
        assertEquals("other", scheduler.decode(CoverWorkGate()) { "other" })
        gate.setVisibility(active = true, visible = true, scrolling = false)
        assertEquals("scrolled", pending.await())
    }

    @Test fun scrollStartingWhileQueuedPreventsDecodeAndAlreadyRunningWorkCanFinish() = runTest {
        val scheduler = CoverDecodeScheduler(maxParallelism = 1)
        val firstGate = CoverWorkGate()
        val finish = CompletableDeferred<Unit>()
        val first = async { scheduler.decode(firstGate) { finish.await(); "finished" } }
        runCurrent()
        val queuedGate = CoverWorkGate()
        val queued = async { scheduler.decode(queuedGate) { "queued" } }
        runCurrent()
        firstGate.setVisibility(true, true, scrolling = true)
        queuedGate.setVisibility(true, true, scrolling = true)
        finish.complete(Unit)
        assertEquals("finished", first.await())
        runCurrent()
        assertFalse(queued.isCompleted)
        queuedGate.setVisibility(true, true, scrolling = false)
        assertEquals("queued", queued.await())
    }

    @Test fun requestWaitsForBothThePageAndTheGridItemToBecomeVisible() = runTest {
        val gate = CoverWorkGate(active = false, visible = false)
        val request = async { gate.awaitReady(); "started" }
        runCurrent()
        assertFalse(request.isCompleted)
        gate.setVisibility(active = false, visible = true)
        runCurrent()
        assertFalse(request.isCompleted)
        gate.setVisibility(active = true, visible = true)
        assertEquals("started", request.await())
    }

    @Test fun denseGridsShareOneNativeDecodeRegardlessOfColumnCount() = runTest {
        val scheduler = CoverDecodeScheduler()
        val release = CompletableDeferred<Unit>()
        var started = 0
        val jobs = List(24) { index ->
            val source = if (index % 2 == 0) DataSource.DISK else DataSource.NETWORK
            launch { scheduler.decode(CoverWorkGate(), source) { started++; release.await() } }
        }
        runCurrent()
        assertEquals(1, started)
        release.complete(Unit)
        jobs.forEach { it.join() }
        assertEquals(24, started)
    }

    @Test fun hidingAPageDoesNotCancelAnAlreadyStartedDecode() = runTest {
        val scheduler = CoverDecodeScheduler(maxParallelism = 1)
        val gate = CoverWorkGate()
        val finish = CompletableDeferred<Unit>()
        val decode = async { scheduler.decode(gate) { finish.await(); "cached" } }
        runCurrent()
        gate.setVisibility(active = false, visible = true)
        finish.complete(Unit)
        assertEquals("cached", decode.await())
    }

    @Test fun denseRowsReleaseOneDecodePerFrameInsteadOfUploadingABatch() = runTest {
        val frames = kotlinx.coroutines.channels.Channel<Unit>()
        val scheduler = CoverDecodeScheduler(awaitFrame = { frames.receive() })
        var decoded = 0
        var published = 0
        val jobs = List(24) {
            launch {
                scheduler.decode(CoverWorkGate(), DataSource.DISK) { decoded++ }
                published++
            }
        }
        runCurrent()
        assertEquals(1, decoded)
        assertEquals(0, published)
        repeat(24) { frame ->
            frames.send(Unit)
            runCurrent()
            assertEquals(frame + 1, published)
            assertEquals(minOf(frame + 2, 24), decoded)
        }
        jobs.forEach { it.join() }
        frames.close()
    }

    @Test fun leavingDuringFrameWaitReleasesTheNativeDecodeSlot() = runTest {
        val frame = CompletableDeferred<Unit>()
        val scheduler = CoverDecodeScheduler(awaitFrame = { frame.await() })
        val running = launch { scheduler.decode(CoverWorkGate(), DataSource.DISK) { Unit } }
        runCurrent()
        running.cancel(); running.join()
        frame.complete(Unit)
        assertEquals("visible", scheduler.decode(CoverWorkGate(), DataSource.DISK) { "visible" })
    }

    @Test fun cancellationReleasesTheDecodePermit() = runTest {
        val scheduler = CoverDecodeScheduler(maxParallelism = 1)
        val running = launch { scheduler.decode(CoverWorkGate()) { awaitCancellation() } }
        runCurrent()
        running.cancel()
        running.join()
        assertEquals("next", scheduler.decode(CoverWorkGate()) { "next" })
    }

    @Test fun failedDecodeReleasesThePermit() = runTest {
        val scheduler = CoverDecodeScheduler(maxParallelism = 1)
        val failure = IllegalStateException("bad cover")
        val result = runCatching { scheduler.decode(CoverWorkGate()) { throw failure } }
        org.junit.Assert.assertSame(failure, result.exceptionOrNull())
        assertEquals("next", scheduler.decode(CoverWorkGate()) { "next" })
    }

    @Test fun disposingAnOffscreenRequestCancelsItsWait() = runTest {
        val scheduler = CoverDecodeScheduler(maxParallelism = 1)
        val hidden = async { scheduler.decode(CoverWorkGate(visible = false)) { error("offscreen") } }
        runCurrent()
        hidden.cancel()
        hidden.join()
        assertTrue(hidden.isCancelled)
        assertEquals("next", scheduler.decode(CoverWorkGate()) { "next" })
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class CoverDecodeVisibilityTest(private val source: DataSource) {
    // Cached results may proceed during scrolling; network results use an idle visible page.
    private val scrolling get() = source == DataSource.DISK

    @Test fun hiddenDecodeWaitsOutsidePermitForBothPageAndItem() = runTest {
        val gate = CoverWorkGate(active = false, visible = false, scrolling = scrolling)
        val scheduler = CoverDecodeScheduler(maxParallelism = 1)
        val waiting = async { scheduler.decode(gate, source) { "ready" } }
        runCurrent()
        assertFalse(waiting.isCompleted)
        assertEquals("other", scheduler.decode(CoverWorkGate(), source) { "other" })
        gate.setVisibility(active = true, visible = false, scrolling = scrolling)
        runCurrent()
        assertFalse(waiting.isCompleted)
        gate.setVisibility(active = true, visible = true, scrolling = scrolling)
        assertEquals("ready", waiting.await())
    }

    @Test fun queuedDecodeRechecksVisibilityAfterAcquiringPermit() = runTest {
        val scheduler = CoverDecodeScheduler(maxParallelism = 1)
        val release = CompletableDeferred<Unit>()
        val first = launch { scheduler.decode(CoverWorkGate(), source) { release.await() } }
        runCurrent()
        val gate = CoverWorkGate(scrolling = scrolling)
        val queued = async { scheduler.decode(gate, source) { "queued" } }
        runCurrent()
        gate.setVisibility(active = true, visible = false, scrolling = scrolling)
        release.complete(Unit)
        first.join()
        runCurrent()
        assertFalse(queued.isCompleted)
        assertEquals("other", scheduler.decode(CoverWorkGate(), source) { "other" })
        gate.setVisibility(active = true, visible = true, scrolling = scrolling)
        assertEquals("queued", queued.await())
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun sources() = listOf(arrayOf(DataSource.DISK), arrayOf(DataSource.NETWORK))
    }
}
