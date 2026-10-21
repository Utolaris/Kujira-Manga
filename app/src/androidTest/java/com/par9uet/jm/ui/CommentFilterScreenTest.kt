package com.par9uet.jm.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.rememberNavController
import com.par9uet.jm.contentfilter.normalizeCommentKeywords
import com.par9uet.jm.data.models.DEFAULT_COMMENT_FILTER_KEYWORDS
import com.par9uet.jm.ui.navigation.LocalMainNavController
import com.par9uet.jm.ui.screens.CommentFilterContent
import com.par9uet.jm.ui.theme.LocalExtendedColors
import com.par9uet.jm.ui.theme.extendedColorSchemeFor
import org.junit.Rule
import org.junit.Test

class CommentFilterScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun defaultsCanBeRemovedNewWordsAreAddedAndEmptyRulesRemainEmpty() {
        var keywords by mutableStateOf(DEFAULT_COMMENT_FILTER_KEYWORDS)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalMainNavController provides rememberNavController(),
                    LocalExtendedColors provides extendedColorSchemeFor(MaterialTheme.colorScheme, false),
                ) {
                    CommentFilterContent(keywords, {
                        keywords = normalizeCommentKeywords(keywords + it)
                        true
                    }, { target -> keywords = keywords - target })
                }
            }
        }
        compose.onNodeWithContentDescription("添加关键词").assertIsNotEnabled()
        compose.onNodeWithText("输入过滤关键词").performTextInput("测试词")
        compose.onNodeWithContentDescription("添加关键词").performClick()
        compose.onNodeWithText("测试词").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("移除 VIP").performScrollTo().performClick()
        compose.onNodeWithText("VIP").assertDoesNotExist()
        compose.runOnIdle { keywords = emptyList() }
        compose.onNodeWithText("未设置关键词，当前不过滤评论。").performScrollTo().assertIsDisplayed()
    }

    @Test fun failedSaveKeepsTheDraftForRetry() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalMainNavController provides rememberNavController(),
                    LocalExtendedColors provides extendedColorSchemeFor(MaterialTheme.colorScheme, false),
                ) { CommentFilterContent(emptyList(), { false }, {}) }
            }
        }
        compose.onNodeWithText("输入过滤关键词").performTextInput("保留草稿")
        compose.onNodeWithContentDescription("添加关键词").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("保留草稿")
        compose.onNodeWithText("0 个").assertExists()
    }
}
