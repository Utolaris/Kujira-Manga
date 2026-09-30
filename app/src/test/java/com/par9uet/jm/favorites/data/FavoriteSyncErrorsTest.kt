package com.par9uet.jm.favorites.data

import com.par9uet.jm.core.SessionRecoveryException
import com.par9uet.jm.core.model.LOCAL_MODE_UNAVAILABLE_MESSAGE
import com.par9uet.jm.core.network.AuthFailure
import com.par9uet.jm.core.network.LocalModeUnavailableException
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.core.network.NetworkErrorKind
import io.github.jukomu.jmcomic.api.exception.ParseResponseException
import io.github.jukomu.jmcomic.api.exception.ResponseException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收藏同步的错误分类：分类顺序错一处，用户就会在本地模式下看到「登录已失效」，
 * 或者拿不到任何可操作的恢复指引。
 */
class FavoriteSyncErrorsTest {

    @Test
    fun `local mode refusal is not reported as an expired login`() {
        // LocalModeUnavailableException 继承了 AuthenticatedSessionRequiredException。
        // 一旦 localModeBlocked 分支排到 authException 之后，这里会变成 Authentication +
        // 「登录已失效，请重新登录后同步」——正是这个分支存在的理由。
        val result = LocalModeUnavailableException().toFavoriteSyncError()

        assertEquals(NetworkErrorKind.Unknown, result.kind)
        assertEquals(LOCAL_MODE_UNAVAILABLE_MESSAGE, result.message)
        assertEquals(-1, result.code)
    }

    @Test
    fun `local mode refusal nested under another failure still wins`() {
        val wrapped = IllegalStateException("wrap", LocalModeUnavailableException())

        val result = wrapped.toFavoriteSyncError()

        assertEquals(NetworkErrorKind.Unknown, result.kind)
        assertEquals(LOCAL_MODE_UNAVAILABLE_MESSAGE, result.message)
    }

    @Test
    fun `a cooldown error keeps its own classification instead of a nested 401`() {
        val cooldown = NetWorkResult.Error(
            message = "登录会话已失效，请重新登录",
            authFailure = AuthFailure.InvalidCredentials,
            kind = NetworkErrorKind.Network,
        )

        // 退避期内的错误必须原样返回，不能被重新包装成别的分类。
        assertSame(cooldown, SessionRecoveryException(cooldown).toFavoriteSyncError())
    }

    @Test
    fun `cancellation is rethrown rather than turned into a sync failure`() {
        val cancellation = CancellationException("cancelled")

        val thrown = assertThrows(CancellationException::class.java) {
            cancellation.toFavoriteSyncError()
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun `a form body null parameter keeps the login state and says so`() {
        val error = NullPointerException(
            "Parameter specified as non-null is null: method okhttp3.FormBody\$Builder.add, parameter value",
        )

        val result = error.toFavoriteSyncError()

        assertEquals("请求构造失败，已保留登录状态，请稍后重试", result.message)
        assertEquals(NetworkErrorKind.Unknown, result.kind)
    }

    @Test
    fun `server failures carry the http status while parsing failures do not`() {
        val server = ResponseException("upstream busy", 503).toFavoriteSyncError()
        assertEquals(NetworkErrorKind.Server, server.kind)
        assertEquals(503, server.code)
        assertTrue(server.message.orEmpty().contains("HTTP 503"))
        assertTrue(server.message.orEmpty().contains("upstream busy"))

        // ParseResponseException 不是 ResponseException，没有 HTTP 状态可报。
        val parsing = ParseResponseException("invalid payload").toFavoriteSyncError()
        assertEquals(NetworkErrorKind.Parsing, parsing.kind)
        assertEquals(-1, parsing.code)
    }

    @Test
    fun `cause chain text flattens newlines truncates each message and caps depth`() {
        assertEquals(
            "IllegalStateException:a b",
            IllegalStateException("a\nb").causeChainText(),
        )

        var deep: Throwable = IllegalStateException("root")
        repeat(11) { deep = RuntimeException("level$it", deep) }
        assertEquals(8, deep.causeChainText(maxDepth = 8).split(" <- ").size)

        // 单条消息截到 160 字符：日志里不能出现无界的一行。
        val truncated = IllegalStateException("x".repeat(400)).causeChainText()
        assertEquals("IllegalStateException:".length + 160, truncated.length)
        assertFalse(truncated.contains('\n'))
    }
}
