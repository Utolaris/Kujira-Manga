package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicSearchOrderFilter
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.storage.ContentPreferences
import com.par9uet.jm.ui.models.ListPositionMemory
import com.par9uet.jm.ui.models.searchListPositionKey
import com.par9uet.jm.ui.pagingSource.SearchComicFilter
import com.par9uet.jm.ui.pagingSource.SearchComicPagingSource
import com.par9uet.jm.utils.log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SearchViewModel(
    private val comicRepository: ComicRepository,
    private val contentPreferences: ContentPreferences,
    private val historySearchManager: com.par9uet.jm.storage.HistorySearchManager,
    private val blockedTagTemplatePreferences: com.par9uet.jm.storage.BlockedTagTemplatePreferences,
    private val miscSettingsPreferences: com.par9uet.jm.storage.MiscSettingsPreferences,
    private val listPositionMemory: ListPositionMemory,
) : ViewModel() {
    /** Search editor / result pages collect prefs + history through the VM. */
    val historySearchState = historySearchManager.historySearchState
    val blockedTagTemplates = blockedTagTemplatePreferences.blockedTagTemplates
    val misc = miscSettingsPreferences.misc
    val blockedTags = contentPreferences.blockedTags

    fun addHistoryItem(item: String) = historySearchManager.addItem(item)
    fun clearHistory() = historySearchManager.clear()

    private val _searchComicFilterState = MutableStateFlow(SearchComicFilter())
    val searchComicFilterState = _searchComicFilterState.asStateFlow()
    private val _searchComicIdState = MutableStateFlow<Int?>(null)
    val searchComicIdState = _searchComicIdState.asStateFlow()

    /**
     * 每个查询一份常驻的 PagingData 缓存（按 queryKey 隔离）。
     *
     * 之前 `flatMapLatest` 换查询即丢弃旧缓存：从查询 A 进详情、点作者进入查询 C、
     * 再返回 A 时 A 的列表要从零重拉——视口恢复等不到 `itemCount > savedIndex`，
     * 表现为「回到顶部」。按查询缓存后返回旧查询立即重放缓存列表，视口恢复有数据可对齐。
     * 缓存数量有上限，最旧的作废（下次进入该查询重新加载）。
     */
    private val searchPagersByQuery = LinkedHashMap<String, Flow<PagingData<Comic>>>()

    /** 屏蔽标签变化时整体作废重建（分页源在构造时读取最新标签）。 */
    private val _searchPagerReload = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            contentPreferences.blockedTags.collect {
                if (searchPagersByQuery.isNotEmpty()) {
                    searchPagersByQuery.clear()
                    _searchPagerReload.update { it + 1 }
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val searchComicPager: kotlinx.coroutines.flow.StateFlow<PagingData<Comic>> = combine(
        _searchComicFilterState,
        _searchPagerReload,
    ) { filter, _ -> filter }
        .flatMapLatest { filter -> pagerFor(filter) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = PagingData.empty(),
        )

    private fun pagerFor(filter: SearchComicFilter): Flow<PagingData<Comic>> {
        val queryKey = filter.queryKey()
        searchPagersByQuery[queryKey]?.let { return it }
        val flow = Pager(
            config = PagingConfig(
                pageSize = 20,
                prefetchDistance = 6,
                initialLoadSize = 20
            ),
            pagingSourceFactory = {
                SearchComicPagingSource(
                    comicRepository,
                    filter.copy(excludedTags = (filter.excludedTags + contentPreferences.blockedTags.value).distinct()),
                ) { id ->
                    _searchComicIdState.update {
                        id
                    }
                }
            }
        ).flow.cachedIn(viewModelScope)
        searchPagersByQuery[queryKey] = flow
        while (searchPagersByQuery.size > MAX_CACHED_SEARCH_PAGERS) {
            searchPagersByQuery.remove(searchPagersByQuery.keys.first())
        }
        return flow
    }

    fun changeSearchComicOrderFilter(order: ComicSearchOrderFilter) {
        _searchComicIdState.update { null }
        val current = _searchComicFilterState.value
        val next = current.copy(order = order)
        if (next == current) return
        // revision 一起 bump：结果页用 revision 做 key，排序切换也必须换代收集。
        // 滚动位置不用管：位置 key 含排序维度，换排序即新 key，自然从顶部开始。
        _searchComicFilterState.value = next.copy(revision = current.revision + 1L)
    }

    /**
     * 只改年/月，关键词、排除标签、排序原样保留。
     * 空串 = 清除该维度（官方「全部年份 / 全部月份」）。
     */
    fun changeSearchComicDateFilter(year: String, month: String) {
        _searchComicIdState.update { null }
        val current = _searchComicFilterState.value
        if (current.year == year && current.month == month) return
        _searchComicFilterState.value = current.copy(
            year = year,
            month = month,
            revision = current.revision + 1L,
        )
    }

    fun changeSearchComicContent(searchContent: String) {
        _searchComicIdState.update { null }
        updateSearchFilter(_searchComicFilterState.value.copy(searchContent = searchContent))
    }

    fun changeSearchComicContent(searchContent: String, excludedTags: List<String>) {
        _searchComicIdState.update { null }
        updateSearchFilter(
            _searchComicFilterState.value.copy(
                searchContent = searchContent,
                excludedTags = excludedTags,
            )
        )
    }

    /**
     * 搜索结果页入口：路由参数是权威查询。
     *
     * 查询变化时 bump revision 强制新 Pager；查询相同则保持不动，
     * 以免返回结果页时打断列表展示——此时 UI 必须用 revision 做 key，
     * 确保不会把上一次查询的缓存列表画到本次结果上。
     *
     * 滚动位置不再由本 VM 管理：结果页按「路由查询 + 排序/年月」这个 key
     * 读写全局位置记录，跨查询往返（搜索→详情→作者搜索→返回旧搜索）时
     * 旧查询的位置自然还在，不需要任何代际归零/恢复逻辑。
     */
    fun enterSearchResult(searchContent: String, excludedTags: List<String>) {
        _searchComicIdState.update { null }
        val current = _searchComicFilterState.value
        val next = current.copy(
            searchContent = searchContent,
            excludedTags = excludedTags,
        )
        // 路由参数是搜索结果页的唯一查询来源，先把「收到的原始查询」落日志，
        // 否则空结果时无法区分「入口就没传关键词」与「上游返回空/推荐」。
        log(
            "SearchViewModel",
            "enterSearchResult content=[$searchContent] excluded=${excludedTags.size} " +
                "same=${next.matchesQuery(current)} revision=${current.revision}",
        )
        if (next.matchesQuery(current) && current.revision > 0L) {
            return
        }
        _searchComicFilterState.value = next.copy(revision = current.revision + 1L)
    }

    /**
     * Called when the user submits the search editor. Unlike [changeSearchComicContent] - which the
     * result destination replays on every re-entry and therefore must stay idempotent - this always
     * asks for a fresh load, so repeating the exact same query surfaces a network failure instead of
     * silently re-rendering the results that are still cached in this ViewModel.
     */
    fun submitSearch(searchContent: String, excludedTags: List<String>) {
        _searchComicIdState.update { null }
        val current = _searchComicFilterState.value
        log(
            "SearchViewModel",
            "submitSearch content=[$searchContent] excluded=${excludedTags.size} $excludedTags",
        )
        // 显式重搜的同一查询也视为新浏览：Pager 缓存作废（重复搜索必须真实触网），
        // 位置记录一并清掉，下次进入从顶部开始。
        val nextFilter = current.copy(searchContent = searchContent, excludedTags = excludedTags)
        searchPagersByQuery.remove(nextFilter.queryKey())
        listPositionMemory.clear(searchListPositionKey(nextFilter.queryKey()))
        _searchComicFilterState.value = nextFilter.copy(revision = current.revision + 1L)
    }

    private fun updateSearchFilter(next: SearchComicFilter) {
        val current = _searchComicFilterState.value
        if (next.matchesQuery(current)) return
        _searchComicFilterState.value = next.copy(revision = current.revision + 1L)
    }

    fun consumeSearchComicId() {
        _searchComicIdState.update { null }
    }

    private companion object {
        /** 按查询缓存的 Pager 上限；超过时最旧的作废，防止常驻内存无限增长。 */
        const val MAX_CACHED_SEARCH_PAGERS = 8
    }
}
