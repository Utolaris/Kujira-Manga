package com.par9uet.jm.core

import com.par9uet.jm.core.network.AuthFailure
import com.par9uet.jm.core.network.AuthenticatedSessionRequiredException
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.core.network.NetworkErrorKind
import com.par9uet.jm.core.network.ResponseWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Repository error mapping is what upstream uses to decide "login expired" vs "try again later"
 * and "retry" vs "give up", so the classification below is regression-critical.
 */
class BaseRepositoryErrorMappingTest {

    private val repository = object : BaseRepository() {
        suspend fun <T> embedded(operation: String, block: suspend () -> T): NetWorkResult<T> =
            safeEmbeddedCall(operation, block)
    }

    @Test
    fun httpExceptionsKeepTransportStatusWithoutBlamingCredentials() = runTest {
        for (code in listOf(401, 403, 429, 500, 503)) {
            val response = retrofit2.Response.error<String>(
                code, "upstream error".toResponseBody()
            )
            val result = repository.safeApiCall<String> { throw retrofit2.HttpException(response) }
            result as NetWorkResult.Error
            assertEquals(code, result.code)
            assertEquals("网络错误：$code", result.message)
        }
    }

    @Test
    fun serverErrorEnvelopeKeepsServerCodeAndMessage() = runTest {
        // 401 must stay distinguishable from a 500 so login expiry can be detected upstream.
        val unauthorized = repository.safeApiCall {
            ResponseWrapper.Error(errorMsg = "登录已失效", code = 401)
        }
        unauthorized as NetWorkResult.Error
        assertEquals(401, unauthorized.code)
        assertEquals("登录已失效", unauthorized.message)

        val server = repository.safeApiCall<String> {
            ResponseWrapper.Error(errorMsg = "服务器开小差", code = 500)
        }
        server as NetWorkResult.Error
        assertEquals(500, server.code)
    }

    @Test
    fun successEnvelopeWithoutPayloadIsReportedAsEmptyInsteadOfSuccess() = runTest {
        // 点赞这类接口返回 code=200 但没有 data，只能按空响应处理，不能当成成功数据。
        val result = repository.safeApiCall<String> { ResponseWrapper(code = 200, data = null) }
        result as NetWorkResult.Error
        assertEquals("响应数据为空", result.message)
    }

    @Test
    fun transientNetworkFailuresAreClassifiedAsTemporary() = runTest {
        val cases = listOf(
            SocketTimeoutException() to "网络连接超时",
            ConnectException() to "网络连接失败",
            UnknownHostException() to "网络不可用",
            IOException() to "网络请求失败",
        )
        for ((throwable, expectedMessage) in cases) {
            val result = repository.safeApiCall<String> { throw throwable }
            result as NetWorkResult.Error
            assertEquals(expectedMessage, result.message)
            assertEquals(AuthFailure.TemporaryFailure, result.authFailure)
        }
    }

    @Test
    fun cancellationIsNeverSwallowedIntoAnError() = runTest {
        val cancellation = CancellationException("cancelled")
        val apiCallFailure = runCatching {
            repository.safeApiCall<String> { throw cancellation }
        }
        assertSame(cancellation, apiCallFailure.exceptionOrNull())

        val embeddedFailure = runCatching {
            repository.embedded("内置操作") { throw cancellation }
        }
        assertPropagatedAcrossDispatcher(cancellation, embeddedFailure.exceptionOrNull())
    }

    @Test
    fun sessionRecoveryExceptionKeepsTheStructuredErrorIntact() = runTest {
        val structured = NetWorkResult.Error(
            message = "登录会话已失效，请重新登录",
            authFailure = AuthFailure.InvalidCredentials,
            kind = NetworkErrorKind.Authentication,
        )
        val result = repository.embedded("内置操作") { throw SessionRecoveryException(structured) }
        // The whole point of SessionRecoveryException: no re-wrap, no lost classification.
        assertSame(structured, result)
    }

    @Test
    fun unauthenticatedEmbeddedCallIsClassifiedAsAuthenticationNotAsGenericFailure() = runTest {
        // 门控在没有可用会话时抛这个异常。它必须在 kind 上标成 Authentication，
        // 否则收藏弹窗（按 errorKind == Authentication 判断需要登录）只会显示普通错误，
        // 用户看不到登录引导。
        val result = repository.embedded<String>("内置 API 收藏失败") {
            throw AuthenticatedSessionRequiredException()
        }
        result as NetWorkResult.Error
        assertEquals(NetworkErrorKind.Authentication, result.kind)
        assertEquals("请先登录", result.message)
    }

    @Test
    fun embeddedOrdinaryExceptionBecomesFailureWithItsOperationPrefix() = runTest {
        val result = repository.embedded("测试失败") { throw IllegalStateException("boom") }
        assertEquals("测试失败：boom", (result as NetWorkResult.Error).message)
    }

    @Test
    fun fatalJvmErrorsPropagateThroughBothCallBoundaries() = runTest {
        val fatal = LinkageError("linkage")
        assertPropagatedAcrossDispatcher(fatal, runCatching { repository.embedded<String>("测试失败") { throw fatal } }.exceptionOrNull())
        assertSame(fatal, runCatching { repository.safeApiCall<String> { throw fatal } }.exceptionOrNull())
    }

    @Test
    fun embeddedFailuresKeepTheOperationPrefix() = runTest {
        val result = repository.embedded("内置 API 获取历史漫画失败") { throw IOException("boom") }
        result as NetWorkResult.Error
        assertEquals("内置 API 获取历史漫画失败：boom", result.message)
    }

    private fun assertPropagatedAcrossDispatcher(original: Throwable, actual: Throwable?) {
        assertEquals(original.javaClass, actual?.javaClass)
        assertEquals(original.message, actual?.message)
        // Coroutine stacktrace recovery may copy an exception across withContext and
        // retain the original as its cause. Both forms preserve the thrown failure.
        assertTrue("Must propagate the original failure or its recovered copy", actual === original || actual?.cause === original)
    }

}
