package com.par9uet.jm.network

import com.par9uet.jm.core.network.AuthenticatedSessionRequiredException
import com.par9uet.jm.core.network.isFormBodyNullParameter
import com.par9uet.jm.utils.logError
import io.github.jukomu.jmcomic.api.exception.ParseResponseException
import io.github.jukomu.jmcomic.api.exception.ResponseException
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient
import kotlinx.coroutines.CancellationException

/** The single entry point for requests that require an authenticated Embedded session. */
class AuthenticatedEmbeddedClient(
    embeddedClientManager: EmbeddedClientManager,
    private val requestGate: AuthenticatedRequestGate,
) {
    private val clientProvider = embeddedClientManager::getClient

    suspend fun <T> withClient(block: (JmApiClient) -> T): T? = requestGate.run {
        try {
            block(clientProvider())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ParseResponseException) {
            // Defense in depth after EmbeddedClientManager now caches username on cookie
            // restore. JMComic-Api-Java 1.1.8 postComment/replyToComment still call
            // getLoggedInUserName() AFTER the server accepted the comment POST. If that
            // cache is missing for any reason, the parser wraps IllegalStateException into
            // ParseResponseException. Retrying would duplicate the remote comment, so treat
            // this exact shape as success.
            if (error.isUpstreamCommentUsernameMappingFailure()) {
                null
            } else {
                throw error
            }
        } catch (error: ResponseException) {
            if (error.isAuthenticationFailure()) {
                val detail = "HTTP ${error.errorCode} ${error.message.orEmpty()}"
                logError("AuthEmbedded", "auth-classified ResponseException: $detail")
                throw AuthenticatedSessionRequiredException(
                    "登录会话已失效，请重新登录（$detail）",
                    error,
                )
            }
            logError(
                "AuthEmbedded",
                "non-auth ResponseException HTTP=${error.errorCode} message=${error.message}",
            )
            throw error
        } catch (error: NullPointerException) {
            // OkHttp FormBody.Builder.add 收到 Kotlin 非空参数为 null 时崩溃。
            //
            // 注意：日志里那条 message 只说明「某个 add 的 value 是 null」，**不说是谁调的**。
            // 2026-09-18 晚间日志里这类 NPE 出现了 14 次（真实 401 只有 3 次），
            // 却因为拿不到调用点而无法定位 —— 所以这里必须记一段栈，否则永远只能猜。
            // 栈里没有凭据，可以安全落盘。
            if (error.isFormBodyNullParameter()) {
                logError(
                    "AuthEmbedded",
                    "auth-classified FormBody null parameter；调用栈=" +
                        error.stackTrace.take(FORM_BODY_STACK_FRAMES).joinToString(" <- ") { frame ->
                            "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
                        },
                )
                throw AuthenticatedSessionRequiredException(
                    "登录会话已失效，请重新登录后同步（SDK 自动重登缺少密码，请走应用内重新登录）",
                    error,
                )
            }
            throw error
        }
    }
}

/**
 * Matches ONLY the verified 1.1.8 post-success username exception produced by
 * ApiParser.parseCommentSubmitResult <- AbstractJmClient.getLoggedInUserName():
 * an IllegalStateException with message "Username is required for this operation. Please login
 * first." wrapped by ParseResponseException("Failed to parse comment submit result API JSON").
 */
internal fun ParseResponseException.isUpstreamCommentUsernameMappingFailure(): Boolean =
    message == "Failed to parse comment submit result API JSON" &&
        cause is IllegalStateException &&
        cause?.message == "Username is required for this operation. Please login first."

/**
 * 会话失效判定。避免把任意含「登录」二字的服务端文案都收成 Authentication——
 * 那会让收藏同步失败只弹「请重新登录」、丢掉真正原因。
 */
internal fun ResponseException.isAuthenticationFailure(): Boolean {
    if (errorCode == 401) return true
    val detail = message.orEmpty()
    if (detail.isBlank()) return false
    val patterns = listOf(
        "未登录",
        "需要登录",
        "请登录",
        "请先登录",
        "重新登录",
        "登入失败",
        "登录失败",
        "登录已失效",
        "登录会话",
        "会话失效",
        "会话过期",
        "unauthorized",
        "unauthenticated",
        "login required",
        "not login",
        "please login",
        "please log in",
    )
    return patterns.any { detail.contains(it, ignoreCase = true) }
}

/**
 * FormBody null 参数 NPE 落栈的帧数上限。
 * 只取前若干帧就够定位调用方（栈顶是 okhttp，紧接着就是我们/SDK 的调用点）。
 */
private const val FORM_BODY_STACK_FRAMES = 12
