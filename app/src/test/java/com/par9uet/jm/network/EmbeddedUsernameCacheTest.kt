package com.par9uet.jm.network

import com.par9uet.jm.core.network.OfficialApiSignature
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient
import io.github.jukomu.jmcomic.core.config.JmConfiguration
import io.github.jukomu.jmcomic.core.net.OkHttpBuilder
import java.net.UnknownHostException
import java.time.Duration
import java.util.Base64
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Public SDK submit/parse contract; reflection only seeds the SDK's otherwise private cache. */
class EmbeddedUsernameCacheTest {
    @Test
    fun missingUsernameFailsAfterAcceptedPostAndCachedUsernameAllowsParsing() {
        val executor = EmbeddedTaskExecutor(Executors.newSingleThreadExecutor()) { }
        val config = JmConfiguration.Builder().executor(executor).apiDomains(listOf("offline.invalid"))
            .retryTimes(0).timeout(Duration.ofMillis(100)).domainProbeTimeoutMs(100).closeTimeoutMs(100).build()
        val context = OkHttpBuilder.build(config)
        context.domainManager.setInitialized(true)
        var posts = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath != "/comment") throw UnknownHostException("offline test")
            assertEquals("POST", request.method)
            posts++
            val timestamp = requireNotNull(request.header("Tokenparam")).substringBefore(',')
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(OfficialApiSignature.token(timestamp).toByteArray(), "AES"))
            val encoded = Base64.getEncoder().encodeToString(cipher.doFinal("""{"status":"ok","msg":"accepted"}""".toByteArray()))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":200,"data":"$encoded"}""".toResponseBody()).build()
        }.build()
        val client = JmApiClient(config, http, context.cookieManager, context.domainManager)
        val cache = Class.forName("io.github.jukomu.jmcomic.core.client.AbstractJmClient")
            .getDeclaredMethod("cacheUsername", String::class.java).apply { isAccessible = true }
        try {
            for (username in listOf(null, "", "  ")) {
                cache.invoke(client, username)
                val before = posts
                val error = assertThrows(IllegalStateException::class.java) { client.postComment("42", "hello") }
                assertEquals("Username is required for this operation. Please login first.", error.message)
                assertEquals("The accepted POST must not be retried by the SDK", before + 1, posts)
                // In SDK 1.1.8 this escapes before ApiParser: it is NOT a ParseResponseException.
                // Do not claim this covers AuthenticatedEmbeddedClient's wrapped-error recovery.
            }
            cache.invoke(client, "restored-user")
            val comment = client.postComment("42", "hello")
            assertEquals("restored-user", comment.username)
            assertEquals("hello", comment.content)
            val reply = client.replyToComment("42", "reply", "7")
            assertEquals("restored-user", reply.username)
            assertEquals("reply", reply.content)
            assertEquals(5, posts)
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
