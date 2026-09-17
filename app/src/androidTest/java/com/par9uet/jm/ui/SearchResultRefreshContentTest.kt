package com.par9uet.jm.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import com.par9uet.jm.ui.screens.SearchResultRefreshContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SearchResultRefreshContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun failedRefreshHidesRetainedResultsAndOffersRetry() {
        val refresh = mutableStateOf<LoadState>(LoadState.NotLoading(false))
        var retries = 0
        compose.setContent {
            MaterialTheme {
                SearchResultRefreshContent(
                    refreshState = refresh.value,
                    // 0 项 + Loading 才走骨架：这条用例末尾依赖「刷新时不再展示上次的结果」。
                    itemCount = 0,
                    topContentPadding = 64.dp,
                    bottomContentPadding = 16.dp,
                    onRetry = { retries++ },
                ) { Text("上次搜索的缓存结果") }
            }
        }
        compose.onNodeWithText("上次搜索的缓存结果").assertIsDisplayed()
        for (message in listOf("网络连接失败", "登录会话已失效，请重新登录")) {
            compose.runOnIdle { refresh.value = LoadState.Error(Exception(message)) }
            compose.onNodeWithText("上次搜索的缓存结果").assertDoesNotExist()
            compose.onNodeWithText(message).assertIsDisplayed()
            compose.onNodeWithText("重试").performClick()
        }
        compose.runOnIdle {
            assertEquals(2, retries)
            refresh.value = LoadState.Loading
        }
        compose.onNodeWithText("上次搜索的缓存结果").assertDoesNotExist()
        compose.runOnIdle { refresh.value = LoadState.NotLoading(false) }
        compose.onNodeWithText("上次搜索的缓存结果").assertIsDisplayed()
    }

    @Test
    fun loadingWithCachedItemsKeepsResultsVisible() {
        val refresh = mutableStateOf<LoadState>(LoadState.Loading)
        compose.setContent {
            MaterialTheme {
                SearchResultRefreshContent(
                    refreshState = refresh.value,
                    itemCount = 2,
                    topContentPadding = 64.dp,
                    bottomContentPadding = 16.dp,
                    onRetry = {},
                ) { Text("上次搜索的缓存结果") }
            }
        }
        // 已有缓存项时 Loading（下拉刷新/返回重连）不得闪骨架。
        compose.onNodeWithText("上次搜索的缓存结果").assertIsDisplayed()
    }
}
