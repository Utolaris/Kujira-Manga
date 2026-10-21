package com.par9uet.jm.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.rememberNavController
import com.par9uet.jm.core.model.ConnectionModeStatus
import com.par9uet.jm.dinner.DinnerSource
import com.par9uet.jm.dinner.model.*
import com.par9uet.jm.ui.components.ArticleBlockContent
import com.par9uet.jm.ui.navigation.LocalMainNavController
import com.par9uet.jm.ui.screens.DinnerArticleScreen
import com.par9uet.jm.ui.viewModel.DinnerViewModel
import com.par9uet.jm.ui.theme.LocalExtendedColors
import com.par9uet.jm.ui.theme.extendedColorSchemeFor
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DinnerArticleRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val settings = object : com.par9uet.jm.storage.MiscSettingsPreferences {
        override val misc = MutableStateFlow(com.par9uet.jm.storage.MiscSettingsState())
    }
    @Test fun nativeBlocksShowFormattedTextTablesAndCode() {
        compose.setContent {
            MaterialTheme {
                Column {
                    ArticleBlockContent(ArticleBlock.Text(listOf(ArticleText("文章标题", bold = true)), heading = 2))
                    ArticleBlockContent(ArticleBlock.Text(listOf(ArticleText("段落文字"), ArticleText("链接", link = "https://example.com"))))
                    ArticleBlockContent(ArticleBlock.Table(listOf(listOf("列名"), listOf("单元格"))))
                    ArticleBlockContent(ArticleBlock.Code("print(1)"))
                }
            }
        }
        compose.onNodeWithText("文章标题").assertIsDisplayed()
        compose.onNodeWithText("段落文字链接").assertIsDisplayed()
        compose.onNodeWithText("单元格").assertIsDisplayed()
        compose.onNodeWithText("print(1)").assertIsDisplayed()
    }

    @Test fun failedArticleOffersRetryAndThenShowsTheBody() {
        var calls = 0
        val source = object : DinnerSource {
            override suspend fun list(page: Int) = DinnerPage(emptyList(), 0)
            override suspend fun article(id: Int): DinnerArticle {
                calls++
                if (calls == 1) error("测试断网")
                return DinnerArticle(id, "恢复后的文章", "", listOf(ArticleBlock.Text(listOf(ArticleText("恢复后的正文")))))
            }
        }
        val mode = object : ConnectionModeStatus {
            override val isLocalMode = false
            override val isLocalModeFlow = MutableStateFlow(false)
        }
        val vm = DinnerViewModel(source, mode, settings)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalMainNavController provides rememberNavController(),
                    LocalExtendedColors provides extendedColorSchemeFor(MaterialTheme.colorScheme, false),
                ) {
                    DinnerArticleScreen(7, vm)
                }
            }
        }
        compose.onNodeWithText("测试断网").assertIsDisplayed()
        compose.onNodeWithText("重新加载文章").performClick()
        compose.onNodeWithText("恢复后的文章").assertIsDisplayed()
        compose.onNodeWithText("恢复后的正文").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, calls) }
    }
}
