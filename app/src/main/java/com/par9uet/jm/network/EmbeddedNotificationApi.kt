package com.par9uet.jm.network

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.Strictness
import com.par9uet.jm.core.network.OfficialApiSignature
import io.github.jukomu.jmcomic.api.exception.JmComicException
import io.github.jukomu.jmcomic.api.exception.ResponseException
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient
import io.github.jukomu.jmcomic.core.constant.JmConstants
import io.github.jukomu.jmcomic.core.crypto.JmCryptoTool
import com.par9uet.jm.core.concurrent.backgroundIoDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MultipartBody
import okhttp3.Request

/** 官方通知协议；共享活动会话、域名切换、DoH 和 SDK 传输。 */
class EmbeddedNotificationApi(private val authenticated: AuthenticatedEmbeddedClient) {
    suspend fun request(method: String, path: String, parameters: Map<String, String>): String =
        withContext(backgroundIoDispatcher()) {
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
    // executeRequest already validates HTTP. Check the business code here without invoking
    // JmApiResponse.requireSuccess(), which would also attempt its own single-key decryption.
    val response = client.executeRequest(request.build())
    val envelope = notificationResponseJson.fromJson(response.text, JsonObject::class.java)
        ?: error("通知接口响应为空")
    val code = envelope.get("code")?.asJsonPrimitive
    if (code?.isNumber != true || code.asInt != 200) {
        // Keep the SDK's exception contract so the authentication gate can classify code 401.
        throw ResponseException("HTTP ${response.httpCode}, error message: ${response.text}", response.httpCode)
    }
    // Writes are acknowledged by HTTP/business success. Their message is not a mandatory
    // encrypted payload: failing to decode it must not turn a successful toggle into a retry.
    if (method == "POST") return ""
    val data = envelope.get("data")
    check(data != null && !data.isJsonNull) { "通知接口缺少响应数据" }
    // Preserve JSON values before the SDK's getEncodedData() coerces them into strings.
    // In particular, a literal false is a subscription status, not AES ciphertext.
    if (!data.isJsonPrimitive || !data.asJsonPrimitive.isString) return data.toString()
    if (path == "album_sertracking" && data.asString.lowercase() in setOf("true", "false", "0", "1")) {
        return data.toString()
    }
    return decodeNotificationData(data.asString, timestamp)
}

private val notificationResponseJson = GsonBuilder().setStrictness(Strictness.STRICT).create()

/** Official HttpUtil.tryDecryption tries both salts against the same response, not another POST. */
private fun decodeNotificationData(encodedData: String, timestamp: String): String {
    // The SDK substitutes a missing-album object for empty data; notification calls must fail instead.
    check(encodedData !in setOf("", "[]", "null")) { "通知接口缺少加密响应" }
    var failure: Exception? = null
    for (secret in listOf(OfficialApiSignature.SECRET, "18comicAPPContent")) {
        try {
            val decoded = JmCryptoTool.decryptApiResponse(encodedData, timestamp, secret)
            // A successful AES operation alone is insufficient; the official client requires JSON.
            requireNotNull(notificationResponseJson.fromJson(decoded, JsonElement::class.java))
            return decoded
        } catch (error: Exception) {
            failure = error
        }
    }
    throw JmComicException("Failed to decrypt API response", failure)
}
