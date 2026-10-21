package com.par9uet.jm.ui.viewModel

import androidx.paging.LoadState
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicSearchPage
import com.par9uet.jm.data.models.ComicSearchOrderFilter
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.storage.BlockedTagTemplatePreferences
import com.par9uet.jm.storage.ContentPreferences
import com.par9uet.jm.storage.HistorySearchManager
import com.par9uet.jm.storage.HistorySearchStore
import com.par9uet.jm.storage.MiscSettingsPreferences
import com.par9uet.jm.storage.MiscSettingsState
import com.par9uet.jm.ui.models.ListPositionMemory
import com.par9uet.jm.ui.models.searchListPositionKey
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private lateinit var scheduler: TestCoroutineScheduler
    @Before fun setUp() {
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun FakeComicRepository(): ComicRepository = Proxy.newProxyInstance(
        ComicRepository::class.java.classLoader, arrayOf(ComicRepository::class.java),
    ) { _, method, _ -> error("Unexpected request: "+method.name) } as ComicRepository

    private class FakeSettings : ContentPreferences, BlockedTagTemplatePreferences, MiscSettingsPreferences {
        override val blockedTags = MutableStateFlow(emptyList<String>())
        override val blockedCommentKeywords = kotlinx.coroutines.flow.MutableStateFlow(emptyList<String>())
        override val blockedTagTemplates =
            MutableStateFlow(emptyList<com.par9uet.jm.data.models.BlockedTagTemplate>())
        override val misc = MutableStateFlow(MiscSettingsState())
    }

    private class FakeHistorySearchStore : HistorySearchStore {
        private var items: List<String>? = emptyList()
        override fun getOrNull(): List<String>? = items
        override fun set(list: List<String>) { items = list }
        override fun remove() { items = emptyList() }
    }

    private fun buildVm(
        repository: ComicRepository,
        memory: ListPositionMemory = ListPositionMemory(),
    ): SearchViewModel {
        val settings = FakeSettings()
        return SearchViewModel(
            repository,
            settings,
            HistorySearchManager(FakeHistorySearchStore()),
            settings,
            settings,
            memory,
        )
    }

    @Test
    fun repeatedSearchRequestsNetworkAndReportsFailuresThenRetries() = runTest(scheduler) {
        val comic = Comic.create(1, "previous result", emptyList())
        var response: NetWorkResult<ComicSearchPage> =
            NetWorkResult.Success(ComicSearchPage(listOf(comic), 1, null))
        var requests = 0
        val repository = Proxy.newProxyInstance(
            ComicRepository::class.java.classLoader, arrayOf(ComicRepository::class.java),
        ) { _, method, _ ->
            check(method.name == "getComicList")
            requests++
            response
        } as ComicRepository
        val vm = buildVm(repository)
        val presenter = object : PagingDataPresenter<Comic>(StandardTestDispatcher(scheduler)) {
            override suspend fun presentPagingDataEvent(event: PagingDataEvent<Comic>) = Unit
        }
        vm.submitSearch("same query", emptyList())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.searchComicPager.collectLatest(presenter::collectFrom)
        }
        runCurrent()
        assertEquals(listOf(comic), presenter.snapshot().items)
        assertEquals(1, requests)

        for (message in listOf("网络连接失败", "登录会话已失效，请重新登录")) {
            val before = requests
            response = NetWorkResult.Error(message)
            vm.submitSearch("same query", emptyList())
            runCurrent()
            assertEquals(before + 1, requests)
            val error = presenter.loadStateFlow.value?.refresh as LoadState.Error
            assertEquals(message, error.error.message)
        }

        response = NetWorkResult.Success(ComicSearchPage(emptyList(), 0, null))
        presenter.retry()
        runCurrent()
        assertTrue(presenter.loadStateFlow.value?.refresh is LoadState.NotLoading)
        assertTrue(presenter.snapshot().items.isEmpty())
    }

    @Test
    fun returningToSameQueryReusesCacheWithoutNewNetworkCall() = runTest(scheduler) {
        val comic = Comic.create(1, "cached result", emptyList())
        var requests = 0
        val repository = Proxy.newProxyInstance(
            ComicRepository::class.java.classLoader, arrayOf(ComicRepository::class.java),
        ) { _, method, _ ->
            check(method.name == "getComicList")
            requests++
            NetWorkResult.Success(ComicSearchPage(listOf(comic), 1, null))
        } as ComicRepository
        val vm = buildVm(repository)
        val first = object : PagingDataPresenter<Comic>(StandardTestDispatcher(scheduler)) {
            override suspend fun presentPagingDataEvent(event: PagingDataEvent<Comic>) = Unit
        }
        vm.submitSearch("same query", emptyList())
        val leaving = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.searchComicPager.collectLatest(first::collectFrom)
        }
        runCurrent()
        assertEquals(1, requests)
        assertEquals(listOf(comic), first.snapshot().items)

        // 结果页进详情：UI 取消收集。
        leaving.cancel()
        runCurrent()

        // 返回结果页：同查询 enterSearchResult 不得换代，也不得再次打网。
        val revisionBefore = vm.searchComicFilterState.value.revision
        vm.enterSearchResult("same query", emptyList())
        runCurrent()
        assertEquals(revisionBefore, vm.searchComicFilterState.value.revision)

        val returning = object : PagingDataPresenter<Comic>(StandardTestDispatcher(scheduler)) {
            override suspend fun presentPagingDataEvent(event: PagingDataEvent<Comic>) = Unit
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.searchComicPager.collectLatest(returning::collectFrom)
        }
        runCurrent()
        assertEquals(1, requests)
        assertEquals(listOf(comic), returning.snapshot().items)

        // 搜索框再次提交同关键词：必须强制换代重新请求。
        vm.submitSearch("same query", emptyList())
        runCurrent()
        assertEquals(2, requests)
    }

    @Test
    fun searchOrderChangeUpdatesFilterAndClearsPendingComicId() = runTest(scheduler) {
        val repository = object : ComicRepository by FakeComicRepository() {
            override suspend fun getComicList(
                page: Int, order: ComicSearchOrderFilter, searchContent: String,
                year: String, month: String,
            ): NetWorkResult<ComicSearchPage> = NetWorkResult.Success(ComicSearchPage(emptyList(), 1, 42))
        }
        val vm = buildVm(repository)
        val presenter = object : PagingDataPresenter<Comic>(StandardTestDispatcher(scheduler)) {
            override suspend fun presentPagingDataEvent(event: PagingDataEvent<Comic>) = Unit
        }
        vm.submitSearch("single result", emptyList())
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.searchComicPager.collectLatest(presenter::collectFrom)
        }
        runCurrent()
        assertEquals(42, vm.searchComicIdState.value)
        collector.cancel()

        assertEquals(ComicSearchOrderFilter.NEWEST, vm.searchComicFilterState.value.order)

        vm.changeSearchComicOrderFilter(ComicSearchOrderFilter.MOST_VIEWED)
        assertEquals(ComicSearchOrderFilter.MOST_VIEWED, vm.searchComicFilterState.value.order)
        assertNull(vm.searchComicIdState.value)

        vm.changeSearchComicOrderFilter(ComicSearchOrderFilter.MOST_PIC_COUNT)
        assertEquals(ComicSearchOrderFilter.MOST_PIC_COUNT, vm.searchComicFilterState.value.order)
        assertNull(vm.searchComicIdState.value)

        vm.changeSearchComicOrderFilter(ComicSearchOrderFilter.MOST_LIKE_COUNT)
        assertEquals(ComicSearchOrderFilter.MOST_LIKE_COUNT, vm.searchComicFilterState.value.order)
        assertNull(vm.searchComicIdState.value)

        vm.changeSearchComicOrderFilter(ComicSearchOrderFilter.NEWEST)
        assertEquals(ComicSearchOrderFilter.NEWEST, vm.searchComicFilterState.value.order)
    }

    @Test
    fun searchOrderChangeKeepsSearchCriteria() = runTest(scheduler) {
        val vm = buildVm(FakeComicRepository())
        vm.changeSearchComicContent("neko", listOf("tag1", "tag2"))

        vm.changeSearchComicOrderFilter(ComicSearchOrderFilter.MOST_PIC_COUNT)

        val filter = vm.searchComicFilterState.value
        assertEquals("neko", filter.searchContent)
        assertEquals(listOf("tag1", "tag2"), filter.excludedTags)
        assertEquals(ComicSearchOrderFilter.MOST_PIC_COUNT, filter.order)
    }

    @Test
    fun dateFilterChangeKeepsSearchCriteriaAndOrder() = runTest(scheduler) {
        val vm = buildVm(FakeComicRepository())
        vm.changeSearchComicContent("neko", listOf("tag1"))
        vm.changeSearchComicOrderFilter(ComicSearchOrderFilter.MOST_LIKE_COUNT)

        vm.changeSearchComicDateFilter("2024", "3")

        val filter = vm.searchComicFilterState.value
        assertEquals("neko", filter.searchContent)
        assertEquals(listOf("tag1"), filter.excludedTags)
        assertEquals(ComicSearchOrderFilter.MOST_LIKE_COUNT, filter.order)
        assertEquals("2024", filter.year)
        assertEquals("3", filter.month)

        vm.changeSearchComicDateFilter("", "")
        assertEquals("", vm.searchComicFilterState.value.year)
        assertEquals("", vm.searchComicFilterState.value.month)
        assertEquals("neko", vm.searchComicFilterState.value.searchContent)
        assertEquals(ComicSearchOrderFilter.MOST_LIKE_COUNT, vm.searchComicFilterState.value.order)
    }

    /**
     * 滚动位置现在由全局位置记录按「查询键」隔离保存。
     *
     * 这一组用例钉住的是**返回时能找回位置**这件事成立的前提：同一查询 = 同一个 key，
     * 换查询 / 换排序 / 换年月 = 另一个 key（于是从顶部开始）。之前的 generation 归零
     * 逻辑在「搜索 → 详情 → 点作者 → 返回 → 返回」时会把返回旧查询误判成换代。
     */
    @Test
    fun returningToUnchangedSearchKeepsSavedViewport() = runTest(scheduler) {
        val memory = ListPositionMemory()
        val vm = buildVm(FakeComicRepository(), memory)
        vm.changeSearchComicContent("neko", listOf("tag1"))
        val key = searchListPositionKey(vm.searchComicFilterState.value.queryKey())
        memory.save(key, index = 42, offset = 96)

        // 结果页每次重新进入都会重放同一个查询（同 key）。
        vm.changeSearchComicContent("neko", listOf("tag1"))
        vm.enterSearchResult("neko", listOf("tag1"))

        val restored = memory.position(searchListPositionKey(vm.searchComicFilterState.value.queryKey()))
        assertEquals(42, restored?.index)
        assertEquals(96, restored?.offset)
    }

    @Test
    fun changingSearchContextStartsFromAFreshKey() = runTest(scheduler) {
        val memory = ListPositionMemory()
        val vm = buildVm(FakeComicRepository(), memory)
        vm.changeSearchComicContent("neko", emptyList())
        val oldKey = searchListPositionKey(vm.searchComicFilterState.value.queryKey())
        memory.save(oldKey, index = 12, offset = 48)

        vm.enterSearchResult("new query", emptyList())
        val newKey = searchListPositionKey(vm.searchComicFilterState.value.queryKey())

        assertTrue("换查询必须换成新的位置 key", newKey != oldKey)
        assertNull("新查询没有记忆，等于从顶部开始", memory.position(newKey))
    }

    @Test
    fun changingSearchOrderStartsFromAFreshKey() = runTest(scheduler) {
        val memory = ListPositionMemory()
        val vm = buildVm(FakeComicRepository(), memory)
        vm.changeSearchComicContent("neko", emptyList())
        val newestKey = searchListPositionKey(vm.searchComicFilterState.value.queryKey())
        memory.save(newestKey, index = 18, offset = 72)

        vm.changeSearchComicOrderFilter(ComicSearchOrderFilter.MOST_VIEWED)
        val mostViewedKey = searchListPositionKey(vm.searchComicFilterState.value.queryKey())

        assertTrue("换排序必须换成新的位置 key", mostViewedKey != newestKey)
        assertNull(memory.position(mostViewedKey))
        // 原来那个排序的位置还在：切回去仍然找得回。
        assertEquals(18, memory.position(newestKey)?.index)
    }

    @Test
    fun repeatingTheSameSearchClearsTheSavedViewport() = runTest(scheduler) {
        val memory = ListPositionMemory()
        val vm = buildVm(FakeComicRepository(), memory)
        vm.changeSearchComicContent("neko", emptyList())
        val key = searchListPositionKey(vm.searchComicFilterState.value.queryKey())
        memory.save(key, index = 42, offset = 96)

        // 显式重搜同一关键词 = 新的一次浏览：位置作废，从顶部开始。
        vm.submitSearch("neko", emptyList())

        assertNull(memory.position(key))
    }
}
