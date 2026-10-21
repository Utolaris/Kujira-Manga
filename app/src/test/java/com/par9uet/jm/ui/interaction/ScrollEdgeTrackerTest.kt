package com.par9uet.jm.ui.interaction

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ScrollEdgeTrackerTest {
    @Test fun `visible edge pull ticks once until the stretch releases`() {
        val tracker = ScrollEdgeTracker()
        assertTrue(tracker.collision(-10f, true, true))
        assertFalse(tracker.collision(-20f, true, true))
        tracker.released()
        assertTrue(tracker.collision(-20f, true, true))
        assertTrue(tracker.collision(10f, true, true))
        assertFalse(tracker.collision(20f, true, true))
    }

    @Test fun `invisible programmatic and subpixel movement are silent`() {
        val tracker = ScrollEdgeTracker()
        assertFalse(tracker.collision(-40f, false, true))
        assertFalse(tracker.collision(-40f, true, false))
        assertFalse(tracker.collision(.2f, true, true))
        assertFalse(tracker.collision(0f, true, true))
        assertTrue(tracker.collision(20f, true, true))
    }

    private class Effect : OverscrollEffect {
        override val node = object : Modifier.Node() {}
        var active = false
        override val isInProgress get() = active
        override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset): Offset {
            val consumed = performScroll(delta)
            active = delta.y != consumed.y
            return delta // Native overscroll consumes the remainder before a parent listener sees it.
        }
        override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
            active = velocity.y != performFling(velocity).y
        }
    }

    @Test fun `wrapper observes native consumed edge and preserves node and scroll result`() {
        var ticks = 0
        val native = Effect()
        val wrapped = HapticOverscrollEffect(native) { ticks++ }
        assertSame(native.node, wrapped.node)
        val delta = Offset(0f, -80f)
        var calls = 0
        assertEquals(delta, wrapped.applyToScroll(delta, NestedScrollSource.UserInput) { calls++; Offset(0f, -60f) })
        wrapped.applyToScroll(delta, NestedScrollSource.UserInput) { Offset.Zero }
        assertEquals(1, calls)
        assertEquals(1, ticks)
        native.active = false
        wrapped.applyToScroll(delta, NestedScrollSource.UserInput) { Offset.Zero }
        assertEquals(2, ticks)
        wrapped.applyToScroll(delta, NestedScrollSource.SideEffect) { Offset.Zero }
        wrapped.applyToScroll(Offset(40f, 0f), NestedScrollSource.UserInput) { Offset.Zero }
        assertEquals(2, ticks)
    }

    @Test fun `fling collision ticks after a user drag but programmatic fling stays silent`() = runTest {
        var ticks = 0
        val wrapped = HapticOverscrollEffect(Effect()) { ticks++ }
        val velocity = Velocity(0f, -2000f)
        var supplied = Velocity.Zero
        wrapped.applyToFling(velocity) { supplied = it; Velocity.Zero }
        assertEquals(velocity, supplied)
        assertEquals(0, ticks)
        wrapped.applyToScroll(Offset(0f, -40f), NestedScrollSource.UserInput) { it }
        wrapped.applyToFling(velocity) { Velocity(0f, -1800f) }
        assertEquals(1, ticks)
        wrapped.applyToFling(velocity) { Velocity.Zero }
        assertEquals(1, ticks)
    }
}
