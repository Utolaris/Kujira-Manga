package com.par9uet.jm.network

import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class EmbeddedLoginHostTest {
    @Test
    fun sdkDomainSwitchStillLogsInOnTheActiveBusinessHost() {
        val request = Request.Builder().url("https://sdk-choice.example/login").build()
        val routed = request.withEmbeddedLoginHost("active.example", listOf("sdk-choice.example"))
        assertEquals("https://active.example/login", routed.url.toString())
    }

    @Test
    fun loginRoutingNeverRewritesExternalOrPlaintextRequests() {
        for (url in listOf("http://sdk.example/login", "https://external.example/login", "https://sdk.example/photo")) {
            val request = Request.Builder().url(url).build()
            assertSame(request, request.withEmbeddedLoginHost("active.example", listOf("sdk.example")))
        }
        val initialLogin = Request.Builder().url("https://sdk.example/login").build()
        assertSame(initialLogin, initialLogin.withEmbeddedLoginHost(null, listOf("sdk.example")))
    }
}
