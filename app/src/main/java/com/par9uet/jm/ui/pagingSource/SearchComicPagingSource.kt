package com.par9uet.jm.ui.pagingSource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicSearchOrderFilter
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.contentfilter.filterBlockedTags
import com.par9uet.jm.contentfilter.normalizeSearchExcludedTags
import com.par9uet.jm.utils.log
import com.par9uet.jm.utils.logError
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class SearchComicFilter(
    val order: ComicSearchOrderFilter = ComicSearchOrderFilter.NEWEST,
    val searchContent: String = "",
    val excludedTags: List<String> = emptyList(),
    /**
     * Bumped by every explicit search so that repeating the same query still reaches the network.
     * The paging source ignores it; it exists only to make `flatMapLatest` create a new Pager.
     * Without it a repeat search was a no-op that left the previous results on screen, with no
     * request made and therefore no error to report.
     */
    val revision: Long = 0L,
) {
    /** Query-level equality, i.e. everything except the reload trigger. */
    fun matchesQuery(other: SearchComicFilter): Boolean =
        order == other.order &&
            searchContent == other.searchContent &&
            excludedTags == other.excludedTags
}

class SearchComicPagingSource(
    private val comicRepository: ComicRepository,
    private val filter: SearchComicFilter,
    private val onFindSingleComicId: (id: Int?) -> Unit = {}
) : PagingSource<Int, Comic>() {
    private val deduplicator = PageItemDeduplicator<Comic> { it.id }

    companion object {
        private const val DETAIL_FILTER_BATCH_SIZE = 6
    }

    private val detailBlockedCache = mutableMapOf<Int, Boolean>()

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Comic> {
        val currentPage = params.key ?: 1
        val excludedTags = normalizeSearchExcludedTags(filter.excludedTags)
        val searchQuery = buildSearchQuery(filter.searchContent, excludedTags)
        // 关键词与排除项都为空时**不能发请求**：上游把空 keyword 当成「没有搜索条件」，
        // 返回的是推荐/默认列表。那批数据会被当成搜索结果展示，用户既看不出没搜成，
        // 也拿不到任何错误 —— 静默降级比失败更糟。这里直接失败，让结果页显示错误 + 空列表。
        if (searchQuery.isBlank()) {
            logError("SearchComicPagingSource", "拒绝空关键词请求 page=$currentPage")
            return LoadResult.Error(IllegalArgumentException("没有可搜索的关键词，请重新输入"))
        }
        // 请求级日志：搜索链路此前一行都不打，出问题时（关键词没送达、上游返回推荐列表、
        // 本地过滤把结果吃光）无法从日志区分，只能靠猜。这里记录真正发出去的 query。
        log(
            "SearchComicPagingSource",
            "请求 page=$currentPage order=${filter.order.value} query=[$searchQuery]",
        )
        return when (val data =
            comicRepository.getComicList(currentPage, filter.order, searchQuery)) {
            is NetWorkResult.Error -> {
                logError(
                    "SearchComicPagingSource",
                    "失败 page=$currentPage query=[$searchQuery]: ${data.message}",
                )
                LoadResult.Error(Exception(data.message))
            }

            is NetWorkResult.Success -> {
                val redirectId = data.data.redirectComicId
                if (redirectId != null) {
                    if (isComicBlockedByDetail(redirectId, excludedTags.toTagSet())) {
                        onFindSingleComicId(null)
                    } else {
                        onFindSingleComicId(redirectId)
                    }
                    LoadResult.Page(
                        data = listOf(),
                        prevKey = null,
                        nextKey = null
                    )
                } else {
                    onFindSingleComicId(null)
                    val uniqueItems = deduplicator.filter(currentPage, data.data.items)
                    val list = filterExcludedComics(uniqueItems, excludedTags)
                    val total = data.data.total
                    val isLastPage = data.data.items.size < REMOTE_PAGE_SIZE ||
                        isLastRemotePage(currentPage, data.data.items.size, total)
                    // raw/kept 分开记：raw=上游返回条数，kept=去掉屏蔽标签后真正展示的条数。
                    // 两者都可能是 0，含义完全不同（关键词没送达 vs 本地屏蔽吃光）。
                    log(
                        "SearchComicPagingSource",
                        "成功 page=$currentPage query=[$searchQuery] raw=${data.data.items.size} " +
                            "kept=${list.size} total=[$total] last=$isLastPage",
                    )
                    LoadResult.Page(
                        data = list,
                        prevKey = if (currentPage == 1) null else currentPage - 1,
                        nextKey = if (isLastPage) null else currentPage + 1
                    )
                }
            }
        }
    }

    override fun getRefreshKey(state: PagingState<Int, Comic>): Int? = null

    private suspend fun filterExcludedComics(
        candidates: List<Comic>,
        excludedTags: List<String>
    ): List<Comic> {
        if (candidates.isEmpty() || excludedTags.isEmpty()) return candidates

        val list = candidates.filterBlockedTags(excludedTags)
        if (excludedTags.size <= 1 || list.isEmpty()) return list

        return filterByDetailTags(list, excludedTags.toTagSet())
    }

    private suspend fun filterByDetailTags(
        candidates: List<Comic>,
        excludedTagSet: Set<String>
    ): List<Comic> {
        val result = mutableListOf<Comic>()
        candidates.chunked(DETAIL_FILTER_BATCH_SIZE).forEach { chunk ->
            val checkedChunk = coroutineScope {
                chunk.map { comic ->
                    async {
                        comic to isComicBlockedByDetail(comic.id, excludedTagSet)
                    }
                }.awaitAll()
            }
            result += checkedChunk
                .filterNot { (_, isBlocked) -> isBlocked }
                .map { (comic, _) -> comic }
        }
        return result
    }

    private suspend fun isComicBlockedByDetail(
        comicId: Int,
        excludedTagSet: Set<String>
    ): Boolean {
        if (excludedTagSet.isEmpty()) return false
        detailBlockedCache[comicId]?.let { return it }

        val isBlocked = when (val detail = comicRepository.getComicDetail(comicId)) {
            is NetWorkResult.Success -> detail.data.containsAnyExcludedTag(excludedTagSet)
            is NetWorkResult.Error -> false
        }
        detailBlockedCache[comicId] = isBlocked
        return isBlocked
    }

    private fun buildSearchQuery(searchContent: String, excludedTags: List<String>): String {
        val baseQuery = searchContent.trim().replace(Regex("\\s+"), " ")
        val excludedQuery = excludedTags.joinToString(" ") { tag -> "-$tag" }
        return listOf(baseQuery, excludedQuery)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    private fun List<String>.toTagSet(): Set<String> {
        return normalizeSearchExcludedTags(this).map { it.toTagKey() }.toSet()
    }

    private fun Comic.containsAnyExcludedTag(excludedTagSet: Set<String>): Boolean {
        return (tagList + roleList + workList)
            .map { it.toTagKey() }
            .any { it in excludedTagSet }
    }

    private fun String.toTagKey(): String {
        return trim().lowercase()
    }
}
