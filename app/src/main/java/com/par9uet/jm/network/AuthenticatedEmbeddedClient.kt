package com.par9uet.jm.network

import com.par9uet.jm.core.network.AuthenticatedSessionRequiredException
import com.par9uet.jm.core.network.isFormBodyNullParameter
import com.par9uet.jm.utils.logError
import com.google.gson.JsonParser
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
        call(block)
    }

    /** Read-only startup probe must not wait on the readiness it is responsible for resolving. */
    fun probeSession(): Unit = call<Unit> { client ->
        requireNotNull(client.getFavorites(
            io.github.jukomu.jmcomic.api.model.FavoriteQuery.Builder().folderId(0).page(1).build(),
        ))
    } ?: error("会话探活未返回结果")

    private fun <T> call(block: (JmApiClient) -> T): T? =
        try {
            block(clientProvider())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ParseResponseException) {
            // JMComic-Api-Java 1.1.8 postComment/replyToComment still call
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
                    "服务端未接受当前会话（$detail）",
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
            // 保留调用栈帮助定位 SDK 问题，但异常本身不证明会话失效。
            // 栈里没有凭据，可以安全落盘。
            if (error.isFormBodyNullParameter()) {
                logError(
                    "AuthEmbedded",
                    "SDK FormBody null parameter（不判定为会话失效）；调用栈=" +
                        error.stackTrace.take(FORM_BODY_STACK_FRAMES).joinToString(" <- ") { frame ->
                            "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
                        },
                )
            }
            throw error
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

/** Require a protocol code, never infer authentication failure from human-readable prose. */
internal fun ResponseException.isAuthenticationFailure(): Boolean {
    if (errorCode == 401) return true
    if (errorCode !in 200..299) return false
    // The SDK wraps business-code rejection with the original HTTP status and JSON body.
    val payload = message.orEmpty().substringAfter("error message: ", "")
    return runCatching {
        JsonParser.parseString(payload).asJsonObject.get("code")?.asInt == 401
    }.getOrDefault(false)
}

/**
 * FormBody null 参数 NPE 落栈的帧数上限。
 * 只取前若干帧就够定位调用方（栈顶是 okhttp，紧接着就是我们/SDK 的调用点）。
 */
private const val FORM_BODY_STACK_FRAMES = 12
