@file:Suppress("GetInstance")

package com.par9uet.jm.retrofit

import java.nio.charset.Charset
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

fun decryptData(str: String): String {
    val encryptedBytes = android.util.Base64.decode(str, android.util.Base64.DEFAULT)
    var lastError: Throwable? = null
    for (decryptKey in ApiContext.getDataDecryptKeys()) {
        try {
            val secretKey = SecretKeySpec(decryptKey.toByteArray(Charset.forName("UTF-8")), "AES")
            // Remote API payload format requires AES/ECB; switching modes breaks decryptData().
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            val text = String(decryptedBytes, Charset.forName("UTF-8"))
            if (text.isNotBlank()) return text
        } catch (error: Throwable) {
            lastError = error
        }
    }
    throw lastError ?: IllegalStateException("响应解密失败")
}
