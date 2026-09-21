package com.par9uet.jm.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class EmbeddedSessionCookiesTest {
    private val trusted = listOf("api-a.example", "api-b.example")
    private val avs = Cookie.Builder().name("AVS").value("session").hostOnlyDomain(trusted[0]).secure().build()

    @Test fun restoredAvsSurvivesResponsesWithoutSetCookieAndRotatesOnlyToTrustedHttps() {
        val restored = mergeEmbeddedCookies(listOf(avs), emptyList())
        assertEquals(listOf(avs), restored)
        val rotated = embeddedCookiesForRequest(restored, "https://api-b.example/history".toHttpUrl(), trusted)
        assertEquals(1, rotated.size)
        assertEquals("AVS", rotated.single().name)
        assertEquals("session", rotated.single().value)
        assertTrue(rotated.single().matches("https://api-b.example/history".toHttpUrl()))
        for (url in listOf("https://evil.example/history", "https://api-b.example.evil.test/", "http://api-b.example/")) {
            assertTrue(embeddedCookiesForRequest(restored, url.toHttpUrl(), trusted).isEmpty())
        }
    }

    @Test fun loginHostAvsIsRehostedToTrustedApiHost() {
        // 真实登录：AVS 挂在站点域，业务 API 在轮换的 API 域；两者都可能不在同一字符串列表里。
        val siteAvs = Cookie.Builder().name("AVS").value("from-login")
            .hostOnlyDomain("www.18comic.vip").secure().build()
        val injected = embeddedCookiesForRequest(
            listOf(siteAvs),
            "https://api-a.example/favorites".toHttpUrl(),
            trusted,
        )
        assertEquals(1, injected.size)
        assertEquals("AVS", injected.single().name)
        assertEquals("from-login", injected.single().value)
        assertTrue(injected.single().matches("https://api-a.example/favorites".toHttpUrl()))
        // 非可信域名仍然拒绝
        assertTrue(
            embeddedCookiesForRequest(
                listOf(siteAvs),
                "https://evil.example/favorites".toHttpUrl(),
                trusted,
            ).isEmpty(),
        )
    }

    @Test fun expiryPathAndOrdinaryCookieDomainsAreHonored() {
        val ordinary = Cookie.Builder().name("other").value("secret").domain(trusted[0]).build()
        val scoped = Cookie.Builder().name("AVS").value("scoped").domain(trusted[0]).path("/private").build()
        val expired = Cookie.Builder().name("AVS").value("expired").domain(trusted[0]).expiresAt(1).build()
        for (cookie in listOf(ordinary, scoped, expired)) {
            assertTrue(embeddedCookiesForRequest(listOf(cookie), "https://api-b.example/public".toHttpUrl(), trusted).isEmpty())
        }
    }

    @Test fun explicitDeletionAndFlagUpdatesPersist() {
        val deletion = Cookie.Builder().name("AVS").value("").hostOnlyDomain(trusted[0]).expiresAt(1).build()
        assertTrue(mergeEmbeddedCookies(listOf(avs), listOf(deletion)).isEmpty())
        val updated = Cookie.Builder().name("AVS").value("session").hostOnlyDomain(trusted[0]).secure().httpOnly().build()
        assertEquals(listOf(updated), mergeEmbeddedCookies(listOf(avs), listOf(updated)))
        assertNotEquals(avs, updated)
    }

    /**
     * 会话令牌的单写者不变量：**只有登录流程能写 AVS**。
     *
     * 原实现把任意同域响应的 `Set-Cookie` 直接并回持久化快照，等于让公开 API 响应也能
     * 改写（或新增一个同名不同域的）会话令牌；而请求侧在同名 AVS 之间按列表顺序取第一个，
     * 选到旧值就表现为「登录成功、几秒后所有认证请求 401」。
     */
    @Test fun responseSetCookieCanNeverRewriteOrDeleteTheSessionToken() {
        val theme = Cookie.Builder().name("theme").value("dark").hostOnlyDomain(trusted[0]).build()
        val stored = listOf(avs, theme)

        val rotated = Cookie.Builder().name("AVS").value("rotated-by-response").hostOnlyDomain(trusted[0]).secure().build()
        assertEquals("响应不得改写会话令牌", stored, mergeEmbeddedResponseCookies(stored, listOf(rotated)))

        val deletion = Cookie.Builder().name("AVS").value("").hostOnlyDomain(trusted[0]).expiresAt(1).build()
        assertEquals("响应也不得删除会话令牌", stored, mergeEmbeddedResponseCookies(stored, listOf(deletion)))

        val rotatedTheme = Cookie.Builder().name("theme").value("light").hostOnlyDomain(trusted[0]).build()
        val merged = mergeEmbeddedResponseCookies(stored, listOf(rotatedTheme, rotated))
        assertEquals(2, merged.size)
        assertTrue("非会话 cookie 照常更新", merged.any { it.name == "theme" && it.value == "light" })
        assertTrue("会话令牌保持登录写入的值", merged.any { it.name == "AVS" && it.value == "session" })
    }
}
