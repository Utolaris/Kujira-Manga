package com.par9uet.jm.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverPageVisibilityTest {
    @Test fun settledPagerOnlyActivatesItsCurrentPage() {
        assertFalse(isCoverPageVisible(0, 1, 0f))
        assertTrue(isCoverPageVisible(1, 1, 0f))
        assertFalse(isCoverPageVisible(2, 1, 0f))
    }

    @Test fun swipeActivatesBothPagesThatIntersectTheViewport() {
        assertTrue(isCoverPageVisible(0, 0, .25f))
        assertTrue(isCoverPageVisible(1, 0, .25f))
        assertFalse(isCoverPageVisible(2, 0, .25f))
        // The current page switches at the halfway point, with the offset changing sign.
        assertTrue(isCoverPageVisible(0, 1, -.25f))
        assertTrue(isCoverPageVisible(1, 1, -.25f))
        assertFalse(isCoverPageVisible(2, 1, -.25f))
    }

    @Test fun reverseSwipeRestoresTheIncomingPageBeforeItSettles() {
        assertTrue(isCoverPageVisible(1, 2, -.1f))
        assertTrue(isCoverPageVisible(2, 2, -.1f))
        assertFalse(isCoverPageVisible(0, 2, -.1f))
    }
}
