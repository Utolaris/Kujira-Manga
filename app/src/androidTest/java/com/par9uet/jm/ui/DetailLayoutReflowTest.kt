package com.par9uet.jm.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.par9uet.jm.ui.interaction.DetailReflowMillis
import com.par9uet.jm.ui.interaction.motionEnabled
import com.par9uet.jm.ui.screens.detailLayoutBottomInset
import com.par9uet.jm.ui.screens.ComicDetailAdaptiveLayout
import com.par9uet.jm.ui.screens.ComicTitleAndAuthors
import com.par9uet.jm.data.models.Comic
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class DetailLayoutReflowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var wide by mutableStateOf(false)
    private var changingBodyHeight by mutableStateOf(1500.dp)
    private val bounds = mutableMapOf<String, Rect>()
    private val instances = mutableMapOf<String, Int>()
    private lateinit var scroll: ScrollState

    @Test fun tabletCommentsReachTheBottomBetweenFloatingButtonsAndReserveSpaceOnlyForTheComposer() {
        val composing = mutableStateOf(false)
        compose.setContent {
            Fixture(bodyHeight = 1500.dp,
                bottomReserved = detailLayoutBottomInset(true, composing.value, 0.dp, 64.dp))
        }
        val density = compose.activity.resources.displayMetrics.density
        for (landscape in listOf(false, true)) {
            compose.runOnIdle { wide = landscape }
            compose.waitForIdle()
            for (composer in listOf(false, true, false)) {
                compose.runOnIdle { composing.value = composer }
                compose.waitForIdle()
                compose.runOnIdle { runBlocking { scroll.scrollTo(scroll.maxValue) } }
                compose.waitForIdle()
                val expected = (if (composer) 88f else 8f) * density
                assertEquals("No invisible tablet bottom bar may truncate the comments",
                    expected, bounds.getValue("root").height - relative("body").bottom, 1f)
            }
        }
    }

    @Test fun rotationMovesComponentsThroughIntermediatePositionsWithoutReplacingThem() {
        assumeTrue(motionEnabled())
        compose.mainClock.autoAdvance = false
        compose.setContent { Fixture() }
        compose.waitForIdle()
        val coverStart = bounds.getValue("cover").width
        val bodyStart = relative("body").left
        compose.runOnIdle { wide = true }
        compose.mainClock.advanceTimeBy(96)
        compose.waitForIdle()
        val coverMiddle = bounds.getValue("cover").width
        val bodyMiddle = relative("body").left
        compose.mainClock.advanceTimeBy(DetailReflowMillis.toLong() + 32)
        compose.waitForIdle()
        assertTrue(coverMiddle > coverStart)
        assertTrue(coverMiddle < bounds.getValue("cover").width)
        assertTrue(bodyMiddle > bodyStart)
        assertTrue(bodyMiddle < relative("body").left)
        compose.runOnIdle { assertEquals(mapOf("cover" to 1, "metadata" to 1, "body" to 1), instances) }
    }

    @Test fun repeatedRotationRetargetsTheExistingComponentsAndRetainsScroll() {
        assumeTrue(motionEnabled())
        compose.mainClock.autoAdvance = false
        compose.setContent { Fixture() }
        compose.waitForIdle()
        compose.runOnIdle { runBlocking { scroll.scrollTo(120) }; wide = true }
        compose.mainClock.advanceTimeBy(96)
        compose.waitForIdle()
        val middleWidth = bounds.getValue("cover").width
        compose.runOnIdle { wide = false }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        // A reversed transition starts at its displayed size, rather than resetting to either layout.
        assertEquals(middleWidth, bounds.getValue("cover").width, middleWidth * .08f)
        compose.mainClock.advanceTimeBy(DetailReflowMillis.toLong() + 32)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(120, scroll.value)
            assertEquals(mapOf("cover" to 1, "metadata" to 1, "body" to 1), instances)
        }
    }

    @Test fun compactCoverFollowsScrollButLandscapeCoverStaysFixed() {
        compose.setContent { Fixture() }
        compose.waitForIdle()
        val start = relative("cover").top
        compose.runOnIdle { runBlocking { scroll.scrollTo(120) } }
        compose.waitForIdle()
        assertEquals(start - 120, relative("cover").top, 1f)
        compose.runOnIdle { wide = true }
        compose.waitForIdle()
        val pinned = relative("cover").top
        compose.runOnIdle { runBlocking { scroll.scrollTo(240) } }
        compose.waitForIdle()
        assertEquals(pinned, relative("cover").top, 1f)
    }

    @Test fun shortContentStaysAtTheHeaderInsteadOfBeingCenteredInTheViewport() {
        compose.setContent { Fixture(bodyHeight = 40.dp) }
        compose.waitForIdle()
        val expectedTop = 80f * compose.activity.resources.displayMetrics.density
        assertEquals(expectedTop, relative("cover").top, 1f)
        assertEquals(expectedTop, relative("metadata").top, 1f)
        compose.runOnIdle { wide = true }
        compose.waitForIdle()
        assertEquals(expectedTop, relative("cover").top, 1f)
        assertEquals(expectedTop, relative("metadata").top, 1f)
    }

    @Test fun downloadDetailsKeepShortContentAtTheToolbarBoundaryInBothOrientations() {
        compose.setContent { Fixture(bodyHeight = 40.dp) }
        compose.waitForIdle()
        val density = compose.activity.resources.displayMetrics.density
        fun assertTopAligned() {
            assertEquals(80f * density, relative("cover").top, 1f)
            assertEquals(relative("cover").top, relative("metadata").top, 1f)
        }
        assertTopAligned()
        assertTrue(bounds.getValue("cover").width < bounds.getValue("root").width / 2)
        assertTrue(relative("metadata").left >= relative("cover").right)
        assertTrue(relative("body").top >= relative("cover").bottom)
        compose.runOnIdle { wide = true }
        compose.waitForIdle()
        assertTopAligned()
        compose.runOnIdle { wide = false }
        compose.waitForIdle()
        assertTopAligned()
        compose.runOnIdle { assertEquals(mapOf("cover" to 1, "metadata" to 1, "body" to 1), instances) }
    }

    @Test fun changingCommentHeightDoesNotMoveTheHeaderOrSpreadItsText() {
        compose.setContent {
            MaterialTheme {
                Fixture(bodyHeight = changingBodyHeight, metadataContent = {
                    Column(Modifier.record("metadata"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ComicTitleAndAuthors(
                            comic = Comic.create(1484063, "漫画标题", listOf("作者")),
                            onTagSearch = {},
                        )
                        Text("JM1484063", Modifier.record("jm"))
                    }
                })
            }
        }
        compose.waitForIdle()
        val expectedTop = 80f * compose.activity.resources.displayMetrics.density
        fun titleBounds() = compose.onNodeWithText("漫画标题").fetchSemanticsNode().boundsInRoot
        fun authorBounds() = compose.onNodeWithText("作者").fetchSemanticsNode().boundsInRoot
        val authorGap = authorBounds().top - titleBounds().bottom
        val jmGap = bounds.getValue("jm").top - authorBounds().bottom
        for (landscape in listOf(false, true, false)) {
            compose.runOnIdle { wide = landscape }
            compose.waitForIdle()
            for (height in listOf(1500.dp, 40.dp, 0.dp, 600.dp)) {
                compose.runOnIdle { changingBodyHeight = height }
                compose.waitForIdle()
                assertEquals(expectedTop, relative("cover").top, 1f)
                assertEquals(expectedTop, titleBounds().top - bounds.getValue("root").top, 1f)
                assertEquals(authorGap, authorBounds().top - titleBounds().bottom, 1f)
                assertEquals(jmGap, bounds.getValue("jm").top - authorBounds().bottom, 1f)
            }
        }
    }

    @Test fun shrinkingViewportKeepsEveryIntermediateContentFrameInsideTheScreen() {
        assumeTrue(motionEnabled())
        wide = true
        compose.mainClock.autoAdvance = false
        compose.setContent { Fixture() }
        compose.waitForIdle()
        compose.runOnIdle { wide = false }
        repeat(24) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertTrue("Body must fit the new screen throughout reflow", relative("body").right <= bounds.getValue("root").width + 1f)
            assertTrue(bounds.getValue("metadata").width > 0f)
        }
    }

    private fun relative(name: String): Rect = bounds.getValue(name).translate(-bounds.getValue("root").topLeft)

    private fun Modifier.record(name: String) = onGloballyPositioned {
        bounds[name] = Rect(it.localToRoot(Offset.Zero), Size(it.size.width.toFloat(), it.size.height.toFloat()))
    }

    @Composable private fun KeepInstance(name: String, content: @Composable () -> Unit) {
        DisposableEffect(Unit) {
            instances[name] = (instances[name] ?: 0) + 1
            onDispose { }
        }
        content()
    }

    @Composable private fun Fixture(bodyHeight: Dp = 1500.dp, bottomReserved: Dp = 80.dp, metadataContent: (@Composable () -> Unit)? = null) {
        scroll = rememberScrollState()
        Box(Modifier.requiredSize(if (wide) 1000.dp else 600.dp, if (wide) 600.dp else 900.dp)) {
            ComicDetailAdaptiveLayout(
                twoColumns = wide,
                headerTopPadding = 80.dp,
                bottomReserved = bottomReserved,
                scrollState = scroll,
                modifier = Modifier.record("root"),
                cover = { modifier -> KeepInstance("cover") { Box(modifier.aspectRatio(.75f).background(Color.Red).record("cover")) } },
                metadata = { KeepInstance("metadata") {
                    if (metadataContent != null) metadataContent()
                    else Box(Modifier.fillMaxWidth().height(140.dp).record("metadata"))
                } },
                body = { KeepInstance("body") { Box(Modifier.fillMaxWidth().height(bodyHeight).record("body")) } },
            )
        }
    }
}
