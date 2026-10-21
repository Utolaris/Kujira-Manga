package com.par9uet.jm.ui.interaction

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetailMotionExitTest {
    @Test fun `resizing a return overlay cannot let its cancelled job clear a newer return`() = runTest {
        val returns = com.par9uet.jm.ui.navigation.CoverReturns(frameScope(),
            com.par9uet.jm.ui.navigation.CoverReturnAnchors(), { "list" }, { androidx.compose.ui.geometry.Offset.Zero })
        val frame = com.par9uet.jm.ui.models.CoverReturnFrame(null, null, Rect(0f, 0f, 400f, 800f),
            null, 1f, 0f, androidx.compose.ui.unit.IntSize(400, 800), 1, "list")
        returns.close(frame) { }
        runCurrent()
        returns.finishOnResize()
        val next = frame.copy()
        returns.close(next) { }
        runCurrent()
        assertSame(next, returns.frame)
        advanceUntilIdle()
        assertNull(returns.frame)
    }

    @Test fun `resize retires an in-flight return exactly once even when its old callback arrives`() = runTest {
        var pending: (() -> Unit)? = null
        var popped = 0
        val motion = DetailMotion(frameScope(), 800f, 1f, true, { Rect(0f, 0f, 400f, 800f) },
            prepare = { ReturnLayers(null, null) }, close = { _, _, after -> pending = after })
        motion.dismiss { popped++ }
        advanceUntilIdle()
        motion.dispose()
        assertEquals(1, popped)
        pending!!.invoke()
        motion.dispose()
        assertEquals(1, popped)
    }

    @Test fun `resize while the content is exiting cancels old captures and completes navigation`() = runTest {
        var captures = 0
        var popped = 0
        val motion = DetailMotion(frameScope(), 800f, 1f, true, { Rect(0f, 0f, 400f, 800f) },
            prepare = { captures++; ReturnLayers(null, null) }, close = { _, _, after -> after() })
        motion.dismiss { popped++ }
        runCurrent(); advanceTimeBy(48); runCurrent()
        motion.dispose()
        advanceUntilIdle()
        assertEquals(0, captures)
        assertEquals(1, popped)
    }

    private fun TestScope.frameScope() = CoroutineScope(coroutineContext + object : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            delay(16)
            return onFrame(testScheduler.currentTime * 1_000_000)
        }
    })

    @Test fun `exit captures once only after the complete content stage and preserves full cover travel`() = runTest {
        var captures = 0
        var commits = 0
        val pixels = ReturnLayers(null, null)
        val motion = DetailMotion(frameScope(), 800f, 1f, true, { Rect(0f, 0f, 400f, 800f) },
            prepare = {
                assertEquals(0f, it.contentProgress)
                assertEquals(1f, it.coverProgress)
                captures++
                pixels
            }, close = { _, prepared, after ->
                assertSame(pixels, prepared)
                commits++
                after()
            })
        var popped = false
        motion.dismiss { popped = true }
        runCurrent()
        advanceTimeBy(DetailContentExitMillis - 40L); runCurrent()
        assertEquals(0, captures)
        assertFalse(popped)
        assertEquals(1f, motion.coverProgress)
        advanceUntilIdle()
        assertEquals(1, captures)
        assertEquals(1, commits)
        assertTrue(popped)
    }

    @Test fun `secondary content exits at constant speed before snapshot preparation`() = runTest {
        val motion = DetailMotion(frameScope(), 800f, 1f, true, { Rect(0f, 0f, 400f, 800f) },
            prepare = { ReturnLayers(null, null) }, close = { _, _, after -> after() })
        var popped = false
        motion.dismiss { popped = true }
        runCurrent()
        advanceTimeBy(48); runCurrent()
        val first = motion.contentProgress
        advanceTimeBy(48); runCurrent()
        val second = motion.contentProgress
        advanceTimeBy(48); runCurrent()
        val third = motion.contentProgress
        assertEquals(first - second, second - third, .0001f)
        assertTrue(first > second && second > third)
        assertFalse(popped)
        advanceUntilIdle()
        assertTrue(popped)
    }

    @Test fun `committed return ignores new drags and repeated dismiss while awaiting pixels`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var captures = 0
        var popped = 0
        val motion = DetailMotion(frameScope(), 800f, 1f, true, { Rect(0f, 0f, 400f, 800f) }, prepare = {
            captures++
            gate.await()
            ReturnLayers(null, null)
        }, close = { _, _, after -> after() })
        motion.dismiss { popped++ }
        advanceTimeBy(320); runCurrent()
        val cover = motion.coverProgress
        val content = motion.contentProgress
        val pose = motion.pose
        assertFalse(motion.beginDrag())
        motion.cancelDrag()
        motion.dismiss { fail("Repeated dismiss must not replace the committed callback") }
        runCurrent()
        assertEquals(cover, motion.coverProgress)
        assertEquals(content, motion.contentProgress)
        assertEquals(pose, motion.pose)
        assertEquals(1, captures)
        assertEquals(0, popped)
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, popped)
    }

    @Test fun `opening a modal cancels existing movement and blocks all new detail drags`() = runTest {
        val motion = DetailMotion(frameScope(), 800f, 1f, true, { Rect(0f, 0f, 400f, 800f) },
            prepare = { ReturnLayers(null, null) }, close = { _, _, after -> after() })
        assertTrue(motion.beginDrag())
        motion.drag(androidx.compose.ui.geometry.Offset(60f, 100f))
        assertNotEquals(CoverPose(), motion.pose)
        motion.dragBlocked = true
        assertFalse(motion.dragging)
        assertFalse(motion.beginDrag())
        advanceUntilIdle()
        assertEquals(CoverPose(), motion.pose)
        motion.dragBlocked = false
        assertTrue(motion.beginDrag())
        motion.cancelDrag(); advanceUntilIdle()
    }

}
