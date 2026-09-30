package com.par9uet.jm.network

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.par9uet.jm.image.JmImageHostHealthManager
import com.par9uet.jm.image.JmImageHostHealthStore
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.emptyFlow
import okhttp3.Dns
import org.junit.Assert.assertTrue
import org.junit.Test

class AppHttpClientDoHDeviceTest {
    @Test
    fun realImageHealthProbeUsesTheInjectedDns() {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val token = "dns-probe-${UUID.randomUUID()}"
        val preferenceNames = mutableListOf<String>()
        val context = object : ContextWrapper(baseContext) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                baseContext.getSharedPreferences("$token-$name", mode).also { preferenceNames += "$token-$name" }
        }
        val host = "cdn-probe.invalid"
        val dnsCalled = CountDownLatch(1)
        val hosts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val dns = Dns { hostname ->
            hosts += hostname
            dnsCalled.countDown()
            // Terminate at the resolver: no external CDN or network availability is needed.
            throw UnknownHostException("controlled DNS failure")
        }
        val http = createSharedCookielessDohClient(dns, okhttp3.ConnectionPool())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = JmImageHostHealthManager(
            context, scope, emptyFlow(), http,
            store = JmImageHostHealthStore(knownHosts = listOf(host)),
        )
        try {
            assertTrue("The production probe bypassed the injected DNS", dnsCalled.await(5, TimeUnit.SECONDS))
            assertTrue(hosts.isNotEmpty() && hosts.all { it == host })
        } finally {
            manager.close()
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
            preferenceNames.forEach(baseContext::deleteSharedPreferences)
        }
    }
}
