package com.par9uet.jm.retrofit.interceptor

import com.par9uet.jm.storage.ApiEndpointPreference
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

class BaseUrlInterceptor(
    private val apiEndpointPreference: ApiEndpointPreference,
    private val fallbackEndpoints: List<String> = emptyList(),
) : Interceptor {

    private fun getBaseUrl() = apiEndpointPreference.apiEndpoint.value

    private fun candidateEndpoints(): List<String> {
        val primary = getBaseUrl()
        if (fallbackEndpoints.isEmpty() || primary !in fallbackEndpoints) {
            return listOf(primary)
        }
        return buildList(fallbackEndpoints.size) {
            add(primary)
            fallbackEndpoints.forEach { if (it != primary) add(it) }
        }
    }

    private fun rewriteRequest(request: Request, baseUrl: String): Request {
        val parsed = baseUrl.toHttpUrlOrNull()
        val newUrl = if (parsed != null) {
            request.url.newBuilder()
                .scheme(parsed.scheme)
                .host(parsed.host)
                .apply {
                    // Only override port when the base URL explicitly specifies a non-default port;
                    // otherwise keep the original request's port (preserves MockWebServer in tests).
                    if (parsed.port != okhttp3.HttpUrl.defaultPort(parsed.scheme)) {
                        port(parsed.port)
                    }
                }
                .build()
        } else {
            request.url.newBuilder()
                .scheme(baseUrl.split("://")[0])
                .host(baseUrl.split("://")[1].removeSuffix("/"))
                .build()
        }
        return request.newBuilder().url(newUrl).build()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val candidates = if (original.method == "GET") candidateEndpoints() else listOf(getBaseUrl())
        var lastError: IOException? = null

        for ((index, baseUrl) in candidates.withIndex()) {
            val isLast = index == candidates.lastIndex
            val request = rewriteRequest(original, baseUrl)
            try {
                val response = chain.proceed(request)
                if (response.isSuccessful || isLast) {
                    return response
                }
                // OkHttp forbids starting another exchange while the previous response is open.
                response.close()
            } catch (error: IOException) {
                lastError = error
                if (isLast) throw error
            }
        }
        throw lastError ?: IOException("无可用 API 节点")
    }
}
