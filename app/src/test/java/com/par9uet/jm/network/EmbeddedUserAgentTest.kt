package com.par9uet.jm.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddedUserAgentTest {
    /** SDK 1.1.8 硬编码的那一串（Android 9 / Chrome 91）。 */
    private val sdkUserAgent = "Mozilla/5.0 (Linux; Android 9; V1938CT Build/PQ3A.190705.11211812; wv) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/91.0.4472.114 Safari/537.36"

    private val deviceUserAgent = "Mozilla/5.0 (Linux; Android 14; 24129PN74C Build/UKQ1.230804.001; wv) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/128.0.6613.99 Mobile Safari/537.36"

    private fun request(userAgent: String? = sdkUserAgent): Request =
        Request.Builder()
            .url("https://api-a.example/favorite".toHttpUrl())
            .apply { if (userAgent != null) header("User-Agent", userAgent) }
            .build()

    @Test fun replacesSdkHardcodedUserAgentWithDeviceOne() {
        val patched = request().withEmbeddedUserAgent(deviceUserAgent)
        assertEquals(deviceUserAgent, patched.header("User-Agent"))
    }

    @Test fun keepsSdkUserAgentWhenDeviceOneIsUnavailable() {
        // 预热失败（WebView 初始化异常）时不能把 UA 抹掉，仍走 SDK 默认值。
        val untouched = request().withEmbeddedUserAgent(null)
        assertEquals(sdkUserAgent, untouched.header("User-Agent"))
        assertEquals(sdkUserAgent, request().withEmbeddedUserAgent("  ").header("User-Agent"))
    }

    @Test fun doesNotIntroduceHeaderWhenThereWasNone() {
        // 预热成功但请求本来没有 UA（理论路径）时也要补上，而不是留空。
        assertNull(request(userAgent = null).header("User-Agent"))
        assertEquals(
            deviceUserAgent,
            request(userAgent = null).withEmbeddedUserAgent(deviceUserAgent).header("User-Agent"),
        )
    }

    @Test fun preservesOtherHeaders() {
        val original = request().newBuilder()
            .header("token", "abc")
            .header("tokenparam", "123,2.1.8")
            .build()
        val patched = original.withEmbeddedUserAgent(deviceUserAgent)
        assertEquals("abc", patched.header("token"))
        assertEquals("123,2.1.8", patched.header("tokenparam"))
        assertEquals(deviceUserAgent, patched.header("User-Agent"))
        // 覆盖是「替换」而不是「追加」
        assertEquals(1, patched.headers("User-Agent").size)
    }

    @Test fun isIdempotent() {
        val once = request().withEmbeddedUserAgent(deviceUserAgent)
        val twice = once.withEmbeddedUserAgent(deviceUserAgent)
        assertEquals(deviceUserAgent, twice.header("User-Agent"))
        assertEquals(1, twice.headers("User-Agent").size)
    }
}
