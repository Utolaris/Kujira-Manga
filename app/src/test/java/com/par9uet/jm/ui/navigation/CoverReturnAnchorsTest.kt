package com.par9uet.jm.ui.navigation

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.par9uet.jm.ui.models.CoverAnchor
import com.par9uet.jm.ui.models.CoverReturnFrame
import org.junit.Assert.*
import org.junit.Test

class CoverReturnAnchorsTest {
    private val viewport = Rect(0f, 0f, 400f, 800f)
    private val frame = CoverReturnFrame(null, null, viewport, Rect(16f, 100f, 196f, 340f),
        1f, 1f, IntSize(400, 800), 100, "list", Rect(220f, 600f, 340f, 760f))
    private fun anchor(key: Any = Any(), page: String = "list", id: Int = 100,
        bounds: Rect = Rect(220f, 600f, 340f, 760f), visible: Rect = bounds) = CoverAnchor(key, page, id, bounds, visible)

    @Test fun `tracked covers do not materialize geometry until navigation asks and always return current bounds`() {
        val registry = CoverReturnAnchors()
        var offset = 0f
        var geometryReads = 0
        val keys = List(40) { Any() }
        keys.forEachIndexed { index, key ->
            registry.track(key) {
                geometryReads++
                anchor(key = key, id = index,
                    bounds = Rect(20f, 200f + offset, 140f, 360f + offset))
            }
        }
        repeat(240) {
            offset += .25f
            keys.forEach { assertFalse(registry.hidden(it)) }
        }
        assertEquals(0, geometryReads)
        assertEquals(260f, registry.visible(keys.first(), "list", viewport)!!.boundsInWindow.top)
        assertEquals(1, geometryReads)
        registry.remove(keys.first())
        assertNull(registry.visible(keys.first(), "list", viewport))
    }

    @Test fun `tracked geometry can detach or become clipped without leaving a stale return target`() {
        val registry = CoverReturnAnchors()
        val key = Any()
        var current: CoverAnchor? = anchor(key)
        registry.track(key) { current }
        registry.pending = "list" to 100
        assertTrue(registry.hidden(key))
        current = current!!.copy(visibleBoundsInWindow = Rect.Zero)
        assertNull(registry.find(frame, "list", viewport))
        current = null
        assertNull(registry.visible(key, "list", viewport))
        assertFalse(registry.hidden(key))
    }

    @Test fun `return uses the current visible layout rather than the old opening coordinates`() {
        val registry = CoverReturnAnchors()
        val changed = anchor(bounds = Rect(16f, 120f, 136f, 280f))
        registry.update(changed)
        assertEquals(changed, registry.find(frame, "list", viewport))
        val moved = changed.copy(boundsInWindow = Rect(150f, 120f, 270f, 280f))
        registry.update(moved)
        assertEquals(moved.boundsInWindow, registry.visible(changed.key, "list", viewport)!!.boundsInWindow)
    }
    @Test fun `same comic on another page and other comics in the old slot are not destinations`() {
        val registry = CoverReturnAnchors()
        registry.update(anchor(page = "another-list"))
        registry.update(anchor(id = 200))
        assertNull(registry.find(frame, "list", viewport))
        assertNull(registry.find(frame, "another-list", viewport))
        assertTrue(registry.anyVisible("list", viewport))
        assertFalse(registry.anyVisible("missing-page", viewport))
    }
    @Test fun `removed covers and fully clipped covers fall back to the center`() {
        val registry = CoverReturnAnchors()
        val original = anchor()
        registry.update(original)
        registry.remove(original.key)
        assertNull(registry.find(frame, "list", viewport))
        registry.update(anchor(bounds = Rect(16f, 850f, 136f, 1010f)))
        registry.update(anchor(visible = Rect.Zero))
        assertNull(registry.find(frame, "list", viewport))
    }
    @Test fun `partially visible covers retain their full image rectangle`() {
        val registry = CoverReturnAnchors()
        val partial = anchor(bounds = Rect(16f, 700f, 136f, 860f), visible = Rect(16f, 700f, 136f, 800f))
        registry.update(partial)
        assertEquals(860f, registry.find(frame, "list", viewport)!!.boundsInWindow.bottom)
    }
    @Test fun `duplicates choose the visible cover nearest the opening slot`() {
        val registry = CoverReturnAnchors()
        registry.update(anchor(bounds = Rect(16f, 100f, 136f, 260f)))
        val nearest = anchor()
        registry.update(nearest)
        assertEquals(nearest, registry.find(frame, "list", viewport))
        registry.hiddenKey = nearest.key
        assertTrue(registry.hidden(nearest.key))
        assertEquals(nearest, registry.visible(nearest.key, "list", viewport))
        registry.remove(nearest.key)
        assertNull(registry.visible(nearest.key, "list", viewport))
    }
    @Test fun `restored source cover is hidden before its first draw until the destination is selected`() {
        val registry = CoverReturnAnchors()
        registry.pending = "list" to 100
        val target = anchor()
        val other = anchor(id = 200)
        registry.update(target)
        registry.update(other)
        assertTrue(registry.hidden(target.key))
        assertFalse(registry.hidden(other.key))
        assertEquals(target, registry.find(frame, "list", viewport))
        registry.hiddenKey = target.key
        registry.pending = null
        assertTrue(registry.hidden(target.key))
        registry.hiddenKey = null
        assertFalse(registry.hidden(target.key))
    }
    @Test fun `a rapid open rechecks the current source page and full cover bounds after layout settles`() {
        val registry = CoverReturnAnchors()
        registry.update(anchor(page = "previous"))
        registry.update(anchor(page = "current", visible = Rect.Zero))
        val clicked = anchor(page = "current", bounds = Rect(20f, 200f, 140f, 360f))
        registry.update(clicked)
        val moved = clicked.copy(boundsInWindow = Rect(20f, 700f, 140f, 860f),
            visibleBoundsInWindow = Rect(20f, 700f, 140f, 800f))
        registry.update(moved)
        assertEquals(moved, registry.source(100, "current", viewport, clicked.boundsInWindow))
        assertEquals(860f, registry.source(100, "current", viewport, null)!!.boundsInWindow.bottom)
        assertNull(registry.source(200, "current", viewport, null))
    }

}
