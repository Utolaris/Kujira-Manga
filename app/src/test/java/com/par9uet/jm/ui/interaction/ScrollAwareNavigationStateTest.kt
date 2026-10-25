package com.par9uet.jm.ui.interaction

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScrollAwareNavigationStateTest {
    @Test
    fun verticalDragAndFlingHideUntilHalfASecondAfterLastMotion() = runTest {
        val state = ScrollAwareNavigationState(backgroundScope) { testScheduler.currentTime }
        assertTrue(state.visible)
        assertEquals(Offset.Zero, state.onPreScroll(Offset(0f, -20f), NestedScrollSource.UserInput))
        assertFalse(state.visible)
        advanceTimeBy(400)
        state.onPreScroll(Offset(0f, -10f), NestedScrollSource.SideEffect)
        advanceTimeBy(499)
        runCurrent()
        assertFalse(state.visible)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(state.visible)

        state.onPreScroll(Offset(0f, 20f), NestedScrollSource.UserInput)
        assertFalse(state.visible)
    }

    @Test
    fun horizontalPagingDoesNotHideNavigation() = runTest {
        val state = ScrollAwareNavigationState(backgroundScope) { testScheduler.currentTime }
        assertEquals(Offset.Zero, state.onPreScroll(Offset(20f, 0f), NestedScrollSource.UserInput))
        assertTrue(state.visible)
    }

    @Test
    fun slowInertialFramesReuseOneWaiterAndDoNotConsumeScroll() = runTest {
        val state = ScrollAwareNavigationState(backgroundScope) { testScheduler.currentTime }
        state.onPreScroll(Offset(0f, -1f), NestedScrollSource.UserInput)
        runCurrent()
        val waiter = backgroundScope.coroutineContext[kotlinx.coroutines.Job]!!.children.single()
        repeat(240) {
            advanceTimeBy(8)
            assertEquals(Offset.Zero, state.onPreScroll(Offset(0f, -.25f), NestedScrollSource.SideEffect))
            runCurrent()
            assertFalse(state.visible)
            assertTrue(waiter === backgroundScope.coroutineContext[kotlinx.coroutines.Job]!!.children.single())
        }
        advanceTimeBy(499)
        runCurrent()
        assertFalse(state.visible)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(state.visible)
    }

    @Test
    fun disposingHiddenNavigationCancelsWaiterAndRestoresItsNextVisibleState() = runTest {
        val state = ScrollAwareNavigationState(backgroundScope) { testScheduler.currentTime }
        state.onPreScroll(Offset(0f, -1f), NestedScrollSource.UserInput)
        runCurrent()
        state.dispose()
        runCurrent()
        assertTrue(state.visible)
        assertTrue(backgroundScope.coroutineContext[kotlinx.coroutines.Job]!!.children.none())
        state.onPreScroll(Offset(0f, -1f), NestedScrollSource.UserInput)
        assertFalse(state.visible)
        advanceTimeBy(500)
        runCurrent()
        assertTrue(state.visible)
    }
}
