package com.par9uet.jm.network

import com.par9uet.jm.core.network.OfficialApiSignature
import io.github.jukomu.jmcomic.core.net.model.JmApiResponse
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient
import io.github.jukomu.jmcomic.core.constant.JmConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.Request

/** SDK 未封装 blogs/blog，复用其签名、域名重试、DoH 和活动会话，不另建 HTTP 客户端。 */
class EmbeddedDinnerApi(private val clientProvider: () -> JmApiClient) {
    suspend fun get(path: String, parameters: Map<String, String>): String = withContext(Dispatchers.IO) {
        require(path == "blogs" || path == "blog")
        val client = clientProvider()
        // 与 SDK 内置 GET 相同，域名拦截器负责把占位 host 换成当前固定/可用 API 域名。
        val url = HttpUrl.Builder().scheme("https").host(JmConstants.PLACEHOLDER_HOST).addPathSegment(path).apply {
            parameters.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val request = Request.Builder().url(url)
            .header("Token", OfficialApiSignature.token(timestamp))
            .header("Tokenparam", OfficialApiSignature.tokenParam(timestamp))
            .build()
        JmApiResponse(client.executeRequest(request), timestamp).apply { requireSuccess() }.decodedData
    }
}
