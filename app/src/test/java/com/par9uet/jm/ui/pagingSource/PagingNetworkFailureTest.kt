package com.par9uet.jm.ui.pagingSource

import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicPage
import com.par9uet.jm.data.models.ComicSearchOrderFilter
import com.par9uet.jm.data.models.ComicSearchPage
import com.par9uet.jm.data.models.Comment
import com.par9uet.jm.data.models.CommentPage
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.session.UserRepository
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class PagingNetworkFailureTest(private val endpoint: Endpoint) {
    enum class Endpoint { SEARCH, HISTORY_COMICS, HISTORY_COMMENTS }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun endpoints(): List<Array<Endpoint>> = Endpoint.entries.map { arrayOf(it) }
    }

    @Test
    fun thrownRequestFailuresBecomeLoadErrorsAndTheSamePageCanRetry() = runTest {
        for (failure in listOf(
            IOException("网络断开"),
            UnknownHostException("无法解析域名"),
            SocketTimeoutException("请求超时"),
            IllegalStateException("响应解析失败"),
        )) {
            for (params in listOf(
                PagingSource.LoadParams.Refresh<Int>(null, 20, false),
                PagingSource.LoadParams.Append(2, 20, false),
            )) {
                val requests = Requests().apply { thrownFailure = failure }
                val source = requests.source()
                val result = source.load(params) as PagingSource.LoadResult.Error
                assertSame(failure, result.throwable)

                requests.thrownFailure = null
                val retry = source.load(params) as PagingSource.LoadResult.Page
                assertEquals(20, retry.data.size)
                assertEquals(listOf(params.key ?: 1, params.key ?: 1), requests.pages)
            }
        }
    }

    @Test
    fun returnedNetworkErrorsStillReportFailureAndAllowRetry() = runTest {
        val requests = Requests().apply { returnedFailure = NetWorkResult.Error("网络不可用") }
        val source = requests.source()
        val params = PagingSource.LoadParams.Refresh<Int>(null, 20, false)
        val error = source.load(params) as PagingSource.LoadResult.Error
        assertEquals("网络不可用", error.throwable.message)

        requests.returnedFailure = null
        assertTrue(source.load(params) is PagingSource.LoadResult.Page)
    }

    @Test
    fun cancellationRemainsCancellationInsteadOfBecomingARetryableError() = runTest {
        val cancellation = CancellationException("页面已离开")
        val requests = Requests().apply { thrownFailure = cancellation }
        val result = runCatching {
            requests.source().load(PagingSource.LoadParams.Refresh(null, 20, false))
        }
        assertSame(cancellation, result.exceptionOrNull())
    }

    @Test
    fun refreshFailureAfterResubscriptionKeepsCachedItemsAndCanRetry() = runTest {
        val requests = Requests()
        val pager = Pager(PagingConfig(pageSize = 20, enablePlaceholders = false)) {
            requests.source()
        }.flow.cachedIn(backgroundScope)
        fun presenter() = object : PagingDataPresenter<Any>(StandardTestDispatcher(testScheduler)) {
            override suspend fun presentPagingDataEvent(event: PagingDataEvent<Any>) = Unit
        }

        val first = presenter()
        val leaving = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            pager.collectLatest(first::collectFrom)
        }
        runCurrent()
        val cached = first.snapshot().items
        assertEquals(20, cached.size)
        leaving.cancel()
        runCurrent()

        val offline = IOException("后台返回时网络断开")
        requests.thrownFailure = offline
        val returning = presenter()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            pager.collectLatest(returning::collectFrom)
        }
        runCurrent()
        assertEquals(cached, returning.snapshot().items)
        assertEquals(listOf(1), requests.pages)

        // Exercise Pager refresh after a collector resubscribes; this is not an Activity lifecycle test.
        returning.refresh()
        runCurrent()
        val error = returning.loadStateFlow.value?.refresh as LoadState.Error
        assertSame(offline, error.error)
        assertEquals(cached, returning.snapshot().items)

        requests.thrownFailure = null
        returning.retry()
        runCurrent()
        assertTrue(returning.loadStateFlow.value?.refresh is LoadState.NotLoading)
        assertEquals(cached, returning.snapshot().items)
        assertEquals(listOf(1, 1, 1), requests.pages)
    }

    private inner class Requests {
        var thrownFailure: Exception? = null
        var returnedFailure: NetWorkResult.Error? = null
        val pages = mutableListOf<Int>()
        private val comics = List(20) { Comic.create(it + 1, "漫画 ${it + 1}", emptyList()) }
        private val comments = List(20) {
            Comment(1, 1, it + 1, "", "评论 ${it + 1}", "用户", "", "", 0, false, emptyList())
        }

        private fun <T> respond(page: Int, data: T): NetWorkResult<T> {
            pages += page
            thrownFailure?.let { throw it }
            return returnedFailure ?: NetWorkResult.Success(data)
        }

        @Suppress("UNCHECKED_CAST")
        fun source(): PagingSource<Int, Any> = when (endpoint) {
            Endpoint.SEARCH -> SearchComicPagingSource(
                object : ComicRepository by unusedRepository<ComicRepository>() {
                    override suspend fun getComicList(
                        page: Int,
                        order: ComicSearchOrderFilter,
                        searchContent: String,
                        year: String,
                        month: String,
                    ) = respond(page, ComicSearchPage(comics, 20, null))
                },
                SearchComicFilter(searchContent = "漫画"),
            )
            Endpoint.HISTORY_COMICS -> HistoryComicPagingSource(
                object : UserRepository by unusedRepository<UserRepository>() {
                    override suspend fun getHistoryComicList(page: Int) = respond(page, ComicPage(comics))
                },
            )
            Endpoint.HISTORY_COMMENTS -> HistoryCommentPagingSource(
                object : UserRepository by unusedRepository<UserRepository>() {
                    override suspend fun getHistoryCommentList(page: Int, userId: Int) =
                        respond(page, CommentPage(comments, 20))
                },
                userId = 1,
            )
        } as PagingSource<Int, Any>
    }

    private inline fun <reified T> unusedRepository(): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ -> error("Unexpected request: ${method.name}") } as T
}
