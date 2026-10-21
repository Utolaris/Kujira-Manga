package com.par9uet.jm.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.par9uet.jm.dinner.model.DinnerSummary
import com.par9uet.jm.ui.screens.DinnerArticleGrid
import com.par9uet.jm.ui.viewModel.DinnerListState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DinnerGridTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun unequalCoversFillTheShortestColumnAndColumnChangesReflowWithoutBreakingOpen() {
        var columns by mutableIntStateOf(2)
        var opened = 0
        var loads = 0
        val articles = (1..3).map { DinnerSummary(it, "文章$it", null, "2026-10-19") }
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(340.dp).height(700.dp)) {
                    DinnerArticleGrid(DinnerListState(items = articles, page = 1, hasMore = false),
                        columns, PaddingValues(), onOpen = { opened = it }, onLoad = { loads++ },
                        cover = { article, open ->
                            Box(Modifier.fillMaxWidth().aspectRatio(if (article.id == 1) .6f else 2f)
                                .background(Color.Blue).clickable(onClick = open).testTag("cover-${article.id}"))
                        })
                }
            }
        }
        val tall = compose.onNodeWithTag("cover-1").fetchSemanticsNode().boundsInRoot
        val short = compose.onNodeWithTag("cover-2").fetchSemanticsNode().boundsInRoot
        val next = compose.onNodeWithTag("cover-3").fetchSemanticsNode().boundsInRoot
        assertEquals(tall.top, short.top, 1f)
        assertEquals(short.left, next.left, 1f)
        assertTrue(next.top < tall.bottom)
        assertEquals(.6f, tall.width / tall.height, .01f)
        assertEquals(2f, short.width / short.height, .01f)
        val grid = compose.onNodeWithTag("dinner-grid").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("dinner-list-status").fetchSemanticsNode().boundsInRoot
        assertEquals(grid.width, footer.width, 1f)
        compose.onNodeWithTag("cover-3").performTouchInput { click() }
        compose.runOnIdle { assertEquals(3, opened); assertEquals(0, loads); columns = 3 }
        val reflowed = (1..3).map { compose.onNodeWithTag("cover-$it").fetchSemanticsNode().boundsInRoot }
        assertEquals(3, reflowed.map { it.left }.distinct().size)
        reflowed.forEach { assertEquals(reflowed.first().top, it.top, 1f) }
    }
}
