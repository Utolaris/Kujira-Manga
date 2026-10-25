package com.par9uet.jm.network

import com.par9uet.jm.core.network.OfficialApiSignature
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient
import io.github.jukomu.jmcomic.core.config.JmConfiguration
import io.github.jukomu.jmcomic.core.net.OkHttpBuilder
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException
import java.time.Duration
import java.util.Base64
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class EmbeddedNotificationApiTest {
    @Test fun `official notification transport signs GET and multipart POST and rejects protocol failures`() {
        val executor = EmbeddedTaskExecutor(Executors.newSingleThreadExecutor()) { }
        val config = JmConfiguration.Builder().executor(executor).apiDomains(listOf("offline.invalid"))
            .retryTimes(0).timeout(Duration.ofMillis(100)).domainProbeTimeoutMs(100).closeTimeoutMs(100).build()
        val context = OkHttpBuilder.build(config)
        context.domainManager.setInitialized(true)
        val requests = mutableListOf<Request>()
        var code = 200
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath !in setOf("/notifications", "/album_sertracking", "/notifications/unreadCount")) throw UnknownHostException("offline test")
            requests += request
            val timestamp = requireNotNull(request.header("Tokenparam")).substringBefore(',')
            assertEquals(OfficialApiSignature.token(timestamp), request.header("Token"))
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(OfficialApiSignature.token(timestamp).toByteArray(), "AES"))
            val payload = when (request.url.encodedPath) {
                "/notifications" -> """{"total":0,"list":[]}"""
                "/notifications/unreadCount" -> """{"comic_follow":2,"site_notice":1}"""
                else -> "true"
            }
            val encoded = Base64.getEncoder().encodeToString(cipher.doFinal(payload.toByteArray()))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":$code,"data":"$encoded","errorMsg":"未登录"}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = JmApiClient(config, http, context.cookieManager, context.domainManager)
        try {
            assertEquals("""{"total":0,"list":[]}""", executeNotificationRequest(client, "GET", "notifications", mapOf("type" to "comic_follow", "page" to "2")))
            assertEquals("comic_follow", requests.last().url.queryParameter("type"))
            assertEquals("2", requests.last().url.queryParameter("page"))
            assertEquals("true", executeNotificationRequest(client, "GET", "album_sertracking", mapOf("id" to "123")))
            executeNotificationRequest(client, "POST", "album_sertracking", mapOf("id" to "123"))
            assertEquals("POST", requests.last().method)
            assertEquals("/album_sertracking", requests.last().url.encodedPath)
            assertMultipartFields(requests.last(), mapOf("id" to "123"))
            executeNotificationRequest(client, "POST", "notifications", mapOf("id" to "notice-1", "read" to "1"))
            assertEquals("POST", requests.last().method)
            assertEquals("/notifications", requests.last().url.encodedPath)
            assertMultipartFields(requests.last(), mapOf("id" to "notice-1", "read" to "1"))
            assertEquals("""{"comic_follow":2,"site_notice":1}""", executeNotificationRequest(client, "GET", "notifications/unreadCount", emptyMap()))
            assertEquals("/notifications/unreadCount", requests.last().url.encodedPath)
            assertNull(requests.last().url.query)
            val requestCount = requests.size
            val invalidMethod = runCatching { executeNotificationRequest(client, "POST", "notifications/unreadCount", emptyMap()) }.exceptionOrNull()
            assertTrue(invalidMethod is IllegalArgumentException)
            assertEquals("Rejected methods must not reach transport", requestCount, requests.size)
            code = 401
            assertTrue(runCatching { executeNotificationRequest(client, "GET", "notifications", emptyMap()) }.exceptionOrNull() is Exception)
        } finally {
            client.close(); executor.shutdownNow()
            context.client.dispatcher.executorService.shutdownNow(); context.client.connectionPool.evictAll()
            http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll()
        }
    }

    private fun assertMultipartFields(request: Request, expected: Map<String, String>) {
        assertNull(request.url.query)
        val body = request.body as MultipartBody
        assertEquals(expected.size, body.parts.size)
        val fields = body.parts.associate { part ->
            val disposition = requireNotNull(part.headers?.get("Content-Disposition"))
            val name = requireNotNull(Regex("name=\"([^\"]+)\"").find(disposition)).groupValues[1]
            name to Buffer().apply { part.body.writeTo(this) }.readUtf8()
        }
        assertEquals(expected, fields)
    }
}
