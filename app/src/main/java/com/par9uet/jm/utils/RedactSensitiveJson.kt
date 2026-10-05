package com.par9uet.jm.utils

import com.google.gson.JsonElement
import com.google.gson.GsonBuilder
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness

/**
 * 登录返回体脱敏：保留业务码等诊断字段，不落盘密码/token 或协议密文载荷。
 * 仅作用于响应体，请求体（含密码）永远不要写入日志。
 */
fun redactSensitiveJson(raw: String): String {
    if (raw.isEmpty()) return raw
    return runCatching {
        val json = DIAGNOSTIC_JSON.fromJson(raw, JsonElement::class.java)
        require(json.isJsonObject || json.isJsonArray)
        redactSensitiveFields(json)
        json.toString()
    }.getOrElse { "[响应正文已省略]" }
}

private val DIAGNOSTIC_JSON = GsonBuilder().setStrictness(Strictness.STRICT).create()

private val SENSITIVE_JSON_FIELDS = setOf(
    "password", "passwd", "pass", "pwd", "token", "jwttoken", "access_token", "refresh_token",
    "cookie", "cookies", "s", "avs", "authorization", "bearer_token", "secret",
)

private fun redactSensitiveFields(element: JsonElement) {
    when {
        element.isJsonObject -> element.asJsonObject.entrySet().forEach { entry ->
            val key = entry.key.lowercase(java.util.Locale.ROOT)
            // Official login may return credentials inside a protocol-encrypted data string.
            // That ciphertext is recoverable with the public protocol key and must not be logged.
            val encryptedLoginData = key == "data" && entry.value.isJsonPrimitive && entry.value.asJsonPrimitive.isString
            if (key in SENSITIVE_JSON_FIELDS || encryptedLoginData) {
                entry.setValue(JsonPrimitive("***"))
            } else redactSensitiveFields(entry.value)
        }
        element.isJsonArray -> element.asJsonArray.forEach(::redactSensitiveFields)
    }
}
