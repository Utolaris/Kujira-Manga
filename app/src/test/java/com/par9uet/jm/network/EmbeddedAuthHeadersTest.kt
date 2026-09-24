package com.par9uet.jm.network

import okhttp3.Cookie
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddedAuthHeadersTest {
    private val trustedDomains = listOf("api.example")
    private val avs = Cookie.Builder().name("AVS").value("session")
        .domain("api.example").path("/").build()

    @Test fun `committed AVS and JWT replace previous auth headers`() {
        val previous = Request.Builder().url("https://api.example/favorite")
            .header("Cookie", "AVS=old")
            .header("Authorization", "Bearer old")
            .build()

        val request = previous.withEmbeddedAuthHeaders(listOf(avs), "new-jwt", trustedDomains)

        assertEquals("AVS=session", request.header("Cookie"))
        assertEquals("Bearer new-jwt", request.header("Authorization"))
        assertEquals(1, request.headers("Authorization").size)
    }

    @Test fun `cleared session removes both auth headers`() {
        val previous = Request.Builder().url("https://api.example/favorite")
            .header("Cookie", "AVS=old")
            .header("Authorization", "Bearer old")
            .build()

        val request = previous.withEmbeddedAuthHeaders(emptyList(), null, trustedDomains)

        assertNull(request.header("Cookie"))
        assertNull(request.header("Authorization"))
    }

    @Test fun `domain feed receives no session auth headers`() {
        val feed = Request.Builder()
            .url("https://rup4a04-c02.tos-cn-hongkong.bytepluses.com/newsvr-2025.txt")
            .header("Cookie", "AVS=old")
            .header("Authorization", "Bearer old")
            .build()

        val request = feed.withEmbeddedAuthHeaders(listOf(avs), "new-jwt", trustedDomains)

        assertNull(request.header("Cookie"))
        assertNull(request.header("Authorization"))
    }

    @Test fun `http request receives no session auth headers`() {
        val request = Request.Builder().url("http://api.example/favorite").build()
            .withEmbeddedAuthHeaders(listOf(avs), "new-jwt", trustedDomains)

        assertNull(request.header("Cookie"))
        assertNull(request.header("Authorization"))
    }
}
