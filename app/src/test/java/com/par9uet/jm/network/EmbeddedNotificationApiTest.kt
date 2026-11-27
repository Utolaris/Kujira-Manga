package com.par9uet.jm.network

import com.par9uet.jm.core.network.OfficialApiSignature
import io.github.jukomu.jmcomic.api.exception.JmComicException
import io.github.jukomu.jmcomic.api.exception.ResponseException
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient
import io.github.jukomu.jmcomic.core.config.JmConfiguration
import io.github.jukomu.jmcomic.core.crypto.JmCryptoTool
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
        var httpCode = 200
        var responseSecret = OfficialApiSignature.SECRET
        var payloadOverride: String? = null
        var encodedOverride: String? = null
        var plainDataOverride: String? = null
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath !in setOf("/notifications", "/album_sertracking", "/notifications/unreadCount")) throw UnknownHostException("offline test")
            requests += request
            val timestamp = requireNotNull(request.header("Tokenparam")).substringBefore(',')
            assertEquals(OfficialApiSignature.token(timestamp), request.header("Token"))
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(JmCryptoTool.md5Hex(timestamp + responseSecret).toByteArray(), "AES"))
            val payload = payloadOverride ?: when (request.url.encodedPath) {
                "/notifications" -> """{"total":0,"list":[]}"""
                "/notifications/unreadCount" -> """{"comic_follow":2,"site_notice":1}"""
                else -> if (request.method == "POST") "\"已订阅\"" else "true"
            }
            val encoded = encodedOverride ?: Base64.getEncoder().encodeToString(cipher.doFinal(payload.toByteArray()))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(httpCode).message("OK")
                .body("""{"code":$code,"data":${plainDataOverride ?: "\"$encoded\""},"errorMsg":"未登录"}""".toResponseBody("application/json".toMediaType())).build()
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
            // Official HttpUtil tries both response keys, independently of the request token.
            responseSecret = "18comicAPPContent"
            val beforeFallback = requests.size
            assertEquals("true", executeNotificationRequest(client, "GET", "album_sertracking", mapOf("id" to "123")))
            assertEquals("123", requests.last().url.queryParameter("id"))
            executeNotificationRequest(client, "POST", "album_sertracking", mapOf("id" to "123"))
            assertMultipartFields(requests.last(), mapOf("id" to "123"))
            assertEquals("Key fallback must decode the same response without replaying the toggle", beforeFallback + 2, requests.size)
            assertEquals("""{"comic_follow":2,"site_notice":1}""", executeNotificationRequest(client, "GET", "notifications/unreadCount", emptyMap()))

            payloadOverride = "not valid JSON"
            assertTrue(runCatching { executeNotificationRequest(client, "GET", "album_sertracking", mapOf("id" to "123")) }.exceptionOrNull() is JmComicException)
            payloadOverride = null
            encodedOverride = "not base64"
            assertTrue(runCatching { executeNotificationRequest(client, "GET", "album_sertracking", mapOf("id" to "123")) }.exceptionOrNull() is JmComicException)
            encodedOverride = ""
            assertTrue(runCatching { executeNotificationRequest(client, "GET", "album_sertracking", mapOf("id" to "123")) }.exceptionOrNull() is IllegalStateException)
            encodedOverride = null
            for (status in listOf("false", "true", "0", "1", "\"false\"", "\"1\"")) {
                plainDataOverride = status
                assertEquals(status, executeNotificationRequest(client, "GET", "album_sertracking", mapOf("id" to "123")))
            }
            val beforeAcknowledgement = requests.size
            for (acknowledgement in listOf("\"订阅成功\"", "\"\"", "null")) {
                plainDataOverride = acknowledgement
                executeNotificationRequest(client, "POST", "album_sertracking", mapOf("id" to "123"))
            }
            assertEquals(beforeAcknowledgement + 3, requests.size)
            plainDataOverride = """{"comic_follow":2,"site_notice":1}"""
            assertEquals(plainDataOverride, executeNotificationRequest(client, "GET", "notifications/unreadCount", emptyMap()))
            plainDataOverride = "[]"
            assertEquals("[]", executeNotificationRequest(client, "GET", "notifications", emptyMap()))
            plainDataOverride = "null"
            assertTrue(runCatching { executeNotificationRequest(client, "GET", "album_sertracking", mapOf("id" to "123")) }.exceptionOrNull() is IllegalStateException)
            plainDataOverride = null
            val requestCount = requests.size
            val invalidMethod = runCatching { executeNotificationRequest(client, "POST", "notifications/unreadCount", emptyMap()) }.exceptionOrNull()
            assertTrue(invalidMethod is IllegalArgumentException)
            assertEquals("Rejected methods must not reach transport", requestCount, requests.size)
            code = 401
            val authenticationError = runCatching { executeNotificationRequest(client, "GET", "notifications", emptyMap()) }.exceptionOrNull()
            assertTrue(authenticationError is ResponseException)
            assertTrue((authenticationError as ResponseException).isAuthenticationFailure())
            plainDataOverride = "\"订阅成功\""
            val rejectedWrite = runCatching { executeNotificationRequest(client, "POST", "album_sertracking", mapOf("id" to "123")) }.exceptionOrNull()
            assertTrue(rejectedWrite is ResponseException)
            assertTrue((rejectedWrite as ResponseException).isAuthenticationFailure())
            code = 500
            val businessError = runCatching { executeNotificationRequest(client, "POST", "album_sertracking", mapOf("id" to "123")) }.exceptionOrNull()
            assertTrue(businessError is ResponseException)
            assertFalse((businessError as ResponseException).isAuthenticationFailure())
            code = 200
            httpCode = 503
            val transportError = runCatching { executeNotificationRequest(client, "GET", "notifications", emptyMap()) }.exceptionOrNull()
            assertTrue(transportError is ResponseException)
            assertEquals(503, (transportError as ResponseException).errorCode)
            assertFalse(transportError.isAuthenticationFailure())
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
