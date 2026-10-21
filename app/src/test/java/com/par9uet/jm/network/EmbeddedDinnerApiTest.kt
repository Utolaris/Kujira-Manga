package com.par9uet.jm.network

import com.par9uet.jm.core.network.OfficialApiSignature
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient
import io.github.jukomu.jmcomic.core.config.JmConfiguration
import io.github.jukomu.jmcomic.core.net.OkHttpBuilder
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException
import java.time.Duration
import java.util.Base64
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class EmbeddedDinnerApiTest {
    @Test fun `shared SDK transport sends official signatures and decodes both article endpoints`() = runTest {
        val executor = EmbeddedTaskExecutor(Executors.newSingleThreadExecutor()) { }
        val config = JmConfiguration.Builder().executor(executor).apiDomains(listOf("offline.invalid"))
            .retryTimes(0).timeout(Duration.ofMillis(100)).domainProbeTimeoutMs(100).closeTimeoutMs(100).build()
        val context = OkHttpBuilder.build(config)
        context.domainManager.setInitialized(true)
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath !in setOf("/blogs", "/blog")) throw UnknownHostException("offline test")
            requests += request
            val timestamp = requireNotNull(request.header("Tokenparam")).substringBefore(',')
            assertEquals(OfficialApiSignature.token(timestamp), request.header("Token"))
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(OfficialApiSignature.token(timestamp).toByteArray(), "AES"))
            val payload = if (request.url.encodedPath == "/blogs") """{"count":25,"list":[]}""" else """{"info":{"content":"<p>正文</p>"}}"""
            val encoded = Base64.getEncoder().encodeToString(cipher.doFinal(payload.toByteArray()))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":200,"data":"$encoded"}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = JmApiClient(config, http, context.cookieManager, context.domainManager)
        try {
            val api = EmbeddedDinnerApi { client }
            assertTrue(api.get("blogs", mapOf("page" to "2", "blog_type" to "dinner")).contains("25"))
            assertTrue(api.get("blog", mapOf("id" to "7")).contains("正文"))
            assertEquals("2", requests[0].url.queryParameter("page"))
            assertEquals("dinner", requests[0].url.queryParameter("blog_type"))
            assertEquals("7", requests[1].url.queryParameter("id"))
        } finally {
            client.close(); executor.shutdownNow()
            context.client.dispatcher.executorService.shutdownNow(); context.client.connectionPool.evictAll()
            http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll()
        }
    }
}
