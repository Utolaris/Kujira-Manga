package com.par9uet.jm.ui.interaction

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test

class CoverReturnPathTest {
    private val start = Rect(16f, 100f, 196f, 340f)
    private val target = Rect(220f, 600f, 340f, 760f)

    @Test fun `a source moving during the final third never consumes a frame with zero travel`() {
        val path = CoverReturnPath(start, target, fading = false)
        var previous = start
        var endpoint = target
        for (frame in 1..20) {
            endpoint = if (frame > 12) target.translate(Offset((frame - 12) * 3f, 0f)) else target
            path.advance(frame / 20f, endpoint, fadeOut = false)
            assertTrue("Frame $frame must continue toward the source", path.bounds!!.left > previous.left)
            assertTrue(path.bounds!!.top > previous.top)
            previous = path.bounds!!
        }
        assertEquals(endpoint, path.bounds)
        assertEquals(1f, path.alpha)
    }

    @Test fun `subpixel source jitter cannot restart the trajectory in the final third`() {
        val path = CoverReturnPath(start, target, fading = false)
        for (frame in 1..20) {
            val time = frame / 20f
            val jitter = if (frame > 12) (if (frame % 2 == 0) .4f else -.4f) else 0f
            path.advance(time, target.translate(Offset(jitter, jitter)), fadeOut = false)
            assertEquals(coverEntryBounds(start, target, time), path.bounds)
        }
    }

    @Test fun `removing the source late begins the center fade without a held frame`() {
        val path = CoverReturnPath(start, target, fading = false)
        path.advance(.7f, target, fadeOut = false)
        val previous = path.bounds!!
        val center = coverCenterExitBounds(previous, Size(400f, 800f))
        path.advance(.75f, center, fadeOut = true)
        assertNotEquals(previous, path.bounds)
        assertTrue(path.alpha < 1f)
        path.advance(1f, center, fadeOut = true)
        assertEquals(center, path.bounds)
        assertEquals(0f, path.alpha)
    }
}
