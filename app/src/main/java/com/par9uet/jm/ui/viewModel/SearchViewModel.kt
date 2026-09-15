package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.par9uet.jm.data.models.ComicSearchOrderFilter
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.storage.ContentPreferences
import com.par9uet.jm.ui.pagingSource.SearchComicFilter
import com.par9uet.jm.ui.pagingSource.SearchComicPagingSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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
     * 每个查询一份 Pager。
     *
     * `cachedIn` 必须写在 `flatMapLatest` **里面**：挂在外层会把「切换查询」的流整体缓存，
     * 换关键字时上一代 PagingData 会和新结果混在同一份展示里（手机端尤其容易复现）。
     * 内层 cachedIn 则缓存的是单个查询的 PagingData，切换查询即换代，列表干净。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val searchComicPager = combine(
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
