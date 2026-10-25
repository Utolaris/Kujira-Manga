package com.par9uet.jm.ui

import androidx.activity.ComponentActivity
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.ui.components.BackIconButton
import com.par9uet.jm.ui.components.MotionButton
import com.par9uet.jm.ui.interaction.CoverDetailTransition
import com.par9uet.jm.ui.interaction.ProvideScrollEdgeFeedback
import com.par9uet.jm.ui.interaction.detailEntryCover
import com.par9uet.jm.ui.interaction.detailEntryContent
import com.par9uet.jm.ui.interaction.motionEnabled
import com.par9uet.jm.ui.interaction.coverSourceBounds
import com.par9uet.jm.ui.interaction.CoverEnterMillis
import com.par9uet.jm.ui.interaction.DetailContentEnterMillis
import com.par9uet.jm.ui.glass.GlassCaptureHost
import com.par9uet.jm.ui.models.DetailEntryControl
import com.par9uet.jm.ui.models.LocalDetailEntry
import com.par9uet.jm.ui.models.LocalDetailMotion
import com.par9uet.jm.ui.interaction.DetailMotion
import com.par9uet.jm.ui.interaction.CoverPose
import com.par9uet.jm.ui.models.LocalTabletLayoutEnabled
import com.par9uet.jm.ui.screens.tabletDetailCoverWidth
import com.par9uet.jm.ui.models.LocalCoverNavigator
import com.par9uet.jm.ui.models.LocalCoverReturnHost
import com.par9uet.jm.ui.models.CoverReturnFrame
import com.par9uet.jm.ui.models.CoverReturnHost
import com.par9uet.jm.ui.navigation.CoverNavigationHost
import com.par9uet.jm.ui.navigation.CoverOrigins
import com.par9uet.jm.ui.navigation.CoverReturns
import com.par9uet.jm.ui.navigation.CoverReturnAnchors
import com.par9uet.jm.ui.navigation.CoverReturnOverlay
import com.par9uet.jm.ui.navigation.LocalMainNavController
import com.par9uet.jm.ui.theme.LocalExtendedColors
import com.par9uet.jm.ui.theme.extendedColorSchemeFor
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class CoverTransitionFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var observedEntry: DetailEntryControl? = null
    private var observedMotion: DetailMotion? = null
    private var removeSource: (() -> Unit)? = null
    private var lastReturnFrame: CoverReturnFrame? = null

    @Test fun edgeBackKeepsDetailStillUntilCommitThenAlwaysRunsTheCompleteButtonExit() {
        assumeTrue(motionEnabled())
        compose.mainClock.autoAdvance = false
        compose.setContent { TestGraph() }
        val dispatcher = compose.activity.onBackPressedDispatcher
        listOf(.05f, .95f).forEach { strength ->
            compose.onNodeWithText("source-cover").performClick()
            compose.mainClock.advanceTimeBy(CoverEnterMillis + DetailContentEnterMillis + 256L)
            val detailBounds = compose.onNodeWithTag("destination-cover").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle {
                lastReturnFrame = null
                assertEquals(1f, observedEntry!!.coverProgress)
                assertEquals(1f, observedEntry!!.contentProgress)
                dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 200f, 0f, BackEventCompat.EDGE_LEFT))
                dispatcher.dispatchOnBackProgressed(BackEventCompat(300f, 200f, .95f, BackEventCompat.EDGE_LEFT))
            }
            compose.runOnIdle {
                assertEquals(1f, observedEntry!!.coverProgress)
                assertEquals(1f, observedEntry!!.contentProgress)
                assertTrue(!observedEntry!!.coverInFlight)
                assertEquals(null, lastReturnFrame)
                dispatcher.dispatchOnBackCancelled()
            }
            compose.mainClock.advanceTimeBy(32)
            compose.runOnIdle {
                assertEquals(1f, observedEntry!!.coverProgress)
                assertEquals(1f, observedEntry!!.contentProgress)
                assertEquals(null, lastReturnFrame)
                dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 200f, 0f, BackEventCompat.EDGE_LEFT))
                dispatcher.dispatchOnBackProgressed(BackEventCompat(200f, 200f, strength, BackEventCompat.EDGE_LEFT))
            }
            compose.runOnIdle { dispatcher.onBackPressed() }
            compose.mainClock.advanceTimeBy(128)
            compose.runOnIdle {
                assertEquals(1f, observedEntry!!.coverProgress)
                assertTrue(observedEntry!!.contentProgress > 0f && observedEntry!!.contentProgress < 1f)
                assertEquals(null, lastReturnFrame)
            }
            compose.mainClock.advanceTimeUntil(2_000) { lastReturnFrame != null }
            compose.runOnIdle {
                assertEquals(1f, lastReturnFrame!!.reveal)
                assertEquals(detailBounds.left, lastReturnFrame!!.coverBounds!!.left, .01f)
                assertEquals(detailBounds.top, lastReturnFrame!!.coverBounds!!.top, .01f)
            }
            compose.mainClock.advanceTimeBy(800)
            compose.onNodeWithText("source-cover").assertIsDisplayed()
            compose.onNodeWithTag("cover-return-overlay").assertDoesNotExist()
        }
    }

    @Test fun committedReturnConsumesSecondTouchAndSystemBackWithoutRestartingAndThenReleasesInput() {
        assumeTrue(motionEnabled())
        compose.mainClock.autoAdvance = false
        lateinit var returns: CoverReturns
        lateinit var frame: CoverReturnFrame
        var underlyingProgress = 0
        var underlyingBacks = 0
        var underlyingTaps = 0
        var pops = 0
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { CoverReturns(scope, CoverReturnAnchors(), { "list" }, { Offset.Zero }) }
            MaterialTheme {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val rect = Rect(0f, 0f, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
                    SideEffect {
                        returns = controller
                        frame = CoverReturnFrame(null, null, rect, null, 1f, 0f,
                            IntSize(constraints.maxWidth, constraints.maxHeight), 100, "list")
                    }
                    PredictiveBackHandler { events ->
                        try {
                            events.collect { underlyingProgress++ }
                            underlyingBacks++
                        } catch (_: CancellationException) { }
                    }
                    Box(Modifier.fillMaxSize().testTag("under-return").clickable { underlyingTaps++ })
                    CoverReturnOverlay(controller, Color.White)
                }
            }
        }
        compose.runOnIdle { returns.close(frame) { pops++ } }
        compose.mainClock.advanceTimeBy(128)
        var before = 0f
        compose.runOnIdle {
            before = returns.progress
            assertTrue(returns.frame != null && before > 0f && before < 1f)
            compose.activity.onBackPressedDispatcher.dispatchOnBackStarted(
                BackEventCompat(0f, 200f, 0f, BackEventCompat.EDGE_LEFT))
            compose.activity.onBackPressedDispatcher.dispatchOnBackProgressed(
                BackEventCompat(60f, 200f, .5f, BackEventCompat.EDGE_LEFT))
        }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        compose.runOnIdle {
            assertEquals(before, returns.progress)
            assertEquals(0, underlyingProgress)
            assertEquals(0, underlyingBacks)
            compose.activity.onBackPressedDispatcher.dispatchOnBackStarted(
                BackEventCompat(0f, 200f, 0f, BackEventCompat.EDGE_LEFT))
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithTag("under-return").performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(0, underlyingBacks)
            assertEquals(0, underlyingTaps)
            assertEquals(1, pops)
            assertTrue(returns.progress >= before)
        }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle {
            assertEquals(null, returns.frame)
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.runOnIdle { assertEquals(1, underlyingBacks) }
        compose.onNodeWithTag("under-return").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, underlyingTaps) }
    }

    @Test fun tabletCommentsKeepTheCoverFixedAndEdgeBackStillRunsTheCompleteExit() {
        assumeTrue(motionEnabled())
        compose.mainClock.autoAdvance = false
        compose.setContent { TestGraph(tablet = true) }
        compose.onNodeWithText("source-cover").performClick()
        compose.mainClock.advanceTimeBy(CoverEnterMillis + DetailContentEnterMillis + 256L)
        val before = compose.onNodeWithTag("destination-cover").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("comment-25").performScrollTo()
        compose.onNodeWithTag("destination-cover").assertIsDisplayed()
        val after = compose.onNodeWithTag("destination-cover").fetchSemanticsNode().boundsInRoot
        assertEquals(before, after)
        compose.mainClock.autoAdvance = false
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 200f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(300f, 200f, .95f, BackEventCompat.EDGE_LEFT))
        }
        compose.runOnIdle {
            assertEquals(1f, observedEntry!!.coverProgress)
            assertEquals(1f, observedEntry!!.contentProgress)
            assertEquals(null, lastReturnFrame)
            dispatcher.onBackPressed()
        }
        compose.mainClock.advanceTimeBy(128)
        compose.runOnIdle {
            assertEquals(1f, observedEntry!!.coverProgress)
            assertTrue(observedEntry!!.contentProgress > 0f && observedEntry!!.contentProgress < 1f)
        }
        compose.mainClock.advanceTimeUntil(2_000) { lastReturnFrame != null }
        compose.runOnIdle {
            assertTrue(lastReturnFrame!!.cover != null)
            assertEquals(before.left, lastReturnFrame!!.coverBounds!!.left, .01f)
            assertEquals(before.top, lastReturnFrame!!.coverBounds!!.top, .01f)
        }
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("source-cover").assertIsDisplayed()
        compose.onNodeWithTag("cover-return-overlay").assertDoesNotExist()
    }

    @Test fun tabletBlankColumnCannotMoveThePageWhileTheCoverStillDragsAndRebounds() {
        assumeTrue(motionEnabled())
        compose.mainClock.autoAdvance = false
        compose.setContent { TestGraph(tablet = true) }
        compose.onNodeWithText("source-cover").performClick()
        compose.mainClock.advanceTimeBy(CoverEnterMillis + DetailContentEnterMillis + 256L)
        compose.onNodeWithTag("detail-surface").performTouchInput {
            down(Offset(20f, height - 160f))
            moveBy(Offset(0f, -70f))
            moveBy(Offset(0f, -70f))
        }
        compose.runOnIdle { assertEquals(CoverPose(), observedMotion!!.pose) }
        compose.onNodeWithTag("detail-surface").performTouchInput { up() }
        compose.onNodeWithTag("destination-cover").performTouchInput {
            down(center)
            moveBy(Offset(0f, 50f))
            moveBy(Offset(0f, 50f))
        }
        compose.runOnIdle { assertTrue(observedMotion!!.pose.y > 0f) }
        compose.onNodeWithTag("destination-cover").performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle {
            assertEquals(CoverPose(), observedMotion!!.pose)
            assertEquals(null, lastReturnFrame)
        }
    }

    @Composable private fun TestGraph(tablet: Boolean = false) {
        val nav = rememberNavController()
        val origins = remember { CoverOrigins() }
        var sourceVisible by remember { mutableStateOf(true) }
        SideEffect { removeSource = { sourceVisible = false } }
        MaterialTheme {
            CompositionLocalProvider(LocalMainNavController provides nav, LocalTabletLayoutEnabled provides tablet) {
                CoverNavigationHost(origins) {
                    NavHost(nav, startDestination = "list", enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None }, popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None }) {
                        composable("list") {
                            val opener = LocalCoverNavigator.current
                            val bounds = remember { com.par9uet.jm.ui.interaction.CoverSourceBounds() }
                            Box(Modifier.fillMaxSize().background(Color.LightGray)) {
                                if (sourceVisible) MotionButton(modifier = Modifier.padding(30.dp).size(120.dp, 160.dp)
                                    .coverSourceBounds(100, bounds), onClick = {
                                    opener!!.open(Comic.create(100, "测试漫画", emptyList()), bounds.inRoot()) { nav.navigate("detail") }
                                }) { Text("source-cover") }
                            }
                        }
                        composable("detail") { entry ->
                            val origin = remember(entry.id) { origins.take(entry, 100) }
                            val parentReturn = LocalCoverReturnHost.current!!
                            val observedReturn = remember(parentReturn) { CoverReturnHost { frame, after ->
                                lastReturnFrame = frame
                                parentReturn.close(frame, after)
                            } }
                            CompositionLocalProvider(LocalCoverReturnHost provides observedReturn) {
                            CoverDetailTransition(entry.id, origin, onExit = { nav.popBackStack() }) {
                                GlassCaptureHost(
                                    sourceContent = {
                                        val entryControl = LocalDetailEntry.current
                                        val detailMotion = LocalDetailMotion.current
                                        val scroll = rememberScrollState()
                                        SideEffect { observedEntry = entryControl; observedMotion = detailMotion as? DetailMotion }
                                        val comments: @Composable () -> Unit = {
                                            Column(Modifier.detailEntryContent()) {
                                                MotionButton(onClick = { nav.navigate("reader") }) { Text("进入阅读") }
                                                repeat(30) { Text("comment-$it", Modifier.height(80.dp)) }
                                            }
                                        }
                                        if (LocalTabletLayoutEnabled.current) {
                                            BoxWithConstraints(Modifier.fillMaxSize().background(Color.White).testTag("detail-surface")) {
                                                val width = tabletDetailCoverWidth(maxWidth, maxHeight, 100.dp, 88.dp)
                                                Row(Modifier.fillMaxSize().padding(start = 16.dp, top = 100.dp, end = 16.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                                    Box(Modifier.width(width).aspectRatio(.75f).detailEntryCover()
                                                        .background(Color.Red).testTag("destination-cover"))
                                                    Column(Modifier.weight(1f).verticalScroll(scroll)) { comments() }
                                                }
                                            }
                                        } else {
                                            Column(Modifier.fillMaxSize().background(Color.White).testTag("detail-surface").verticalScroll(scroll)) {
                                                Box(Modifier.padding(top = 100.dp, start = 16.dp).size(180.dp, 240.dp)
                                                    .detailEntryCover().background(Color.Red).testTag("destination-cover"))
                                                comments()
                                            }
                                        }
                                    },
                                    overlayContent = { BackIconButton() },
                                )
                            }
                            }
                        }
                        composable("reader") {
                            Column { BackIconButton(); Text("reader-page") }
                        }
                    }
                }
            }
        }
    }

    @Test fun scrollingTheDetailCoverOffscreenRevealsThePreviousCoverWithoutAFlyingCopy() {
        compose.setContent { TestGraph() }
        val source = compose.onNodeWithText("source-cover").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("source-cover").performClick()
        compose.onNodeWithText("comment-25").performScrollTo()
        compose.onNodeWithTag("destination-cover").assertIsNotDisplayed()
        compose.onNodeWithContentDescription("返回上一页，长按返回首页").performClick()
        compose.waitUntil(5_000) {
            lastReturnFrame != null && compose.onAllNodesWithTag("cover-return-overlay").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("source-cover").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(null, lastReturnFrame!!.cover)
            assertEquals(null, lastReturnFrame!!.coverBounds)
        }
        assertEquals(source, compose.onNodeWithText("source-cover").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun removingTheSourceWhileInDetailStillCompletesTheReturn() {
        compose.setContent { TestGraph() }
        compose.onNodeWithText("source-cover").performClick()
        compose.onNodeWithTag("destination-cover").assertIsDisplayed()
        compose.runOnIdle { removeSource!!() }
        compose.onNodeWithContentDescription("返回上一页，长按返回首页").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("destination-cover").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag("cover-return-overlay").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("cover-return-overlay").assertDoesNotExist()
        compose.onNodeWithTag("destination-cover").assertDoesNotExist()
        compose.onNodeWithText("source-cover").assertDoesNotExist()
    }

    @Test fun introductionWaitsForTheCoverAndBackgroundAndReaderReturnDoesNotReplayEntry() {
        assumeTrue(motionEnabled())
        compose.mainClock.autoAdvance = false
        compose.setContent { TestGraph() }
        compose.onNodeWithText("source-cover").performClick()
        compose.mainClock.advanceTimeUntil(2_000) { observedEntry?.coverProgress?.let { it > 0f } == true }
        compose.runOnIdle {
            assertTrue(observedEntry!!.coverProgress < 1f)
            assertEquals(0f, observedEntry!!.contentProgress)
        }
        compose.mainClock.advanceTimeBy(CoverEnterMillis.toLong())
        compose.runOnIdle {
            assertEquals(1f, observedEntry!!.coverProgress)
            assertTrue(observedEntry!!.contentProgress < 1f)
        }
        compose.mainClock.advanceTimeBy(DetailContentEnterMillis + 32L)
        compose.runOnIdle { assertEquals(1f, observedEntry!!.contentProgress) }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("进入阅读").performClick()
        compose.onNodeWithContentDescription("返回上一页，长按返回首页").performClick()
        compose.onNodeWithTag("destination-cover").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1f, observedEntry!!.coverProgress)
            assertEquals(1f, observedEntry!!.contentProgress)
        }
    }

    @Test fun shortDragReturnsLongDragDismissesAndReaderReturnsToTheSameDetail() {
        compose.setContent { TestGraph() }
        compose.onNodeWithText("source-cover").performClick()
        compose.onNodeWithTag("detail-surface").assertIsDisplayed()
        compose.onNodeWithTag("detail-surface").performTouchInput {
            down(center)
            moveBy(Offset(0f, height * .04f), 200)
            moveBy(Offset(0f, height * .03f), 300)
            up()
        }
        compose.onNodeWithTag("detail-surface").assertIsDisplayed()
        compose.onNodeWithText("进入阅读").performClick()
        compose.onNodeWithText("reader-page").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回上一页，长按返回首页").performClick()
        compose.onNodeWithTag("detail-surface").assertIsDisplayed()
        compose.onNodeWithTag("detail-surface").performTouchInput {
            down(center)
            moveBy(Offset(0f, height * .04f), 200)
            moveBy(Offset(0f, height * .26f), 500)
            up()
        }
        compose.onNodeWithText("source-cover").assertIsDisplayed()
    }

    @Test fun arrivingAtScrollEdgesTicksOnceButProgrammaticScrollIsSilent() {
        var ticks = 0
        var reset: (() -> Unit)? = null
        var state: androidx.compose.foundation.ScrollState? = null
        compose.setContent {
            val scroll = rememberScrollState()
            state = scroll
            val scope = rememberCoroutineScope()
            reset = { scope.launch { scroll.scrollTo(0) } }
            MaterialTheme {
                CompositionLocalProvider(
                    LocalMainNavController provides rememberNavController(),
                    LocalExtendedColors provides extendedColorSchemeFor(MaterialTheme.colorScheme, false),
                ) {
                    ProvideScrollEdgeFeedback(feedback = { ticks++ }) {
                        GlassCaptureHost(
                            modifier = Modifier.fillMaxWidth().height(300.dp).testTag("scroll-root"),
                            sourceContent = {
                                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                                    repeat(12) { Text("row-$it", Modifier.height(80.dp)) }
                                }
                            },
                            overlayContent = {},
                        )
                    }
                }
            }
        }
        repeat(8) {
            var forward = false
            compose.runOnIdle { forward = state!!.canScrollForward }
            if (forward) compose.onNodeWithTag("scroll-root").performTouchInput { swipeUp(durationMillis = 600) }
        }
        compose.runOnIdle { assertEquals(1, ticks) }
        compose.onNodeWithTag("scroll-root").performTouchInput { swipeUp(durationMillis = 600) }
        compose.runOnIdle { assertEquals(2, ticks) }
        compose.runOnIdle { reset!!() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(2, ticks) }
        compose.onNodeWithTag("scroll-root").performTouchInput {
            swipe(start = Offset(centerX, height * .65f), end = Offset(centerX, height * .45f), durationMillis = 800)
        }
        compose.onNodeWithTag("scroll-root").performTouchInput { swipeDown(durationMillis = 800) }
        compose.runOnIdle { assertEquals(3, ticks) }
    }
}
