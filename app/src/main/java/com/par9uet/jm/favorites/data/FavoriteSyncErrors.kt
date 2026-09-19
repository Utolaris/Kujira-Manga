package com.par9uet.jm.favorites.data

import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.core.network.NetworkErrorKind
import com.par9uet.jm.core.network.AuthenticatedSessionRequiredException
import com.par9uet.jm.core.network.isFormBodyNullParameter
import io.github.jukomu.jmcomic.api.exception.NetworkException
import io.github.jukomu.jmcomic.api.exception.ParseResponseException
import io.github.jukomu.jmcomic.api.exception.ResponseException
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** 把异常链压成一行，供日志与用户可见文案使用，避免只剩「登录失效」。 */
internal fun Throwable.causeChainText(maxDepth: Int = 8): String {
    return generateSequence(this) { it.cause }
        .take(maxDepth)
        .joinToString(" <- ") { t ->
            val msg = t.message?.replace('\n', ' ')?.take(160).orEmpty()
            "${t.javaClass.simpleName}:$msg"
        }
}

/** Classify before the sync boundary loses the SDK exception and its nested cause. */
internal fun Throwable.toFavoriteSyncError(): NetWorkResult.Error {
    val causes = generateSequence(this) { it.cause }.take(16).toList()
    causes.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
    val response = causes.filterIsInstance<ResponseException>().firstOrNull()
    val authException = causes.filterIsInstance<AuthenticatedSessionRequiredException>().firstOrNull()
    val formBodyNull = isFormBodyNullParameter()
    val chain = causeChainText()
    val kind = when {
        authException != null || response?.errorCode == 401 || formBodyNull ->
            NetworkErrorKind.Authentication
        causes.any { it is ParseResponseException } -> NetworkErrorKind.Parsing
        response != null -> NetworkErrorKind.Server
        causes.any { it is NetworkException || it is IOException } -> NetworkErrorKind.Network
        else -> NetworkErrorKind.Unknown
    }
    val message = when (kind) {
        NetworkErrorKind.Network -> {
            val detail = causes.firstOrNull { it is NetworkException || it is IOException }
                ?.message?.replace('\n', ' ')?.take(120).orEmpty()
            buildString {
                append("网络连接失败，请检查网络后重试")
                if (detail.isNotBlank()) append("：").append(detail)
            }
        }
        NetworkErrorKind.Authentication -> {
            val detail = when {
                formBodyNull -> "SDK 自动重登缺少密码，请在应用内重新登录"
                authException != null -> authException.message?.replace('\n', ' ')?.take(160)
                    ?: chain.take(160)
                response != null -> "HTTP ${response.errorCode} ${response.message?.take(120).orEmpty()}"
                else -> chain.take(160)
            }
            buildString {
                append("登录已失效，请重新登录后同步")
                if (detail.isNotBlank()) append("：").append(detail)
            }
        }
        NetworkErrorKind.Server -> {
            val status = response?.errorCode?.takeIf { it > 0 }?.toString().orEmpty()
            val serverMsg = response?.message?.replace('\n', ' ')?.take(120).orEmpty()
            buildString {
                append("收藏同步被服务器拒绝")
                if (status.isNotBlank()) append("（HTTP ").append(status).append("）")
                if (serverMsg.isNotBlank()) append("：").append(serverMsg)
                if (serverMsg.isBlank() && status.isBlank()) append("，请稍后重试")
            }
        }
        NetworkErrorKind.Parsing -> {
            val parseMsg = causes.firstOrNull { it is ParseResponseException }
                ?.message?.replace('\n', ' ')?.take(120).orEmpty()
            buildString {
                append("收藏同步响应解析失败，请稍后重试或更新应用")
                if (parseMsg.isNotBlank()) append("：").append(parseMsg)
            }
        }
        NetworkErrorKind.Unknown -> {
            val msg = this.message?.replace('\n', ' ')?.take(200)
            if (!msg.isNullOrBlank()) "收藏同步失败：$msg" else "收藏同步失败，请重试（$chain）"
        }
    }
    return NetWorkResult.Error(
        message = message,
        code = response?.errorCode ?: -1,
        kind = kind,
        cause = this,
    )
}
