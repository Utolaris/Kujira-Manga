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
        val state = ScrollAwareNavigationState(backgroundScope)
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
        val state = ScrollAwareNavigationState(backgroundScope)
        assertEquals(Offset.Zero, state.onPreScroll(Offset(20f, 0f), NestedScrollSource.UserInput))
        assertTrue(state.visible)
    }
}
