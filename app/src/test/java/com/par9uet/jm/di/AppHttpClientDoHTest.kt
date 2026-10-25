package com.par9uet.jm.di

import java.net.InetAddress
import okhttp3.CookieJar
import okhttp3.Dns
import org.junit.Assert.assertSame
import org.junit.Test

/** Shared HTTP factory configuration; transport coverage lives in AppHttpClientDoHDeviceTest. */
class AppHttpClientDoHTest {
    private val recordingDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return listOf(InetAddress.getByName("127.0.0.1"))
        }
    }

    @Test
    fun `shared factory sets DoH dns and disables cookies`() {
        val client = com.par9uet.jm.network.createSharedCookielessDohClient(
            dns = recordingDns,
            connectionPool = okhttp3.ConnectionPool(),
        )

        assertSame(recordingDns, client.dns)
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
    }

}
