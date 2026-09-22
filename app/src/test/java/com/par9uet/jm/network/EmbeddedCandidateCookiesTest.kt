package com.par9uet.jm.network

import java.net.CookieManager
import java.net.CookiePolicy
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.JavaNetCookieJar
import org.junit.Assert.*
import org.junit.Test

class EmbeddedCandidateCookiesTest {
    private val url = "https://api.example.com/login".toHttpUrl()
    private val headers = Headers.Builder()
        .add("Set-Cookie", "AVS=anonymous; Domain=example.com; Path=/")
        .add("Set-Cookie", "__cflb=affinity; Path=/; Secure")
        .add("Content-Type", "application/json")
        .build()
    private val loginToken = Cookie.Builder().name("AVS").value("authenticated")
        .domain(url.host).path("/").build()

    private fun loginCookies(responseHeaders: Headers): List<Cookie> {
        val manager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        val jar = JavaNetCookieJar(manager)
        // BridgeInterceptor saves response headers before SDK login writes JSON `s`.
        manager.put(url.toUri(), mapOf("Set-Cookie" to responseHeaders.values("Set-Cookie")))
        jar.saveFromResponse(url, listOf(loginToken))
        return jar.loadForRequest(url)
    }

    @Test fun duplicateHeaderTokenMakesRequestSelectionDependOnJarOrder() {
        val cookies = loginCookies(headers)
        assertEquals(2, cookies.count { it.name == "AVS" })
        // CookieStore order is not an authentication guarantee. Exercise both orders;
        // exported device logs show duplicate AVS names but do not identify their values.
        val headerFirst = cookies.sortedBy { it.value != "anonymous" }
        assertEquals("anonymous", embeddedCookiesForRequest(headerFirst, url, listOf(url.host))
            .single { it.name == "AVS" }.value)
        assertEquals("authenticated", embeddedCookiesForRequest(headerFirst.reversed(), url, listOf(url.host))
            .single { it.name == "AVS" }.value)
    }

    @Test fun filteringHeadersLeavesOnlyJsonSessionAndPreservesAffinity() {
        val filtered = headers.withoutEmbeddedSessionCookie()
        assertEquals("application/json", filtered["Content-Type"])
        val cookies = loginCookies(filtered)
        assertEquals(1, cookies.count { it.name == "AVS" })
        assertEquals("affinity", cookies.single { it.name == "__cflb" }.value)
        assertEquals("authenticated", embeddedCookiesForRequest(cookies, url, listOf(url.host))
            .single { it.name == "AVS" }.value)
    }
}
