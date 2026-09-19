package com.par9uet.jm.ui.viewModel

import androidx.paging.LoadState
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicSearchPage
import com.par9uet.jm.data.models.ComicSearchOrderFilter
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.storage.ContentPreferences
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

    private class FakeSettings : ContentPreferences {
        override val blockedTags = MutableStateFlow(emptyList<String>())
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
        val vm = SearchViewModel(repository, FakeSettings())
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
        val vm = SearchViewModel(repository, FakeSettings())
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
        val vm = SearchViewModel(FakeComicRepository(), FakeSettings())

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
        val vm = SearchViewModel(FakeComicRepository(), FakeSettings())
        vm.changeSearchComicContent("neko", listOf("tag1", "tag2"))

        vm.changeSearchComicOrderFilter(ComicSearchOrderFilter.MOST_PIC_COUNT)

        val filter = vm.searchComicFilterState.value
        assertEquals("neko", filter.searchContent)
        assertEquals(listOf("tag1", "tag2"), filter.excludedTags)
        assertEquals(ComicSearchOrderFilter.MOST_PIC_COUNT, filter.order)
    }

    @Test
    fun returningToUnchangedSearchKeepsSavedViewport() = runTest(scheduler) {
        val vm = SearchViewModel(FakeComicRepository(), FakeSettings())
        vm.changeSearchComicContent("neko", listOf("tag1"))
        val generation = vm.searchViewportState.value.resetGeneration
        vm.saveSearchViewport(
            firstVisibleItemIndex = 42,
            firstVisibleItemScrollOffset = 96,
            resetGeneration = generation,
        )

        vm.changeSearchComicContent("neko", listOf("tag1"))

        assertEquals(42, vm.searchViewportState.value.firstVisibleItemIndex)
        assertEquals(96, vm.searchViewportState.value.firstVisibleItemScrollOffset)
        assertEquals(generation, vm.searchViewportState.value.resetGeneration)
    }

    @Test
    fun changingSearchContextResetsViewportAndRejectsStaleSaves() = runTest(scheduler) {
        val vm = SearchViewModel(FakeComicRepository(), FakeSettings())
        val oldGeneration = vm.searchViewportState.value.resetGeneration
        vm.saveSearchViewport(12, 48, oldGeneration)

        vm.changeSearchComicContent("new query", emptyList())
        val reset = vm.searchViewportState.value

        assertEquals(0, reset.firstVisibleItemIndex)
        assertEquals(0, reset.firstVisibleItemScrollOffset)
        assertTrue(reset.resetGeneration > oldGeneration)

        vm.saveSearchViewport(99, 99, oldGeneration)
        assertEquals(reset, vm.searchViewportState.value)
    }

    @Test
    fun changingSearchOrderResetsViewport() = runTest(scheduler) {
        val vm = SearchViewModel(FakeComicRepository(), FakeSettings())
        val generation = vm.searchViewportState.value.resetGeneration
        vm.saveSearchViewport(18, 72, generation)

        vm.changeSearchComicOrderFilter(ComicSearchOrderFilter.MOST_VIEWED)

        assertEquals(0, vm.searchViewportState.value.firstVisibleItemIndex)
        assertEquals(0, vm.searchViewportState.value.firstVisibleItemScrollOffset)
        assertTrue(vm.searchViewportState.value.resetGeneration > generation)

        val resetGeneration = vm.searchViewportState.value.resetGeneration
        vm.changeSearchComicOrderFilter(ComicSearchOrderFilter.MOST_VIEWED)
        assertEquals(resetGeneration, vm.searchViewportState.value.resetGeneration)
    }
}
