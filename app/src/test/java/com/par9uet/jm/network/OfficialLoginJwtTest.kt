package com.par9uet.jm.network

import com.google.gson.JsonParser
import com.par9uet.jm.core.network.OfficialApiSignature
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfficialLoginJwtTest {
    @Test fun `extracts JWT from official encrypted login response`() {
        val timestamp = "1700000000"
        val payload = "{\"uid\":7,\"s\":\"session\",\"jwttoken\":\"jwt-value\"}"
        val key = OfficialApiSignature.token(timestamp).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val encrypted = Base64.getEncoder().encodeToString(cipher.doFinal(payload.toByteArray()))
        val envelope = JsonParser.parseString("{\"code\":200,\"data\":\"$encrypted\"}").asJsonObject

        assertEquals("jwt-value", officialLoginJwt(envelope, "$timestamp,2.1.8"))
        assertNull(officialLoginJwt(envelope, null))
    }

    @Test fun `does not use token from rejected login`() {
        val envelope = JsonParser.parseString("{\"code\":401,\"data\":{\"jwttoken\":\"old\"}}").asJsonObject
        assertNull(officialLoginJwt(envelope, "1700000000,2.1.8"))
    }
}
