package com.par9uet.jm.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.par9uet.jm.dinner.model.DinnerSummary
import com.par9uet.jm.ui.glass.GlassCaptureHost
import com.par9uet.jm.ui.models.ListPositionKeyDinner
import com.par9uet.jm.ui.models.ListPositionMemory
import com.par9uet.jm.ui.interaction.LocalListPositionMemory
import com.par9uet.jm.ui.navigation.LocalMainNavController
import com.par9uet.jm.ui.screens.DinnerArticleGrid
import com.par9uet.jm.ui.theme.LocalExtendedColors
import com.par9uet.jm.ui.theme.extendedColorSchemeFor
import com.par9uet.jm.ui.viewModel.DinnerListState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DinnerNavigationAndPagingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Composable private fun Theme(memory: ListPositionMemory, content: @Composable () -> Unit) {
        MaterialTheme {
            CompositionLocalProvider(
                LocalMainNavController provides rememberNavController(),
                LocalExtendedColors provides extendedColorSchemeFor(MaterialTheme.colorScheme, false),
                LocalListPositionMemory provides memory,
                content = content,
            )
        }
    }

    @Composable private fun Cover(article: DinnerSummary, open: () -> Unit) {
        Box(Modifier.fillMaxWidth().aspectRatio(if (article.id % 3 == 0) .6f else 1.7f)
            .background(Color.Blue).clickable(onClick = open).testTag("article-cover-${article.id}"))
    }

    private fun articles(count: Int) = (1..count).map { DinnerSummary(it, "文章$it", null, "") }

    @Test fun coverOpensTheBodyAndReturningThroughANewGlassHostRestoresTheWaterfallLanes() {
        val memory = ListPositionMemory()
        var showList by mutableStateOf(true)
        compose.setContent {
            Theme(memory) {
                Box(Modifier.fillMaxWidth().height(500.dp)) {
                    if (showList) {
                        GlassCaptureHost(sourceContent = {
                            DinnerArticleGrid(DinnerListState(articles(40), page = 4), 2, PaddingValues(),
                                onOpen = { showList = false }, onLoad = {}, cover = { article, open -> Cover(article, open) })
                        }, overlayContent = {})
                    } else Column {
                        Text("文章正文")
                        TextButton(onClick = { showList = true }) { Text("返回食堂") }
                    }
                }
            }
        }
        compose.onNodeWithTag("dinner-grid").performScrollToIndex(20)
        val before = listOf(21, 22).map { compose.onNodeWithTag("article-cover-$it").fetchSemanticsNode().boundsInRoot }
        compose.onNodeWithTag("article-cover-21").performTouchInput { click() }
        compose.onNodeWithText("文章正文").assertIsDisplayed()
        compose.runOnIdle { assertNotNull(memory.staggeredPosition(ListPositionKeyDinner)) }
        compose.onNodeWithText("返回食堂").performClick()
        val after = listOf(21, 22).map { compose.onNodeWithTag("article-cover-$it").fetchSemanticsNode().boundsInRoot }
        before.zip(after).forEach { (a, b) ->
            assertEquals(a.left, b.left, 1f)
            assertEquals(a.top, b.top, 1f)
            assertEquals(a.height, b.height, 1f)
        }
    }

    @Test fun reachingTheFooterLoadsAutomaticallyOnceAndNetworkErrorsWaitForExplicitRetry() {
        var calls = 0
        var state by mutableStateOf(DinnerListState(articles(12), page = 1, hasMore = true))
        val memory = ListPositionMemory()
        compose.setContent {
            Theme(memory) {
                Box(Modifier.fillMaxWidth().height(320.dp)) {
                    DinnerArticleGrid(state, 2, PaddingValues(), onOpen = {}, onLoad = {
                        calls++
                        state = state.copy(loading = true, error = null)
                    }, cover = { article, open -> Cover(article, open) })
                }
            }
        }
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithTag("dinner-grid").performScrollToIndex(12)
        compose.runOnIdle { assertEquals(1, calls) }
        compose.onNodeWithText("加载更多文章").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(loading = false, error = "测试断网") }
        compose.onNodeWithText("测试断网").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, calls) }
        compose.onNodeWithText("重试").performClick()
        compose.runOnIdle {
            assertEquals(2, calls)
            state = DinnerListState(articles(24), page = 2, hasMore = false)
        }
        compose.onNodeWithTag("dinner-grid").performScrollToIndex(24)
        compose.runOnIdle { assertEquals(2, calls) }
    }
}
