package com.par9uet.jm.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainTabSelectionTest {
    private val home = MainTab.Home.index
    private val collect = MainTab.Collect.index
    private val settings = MainTab.Settings.index

    // ----- shouldIgnoreTabSelection -----

    @Test
    fun `case A - different pending target overrides same settled page`() {
        // settled = Home, pending = Favorites, not scrolling, request = Home.
        assertFalse(
            shouldIgnoreTabSelection(
                settledPage = home,
                pendingTargetPage = collect,
                isScrollInProgress = false,
                requestedPage = home,
            )
        )
    }

    @Test
    fun `case B - same settled page and idle pager ignores the tap`() {
        assertTrue(
            shouldIgnoreTabSelection(
                settledPage = home,
                pendingTargetPage = null,
                isScrollInProgress = false,
                requestedPage = home,
            )
        )
    }

    @Test
    fun `case C - pending target equal to the request still ignores`() {
        assertTrue(
            shouldIgnoreTabSelection(
                settledPage = home,
                pendingTargetPage = home,
                isScrollInProgress = false,
                requestedPage = home,
            )
        )
    }

    @Test
    fun `case D - scroll in progress is never ignored even with other pending target`() {
        assertFalse(
            shouldIgnoreTabSelection(
                settledPage = collect,
                pendingTargetPage = settings,
                isScrollInProgress = true,
                requestedPage = home,
            )
        )
    }

}
