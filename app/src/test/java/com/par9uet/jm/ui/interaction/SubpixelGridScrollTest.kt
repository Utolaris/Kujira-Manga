package com.par9uet.jm.ui.interaction

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class SubpixelGridScrollTest {
    @Test fun decayingFractionalDeltasRemainVisibleInEveryFrame() {
        // Explicit input trajectory includes the subpixel tail; no Compose-internal calculator.
        val deltas = listOf(-4.8f, -3.1f, -1.7f, -.9f, -.48f, -.25f, -.125f, -.06f, -.015f, -.0037f)
        val grid = Grid()
        var distance = 0f
        var integerStalls = 0
        deltas.forEach { delta ->
            val integerBefore = grid.placed
            val renderedBefore = grid.rendered
            grid.step(delta)
            if (grid.placed == integerBefore) integerStalls++
            assertEquals(delta, grid.rendered - renderedBefore, .0001f)
            distance += delta
        }
        assertTrue("Fixture must include frames lost by integer placement", integerStalls > 0)
        assertEquals(distance, grid.rendered, .0001f)
    }

    @Test fun tinyMotionAndReversalsStayContinuousAcrossRowsWithoutDrift() {
        val grid = Grid()
        grid.jumpTo(-99)
        var distance = -99f
        // Cross the row boundary in both directions, including very small decimal deltas.
        val deltas = List(12) { -.25f } + List(24) { .125f } +
            listOf(-.0037f, .0037f, -.48f, .48f)
        deltas.forEach { delta ->
            val previous = grid.rendered
            grid.step(delta)
            distance += delta
            assertEquals(delta, grid.rendered - previous, .0001f)
            assertTrue(abs(grid.scroll.translation.value) <= .5f)
        }
        assertEquals(distance, grid.rendered, .0001f)
    }

    @Test fun parentConsumptionDoesNotMoveTheGridAndTheObserverConsumesNothing() {
        val grid = Grid()
        // Parent pre-scroll has consumed 10px: only the remaining .25px reaches the grid.
        grid.step(-.25f)
        assertEquals(-.25f, grid.rendered, 0f)
        grid.step(-10f, consumed = 0f, available = -10f)
        assertEquals(0f, grid.scroll.translation.value, 0f)
        // A nested child may scroll while the grid itself is idle.
        grid.active = false
        grid.step(.25f, moveGrid = false)
        assertEquals(0f, grid.scroll.translation.value, 0f)
    }

    @Test fun boundariesLeaveTheNativeLayoutAndOverscrollInControl() {
        val grid = Grid()
        grid.step(-.25f)
        grid.forward = false
        grid.step(-4f, consumed = -.75f, available = -3.25f)
        assertEquals(0f, grid.scroll.translation.value, 0f)
        grid.backward = false
        grid.step(4f, consumed = 1f, available = 3f)
        assertEquals(0f, grid.scroll.translation.value, 0f)
    }

    @Test fun externalJumpsAndViewportChangesInvalidateBeforeDrawingAndCannotResurrectOldFractions() {
        val grid = Grid()
        grid.step(-.25f)
        grid.jumpTo(-400)
        assertFalse(grid.scroll.positionIsCurrent.value)
        assertEquals(0f, grid.scroll.translation.value, 0f)
        grid.scroll.discardMovedLayout()
        grid.jumpTo(0)
        assertEquals(0f, grid.scroll.translation.value, 0f)
        grid.step(-.25f)
        grid.viewport = IntSize(400, 600)
        grid.changed()
        assertFalse(grid.scroll.positionIsCurrent.value)
        assertEquals(0f, grid.scroll.translation.value, 0f)
        grid.scroll.discardMovedLayout()
        grid.step(-.25f)
        assertEquals(-.25f, grid.scroll.translation.value, 0f)
    }

    @Test fun losingTheAnchorDuringAFastJumpDoesNotAccumulateTheJumpAsACorrection() {
        val grid = Grid()
        grid.step(-.25f)
        grid.step(-1000f)
        assertEquals(0f, grid.scroll.translation.value, 0f)
        val before = grid.rendered
        grid.step(-.25f)
        assertEquals(-.25f, grid.rendered - before, 0f)
    }

    @Test fun attachingWithAnExistingFractionDoesNotWrapAtHalfAPixel() {
        val grid = Grid()
        grid.pending = .4f
        repeat(40) {
            val before = grid.rendered
            grid.step(.3f)
            assertEquals(.3f, grid.rendered - before, .00001f)
            assertTrue(abs(grid.scroll.translation.value) <= 1f)
        }
    }

    @Test fun synchronousLayoutPublicationDuringAScrollIsNotMistakenForAnExternalJump() {
        val grid = Grid()
        grid.validateDuringScroll = true
        repeat(40) {
            val before = grid.rendered
            grid.step(-.25f)
            assertEquals(-.25f, grid.rendered - before, 0f)
        }
    }

    private class Grid {
        var placed = 0
        var pending = 0f
        var active = true
        var validateDuringScroll = false
        var backward = true
        var forward = true
        var viewport = IntSize(600, 400)
        private val revision = mutableIntStateOf(0)
        val scroll = SubpixelGridScroll(::frame) { active }
        val rendered get() = placed + scroll.translation.value

        fun changed() {
            revision.intValue++
            if (validateDuringScroll) scroll.discardMovedLayout()
        }
        fun jumpTo(position: Int) { placed = position; pending = 0f; changed() }

        fun step(
            requested: Float,
            consumed: Float = requested,
            available: Float = 0f,
            source: NestedScrollSource = NestedScrollSource.SideEffect,
            moveGrid: Boolean = true,
        ) {
            assertEquals(Offset.Zero, scroll.onPreScroll(Offset(0f, requested), source))
            if (moveGrid) {
                // LazyGrid's integer placement and retained fractional consumption contract.
                pending += consumed
                if (abs(pending) > .5f) {
                    val rounded = pending.roundToInt()
                    placed += rounded
                    pending -= rounded
                }
                changed()
            }
            assertEquals(Offset.Zero, scroll.onPostScroll(Offset(0f, consumed), Offset(0f, available), source))
        }

        private fun frame(anchorKey: Any?): GridScrollFrame {
            revision.intValue
            val first = 10 + Math.floorDiv(-placed, 100)
            val firstY = -Math.floorMod(-placed, 100)
            val anchor = (anchorKey as? Int ?: (first + 2)).takeIf { it in first..first + 4 }
            return GridScrollFrame(
                viewport, 2, 0, 0, 0, first, firstY, IntSize(300, 100),
                anchor, anchor?.let { (it - first) * 100 + firstY } ?: 0,
                anchor?.let { IntSize(300, 100) }, backward, forward,
            )
        }
    }
}
