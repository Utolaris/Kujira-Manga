package com.par9uet.jm.dinner

import com.par9uet.jm.core.model.ConnectionModeStatus
import com.par9uet.jm.dinner.model.*
import com.par9uet.jm.ui.viewModel.DinnerViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DinnerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private val settings = object : com.par9uet.jm.storage.MiscSettingsPreferences {
        override val misc = MutableStateFlow(com.par9uet.jm.storage.MiscSettingsState())
    }
    private val mode = object : ConnectionModeStatus {
        override val isLocalModeFlow = MutableStateFlow(false)
        override val isLocalMode get() = isLocalModeFlow.value
    }
    private class Source : DinnerSource {
        val requests = mutableListOf<Int>()
        var handler: suspend (Int) -> DinnerPage = { page -> DinnerPage((1..12).map { summary((page - 1) * 12 + it) }, 25) }
        var detail: suspend (Int) -> DinnerArticle = { DinnerArticle(it, "文章$it", "", emptyList()) }
        override suspend fun list(page: Int): DinnerPage { requests += page; return handler(page) }
        override suspend fun article(id: Int) = detail(id)
    }

    @Test fun `pagination retains articles on failure and retries the same page`() = runTest(dispatcher) {
        val source = Source()
        val vm = DinnerViewModel(source, mode, settings)
        vm.loadList(); runCurrent()
        assertEquals(12, vm.list.value.items.size)
        assertTrue(vm.list.value.hasMore)
        source.handler = { error("网络断开") }
        vm.loadList(); runCurrent()
        assertEquals(1, vm.list.value.page)
        assertEquals(12, vm.list.value.items.size)
        source.handler = { DinnerPage((13..24).map(::summary), 25) }
        vm.loadList(); runCurrent()
        assertEquals(listOf(1, 2, 2), source.requests)
        assertEquals(24, vm.list.value.items.size)
        source.handler = { DinnerPage(listOf(summary(25)), 25) }
        vm.loadList(); runCurrent()
        assertFalse(vm.list.value.hasMore)
        vm.loadList(); runCurrent()
        assertEquals(listOf(1, 2, 2, 3), source.requests)
    }

    @Test fun `failed refresh of a terminal list retries page one and replaces the old list`() = runTest(dispatcher) {
        val source = Source().apply { handler = { DinnerPage(listOf(summary(1)), 1) } }
        val vm = DinnerViewModel(source, mode, settings)
        vm.loadList(); runCurrent()
        source.handler = { error("刷新失败") }
        vm.loadList(refresh = true); runCurrent()
        assertEquals(1, vm.list.value.items.single().id)
        source.handler = { DinnerPage(listOf(summary(2)), 1) }
        vm.loadList(); runCurrent()
        assertEquals(listOf(1, 1, 1), source.requests)
        assertEquals(2, vm.list.value.items.single().id)
    }

    @Test fun `late uncancellable article response cannot overwrite a different article`() = runTest(dispatcher) {
        val source = Source()
        val pending = CompletableDeferred<DinnerArticle>()
        source.detail = { id -> if (id == 1) withContext(NonCancellable) { pending.await() }
            else DinnerArticle(2, "第二篇", "", emptyList()) }
        val vm = DinnerViewModel(source, mode, settings)
        vm.openArticle(1); runCurrent()
        vm.openArticle(2); runCurrent()
        pending.complete(DinnerArticle(1, "旧文章", "", emptyList())); runCurrent()
        assertEquals(2, vm.article.value.article?.id)
    }

    @Test fun `local mode never requests the remote article API`() = runTest(dispatcher) {
        mode.isLocalModeFlow.value = true
        val source = Source()
        val vm = DinnerViewModel(source, mode, settings)
        vm.loadList(); runCurrent()
        assertTrue(source.requests.isEmpty())
        assertNotNull(vm.list.value.error)
    }

    @Test fun `a full remote page filtered to zero articles still permits the following page`() = runTest(dispatcher) {
        val source = Source().apply {
            handler = { page -> if (page == 1) DinnerPage(emptyList(), 24, rawItemCount = 12)
                else DinnerPage(listOf(summary(20)), 24, rawItemCount = 12) }
        }
        val vm = DinnerViewModel(source, mode, settings)
        vm.loadList(); runCurrent()
        assertTrue(vm.list.value.items.isEmpty())
        assertTrue(vm.list.value.hasMore)
        vm.loadList(); runCurrent()
        assertEquals(listOf(1, 2), source.requests)
        assertEquals(20, vm.list.value.items.single().id)
        assertFalse(vm.list.value.hasMore)
    }

    @Test fun `promotion discovered only in detail is removed from the visible list`() = runTest(dispatcher) {
        val source = Source().apply { detail = { throw DinnerPromotionBlocked() } }
        val vm = DinnerViewModel(source, mode, settings)
        vm.loadList(); runCurrent()
        vm.openArticle(1); runCurrent()
        assertTrue(vm.article.value.blocked)
        assertTrue(vm.list.value.items.none { it.id == 1 })
        assertEquals(1, vm.list.value.page)
        assertTrue(vm.list.value.hasMore)
    }

    @Test fun `returning to an article list retains measured cover ratios without fetching list again`() = runTest(dispatcher) {
        val source = Source()
        val vm = DinnerViewModel(source, mode, settings)
        vm.loadList(); runCurrent()
        vm.rememberCoverRatio("https://image.test/a.jpg", .6f)
        vm.openArticle(1); runCurrent()
        assertEquals(.6f, vm.coverRatio("https://image.test/a.jpg"))
        assertEquals(4f / 3f, vm.coverRatio("https://image.test/other.jpg"))
        assertEquals(listOf(1), source.requests)
    }

    companion object { private fun summary(id: Int) = DinnerSummary(id, "文章$id", null, "") }
}
