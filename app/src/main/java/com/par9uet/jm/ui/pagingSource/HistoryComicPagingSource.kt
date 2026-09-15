package com.par9uet.jm.ui.pagingSource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.session.UserRepository
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.contentfilter.filterBlockedTags

class HistoryComicPagingSource(
    private val userRepository: UserRepository,
    private val blockedTagList: List<String> = listOf(),
) : PagingSource<Int, Comic>() {
    private val deduplicator = PageItemDeduplicator<Comic> { it.id }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Comic> {
        val currentPage = params.key ?: 1
        return when (val data =
            userRepository.getHistoryComicList(currentPage)) {
            is NetWorkResult.Error -> {
                LoadResult.Error(Exception(data.message))
            }

            is NetWorkResult.Success -> {
                val list = deduplicator.filter(currentPage, data.data.items)
                    .filterBlockedTags(blockedTagList)
                // watch_list has no total and always uses the server page size. Paging may
                // request a larger loadSize; tag filtering must not truncate pagination.
                val isLastPage = isLastRemotePage(currentPage, data.data.items.size)
                LoadResult.Page(
                    data = list,
                    prevKey = if (currentPage == 1) null else currentPage - 1,
                    nextKey = if (isLastPage) null else currentPage + 1
                )
            }
        }
    }

    companion object {
        const val PAGE_SIZE = REMOTE_PAGE_SIZE
    }

    override fun getRefreshKey(state: PagingState<Int, Comic>): Int? = null
}
