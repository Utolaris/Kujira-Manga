package com.par9uet.jm.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.par9uet.jm.ui.interaction.SubpixelGridScroll
import com.par9uet.jm.ui.interaction.rememberSubpixelGridScroll
import com.par9uet.jm.ui.interaction.subpixelGridItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Public nested-scroll dispatch + real LazyGrid consumption and LayoutCoordinates, no fake layout. */
class SubpixelGridScrollTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var gridState: LazyGridState
    private lateinit var scroll: SubpixelGridScroll
    private lateinit var scope: CoroutineScope
    private lateinit var coordinates: LayoutCoordinates
    private val dispatcher = NestedScrollDispatcher()
    private val childConnection = object : NestedScrollConnection {}
    private var itemCompositions = 0

    @Test fun quarterPixelNestedScrollSourcesMoveRealCoordinatesWithoutRecomposingItems() {
        showGrid()
        val initialCompositions = compose.runOnIdle { itemCompositions }
        var previous = position()
        repeat(40) { frame ->
            step(-.25f, if (frame < 20) NestedScrollSource.UserInput else NestedScrollSource.SideEffect)
            val current = position()
            assertEquals(-.25f, current - previous, .001f)
            previous = current
        }
        // Reversal after releasing/reacquiring the scroll session retains the fraction.
        repeat(3) {
            step(.125f, NestedScrollSource.UserInput)
            val current = position()
            assertEquals(.125f, current - previous, .001f)
            previous = current
        }
        compose.runOnIdle { assertEquals(initialCompositions, itemCompositions) }
    }

    @Test fun programmaticPositionRestoreDoesNotBringBackAnOldFraction() {
        showGrid()
        val initial = position()
        step(-.25f, NestedScrollSource.SideEffect)
        assertEquals(initial - .25f, position(), .001f)
        compose.runOnIdle { scope.launch { gridState.scrollToItem(40) } }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0f, scroll.translation.value, 0f) }
        compose.runOnIdle { scope.launch { gridState.scrollToItem(20) } }
        compose.waitForIdle()
        assertEquals(initial, position(), .001f)
        compose.runOnIdle { assertEquals(0f, scroll.translation.value, 0f) }
    }

    private fun showGrid() {
        compose.setContent {
            gridState = rememberLazyGridState(20)
            scroll = rememberSubpixelGridScroll(gridState)
            scope = rememberCoroutineScope()
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                modifier = Modifier.size(320.dp, 450.dp).nestedScroll(scroll),
            ) {
                items(1000, key = { it }) { index ->
                    SideEffect { itemCompositions++ }
                    val child = if (index == 24) Modifier.nestedScroll(childConnection, dispatcher) else Modifier
                    Box(Modifier.fillMaxWidth().height(100.dp).then(child).subpixelGridItem(scroll)) {
                        if (index == 24) {
                            // The stored coordinates remain live when only the ancestor layer moves.
                            Box(
                                Modifier.fillMaxSize().testTag("moving-cover")
                                    .onGloballyPositioned { coordinates = it },
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun position(): Float = compose.runOnIdle { coordinates.positionInRoot().y }

    private fun step(delta: Float, source: NestedScrollSource) {
        compose.runOnIdle {
            scope.launch {
                gridState.scroll {
                    val requested = Offset(0f, delta)
                    val available = requested - dispatcher.dispatchPreScroll(requested, source)
                    val consumed = Offset(0f, -scrollBy(-available.y))
                    dispatcher.dispatchPostScroll(consumed, available - consumed, source)
                }
            }
        }
        compose.waitForIdle()
    }
}
