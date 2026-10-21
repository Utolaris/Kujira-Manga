package com.par9uet.jm.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.par9uet.jm.data.models.ComicChapter
import com.par9uet.jm.ui.components.ChapterMultiSelectDialog
import com.par9uet.jm.ui.models.LocalTabletLayoutEnabled
import com.par9uet.jm.ui.navigation.LocalMainNavController
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChapterDialogViewportTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun longChapterListKeepsBothExportButtonsVisibleInAShortLandscapeWindow() {
        var confirmed = false
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalMainNavController provides rememberNavController(), LocalTabletLayoutEnabled provides true) {
                    Box(Modifier.requiredSize(650.dp, 360.dp)) {
                        ChapterMultiSelectDialog(
                            title = "选择导出章节",
                            chapters = (1..100).map { ComicChapter(id = it, name = "第${it}章") },
                            selectedChapterIds = setOf(1), onSelectedChange = {}, onDismiss = {},
                            confirmText = "合并", onConfirm = { confirmed = true },
                            secondaryConfirmText = "分章", onSecondaryConfirm = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("取消").assertIsDisplayed()
        compose.onNodeWithText("分章").assertIsDisplayed()
        compose.onNodeWithText("合并").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(confirmed) }
    }
}
