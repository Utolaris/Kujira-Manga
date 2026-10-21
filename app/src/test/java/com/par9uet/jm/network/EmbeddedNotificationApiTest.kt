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
            assertTrue(executeNotificationRequest(client, "GET", "notifications", mapOf("type" to "comic_follow", "page" to "2")).contains("list"))
            assertEquals("comic_follow", requests.last().url.queryParameter("type"))
            assertEquals("2", requests.last().url.queryParameter("page"))
            assertEquals("true", executeNotificationRequest(client, "GET", "album_sertracking", mapOf("id" to "123")))
            executeNotificationRequest(client, "POST", "album_sertracking", mapOf("id" to "123"))
            assertEquals("POST", requests.last().method)
            val body = requests.last().body as MultipartBody
            val text = Buffer().apply { body.writeTo(this) }.readUtf8()
            assertTrue(text.contains("name=\"id\"")); assertTrue(text.contains("123"))
            executeNotificationRequest(client, "POST", "notifications", mapOf("id" to "notice-1", "read" to "1"))
            val readBody = Buffer().apply { requests.last().body!!.writeTo(this) }.readUtf8()
            assertTrue(readBody.contains("name=\"read\"")); assertTrue(readBody.contains("notice-1"))
            assertTrue(executeNotificationRequest(client, "GET", "notifications/unreadCount", emptyMap()).contains("site_notice"))
            assertEquals("/notifications/unreadCount", requests.last().url.encodedPath)
            assertNull(requests.last().url.query)
            assertNotNull(runCatching { executeNotificationRequest(client, "POST", "notifications/unreadCount", emptyMap()) }.exceptionOrNull())
            code = 401
            assertNotNull(runCatching { executeNotificationRequest(client, "GET", "notifications", emptyMap()) }.exceptionOrNull())
        } finally {
            client.close(); executor.shutdownNow()
            context.client.dispatcher.executorService.shutdownNow(); context.client.connectionPool.evictAll()
            http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll()
        }
    }
}
