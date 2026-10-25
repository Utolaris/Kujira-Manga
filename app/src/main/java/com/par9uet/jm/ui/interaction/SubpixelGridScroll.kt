package com.par9uet.jm.ui.interaction

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

/** Values must be copied: LazyGrid mutates its visible item offsets during its fast scroll path. */
internal data class GridScrollFrame(
    val viewportSize: IntSize,
    val columns: Int,
    val beforePadding: Int,
    val afterPadding: Int,
    val spacing: Int,
    val firstKey: Any,
    val firstY: Int,
    val firstSize: IntSize,
    val anchorKey: Any?,
    val anchorY: Int,
    val anchorSize: IntSize?,
    val canScrollBackward: Boolean,
    val canScrollForward: Boolean,
) {
    fun sameViewport(other: GridScrollFrame): Boolean =
        viewportSize == other.viewportSize && columns == other.columns &&
            beforePadding == other.beforePadding && afterPadding == other.afterPadding &&
            spacing == other.spacing

    fun samePosition(other: GridScrollFrame?): Boolean = other != null &&
        sameViewport(other) && firstKey == other.firstKey && firstY == other.firstY &&
        firstSize == other.firstSize
}

/** Observes the child's consumption; never consumes gestures, flings or overscroll itself. */
internal class SubpixelGridScroll(
    private val readFrame: (anchorKey: Any?) -> GridScrollFrame?,
    private val isScrolling: () -> Boolean,
) : NestedScrollConnection {
    private var before: GridScrollFrame? = null
    private var expected by mutableStateOf<GridScrollFrame?>(null)
    private var remainder by mutableFloatStateOf(0f)

    // Read only in item graphics layers. External jumps/reflow invalidate the old correction
    // before drawing; ordinary scrolling does not recompose or remeasure the item content.
    val positionIsCurrent = derivedStateOf(structuralEqualityPolicy()) {
        expected == null || expected?.samePosition(readFrame(null)) == true
    }
    val translation = derivedStateOf(structuralEqualityPolicy()) {
        if (positionIsCurrent.value) remainder else 0f
    }

    fun discardMovedLayout() {
        // A synchronous remeasure can publish layout state between pre/post-scroll. Its new
        // position belongs to the current scroll transaction, not an external jump.
        if (before == null && !positionIsCurrent.value) {
            expected = null
            remainder = 0f
        }
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        before = null
        if (available.y != 0f && isScrolling()) {
            val current = readFrame(null)
            if (expected?.samePosition(current) != true) remainder = 0f
            before = current
        }
        return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        val previous = before ?: return Offset.Zero
        val current = readFrame(previous.anchorKey)
        if (current == null || !previous.sameViewport(current) ||
            previous.anchorKey == null || previous.anchorKey != current.anchorKey ||
            previous.anchorSize != current.anchorSize
        ) {
            remainder = 0f
        } else {
            val placed = current.anchorY - previous.anchorY
            val next = remainder + consumed.y - placed
            val atEdge = available.y != 0f ||
                (consumed.y > 0f && !current.canScrollBackward) ||
                (consumed.y < 0f && !current.canScrollForward)
            // A newly attached observer may inherit up to half a pixel already held by LazyGrid.
            // Hence a relative correction can reach one pixel, but must never absorb a reflow.
            remainder = if (atEdge || !next.isFinite() || abs(next) > 1.001f) 0f else next
        }
        expected = current
        before = null
        // Keep the remainder on stop, touch-down and reversal: zeroing it would cause a final jump.
        return Offset.Zero
    }
}

@Composable
internal fun rememberSubpixelGridScroll(state: LazyGridState): SubpixelGridScroll {
    val scroll = remember(state) {
        SubpixelGridScroll(
            readFrame = { anchorKey ->
                val layout = state.layoutInfo
                val items = layout.visibleItemsInfo
                val first = items.firstOrNull { it.row >= 0 }
                if (first == null) {
                    null
                } else {
                    val anchor = if (anchorKey == null) {
                        items[items.size / 2].takeIf { it.row >= 0 } ?: first
                    } else {
                        items.firstOrNull { it.key == anchorKey && it.row >= 0 }
                    }
                    GridScrollFrame(
                        viewportSize = layout.viewportSize,
                        columns = layout.maxSpan,
                        beforePadding = layout.beforeContentPadding,
                        afterPadding = layout.afterContentPadding,
                        spacing = layout.mainAxisItemSpacing,
                        firstKey = first.key,
                        firstY = first.offset.y,
                        firstSize = first.size,
                        anchorKey = anchor?.key,
                        anchorY = anchor?.offset?.y ?: 0,
                        anchorSize = anchor?.size,
                        canScrollBackward = state.canScrollBackward,
                        canScrollForward = state.canScrollForward,
                    )
                }
            },
            isScrolling = { state.isScrollInProgress },
        )
    }
    LaunchedEffect(scroll) {
        // Programmatic scrollToItem/reflow bypasses nested scroll. Drop its old fraction for good,
        // including when a later jump happens to land at the same integer position again.
        snapshotFlow { scroll.positionIsCurrent.value }.collect { current ->
            if (!current) scroll.discardMovedLayout()
        }
    }
    return scroll
}

/** Moving items, rather than the viewport, keeps the grid's clipping and overscroll fixed. */
internal fun Modifier.subpixelGridItem(scroll: SubpixelGridScroll): Modifier =
    graphicsLayer { translationY = scroll.translation.value }
