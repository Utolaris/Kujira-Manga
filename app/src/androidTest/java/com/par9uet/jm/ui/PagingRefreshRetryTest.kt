package com.par9uet.jm.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.lifecycleScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicSearchOrderFilter
import com.par9uet.jm.data.models.ComicSearchPage
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.ui.components.PullRefreshAndLoadMoreGrid
import com.par9uet.jm.ui.pagingSource.SearchComicFilter
import com.par9uet.jm.ui.pagingSource.SearchComicPagingSource
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference
import org.junit.Rule
import org.junit.Test

class PagingRefreshRetryTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val failure = AtomicReference<Exception?>(null)
    private lateinit var refreshItems: () -> Unit

    @Test
    fun refreshFailureKeepsCachedItemsVisibleAndCanRetry() {
        showPagedGrid()
        waitForText("已有条目")

        failure.set(IOException("网络连接失败"))
        compose.runOnIdle { refreshItems() }

        waitForText("网络连接失败")
        compose.onNodeWithText("已有条目").assertIsDisplayed()
        compose.onNodeWithText("没有更多数据了").assertDoesNotExist()
        failure.set(null)
        compose.onNodeWithText("重试").performClick()
        waitForErrorToClear()
        compose.onNodeWithText("已有条目").assertIsDisplayed()
    }

    @Test
    fun firstLoadFailureShowsRetryAndRecoveryDisplaysItems() {
        failure.set(IOException("网络连接失败"))
        showPagedGrid()

        waitForText("网络连接失败")
        compose.onNodeWithText("已有条目").assertDoesNotExist()
        failure.set(null)
        compose.onNodeWithText("重试").performClick()
        waitForText("已有条目")
        compose.onNodeWithText("网络连接失败").assertDoesNotExist()
    }

    private fun showPagedGrid() {
        val unused = Proxy.newProxyInstance(
            ComicRepository::class.java.classLoader,
            arrayOf(ComicRepository::class.java),
        ) { _, method, _ -> error("Unexpected request: ${method.name}") } as ComicRepository
        val repository = object : ComicRepository by unused {
            override suspend fun getComicList(
                page: Int,
                order: ComicSearchOrderFilter,
                searchContent: String,
                year: String,
                month: String,
            ): NetWorkResult<ComicSearchPage> {
                failure.get()?.let { throw it }
                return NetWorkResult.Success(
                    ComicSearchPage(
                        items = listOf(Comic.create(1, "已有条目", emptyList())),
                        total = 1,
                    ),
                )
            }
        }
        val pager = Pager(PagingConfig(pageSize = 20)) {
            SearchComicPagingSource(repository, SearchComicFilter(searchContent = "关键词"))
        }.flow.cachedIn(compose.activity.lifecycleScope)
        compose.setContent {
            val items = pager.collectAsLazyPagingItems()
            // Tests the production paging grid; app navigation/resume is a separate scope.
            refreshItems = items::refresh
            MaterialTheme {
                PullRefreshAndLoadMoreGrid(
                    lazyPagingItems = items,
                    key = { it.id },
                    columns = GridCells.Fixed(1),
                    enablePullRefresh = false,
                ) { Text(it.name) }
            }
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun waitForErrorToClear() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("网络连接失败").fetchSemanticsNodes().isEmpty()
        }
    }
}
