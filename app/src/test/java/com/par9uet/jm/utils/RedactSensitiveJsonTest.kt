package com.par9uet.jm.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertEquals
import com.google.gson.Gson
import com.google.gson.JsonParser

class RedactSensitiveJsonTest {
    @Test fun `protocol encrypted login data is omitted while business code remains`() {
        val redacted = redactSensitiveJson("""{"code":200,"data":"encrypted-login-session"}""")
        assertFalse(redacted.contains("encrypted-login-session"))
        assertEquals(200, JsonParser.parseString(redacted).asJsonObject.get("code").asInt)
    }
    @Test fun `escaped quotes and backslashes cannot leave a sensitive suffix`() {
        val raw = Gson().toJson(mapOf("password" to "prefix\"sensitive-suffix\\tail", "uid" to 7))
        val redacted = redactSensitiveJson(raw)
        assertFalse(redacted.contains("sensitive-suffix"))
        assertFalse(redacted.contains("tail"))
        val parsed = JsonParser.parseString(redacted).asJsonObject
        assertEquals("***", parsed.get("password").asString)
        assertEquals(7, parsed.get("uid").asInt)
    }

    @Test fun `nested arrays and object credentials are redacted without coercion`() {
        val raw = """{"users":[{"Token":{"secret":"nested-secret"},"uid":7}],"Cookies":["avs-value"],"status":"ok"}"""
        val redacted = redactSensitiveJson(raw)
        assertFalse(redacted.contains("nested-secret"))
        assertFalse(redacted.contains("avs-value"))
        val parsed = JsonParser.parseString(redacted).asJsonObject
        assertEquals("ok", parsed.get("status").asString)
        assertEquals(7, parsed.getAsJsonArray("users")[0].asJsonObject.get("uid").asInt)
    }

    @Test fun `truncated and non JSON bodies are omitted entirely`() {
        listOf("""{"password":"secret""", "HTML secret response", "\"secret\"", "{password:'secret'}", "{} trailing-secret").forEach {
            assertEquals("[响应正文已省略]", redactSensitiveJson(it))
        }
    }
    @Test fun `login JWT and AVS are absent from diagnostic text`() {
        val redacted = redactSensitiveJson("{\"jwttoken\":\"secret-jwt\",\"s\":\"secret-avs\",\"uid\":7}")
        assertFalse(redacted.contains("secret-jwt"))
        assertFalse(redacted.contains("secret-avs"))
        assertTrue(redacted.contains("\"uid\":7"))
    }
}
