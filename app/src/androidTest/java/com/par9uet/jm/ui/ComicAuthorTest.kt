package com.par9uet.jm.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.ui.screens.ComicTitleAndAuthors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ComicAuthorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun unavailableAuthorShowsAnUnclickableUnknownLabel() {
        compose.setContent {
            MaterialTheme { ComicTitleAndAuthors(Comic.create(1, "漫画", listOf("N/A", " ")), {}) }
        }
        compose.onNodeWithText("未知作者").assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithText("N/A").assertDoesNotExist()
    }

    @Test fun knownAuthorsKeepTheirSearchAction() {
        var searched = ""
        compose.setContent {
            MaterialTheme { ComicTitleAndAuthors(Comic.create(1, "漫画", listOf("N/A", "作者")), { searched = it }) }
        }
        compose.onNodeWithText("作者").performClick()
        compose.runOnIdle { assertEquals("作者", searched) }
        compose.onNodeWithText("未知作者").assertDoesNotExist()
    }
}
