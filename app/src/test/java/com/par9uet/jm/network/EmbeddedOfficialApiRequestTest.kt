package com.par9uet.jm.network

import com.par9uet.jm.core.network.OfficialApiSignature
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedOfficialApiRequestTest {
    private val domains = listOf("api.example")

    @Test fun `SDK login uses official signature and multipart body without changing its timestamp`() {
        val original = Request.Builder().url("https://api.example/login")
            .header("tokenparam", "1700000000,2.0.20")
            .header("token", "old-sdk-token")
            .post(FormBody.Builder().add("username", "alice").add("password", "secret").build())
            .build()

        val aligned = original.withOfficialApiRequest(domains)

        assertEquals("1700000000,2.1.8", aligned.header("Tokenparam"))
        assertEquals("880c64833265ad47a928afcf1b1220f5", aligned.header("Token"))
        assertEquals(1, aligned.headers("Tokenparam").size)
        assertEquals(1, aligned.headers("Token").size)
        assertEquals(MultipartBody.FORM, (aligned.body as MultipartBody).type)
        val body = Buffer().also { aligned.body!!.writeTo(it) }.readUtf8()
        assertTrue(body.contains("name=\"username\"\r\n\r\nalice"))
        assertTrue(body.contains("name=\"password\"\r\n\r\nsecret"))
        assertTrue(original.body is FormBody)
    }

    @Test fun `JSON post stays JSON while token headers align`() {
        val json = "{\"tags\":[\"a\"]}".toRequestBody("application/json".toMediaType())
        val original = Request.Builder().url("https://api.example/tag_block")
            .header("tokenparam", "1700000000,2.0.20")
            .post(json).build()

        val aligned = original.withOfficialApiRequest(domains)

        assertSame(json, aligned.body)
        assertEquals(OfficialApiSignature.token("1700000000"), aligned.header("Token"))
    }

    @Test fun `untrusted hosts and requests without SDK token are unchanged`() {
        val foreign = Request.Builder().url("https://foreign.example/login")
            .header("tokenparam", "1700000000,2.0.20").get().build()
        val unsigned = Request.Builder().url("https://api.example/").get().build()

        assertSame(foreign, foreign.withOfficialApiRequest(domains))
        assertSame(unsigned, unsigned.withOfficialApiRequest(domains))
    }
}
