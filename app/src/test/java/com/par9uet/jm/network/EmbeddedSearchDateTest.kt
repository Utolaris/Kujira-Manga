package com.par9uet.jm.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddedSearchDateTest {
    private val apiDomains = listOf("api-a.example", "api-b.example")

    private fun get(url: String) = Request.Builder().url(url.toHttpUrl()).get().build()

    @Test
    fun addsYearAndMonthToSearch() {
        val patched = get("https://api-a.example/search?search_query=x&page=1&o=mr&t=a")
            .withEmbeddedSearchDate(apiDomains, EmbeddedSearchDate(year = "2024", month = "3"))
        assertEquals("2024", patched.url.queryParameter("y"))
        assertEquals("3", patched.url.queryParameter("m"))
        // 写入年月时去掉 SDK 的相对时间 t，避免与 y/m 互相干扰
        assertNull(patched.url.queryParameter("t"))
        // 原有参数不能被挤掉
        assertEquals("x", patched.url.queryParameter("search_query"))
        assertEquals("1", patched.url.queryParameter("page"))
        assertEquals("mr", patched.url.queryParameter("o"))
    }

    @Test
    fun supportsYearOnlyAndMonthOnly() {
        val yearOnly = get("https://api-a.example/search?search_query=x")
            .withEmbeddedSearchDate(apiDomains, EmbeddedSearchDate(year = "2020", month = null))
        assertEquals("2020", yearOnly.url.queryParameter("y"))
        assertNull(yearOnly.url.queryParameter("m"))

        val monthOnly = get("https://api-a.example/search?search_query=x")
            .withEmbeddedSearchDate(apiDomains, EmbeddedSearchDate(year = "", month = "12"))
        assertNull(monthOnly.url.queryParameter("y"))
        assertEquals("12", monthOnly.url.queryParameter("m"))
    }

    @Test
    fun leavesRequestAloneWhenFilterEmpty() {
        val original = get("https://api-a.example/search?search_query=x&t=a")
        val patched = original.withEmbeddedSearchDate(apiDomains, EmbeddedSearchDate.EMPTY)
        assertNull(patched.url.queryParameter("y"))
        assertNull(patched.url.queryParameter("m"))
        assertEquals("a", patched.url.queryParameter("t"))
    }

    @Test
    fun leavesNonSearchEndpointsAlone() {
        val patched = get("https://api-a.example/album?id=1")
            .withEmbeddedSearchDate(apiDomains, EmbeddedSearchDate(year = "2024", month = "3"))
        assertNull(patched.url.queryParameter("y"))
        assertNull(patched.url.queryParameter("m"))
    }

    @Test
    fun leavesNonApiHostsAlone() {
        val patched = get("https://cdn-a.example/search?search_query=x")
            .withEmbeddedSearchDate(apiDomains, EmbeddedSearchDate(year = "2024", month = "3"))
        assertNull(patched.url.queryParameter("y"))
        assertNull(patched.url.queryParameter("m"))
    }

    @Test
    fun scopeInjectsAndRestores() {
        val request = get("https://api-a.example/search?search_query=x")
        assertNull(request.withEmbeddedSearchDate(apiDomains).url.queryParameter("y"))

        val scoped = EmbeddedSearchDateScope.withDate(EmbeddedSearchDate(year = "2021", month = "7")) {
            request.withEmbeddedSearchDate(apiDomains)
        }
        assertEquals("2021", scoped.url.queryParameter("y"))
        assertEquals("7", scoped.url.queryParameter("m"))
        // 作用域退出后不得泄漏到后续请求
        assertNull(request.withEmbeddedSearchDate(apiDomains).url.queryParameter("y"))
    }
}
