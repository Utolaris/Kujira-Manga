package com.par9uet.jm.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import coil.decode.DataSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverLoadingScrollTest {
    @Test
    fun `network completion is deferred only while scrolling`() {
        assertTrue(shouldDeferCoverResult(true, DataSource.NETWORK))
        assertFalse(shouldDeferCoverResult(false, DataSource.NETWORK))
    }

    @Test
    fun `memory cache completion is immediately drawable during scrolling`() {
        assertFalse(shouldDeferCoverResult(true, DataSource.MEMORY_CACHE))
        assertFalse(shouldDeferCoverResult(true, DataSource.MEMORY))
    }

    @Test
    fun `disk cache completion is immediately drawable without fading during scrolling`() {
        assertFalse(shouldDeferCoverResult(true, DataSource.DISK))
        assertFalse(shouldAnimateCoverResult(true, DataSource.DISK))
        assertTrue(shouldAnimateCoverResult(false, DataSource.DISK))
        assertFalse(shouldAnimateCoverResult(false, DataSource.MEMORY_CACHE))
    }

    @Test
    fun `disk result completed while hidden can be published on a scrolling page`() {
        val gate = CoverImageDisplayGate()
        val painter = ColorPainter(Color.Red)
        gate.deferSuccess(painter, DataSource.DISK)
        val result = gate.takeDeferredCover(isScrolling = true)
        assertSame(painter, result?.painter)
        assertSame(DataSource.DISK, result?.dataSource)
        assertSame(null, gate.takeDeferredCover())
    }

    @Test
    fun `deferred painter is released after scrolling settles`() {
        val gate = CoverImageDisplayGate()
        val painter = ColorPainter(Color.Red)

        gate.deferSuccess(painter)

        assertSame(null, gate.takeDeferredCover(isScrolling = true))
        assertSame(painter, gate.takeDeferredCover(isScrolling = false)?.painter)
        assertSame(null, gate.takeDeferredCover())
    }
}
