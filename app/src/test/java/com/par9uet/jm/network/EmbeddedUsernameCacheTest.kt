package com.par9uet.jm.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertThrows
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient
import io.github.jukomu.jmcomic.core.config.JmConfiguration
import io.github.jukomu.jmcomic.core.net.OkHttpBuilder
import java.util.concurrent.Executors
import java.time.Duration
import java.net.UnknownHostException

/**
 * Checks the embedded SDK username API and its actual blank/cache behavior.
 * Application cookie restoration does not populate this cache (it would enable SDK relogin).
 */
class EmbeddedUsernameCacheTest {

    @Test
    fun abstractJmClientExposesProtectedCacheUsername() {
        val method = Class.forName("io.github.jukomu.jmcomic.core.client.AbstractJmClient")
            .getDeclaredMethod("cacheUsername", String::class.java)
        assertTrue(Modifier.isProtected(method.modifiers))
        assertEquals(Void.TYPE, method.returnType)
    }

    @Test
    fun sdkUsernameGetterRejectsBlankCacheAndReturnsCachedUsername() {
        val get = Class.forName("io.github.jukomu.jmcomic.core.client.AbstractJmClient")
            .getDeclaredMethod("getLoggedInUserName").apply { isAccessible = true }
        val cache = get.declaringClass.getDeclaredMethod("cacheUsername", String::class.java)
            .apply { isAccessible = true }
        val executor = EmbeddedTaskExecutor(Executors.newSingleThreadExecutor()) { }
        val config = JmConfiguration.Builder()
            .executor(executor)
            .apiDomains(listOf("offline.invalid"))
            .retryTimes(0)
            .timeout(Duration.ofMillis(100))
            .domainProbeTimeoutMs(100)
            .closeTimeoutMs(100)
            .build()
        val context = OkHttpBuilder.build(config)
        val http = okhttp3.OkHttpClient.Builder().addInterceptor {
            throw UnknownHostException("offline test")
        }.build()
        val client = JmApiClient(config, http, context.cookieManager, context.domainManager)
        try {
            for (username in listOf(null, "", "  ")) {
                cache.invoke(client, username)
                val error = assertThrows(InvocationTargetException::class.java) { get.invoke(client) }
                assertTrue(error.cause is IllegalStateException)
                assertTrue(error.cause!!.message!!.contains("Please login first"))
            }
            cache.invoke(client, "restored-user")
            assertEquals("restored-user", get.invoke(client))
        } finally {
            client.close()
            context.client.dispatcher.executorService.shutdownNow()
            context.client.connectionPool.evictAll()
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
            executor.shutdownNow()
        }
    }
}
