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
import com.par9uet.jm.data.models.ComicPage
import com.par9uet.jm.session.UserRepository
import com.par9uet.jm.ui.components.PullRefreshAndLoadMoreGrid
import com.par9uet.jm.ui.pagingSource.HistoryComicPagingSource
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference
import org.junit.Rule
import org.junit.Test

class PagingRefreshRetryTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val failure = AtomicReference<Exception?>(null)
    private lateinit var refreshHistory: () -> Unit

    @Test
    fun refreshFailureKeepsCachedHistoryVisibleAndCanRetry() {
        showHistory()
        waitForText("已有历史")

        failure.set(IOException("网络连接失败"))
        compose.runOnIdle { refreshHistory() }

        waitForText("网络连接失败")
        compose.onNodeWithText("已有历史").assertIsDisplayed()
        compose.onNodeWithText("没有更多数据了").assertDoesNotExist()
        failure.set(null)
        compose.onNodeWithText("重试").performClick()
        waitForErrorToClear()
        compose.onNodeWithText("已有历史").assertIsDisplayed()
    }

    @Test
    fun firstLoadFailureShowsRetryAndRecoveryDisplaysHistory() {
        failure.set(IOException("网络连接失败"))
        showHistory()

        waitForText("网络连接失败")
        compose.onNodeWithText("已有历史").assertDoesNotExist()
        failure.set(null)
        compose.onNodeWithText("重试").performClick()
        waitForText("已有历史")
        compose.onNodeWithText("网络连接失败").assertDoesNotExist()
    }

    private fun showHistory() {
        val unused = Proxy.newProxyInstance(
            UserRepository::class.java.classLoader,
            arrayOf(UserRepository::class.java),
        ) { _, method, _ -> error("Unexpected request: ${method.name}") } as UserRepository
        val repository = object : UserRepository by unused {
            override suspend fun getHistoryComicList(page: Int): NetWorkResult<ComicPage> {
                failure.get()?.let { throw it }
                return NetWorkResult.Success(ComicPage(listOf(Comic.create(1, "已有历史", emptyList()))))
            }
        }
        val pager = Pager(PagingConfig(pageSize = 20)) {
            HistoryComicPagingSource(repository)
        }.flow.cachedIn(compose.activity.lifecycleScope)
        compose.setContent {
            val items = pager.collectAsLazyPagingItems()
            // Tests the production paging grid; app navigation/resume is a separate scope.
            refreshHistory = items::refresh
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
