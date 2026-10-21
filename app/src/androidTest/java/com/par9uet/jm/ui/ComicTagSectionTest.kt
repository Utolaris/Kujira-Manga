package com.par9uet.jm.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.ui.navigation.LocalMainNavController
import com.par9uet.jm.ui.screens.ComicTagSection
import org.junit.Rule
import org.junit.Test

class ComicTagSectionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun blankTagsRolesAndWorksInExistingCachedComicsDoNotCreateEmptyChips() {
        compose.setContent {
            MaterialTheme {
                ComicTagSection(
                    comic = Comic.create(1468134, "测试漫画", emptyList()).copy(
                        tagList = listOf("", " \n\t"), roleList = listOf("\u3000"), workList = listOf("\r\n"),
                    ),
                    modifier = Modifier.width(320.dp).testTag("tags"),
                )
            }
        }
        compose.onNodeWithTag("tags").assertHeightIsEqualTo(0.dp)
    }

    @Test
    fun loadingUsesOneRowAndFailureRemovesThePlaceholder() {
        val loading = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                ComicTagSection(
                    comic = Comic.create(1, "测试", emptyList()),
                    isLoading = loading.value,
                    modifier = Modifier.width(320.dp).testTag("tags"),
                )
            }
        }

        compose.onNodeWithTag("tags").assertHeightIsEqualTo(32.dp)
        compose.runOnIdle { loading.value = false }
        compose.onNodeWithTag("tags").assertHeightIsEqualTo(0.dp)
    }

    @Test
    fun replacingSeedTagsWithTheSameRowCountRevealsEveryNewRow() {
        val comic = mutableStateOf(taggedComic("初始"))
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalMainNavController provides rememberNavController()) {
                    Box(Modifier.width(100.dp)) {
                        ComicTagSection(comic.value)
                    }
                }
            }
        }

        compose.onNodeWithText("初始四").assertIsDisplayed()
        compose.runOnIdle { comic.value = taggedComic("完整") }
        compose.onNodeWithText("完整一").assertIsDisplayed()
        compose.onNodeWithText("完整四").assertIsDisplayed()
    }

    private fun taggedComic(prefix: String): Comic =
        Comic.create(1, "测试", emptyList()).copy(
            tagList = listOf("一", "二", "三", "四").map { prefix + it },
        )
}
