package com.par9uet.jm.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.navigation.compose.rememberNavController
import com.par9uet.jm.data.models.TagFilterLogic
import com.par9uet.jm.favorites.model.FavoritesFilterPreview
import com.par9uet.jm.ui.navigation.LocalMainNavController
import com.par9uet.jm.ui.models.LocalTabletLayoutEnabled
import com.par9uet.jm.ui.screens.FavoritesFilterContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FavoritesFilterTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun swipeSwitchesCategoriesAndClearsThePreviousCategorySearch() {
        content()
        compose.onNodeWithText("搜索标签").performTextInput("科幻")
        compose.onNodeWithTag("favorites-filter-pager").performTouchInput { swipeLeft() }
        compose.onNodeWithText("搜索作者").assertIsDisplayed()
        compose.onNodeWithText("林间工作室  12").assertIsDisplayed()
        compose.onNodeWithTag("favorites-filter-pager").performTouchInput { swipeRight() }
        compose.onNodeWithText("日常  36").assertIsDisplayed()
        compose.onNodeWithText("作者 (1)").performClick()
        compose.onNodeWithText("搜索作者").assertIsDisplayed()
    }

    @Test fun selectedTermsOnlyShowInTheTermListAndZeroMatchesRemainExplicit() {
        var tags by mutableStateOf(setOf("科幻"))
        var authors by mutableStateOf(setOf("林间工作室"))
        content(tags = { tags }, authors = { authors },
            onTagToggle = { tags = tags - it }, onAuthorToggle = { authors = authors - it })
        compose.onNodeWithText("匹配 0 部漫画").assertIsDisplayed()
        compose.onNodeWithText("科幻", substring = false).assertDoesNotExist()
        compose.onNodeWithText("林间工作室", substring = false).assertDoesNotExist()
        compose.onNodeWithText("科幻  42").assertIsSelected().performClick().assertIsNotSelected()
        compose.onNodeWithText("作者 (1)").performClick()
        compose.onNodeWithText("林间工作室  12").assertIsSelected().performClick().assertIsNotSelected()
        compose.onNodeWithText("匹配 0 部漫画").assertIsDisplayed()
    }

    @Test fun modesAreLargeEqualWidthAndStayAboveTheSearchAndScrollableTerms() {
        content(manyTerms = true)
        val modes = listOf("同时包含", "包含任意", "不包含").map {
            compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot
        }
        val search = compose.onNodeWithText("搜索标签").fetchSemanticsNode().boundsInRoot
        modes.forEach {
            org.junit.Assert.assertTrue(it.height >= with(compose.density) { 48.dp.toPx() })
            org.junit.Assert.assertTrue(it.bottom < search.top)
            assertEquals(modes.first().width, it.width, 1f)
        }
        compose.onNodeWithTag("favorites-filter-pager").performTouchInput { swipeUp() }
        assertEquals(modes.first(), compose.onNodeWithText("同时包含").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun floatingActionsKeepTheirPositionAndRemainClickableWhileTermsScroll() {
        var confirmed = 0
        var cleared = 0
        content(manyTerms = true, onConfirm = { confirmed++ }, onClear = { cleared++ })
        val before = compose.onNodeWithText("确定").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("favorites-filter-pager").performTouchInput { swipeUp() }
        assertEquals(before, compose.onNodeWithText("确定").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("确定").assertIsDisplayed().performClick()
        compose.onNodeWithText("清空").assertIsDisplayed().performClick()
        assertEquals(1, confirmed)
        assertEquals(1, cleared)
    }

    @Test fun tabletKeepsCategoryAndModeWhenTheWindowNarrows() {
        var viewport by mutableStateOf(DpSize(360.dp, 640.dp))
        var logic by mutableStateOf(TagFilterLogic.AND)
        content(tablet = true, viewport = { viewport }, logic = { logic }, onLogicChange = { logic = it })
        compose.onNodeWithText("不包含").performClick().assertIsSelected()
        compose.onNodeWithTag("favorites-filter-pager").performTouchInput { swipeLeft() }
        compose.onNodeWithText("搜索作者").assertIsDisplayed()
        compose.runOnIdle { viewport = DpSize(280.dp, 480.dp) }
        compose.onNodeWithText("搜索作者").assertIsDisplayed()
        compose.onNodeWithText("匹配 0 部漫画").assertIsDisplayed()
        compose.onNodeWithText("不包含").assertIsSelected()
        compose.onNodeWithText("清空").assertIsDisplayed()
        compose.onNodeWithText("确定").assertIsDisplayed()
        compose.onNodeWithTag("favorites-filter-pager").performTouchInput { swipeRight() }
        compose.onNodeWithText("搜索标签").assertIsDisplayed()
    }

    private fun content(
        tags: () -> Set<String> = { emptySet() },
        authors: () -> Set<String> = { emptySet() },
        onTagToggle: (String) -> Unit = {},
        onAuthorToggle: (String) -> Unit = {},
        manyTerms: Boolean = false,
        onConfirm: () -> Unit = {},
        onClear: () -> Unit = {},
        tablet: Boolean = false,
        viewport: () -> DpSize = { DpSize(360.dp, 640.dp) },
        logic: () -> TagFilterLogic = { TagFilterLogic.AND },
        onLogicChange: (TagFilterLogic) -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalMainNavController provides rememberNavController(),
                    LocalTabletLayoutEnabled provides tablet,
                ) {
                    Box(Modifier.width(viewport().width).height(viewport().height)) {
                        FavoritesFilterContent(
                            tagCountMap = if (manyTerms) (1..80).associate { "标签 $it" to it }
                                else linkedMapOf("科幻" to 42, "日常" to 36),
                            authorCountMap = mapOf("林间工作室" to 12),
                            selectedTags = tags(), selectedAuthors = authors(), tagLogic = logic(),
                            preview = FavoritesFilterPreview(count = 0),
                            onTagToggle = onTagToggle, onAuthorToggle = onAuthorToggle,
                            onTagLogicChange = onLogicChange, onConfirm = onConfirm, onClear = onClear, onDismiss = {},
                        )
                    }
                }
            }
        }
    }
}
