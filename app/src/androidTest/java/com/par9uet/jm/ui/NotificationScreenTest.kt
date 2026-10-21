package com.par9uet.jm.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.rememberNavController
import com.par9uet.jm.core.model.ConnectionModeStatus
import com.par9uet.jm.dinner.model.ArticleBlock
import com.par9uet.jm.dinner.model.ArticleText
import com.par9uet.jm.notification.NotificationSource
import com.par9uet.jm.notification.model.*
import com.par9uet.jm.session.AccountRequestScope
import com.par9uet.jm.session.UserSessionSnapshot
import com.par9uet.jm.ui.navigation.LocalMainNavController
import com.par9uet.jm.ui.screens.ComicSubscriptionButton
import com.par9uet.jm.ui.screens.NotificationScreen
import com.par9uet.jm.ui.theme.LocalExtendedColors
import com.par9uet.jm.ui.theme.extendedColorSchemeFor
import com.par9uet.jm.ui.viewModel.ComicTrackingState
import com.par9uet.jm.ui.viewModel.NotificationViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NotificationScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class Source : NotificationSource {
        override suspend fun unreadCounts() = NotificationUnreadCounts()
        val filters = mutableListOf<NotificationFilter>()
        var reads = 0
        var allReads = 0
        override suspend fun list(filter: NotificationFilter, page: Int): InboxPage {
            filters += filter
            val message = InboxMessage(
                "1", "维护公告", "2026-10-19 09:00", "site_notice", false,
                listOf(ArticleBlock.Text(listOf(ArticleText("公告正文")))),
            )
            return InboxPage(listOf(message), false)
        }
        override suspend fun markRead(id: String) { reads++ }
        override suspend fun markAllRead() { allReads++ }
        override suspend fun tracking(id: Int) = false
        override suspend fun toggleTracking(id: Int) = Unit
    }

    @Test fun cardShowsTitleAndTimeOpensBodyMarksReadAndTitleTapChangesFilter() {
        val source = Source()
        val account = object : AccountRequestScope {
            override val sessions = MutableStateFlow(UserSessionSnapshot(1, 1))
            override suspend fun <T> run(owner: UserSessionSnapshot, block: suspend () -> T): T = block()
        }
        val mode = object : ConnectionModeStatus {
            override val isLocalMode = false
            override val isLocalModeFlow = MutableStateFlow(false)
        }
        val vm = NotificationViewModel(source, account, mode, object : NotificationUnreadUpdates {
            override fun read(owner: UserSessionSnapshot, id: String, type: String) = Unit
            override fun allRead(owner: UserSessionSnapshot) = Unit
        })
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalMainNavController provides rememberNavController(),
                    LocalExtendedColors provides extendedColorSchemeFor(MaterialTheme.colorScheme, false),
                ) { NotificationScreen(vm) }
            }
        }
        compose.onNodeWithText("维护公告").assertIsDisplayed()
        compose.onNodeWithText("2026-10-19 09:00").assertIsDisplayed()
        compose.onNodeWithText("维护公告").performClick()
        compose.onNodeWithText("公告正文").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, source.reads) }
        compose.onNodeWithText("全部通知").performClick()
        compose.onNodeWithText("漫画追踪").performClick()
        compose.runOnIdle { assertEquals(NotificationFilter.Tracking, source.filters.last()) }
        compose.onNodeWithContentDescription("全部通知一键已读").performClick()
        compose.runOnIdle { assertEquals(1, source.allReads) }
    }

    @Test fun subscriptionPillOnlyAppearsForEligibleCollectionAndUsesSubscribedLabel() {
        compose.setContent {
            MaterialTheme {
                ComicSubscriptionButton(100, ComicTrackingState(id = 100, eligible = false)) { }
                ComicSubscriptionButton(200, ComicTrackingState(id = 200, eligible = true, subscribed = true)) { }
            }
        }
        compose.onNodeWithText("订阅").assertDoesNotExist()
        compose.onNodeWithText("已订阅").assertIsDisplayed()
    }
}
