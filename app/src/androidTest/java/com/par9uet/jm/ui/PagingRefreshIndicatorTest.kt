package com.par9uet.jm.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.par9uet.jm.ui.components.PullRefreshAndLoadMoreGrid
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test

class PagingRefreshIndicatorTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val pendingLoad = AtomicReference<CompletableDeferred<Unit>?>(null)
    private val failure = AtomicReference<Exception?>(null)
    private lateinit var items: LazyPagingItems<String>

    @Test
    fun backgroundRefreshKeepsContentWithoutShowingThePullIndicator() {
        showGrid()
        compose.onNodeWithText("已有内容").assertIsDisplayed()
        val pending = CompletableDeferred<Unit>()
        pendingLoad.set(pending)

        compose.runOnIdle { items.refresh() }
        waitForRefreshLoading()

        compose.onNodeWithText("已有内容").assertIsDisplayed()
        compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertCountEquals(0)
        pending.complete(Unit)
        waitForRefreshComplete()
    }

    @Test
    fun userPullShowsTheIndicatorUntilRefreshCompletes() {
        showGrid()
        compose.onNodeWithText("已有内容").assertIsDisplayed()
        val pending = CompletableDeferred<Unit>()
        pendingLoad.set(pending)

        compose.onNodeWithTag("grid").performTouchInput { swipeDown() }
        waitForRefreshLoading()

        compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertCountEquals(1)
        pending.complete(Unit)
        waitForRefreshComplete()
        compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertCountEquals(0)
    }

    @Test
    fun failedUserPullStopsTheIndicatorAndKeepsCachedContent() {
        showGrid()
        val pending = CompletableDeferred<Unit>()
        pendingLoad.set(pending)
        failure.set(IOException("网络连接失败"))

        compose.onNodeWithTag("grid").performTouchInput { swipeDown() }
        waitForRefreshLoading()
        pending.complete(Unit)
        compose.waitUntil(5_000) { items.loadState.refresh is LoadState.Error }
        compose.waitForIdle()

        compose.onNodeWithText("网络连接失败").assertIsDisplayed()
        compose.onNodeWithText("已有内容").assertIsDisplayed()
        compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertCountEquals(0)
    }

    private fun showGrid() {
        val pager = Pager(PagingConfig(pageSize = 20)) {
            object : PagingSource<Int, String>() {
                override fun getRefreshKey(state: PagingState<Int, String>): Int? = null
                override suspend fun load(params: LoadParams<Int>): LoadResult<Int, String> {
                    pendingLoad.get()?.await()
                    failure.get()?.let { return LoadResult.Error(it) }
                    return LoadResult.Page(listOf("已有内容"), prevKey = null, nextKey = null)
                }
            }
        }.flow.cachedIn(compose.activity.lifecycleScope)
        compose.setContent {
            items = pager.collectAsLazyPagingItems()
            MaterialTheme {
                PullRefreshAndLoadMoreGrid(
                    modifier = Modifier.fillMaxSize().testTag("grid"),
                    lazyPagingItems = items,
                    key = { it },
                    columns = GridCells.Fixed(1),
                ) { Text(it) }
            }
        }
        waitForRefreshComplete()
    }

    private fun waitForRefreshLoading() {
        compose.waitUntil(5_000) { items.loadState.refresh is LoadState.Loading }
    }

    private fun waitForRefreshComplete() {
        compose.waitUntil(5_000) {
            items.loadState.refresh is LoadState.NotLoading && items.itemCount > 0
        }
        compose.waitForIdle()
    }
}
