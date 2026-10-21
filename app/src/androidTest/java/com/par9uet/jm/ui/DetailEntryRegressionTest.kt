package com.par9uet.jm.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.par9uet.jm.ui.glass.GlassCaptureHost
import com.par9uet.jm.ui.glass.GlassModal
import com.par9uet.jm.ui.interaction.*
import com.par9uet.jm.ui.models.DetailEntryControl
import com.par9uet.jm.ui.models.LocalDetailEntry
import com.par9uet.jm.ui.navigation.CoverOrigin
import com.par9uet.jm.ui.navigation.LocalMainNavController
import com.par9uet.jm.ui.theme.LocalExtendedColors
import com.par9uet.jm.ui.theme.extendedColorSchemeFor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DetailEntryRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Composable private fun Theme(content: @Composable () -> Unit) {
        MaterialTheme {
            CompositionLocalProvider(
                LocalMainNavController provides rememberNavController(),
                LocalExtendedColors provides extendedColorSchemeFor(MaterialTheme.colorScheme, false),
                content = content,
            )
        }
    }

    @Test fun directEntryShowsLoadingImmediatelyWithoutWaitingForACoverOrRunningAnExpansion() {
        var entry: DetailEntryControl? = null
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Theme {
                CoverDetailTransition("direct", null, {}) {
                    val control = LocalDetailEntry.current
                    SideEffect { entry = control }
                    Text("正在加载详情", Modifier.detailEntryContent())
                }
            }
        }
        compose.onNodeWithText("正在加载详情").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1f, entry!!.coverProgress)
            assertEquals(1f, entry!!.contentProgress)
            assertFalse(entry!!.coverInFlight)
        }
    }

    @Test fun aLoadedThumbnailSurvivesAnUnreadyDestinationEvenAfterTheFlightCompletes() {
        var ready by mutableStateOf(false)
        compose.setContent {
            Theme {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val size = IntSize(constraints.maxWidth, constraints.maxHeight)
                    val image = remember(size) { ImageBitmap(size.width, size.height).apply {
                        asAndroidBitmap().eraseColor(android.graphics.Color.BLUE)
                    } }
                    val origin = remember(size) { CoverOrigin(Rect(20f, 80f, 140f, 240f), image, size) }
                    CoverDetailTransition("thumbnail", origin, {}) {
                        GlassCaptureHost(sourceContent = {
                            Box(Modifier.fillMaxSize().background(Color.White)) {
                                Box(Modifier.padding(top = 100.dp, start = 20.dp).size(180.dp, 240.dp)
                                    .detailEntryCover(ready).background(if (ready) Color.Red else Color.Gray)
                                    .testTag("destination"))
                            }
                        }, overlayContent = {})
                    }
                }
            }
        }
        compose.waitForIdle()
        var pixels = compose.onNodeWithTag("destination").captureToImage().toPixelMap()
        assertEquals(Color.Blue, pixels[pixels.width / 2, pixels.height / 2])
        compose.runOnIdle { ready = true }
        compose.waitForIdle()
        pixels = compose.onNodeWithTag("destination").captureToImage().toPixelMap()
        assertEquals(Color.Red, pixels[pixels.width / 2, pixels.height / 2])
    }

    @Test fun selectingAndDraggingTheFullTitleModalCannotMoveTheDetailPage() {
        compose.setContent {
            Theme {
                CoverDetailTransition("title", null, {}) {
                    var show by remember { mutableStateOf(false) }
                    BlockDetailDrag(show)
                    GlassCaptureHost(modifier = Modifier.fillMaxSize().testTag("detail-page"), sourceContent = {
                        Box(Modifier.fillMaxSize()) {
                            Text("漫画标题", Modifier.padding(40.dp).clickable { show = true })
                        }
                    }, overlayContent = {
                        GlassModal(show, { show = false }) {
                            SelectionContainer { Text("完整标题", Modifier.padding(30.dp)) }
                        }
                    })
                }
            }
        }
        compose.onNodeWithText("漫画标题").performClick()
        compose.onNodeWithText("完整标题").assertIsDisplayed()
        val before = compose.onNodeWithTag("detail-page").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("detail-page").performTouchInput {
            down(center); advanceEventTime(600); moveBy(Offset(80f, 150f))
        }
        val during = compose.onNodeWithTag("detail-page").fetchSemanticsNode().boundsInRoot
        assertEquals(before, during)
        compose.onNodeWithTag("detail-page").performTouchInput { up() }
    }
}
