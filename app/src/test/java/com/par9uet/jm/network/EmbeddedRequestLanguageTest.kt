package com.par9uet.jm.network

import com.par9uet.jm.data.models.APP_LANGUAGE_SIMPLIFIED
import com.par9uet.jm.data.models.APP_LANGUAGE_TRADITIONAL
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddedRequestLanguageTest {
    private val apiDomains = listOf("api-a.example", "api-b.example")

    private fun get(url: String) = Request.Builder().url(url.toHttpUrl()).get().build()

    @Test fun addsLangToApiGetRequests() {
        val patched = get("https://api-a.example/search?search_query=x&page=1")
            .withEmbeddedLang(apiDomains, APP_LANGUAGE_SIMPLIFIED)
        assertEquals("CN", patched.url.queryParameter("lang"))
        // 原有参数不能被挤掉
        assertEquals("x", patched.url.queryParameter("search_query"))
        assertEquals("1", patched.url.queryParameter("page"))
    }

    @Test fun sendsTraditionalWhenSelected() {
        val patched = get("https://api-a.example/categories")
            .withEmbeddedLang(apiDomains, APP_LANGUAGE_TRADITIONAL)
        assertEquals("TW", patched.url.queryParameter("lang"))
    }

    @Test fun skipsLangWhenPreferenceNotReadyYet() {
        // 取值尚未就绪时宁可不补，也不要发出空 / 非法 lang —— 这与 SDK 原本的行为一致。
        // 合法性由 LocalSettingManager 收口，network 层不校验（架构边界禁止它依赖 data.*）。
        assertNull(get("https://api-a.example/categories").withEmbeddedLang(apiDomains, null).url.queryParameter("lang"))
        assertNull(get("https://api-a.example/categories").withEmbeddedLang(apiDomains, "").url.queryParameter("lang"))
    }

    @Test fun keepsExistingLangUntouched() {
        // SDK 自己会发 lang 的方法（如 getNovelChapter）必须保持原值，不被覆盖
        val patched = get("https://api-a.example/novelchapters?id=1&lang=CN")
            .withEmbeddedLang(apiDomains, APP_LANGUAGE_TRADITIONAL)
        assertEquals("CN", patched.url.queryParameter("lang"))
    }

    @Test fun leavesNonApiHostsAlone() {
        // 图床 CDN 不属于 API 域，不该被加上 lang
        val patched = get("https://cdn-a.example/media/photos/1.jpg")
            .withEmbeddedLang(apiDomains, APP_LANGUAGE_SIMPLIFIED)
        assertNull(patched.url.queryParameter("lang"))
    }

    @Test fun leavesPostRequestsAlone() {
        // 官方只在 fetchGet 里补 lang；POST（收藏 / 收藏夹 / 评论）不补
        val body = "aid=123".toRequestBody()
        val post = Request.Builder().url("https://api-a.example/favorite".toHttpUrl()).post(body).build()
        assertNull(post.withEmbeddedLang(apiDomains, APP_LANGUAGE_SIMPLIFIED).url.queryParameter("lang"))
    }

    @Test fun isIdempotent() {
        val once = get("https://api-a.example/setting")
            .withEmbeddedLang(apiDomains, APP_LANGUAGE_SIMPLIFIED)
        val twice = once.withEmbeddedLang(apiDomains, APP_LANGUAGE_TRADITIONAL)
        assertEquals(1, twice.url.querySize)
        assertEquals("CN", twice.url.queryParameter("lang"))
    }
}
