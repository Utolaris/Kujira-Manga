package com.par9uet.jm.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.par9uet.jm.ui.components.FavoriteSyncIconButton
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FavoriteSyncIndicatorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun completedSyncShowsACheckAndCanStillBeSyncedAgain() {
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                FavoriteSyncIconButton(false, false, { clicks++ }, hasSynced = true)
            }
        }
        compose.onNodeWithContentDescription("收藏夹同步完成").assertIsDisplayed().performClick()
        assertEquals(1, clicks)
    }

    @Test fun localModeAlwaysShowsItsExactPendingCountIncludingZeroAndUnknown() {
        var count by mutableStateOf<Int?>(12)
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                FavoriteSyncIconButton(
                    false, true, { clicks++ }, hasSynced = true, isLocalMode = true, pendingComicCount = count,
                )
            }
        }
        compose.onNodeWithText("12").assertIsDisplayed().performClick()
        assertEquals(1, clicks)
        compose.runOnIdle { count = 0 }
        compose.onNodeWithText("0").assertIsDisplayed()
        compose.runOnIdle { count = null }
        compose.onNodeWithText("—").assertIsDisplayed()
    }

    @Test fun syncingLocalCountRemainsVisibleAndCannotStartAnotherSync() {
        compose.setContent {
            MaterialTheme {
                FavoriteSyncIconButton(true, false, {}, isLocalMode = true, pendingComicCount = 3)
            }
        }
        compose.onNodeWithContentDescription("正在同步收藏夹，剩余 3 部漫画未同步到云端")
            .assertIsDisplayed().assertIsNotEnabled()
    }
}
