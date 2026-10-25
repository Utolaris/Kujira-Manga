package com.par9uet.jm.network

import com.par9uet.jm.core.network.OfficialApiSignature
import io.github.jukomu.jmcomic.core.config.JmConfiguration
import io.github.jukomu.jmcomic.core.net.OkHttpBuilder
import okhttp3.Cookie
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException
import java.time.Duration
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class LanguageAwareJmApiClientTest {
    @Test
    fun languageChangesInvalidateSdkCacheAndRetainSession() {
        Fixture().use { fixture ->
            val cookie = Cookie.Builder().name("AVS").value("session")
                .domain("jm-placeholder.domain.com").path("/").build()
            fixture.client.client.setCookies(listOf(cookie))
            assertEquals("漫画标题", fixture.client.getAlbum("123").title())
            assertEquals("漫画标题", fixture.client.getAlbum("123").title())
            fixture.language = "TW"
            assertEquals("漫畫標題", fixture.client.getAlbum("123").title())
            fixture.language = "CN"
            assertEquals("漫画标题", fixture.client.getAlbum("123").title())
            fixture.language = "TW"
            assertEquals("漫畫標題", fixture.client.getAlbum("123").title())
            assertEquals(listOf("CN", "TW", "CN", "TW"), fixture.sentLanguages)
            assertEquals("session", fixture.client.client.getCookies().single { it.name == "AVS" }.value)
        }
    }

    @Test
    fun preferenceChangeBeforeResponseDoesNotPolluteNewLanguageCache() {
        Fixture().use { fixture ->
            fixture.onResponse = { fixture.language = "TW" }
            assertEquals("漫画标题", fixture.client.getAlbum("123").title())
            fixture.onResponse = {}
            assertEquals("漫畫標題", fixture.client.getAlbum("123").title())
            fixture.language = "CN"
            assertEquals("漫画标题", fixture.client.getAlbum("123").title())
            assertEquals(listOf("CN", "TW", "CN"), fixture.sentLanguages)
        }
    }

    @Test
    fun sameLanguageLoadsConcurrentlyAndLanguageChangeWaitsForTheirResponses() {
        Fixture().use { fixture ->
            val started = CountDownLatch(2)
            val release = CountDownLatch(1)
            val changed = CountDownLatch(1)
            val workers = Executors.newFixedThreadPool(3)
            fixture.onResponse = { language ->
                if (language == "CN") {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                } else changed.countDown()
            }
            try {
                val first = workers.submit<String> { fixture.client.getAlbum("123").title() }
                val second = workers.submit<String> { fixture.client.getAlbum("456").title() }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                fixture.language = "TW"
                val third = workers.submit<String> { fixture.client.getAlbum("789").title() }
                assertFalse(changed.await(100, TimeUnit.MILLISECONDS))
                release.countDown()
                assertEquals("漫画标题", first.get(5, TimeUnit.SECONDS))
                assertEquals("漫画标题", second.get(5, TimeUnit.SECONDS))
                assertEquals("漫畫標題", third.get(5, TimeUnit.SECONDS))
                assertEquals(3, fixture.sentLanguages.size)
            } finally {
                release.countDown()
                workers.shutdownNow()
            }
        }
    }

    @Test
    fun failedCallReleasesItsLanguageSnapshot() {
        var language = "CN"
        val provider = EmbeddedRequestLanguageProvider { language }
        assertThrows(IllegalStateException::class.java) {
            EmbeddedRequestLanguageScope.withLanguage(provider) {
                language = "TW"
                assertEquals("CN", EmbeddedRequestLanguageScope.current(provider))
                error("failed request")
            }
        }
        assertEquals("TW", EmbeddedRequestLanguageScope.current(provider))
    }

    private class Fixture : AutoCloseable {
        @Volatile var language = "CN"
        var onResponse: (String?) -> Unit = {}
        val sentLanguages: MutableList<String?> = java.util.Collections.synchronizedList(mutableListOf())
        private val provider = EmbeddedRequestLanguageProvider { language }
        private val executor = EmbeddedTaskExecutor(Executors.newSingleThreadExecutor()) {}
        private val config = JmConfiguration.Builder().executor(executor)
            .apiDomains(listOf("offline.invalid")).retryTimes(0)
            .timeout(Duration.ofMillis(100)).domainProbeTimeoutMs(100).closeTimeoutMs(100).build()
        private val context = OkHttpBuilder.build(config).also { it.domainManager.setInitialized(true) }
        private val http = OkHttpClient.Builder().cookieJar(context.client.cookieJar).addInterceptor { chain ->
            // 生产中的 SDK 重试拦截器会把占位域替换为活动 API 域。
            val original = chain.request()
            val routed = original.newBuilder()
                .url(original.url.newBuilder().host("offline.invalid").build()).build()
            chain.proceed(routed
                .withEmbeddedLang(listOf("offline.invalid"), EmbeddedRequestLanguageScope.current(provider))
                .withOfficialApiRequest(listOf("offline.invalid")))
        }.addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath != "/album") throw UnknownHostException("offline test")
            val selected = request.url.queryParameter("lang")
            sentLanguages += selected
            val title = if (selected == "CN") "漫画标题" else "漫畫標題"
            val timestamp = requireNotNull(request.header("Tokenparam")).substringBefore(',')
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE,
                SecretKeySpec(OfficialApiSignature.token(timestamp).toByteArray(), "AES"))
            val id = request.url.queryParameter("id")
            val payload = """{"id":"$id","name":"$title","author":[],"tags":[],"series":[],"related_list":[]}"""
            val encoded = Base64.getEncoder().encodeToString(cipher.doFinal(payload.toByteArray()))
            onResponse(selected)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":200,"data":"$encoded"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        private val sdk = io.github.jukomu.jmcomic.core.client.impl.JmApiClient(config, http, context.cookieManager, context.domainManager)
        val client = LanguageAwareJmApiClient(sdk, config.cachePool, provider)

        override fun close() {
            sdk.close()
            executor.shutdownNow()
            context.client.dispatcher.executorService.shutdownNow()
            context.client.connectionPool.evictAll()
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
        }
    }
}
