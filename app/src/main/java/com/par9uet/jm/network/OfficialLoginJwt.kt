package com.par9uet.jm.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.par9uet.jm.core.network.OfficialApiSignature
import io.github.jukomu.jmcomic.core.crypto.JmCryptoTool

/** The SDK maps login identity and AVS but discards `data.jwttoken`. */
internal fun officialLoginJwt(envelope: JsonObject, tokenParam: String?): String? {
    if (envelope.get("code")?.asInt != 200) return null
    val data = envelope.get("data") ?: return null
    val payload = when {
        data.isJsonObject -> data.asJsonObject
        data.isJsonPrimitive && data.asJsonPrimitive.isString -> {
            val timestamp = tokenParam?.substringBefore(',')?.takeIf { it.isNotEmpty() } ?: return null
            val decoded = runCatching {
                JmCryptoTool.decryptApiResponse(data.asString, timestamp, OfficialApiSignature.SECRET)
            }.getOrNull() ?: return null
            runCatching { JsonParser.parseString(decoded).asJsonObject }.getOrNull() ?: return null
        }
        else -> return null
    }
    return payload.get("jwttoken")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
}
