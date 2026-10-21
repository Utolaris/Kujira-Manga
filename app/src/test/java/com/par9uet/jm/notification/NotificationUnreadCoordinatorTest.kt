package com.par9uet.jm.notification

import com.par9uet.jm.core.model.ConnectionModeStatus
import com.par9uet.jm.notification.model.*
import com.par9uet.jm.session.AccountRequestScope
import com.par9uet.jm.session.UserSessionSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationUnreadCoordinatorTest {
    private val account = object : AccountRequestScope {
        override val sessions = MutableStateFlow(UserSessionSnapshot(1, 1))
        override suspend fun <T> run(owner: UserSessionSnapshot, block: suspend () -> T): T = block()
    }
    private val mode = object : ConnectionModeStatus {
        override val isLocalModeFlow = MutableStateFlow(false)
        override val isLocalMode get() = isLocalModeFlow.value
    }
    private class Source : NotificationSource {
        var calls = 0
        var counts: suspend () -> NotificationUnreadCounts = { NotificationUnreadCounts(1, 2) }
        override suspend fun unreadCounts(): NotificationUnreadCounts { calls++; return counts() }
        override suspend fun list(filter: NotificationFilter, page: Int): InboxPage = error("Counts must not fetch bodies")
        override suspend fun markRead(id: String) = Unit
        override suspend fun markAllRead() = Unit
        override suspend fun tracking(id: Int) = false
        override suspend fun toggleTracking(id: Int) = Unit
    }

    @Test fun `cold check runs once and reads decrement both categories without polling`() = runTest {
        val source = Source()
        val badge = NotificationUnreadCoordinator(source, account, mode, backgroundScope)
        assertFalse(badge.hasUnread.value)
        badge.checkOnce(); badge.checkOnce()
        assertTrue(badge.hasUnread.value)
        val owner = account.sessions.value
        badge.read(owner, "a", "comic_follow")
        badge.read(owner, "a", "comic_follow")
        badge.read(owner, "b", "site_notice")
        assertTrue(badge.hasUnread.value)
        badge.read(owner, "c", "site_notice")
        assertFalse(badge.hasUnread.value)
        badge.checkOnce()
        assertEquals(1, source.calls)
    }

    @Test fun `mark all clears badge and a late cold response cannot relight it`() = runTest {
        val source = Source()
        val pending = CompletableDeferred<NotificationUnreadCounts>()
        source.counts = { pending.await() }
        val badge = NotificationUnreadCoordinator(source, account, mode, backgroundScope)
        val check = launch { badge.checkOnce() }
        runCurrent()
        badge.allRead(account.sessions.value)
        pending.complete(NotificationUnreadCounts(10, 5)); check.join()
        assertFalse(badge.hasUnread.value)
        assertEquals(1, source.calls)
    }

    @Test fun `account switch resets counts and ignores old account reads and responses`() = runTest {
        val source = Source()
        val badge = NotificationUnreadCoordinator(source, account, mode, backgroundScope)
        badge.checkOnce()
        assertTrue(badge.hasUnread.value)
        val old = account.sessions.value
        account.sessions.value = UserSessionSnapshot(2, 2); runCurrent()
        assertFalse(badge.hasUnread.value)
        badge.read(old, "a", "site_notice"); badge.allRead(old)
        badge.checkOnce()
        assertEquals(1, source.calls)

        val pending = CompletableDeferred<NotificationUnreadCounts>()
        source.counts = { pending.await() }
        val newBadge = NotificationUnreadCoordinator(source, account, mode, backgroundScope)
        val check = launch { newBadge.checkOnce() }; runCurrent()
        account.sessions.value = UserSessionSnapshot(3, 3)
        pending.complete(NotificationUnreadCounts(2, 2)); check.join()
        assertFalse(newBadge.hasUnread.value)
    }

    @Test fun `local mode logged out and failed cold check never retry on resume`() = runTest {
        val source = Source()
        mode.isLocalModeFlow.value = true
        val local = NotificationUnreadCoordinator(source, account, mode, backgroundScope)
        local.checkOnce()
        mode.isLocalModeFlow.value = false
        local.checkOnce()
        assertEquals(0, source.calls)
        account.sessions.value = UserSessionSnapshot(0, 2)
        val guest = NotificationUnreadCoordinator(source, account, mode, backgroundScope)
        guest.checkOnce()
        account.sessions.value = UserSessionSnapshot(1, 3)
        guest.checkOnce()
        assertEquals(0, source.calls)
        source.counts = { error("offline") }
        val failed = NotificationUnreadCoordinator(source, account, mode, backgroundScope)
        assertNotNull(runCatching { failed.checkOnce() }.exceptionOrNull())
        failed.checkOnce()
        assertFalse(failed.hasUnread.value)
        assertEquals(1, source.calls)
    }

    @Test fun `all read clears an existing red badge`() = runTest {
        val badge = NotificationUnreadCoordinator(Source(), account, mode, backgroundScope)
        badge.checkOnce()
        badge.allRead(account.sessions.value)
        assertFalse(badge.hasUnread.value)
    }
}
