package com.par9uet.jm.notification

import com.par9uet.jm.core.model.ConnectionModeStatus
import com.par9uet.jm.notification.model.*
import com.par9uet.jm.session.AccountRequestScope
import com.par9uet.jm.session.UserSessionSnapshot
import com.par9uet.jm.ui.viewModel.ComicTrackingViewModel
import com.par9uet.jm.ui.viewModel.NotificationViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private val account = object : AccountRequestScope {
        override val sessions = MutableStateFlow(UserSessionSnapshot(1, 1))
        override suspend fun <T> run(owner: UserSessionSnapshot, block: suspend () -> T): T {
            check(owner == sessions.value)
            return block()
        }
    }
    private val mode = object : ConnectionModeStatus {
        override val isLocalModeFlow = MutableStateFlow(false)
        override val isLocalMode get() = isLocalModeFlow.value
    }
    private val updates = object : NotificationUnreadUpdates {
        val reads = mutableListOf<String>()
        var all = 0
        override fun read(owner: UserSessionSnapshot, id: String, type: String) { reads += id }
        override fun allRead(owner: UserSessionSnapshot) { all++ }
    }
    private class Source : NotificationSource {
        override suspend fun unreadCounts() = NotificationUnreadCounts()
        val listRequests = mutableListOf<Pair<NotificationFilter, Int>>()
        val readRequests = mutableListOf<String>()
        var allRead = 0
        var toggles = 0
        var trackingReads = 0
        var subscribed = false
        var query: suspend (NotificationFilter, Int) -> InboxPage = { _, _ -> InboxPage(listOf(message("1")), false) }
        var read: suspend () -> Unit = { }
        var status: suspend (Int) -> Boolean = { subscribed }
        var toggle: suspend () -> Unit = { subscribed = !subscribed }
        override suspend fun list(filter: NotificationFilter, page: Int): InboxPage {
            listRequests += filter to page; return query(filter, page)
        }
        override suspend fun markRead(id: String) { readRequests += id; read() }
        override suspend fun markAllRead() { allRead++ }
        override suspend fun tracking(id: Int): Boolean { trackingReads++; return status(id) }
        override suspend fun toggleTracking(id: Int) { toggles++; toggle() }
    }

    @Test fun `opening marks read once and mark all includes the source inbox`() = runTest(dispatcher) {
        val source = Source()
        val vm = NotificationViewModel(source, account, mode, updates); runCurrent()
        vm.enter(); runCurrent()
        vm.open("1"); vm.open("1"); runCurrent()
        assertEquals(listOf("1"), source.readRequests)
        assertEquals(listOf("1"), updates.reads)
        assertTrue(vm.state.value.items.single().read)
        vm.selectFilter(NotificationFilter.Tracking); runCurrent()
        vm.markAllRead(); runCurrent()
        assertEquals(1, source.allRead)
        assertEquals(1, updates.all)
        assertTrue(vm.state.value.items.all { it.read })
        vm.enter(); runCurrent()
        assertEquals(NotificationFilter.All, vm.state.value.filter)
    }

    @Test fun `failed read stays unread and can be retried`() = runTest(dispatcher) {
        val source = Source().apply { read = { error("网络断开") } }
        val vm = NotificationViewModel(source, account, mode, updates); runCurrent()
        vm.enter(); runCurrent(); vm.open("1"); runCurrent()
        assertFalse(vm.state.value.items.single().read)
        assertTrue(updates.reads.isEmpty())
        assertNotNull(vm.state.value.error)
        source.read = { }
        vm.open("1"); runCurrent()
        assertTrue(vm.state.value.items.single().read)
    }

    @Test fun `late old filter cannot replace current notifications and failed page retries same page`() = runTest(dispatcher) {
        val source = Source()
        val pending = CompletableDeferred<InboxPage>()
        source.query = { filter, _ -> if (filter == NotificationFilter.All) withContext(NonCancellable) { pending.await() }
            else InboxPage(listOf(message("site")), true) }
        val vm = NotificationViewModel(source, account, mode, updates); runCurrent()
        vm.enter(); runCurrent(); vm.selectFilter(NotificationFilter.Site); runCurrent()
        pending.complete(InboxPage(listOf(message("old")), false)); runCurrent()
        assertEquals("site", vm.state.value.items.single().id)
        source.query = { _, _ -> error("网络断开") }
        vm.load(); runCurrent()
        assertEquals(1, vm.state.value.page)
        source.query = { _, _ -> InboxPage(listOf(message("next")), false) }
        vm.load(); runCurrent()
        assertEquals(listOf(2, 2), source.listRequests.takeLast(2).map { it.second })
        assertEquals(listOf("site", "next"), vm.state.value.items.map { it.id })
    }

    @Test fun `account change clears inbox and discards old uninterruptible response`() = runTest(dispatcher) {
        val source = Source()
        val pending = CompletableDeferred<InboxPage>()
        source.query = { _, _ -> if (account.sessions.value.accountId == 1) withContext(NonCancellable) { pending.await() }
            else InboxPage(listOf(message("accountB")), false) }
        val vm = NotificationViewModel(source, account, mode, updates); runCurrent()
        vm.enter(); runCurrent()
        account.sessions.value = UserSessionSnapshot(2, 2); runCurrent()
        pending.complete(InboxPage(listOf(message("old")), false)); runCurrent()
        assertEquals(listOf("accountB"), vm.state.value.items.map { it.id })
        assertFalse(vm.state.value.loading)
        vm.leave()
        val calls = source.listRequests.size
        account.sessions.value = UserSessionSnapshot(3, 3); runCurrent()
        assertTrue(vm.state.value.items.isEmpty())
        assertEquals(calls, source.listRequests.size)
    }

    @Test fun `finishing login restoration reloads a visible inbox`() = runTest(dispatcher) {
        val source = Source()
        account.sessions.value = UserSessionSnapshot(0, 1)
        val vm = NotificationViewModel(source, account, mode, updates); runCurrent()
        vm.enter(); runCurrent()
        assertTrue(source.listRequests.isEmpty())
        account.sessions.value = UserSessionSnapshot(1, 2); runCurrent()
        assertEquals("1", vm.state.value.items.single().id)
        assertNull(vm.state.value.error)
    }

    @Test fun `local mode and logged out requests never reach remote notification source`() = runTest(dispatcher) {
        val source = Source()
        val vm = NotificationViewModel(source, account, mode, updates); runCurrent()
        mode.isLocalModeFlow.value = true
        vm.enter(); runCurrent()
        assertTrue(source.listRequests.isEmpty())
        mode.isLocalModeFlow.value = false
        account.sessions.value = UserSessionSnapshot(0, 2); runCurrent()
        vm.enter(); runCurrent()
        assertTrue(source.listRequests.isEmpty())
        assertTrue(vm.state.value.error!!.contains("登录"))
    }

    @Test fun `only collections fetch tracking and successful toggles recheck server status`() = runTest(dispatcher) {
        val source = Source()
        val vm = ComicTrackingViewModel(source, account, mode); runCurrent()
        vm.bind(100, listOf(100)); runCurrent()
        assertEquals(0, source.trackingReads)
        vm.bind(101, listOf(102)); runCurrent()
        assertEquals(false, vm.state.value.subscribed)
        vm.toggle(); vm.toggle(); runCurrent()
        assertEquals(1, source.toggles)
        assertEquals(true, vm.state.value.subscribed)
        vm.toggle(); runCurrent()
        assertEquals(false, vm.state.value.subscribed)
    }

    @Test fun `logged out collections keep subscription entry without automatic requests`() = runTest(dispatcher) {
        val source = Source()
        account.sessions.value = UserSessionSnapshot(0, 2)
        val vm = ComicTrackingViewModel(source, account, mode); runCurrent()
        vm.bind(100, listOf(101)); runCurrent()
        assertTrue(vm.state.value.eligible)
        assertNull(vm.state.value.error)
        assertEquals(0, source.trackingReads)
        vm.toggle(); runCurrent()
        assertEquals(0, source.toggles)
        assertTrue(vm.state.value.error!!.contains("登录"))
        account.sessions.value = UserSessionSnapshot(1, 3); runCurrent()
        assertEquals(false, vm.state.value.subscribed)
    }

    @Test fun `uncertain toggle result retries query rather than toggling subscription again`() = runTest(dispatcher) {
        val source = Source()
        val vm = ComicTrackingViewModel(source, account, mode); runCurrent()
        vm.bind(100, listOf(101)); runCurrent()
        source.toggle = { source.subscribed = true; error("响应超时") }
        vm.toggle(); runCurrent()
        assertNull(vm.state.value.subscribed)
        vm.toggle(); runCurrent()
        assertEquals(1, source.toggles)
        assertEquals(true, vm.state.value.subscribed)
    }

    @Test fun `late tracking status cannot overwrite another comic or account`() = runTest(dispatcher) {
        val source = Source()
        val pending = CompletableDeferred<Boolean>()
        source.status = { id -> if (id == 100) withContext(NonCancellable) { pending.await() } else false }
        val vm = ComicTrackingViewModel(source, account, mode); runCurrent()
        vm.bind(100, listOf(101)); runCurrent(); vm.bind(200, listOf(201)); runCurrent()
        pending.complete(true); runCurrent()
        assertEquals(200, vm.state.value.id)
        assertEquals(false, vm.state.value.subscribed)
        source.status = { true }
        account.sessions.value = UserSessionSnapshot(2, 2); runCurrent()
        assertEquals(true, vm.state.value.subscribed)
    }

    companion object {
        private fun message(id: String) = InboxMessage(id, "公告$id", "2026-10-19", "site_notice", false, emptyList())
    }
}
