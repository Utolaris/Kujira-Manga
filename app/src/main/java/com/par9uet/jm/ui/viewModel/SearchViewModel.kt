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
import com.par9uet.jm.ui.pagingSource.SearchComicFilter
import com.par9uet.jm.ui.pagingSource.SearchComicPagingSource
import com.par9uet.jm.utils.log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class SearchViewportState(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
    val resetGeneration: Long = 0L,
) {
    fun reset(): SearchViewportState = SearchViewportState(
        resetGeneration = resetGeneration + 1L,
    )
}

class SearchViewModel(
    private val comicRepository: ComicRepository,
    private val contentPreferences: ContentPreferences,
) : ViewModel() {
    private val _searchComicFilterState = MutableStateFlow(SearchComicFilter())
    val searchComicFilterState = _searchComicFilterState.asStateFlow()
    private val _searchComicIdState = MutableStateFlow<Int?>(null)
    val searchComicIdState = _searchComicIdState.asStateFlow()
    private val _searchViewportState = MutableStateFlow(SearchViewportState())
    val searchViewportState = _searchViewportState.asStateFlow()

    /**
     * 每个查询一份 Pager；`cachedIn` 留在 `flatMapLatest` **内**，避免换关键字时
     * 上一代 PagingData 和新结果混在同一份展示里。
     *
     * 外层再 `stateIn(Eagerly)` 把整条链挂在 viewModelScope：结果页进详情后 UI 会
     * 取消收集，若不共享，`flatMapLatest` 上游会随收集一起取消、缓存销毁，返回时
     * 只能重新请求。Eagerly 共享后返回直接拿到当前查询的缓存 PagingData。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val searchComicPager: kotlinx.coroutines.flow.StateFlow<PagingData<Comic>> = combine(
        _searchComicFilterState,
        contentPreferences.blockedTags
    ) { filter, blockedTagList -> filter to blockedTagList }
        .flatMapLatest { (filter, blockedTagList) ->
            Pager(
                config = PagingConfig(
                    pageSize = 20,
                    prefetchDistance = 6,
                    initialLoadSize = 20
                ),
                pagingSourceFactory = {
                    SearchComicPagingSource(
                        comicRepository,
                        filter.copy(excludedTags = (filter.excludedTags + blockedTagList).distinct()),
                    ) { id ->
                        _searchComicIdState.update {
                            id
                        }
                    }
                }
            ).flow.cachedIn(viewModelScope)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = PagingData.empty(),
        )

    fun changeSearchComicOrderFilter(order: ComicSearchOrderFilter) {
        _searchComicIdState.update { null }
        val current = _searchComicFilterState.value
        val next = current.copy(order = order)
        if (next == current) return
        // revision 一起 bump：结果页用 revision 做 key，排序切换也必须换代收集。
        _searchComicFilterState.value = next.copy(revision = current.revision + 1L)
        _searchViewportState.update(SearchViewportState::reset)
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
     * 以免返回结果页时打断视口恢复——此时 UI 必须用 revision 做 key，
     * 确保不会把上一次查询的缓存列表画到本次结果上。
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
        _searchViewportState.update(SearchViewportState::reset)
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
        _searchComicFilterState.value = current
            .copy(searchContent = searchContent, excludedTags = excludedTags)
            .copy(revision = current.revision + 1L)
        _searchViewportState.update(SearchViewportState::reset)
    }

    fun saveSearchViewport(
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffset: Int,
        resetGeneration: Long,
    ) {
        _searchViewportState.update { current ->
            if (current.resetGeneration != resetGeneration) {
                current
            } else {
                current.copy(
                    firstVisibleItemIndex = firstVisibleItemIndex.coerceAtLeast(0),
                    firstVisibleItemScrollOffset = firstVisibleItemScrollOffset.coerceAtLeast(0),
                )
            }
        }
    }

    private fun updateSearchFilter(next: SearchComicFilter) {
        val current = _searchComicFilterState.value
        if (next.matchesQuery(current)) return
        _searchComicFilterState.value = next.copy(revision = current.revision + 1L)
        _searchViewportState.update(SearchViewportState::reset)
    }

    fun consumeSearchComicId() {
        _searchComicIdState.update { null }
    }

}
