package com.par9uet.jm.ui.pagingSource

import androidx.paging.PagingSource
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.data.models.ActionResult
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicPage
import com.par9uet.jm.data.models.ComicPageList
import com.par9uet.jm.data.models.ComicSearchOrderFilter
import com.par9uet.jm.data.models.ComicSearchPage
import com.par9uet.jm.data.models.CommentPage
import com.par9uet.jm.data.models.HomeComicSwiperItem
import com.par9uet.jm.data.models.WeekData
import com.par9uet.jm.repository.ComicRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class SearchComicPagingSourceTest {
    @Test
    fun excludedTagsAreOnlySentAsServerQueryNotPostFilteredLocally() = runBlocking {
        val repository = FakeComicRepository()
        val source = SearchComicPagingSource(
            comicRepository = repository,
            filter = SearchComicFilter(
                searchContent = "artist",
                excludedTags = listOf("a", "b")
            )
        )

        val result = source.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 20,
                placeholdersEnabled = false
            )
        )

        val page = result as PagingSource.LoadResult.Page<Int, Comic>
        // 排除项只拼进 query；返回列表原样展示，不再对每条拉详情做本地排除。
        assertEquals("artist -a -b", repository.lastSearchContent)
        assertEquals(0, repository.detailCallCount)
        assertEquals(listOf(1, 2), page.data.map { it.id })
    }

    @Test
    fun blankKeywordFailsInsteadOfAskingTheServerForAnEmptySearch() = runBlocking {
        val repository = FakeComicRepository()
        val source = SearchComicPagingSource(
            comicRepository = repository,
            filter = SearchComicFilter(searchContent = "   ", excludedTags = emptyList())
        )

        val result = source.load(PagingSource.LoadParams.Refresh(null, 20, false))

        // 上游把空 keyword 当「没有搜索条件」，返回的是推荐列表；那批数据会被当成搜索结果
        // 展示且没有任何报错。空查询必须在本地就失败。
        val error = result as PagingSource.LoadResult.Error<Int, Comic>
        assertEquals("没有可搜索的关键词，请重新输入", error.throwable.message)
        assertNull(repository.lastSearchContent)
    }

    @Test
    fun exclusionsOnlyQueryStillSearches() = runBlocking {
        val repository = FakeComicRepository()
        val source = SearchComicPagingSource(
            comicRepository = repository,
            filter = SearchComicFilter(searchContent = "", excludedTags = listOf("a"))
        )

        source.load(PagingSource.LoadParams.Refresh(null, 20, false))

        // 只有排除项也是合法查询，不能因为关键词为空就拦掉。
        assertEquals("-a", repository.lastSearchContent)
    }

    @Test
    fun dateFilterIsForwardedWithoutTouchingKeyword() = runBlocking {
        val repository = FakeComicRepository()
        val source = SearchComicPagingSource(
            comicRepository = repository,
            filter = SearchComicFilter(
                searchContent = "artist",
                excludedTags = listOf("a"),
                year = "2024",
                month = "3",
            )
        )

        source.load(PagingSource.LoadParams.Refresh(null, 20, false))

        // 年月只作为独立参数下传；关键词与排除项保持原拼装。
        assertEquals("artist -a", repository.lastSearchContent)
        assertEquals("2024", repository.lastYear)
        assertEquals("3", repository.lastMonth)
    }

    private class FakeComicRepository : ComicRepository {
        var lastSearchContent: String? = null
        var lastYear: String? = null
        var lastMonth: String? = null
        var detailCallCount = 0

        override suspend fun getComicList(
            page: Int,
            order: ComicSearchOrderFilter,
            searchContent: String,
            year: String,
            month: String,
        ): NetWorkResult<ComicSearchPage> {
            lastSearchContent = searchContent
            lastYear = year
            lastMonth = month
            return NetWorkResult.Success(
                ComicSearchPage(
                    items = listOf(
                        comic(id = 1),
                        comic(id = 2),
                    ),
                    total = 2,
                    redirectComicId = null,
                )
            )
        }

        override suspend fun getComicDetail(id: Int): NetWorkResult<Comic> {
            detailCallCount++
            fail("搜索排除不应再请求漫画详情 id=$id")
            throw AssertionError("unreachable")
        }

        override suspend fun getComicIdsByTag(tagName: String, maxPages: Int): Set<Int> {
            error("getComicIdsByTag should not be used for search exclusions")
        }

        override suspend fun collectComic(id: Int): NetWorkResult<Unit> = unused()

        override suspend fun unCollectComic(id: Int): NetWorkResult<Unit> = unused()

        override suspend fun getEmbeddedHomeCategory(categoryId: String): NetWorkResult<List<Comic>> =
            unused()

        override suspend fun getNetworkHomePage(): NetWorkResult<List<HomeComicSwiperItem>> = unused()

        override suspend fun getComicPicList(id: Int): NetWorkResult<ComicPageList> = unused()

        override suspend fun downloadImageBytes(comicId: Int, imageIndex: Int): ByteArray? = unused()

        override suspend fun getWeekData(): NetWorkResult<WeekData> = unused()

        override suspend fun getWeekRecommendComicList(
            page: Int,
            categoryId: String,
            typeId: String
        ): NetWorkResult<ComicPage> = unused()

        override suspend fun getCommentList(page: Int, comicId: Int): NetWorkResult<CommentPage> = unused()

        override suspend fun comment(
            content: String,
            comicId: Int,
            commentId: Int?
        ): NetWorkResult<ActionResult> = unused()

        private fun comic(id: Int): Comic = Comic(
            id = id,
            name = "comic $id",
            authorList = listOf("author"),
            description = "",
            readCount = 0,
            likeCount = 0,
            commentCount = 0,
            tagList = listOf("category"),
            roleList = emptyList(),
            workList = emptyList(),
            price = 0,
        )

        private fun unused(): Nothing {
            throw UnsupportedOperationException("Unused fake repository method")
        }
    }
}
