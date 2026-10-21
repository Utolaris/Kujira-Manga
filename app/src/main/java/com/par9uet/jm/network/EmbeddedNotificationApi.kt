package com.par9uet.jm.network

import com.par9uet.jm.core.network.OfficialApiSignature
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient
import io.github.jukomu.jmcomic.core.constant.JmConstants
import io.github.jukomu.jmcomic.core.net.model.JmApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MultipartBody
import okhttp3.Request

/** 官方通知协议；共享活动会话、域名切换、DoH 和 SDK 传输。 */
class EmbeddedNotificationApi(private val authenticated: AuthenticatedEmbeddedClient) {
    suspend fun request(method: String, path: String, parameters: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            requireNotNull(authenticated.withClient { client -> executeNotificationRequest(client, method, path, parameters) })
        }
}

internal fun executeNotificationRequest(
    client: JmApiClient, method: String, path: String, parameters: Map<String, String>,
): String {
    require(method in setOf("GET", "POST"))
    require(path in setOf("notifications", "notifications/unreadCount", "album_sertracking"))
    require(path != "notifications/unreadCount" || method == "GET")
    val url = HttpUrl.Builder().scheme("https").host(JmConstants.PLACEHOLDER_HOST).apply {
        path.split('/').forEach { addPathSegment(it) }
        if (method == "GET") parameters.forEach { (key, value) -> addQueryParameter(key, value) }
    }.build()
    val timestamp = (System.currentTimeMillis() / 1000).toString()
    val request = Request.Builder().url(url)
        .header("Token", OfficialApiSignature.token(timestamp))
        .header("Tokenparam", OfficialApiSignature.tokenParam(timestamp))
    if (method == "POST") request.post(MultipartBody.Builder().setType(MultipartBody.FORM).apply {
        parameters.forEach { (key, value) -> addFormDataPart(key, value) }
    }.build())
    return JmApiResponse(client.executeRequest(request.build()), timestamp).apply { requireSuccess() }.decodedData
}
