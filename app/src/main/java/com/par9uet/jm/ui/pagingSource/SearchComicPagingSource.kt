package com.par9uet.jm.ui.pagingSource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicSearchOrderFilter
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.contentfilter.normalizeSearchExcludedTags
import com.par9uet.jm.utils.log
import com.par9uet.jm.utils.logError

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

/**
 * 排除标签只通过查询串的 `-tag` 交给服务端过滤，请求返回后不再做本地二次排除。
 * 本地曾对每条结果串行拉 `getComicDetail` 判标签，一页 20 条可多出 4 批详情请求，
 * 是搜索「有时候特别慢」的主因；列表项本身也几乎不带真实标签，本地滤不准。
 */
class SearchComicPagingSource(
    private val comicRepository: ComicRepository,
    private val filter: SearchComicFilter,
    private val onFindSingleComicId: (id: Int?) -> Unit = {}
) : PagingSource<Int, Comic>() {
    private val deduplicator = PageItemDeduplicator<Comic> { it.id }

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
                    // 服务端已用含 `-tag` 的 query 判定唯一命中，直接跳详情，不再二次校验。
                    onFindSingleComicId(redirectId)
                    LoadResult.Page(
                        data = listOf(),
                        prevKey = null,
                        nextKey = null
                    )
                } else {
                    onFindSingleComicId(null)
                    val list = deduplicator.filter(currentPage, data.data.items)
                    val total = data.data.total
                    val isLastPage = data.data.items.size < REMOTE_PAGE_SIZE ||
                        isLastRemotePage(currentPage, data.data.items.size, total)
                    log(
                        "SearchComicPagingSource",
                        "成功 page=$currentPage query=[$searchQuery] kept=${list.size} " +
                            "total=[$total] last=$isLastPage",
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

    private fun buildSearchQuery(searchContent: String, excludedTags: List<String>): String {
        val baseQuery = searchContent.trim().replace(Regex("\\s+"), " ")
        val excludedQuery = excludedTags.joinToString(" ") { tag -> "-$tag" }
        return listOf(baseQuery, excludedQuery)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }
}
