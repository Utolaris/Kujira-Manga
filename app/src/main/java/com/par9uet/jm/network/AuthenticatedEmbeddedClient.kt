package com.par9uet.jm.network

import com.par9uet.jm.core.network.AuthenticatedSessionRequiredException
import com.par9uet.jm.core.network.isFormBodyNullParameter
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
                throw AuthenticatedSessionRequiredException("登录会话已失效，请重新登录", error)
            }
            throw error
        } catch (error: NullPointerException) {
            // OkHttp FormBody.Builder.add 在密码缓存缺失时会以 Kotlin 非空断言崩溃
            // （多端互踢后库试图用 username+null 密码自动重登）。视为会话失效。
            if (error.isFormBodyNullParameter()) {
                throw AuthenticatedSessionRequiredException("登录会话已失效，请重新登录", error)
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

private fun ResponseException.isAuthenticationFailure(): Boolean {
    val detail = message.orEmpty()
    return errorCode == 401 ||
        detail.contains("登入") ||
        detail.contains("登录") ||
        detail.contains("login", ignoreCase = true)
}
