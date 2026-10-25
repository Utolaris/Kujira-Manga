package com.par9uet.jm.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.par9uet.jm.ui.screens.ComicDetailSectionSpacing
import com.par9uet.jm.ui.screens.PhoneDetailBackdrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PhoneDetailBackdropTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun backgroundIncludesGrowingTagsAndStopsBeforeComments() {
        val tagHeight = mutableStateOf(32.dp)
        compose.setContent {
            MaterialTheme {
                Column(verticalArrangement = Arrangement.spacedBy(ComicDetailSectionSpacing)) {
                    PhoneDetailBackdrop(
                        background = { Box(Modifier.matchParentSize().background(Color.Blue).testTag("backdrop")) },
                        header = { Box(Modifier.fillMaxWidth().height(120.dp).testTag("header")) },
                        tags = { Box(Modifier.fillMaxWidth().height(tagHeight.value).testTag("tags")) },
                    )
                    Box(Modifier.fillMaxWidth().height(100.dp).testTag("comments"))
                }
            }
        }
        for (height in listOf(32.dp, 128.dp, 16.dp)) {
            compose.runOnIdle { tagHeight.value = height }
            val background = compose.onNodeWithTag("backdrop").fetchSemanticsNode().boundsInRoot
            val header = compose.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
            val tags = compose.onNodeWithTag("tags").fetchSemanticsNode().boundsInRoot
            val comments = compose.onNodeWithTag("comments").fetchSemanticsNode().boundsInRoot
            assertEquals(header.top, background.top, 0.5f)
            assertEquals(tags.bottom, background.bottom, 0.5f)
            assertTrue("The backdrop must stop before the comments", background.bottom < comments.top)
        }
    }
}
