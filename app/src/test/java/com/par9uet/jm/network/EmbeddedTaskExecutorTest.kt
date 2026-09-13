package com.par9uet.jm.network

import io.github.jukomu.jmcomic.api.exception.JmComicException
import io.github.jukomu.jmcomic.api.exception.NetworkException
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient
import io.github.jukomu.jmcomic.core.config.JmConfiguration
import io.github.jukomu.jmcomic.core.net.OkHttpBuilder
import okhttp3.OkHttpClient
import java.net.UnknownHostException
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedTaskExecutorTest {
    @Test
    fun `real SDK initialization failure is contained and later requests still execute`() {
        val failures = LinkedBlockingQueue<JmComicException>()
        val uncaught = LinkedBlockingQueue<Throwable>()
        val delegate = Executors.newSingleThreadExecutor { task ->
            Thread(task).apply { uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> uncaught.add(e) } }
        }
        val executor = EmbeddedTaskExecutor(delegate) { failures.add(it) }
        val config = JmConfiguration.Builder()
            .executor(executor)
            .apiDomains(listOf("offline.invalid"))
            .retryTimes(0)
            .timeout(Duration.ofMillis(100))
            .domainProbeTimeoutMs(100)
            .closeTimeoutMs(100)
            .build()
        val context = OkHttpBuilder.build(config)
        val http = OkHttpClient.Builder().addInterceptor {
            throw UnknownHostException("DoH 解析器未就绪")
        }.build()
        val client = JmApiClient(config, http, context.cookieManager, context.domainManager)
        try {
            val failure = failures.poll(10, TimeUnit.SECONDS)
            assertNotNull("SDK constructor must exercise the real failing initialization task", failure)
            assertTrue(failure is NetworkException)
            assertTrue(uncaught.isEmpty())
            // Network failures in explicit user requests must remain visible to the repository.
            assertThrows(NetworkException::class.java) { client.setting() }
            assertEquals(42, executor.submit<Int> { 42 }.get(2, TimeUnit.SECONDS))
        } finally {
            client.close()
            context.client.dispatcher.executorService.shutdownNow()
            context.client.connectionPool.evictAll()
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
            executor.shutdownNow()
        }
    }

    @Test
    fun `submitted task failure remains observable through its future`() {
        val failures = mutableListOf<JmComicException>()
        val executor = EmbeddedTaskExecutor(Executors.newSingleThreadExecutor()) { failures.add(it) }
        try {
            val future = executor.submit<Unit> { throw NetworkException("offline") }
            val error = assertThrows(ExecutionException::class.java) { future.get(2, TimeUnit.SECONDS) }
            assertTrue(error.cause is NetworkException)
            assertTrue(failures.isEmpty())
        } finally {
            executor.shutdownNow()
        }
    }
}
