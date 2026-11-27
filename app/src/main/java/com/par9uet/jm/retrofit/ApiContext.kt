package com.par9uet.jm.retrofit

import com.par9uet.jm.core.network.OfficialApiSignature
import com.par9uet.jm.utils.md5

// Network recommendation response decryption key.
const val APP_DATA_SECRET = OfficialApiSignature.SECRET

/**
 * API request context for recommendation response decryption.
 *
 * TokenInterceptor always uses the process-fixed [API_TS], so a per-request
 * ThreadLocal is unnecessary; keep a single accessor for converters.
 */
object ApiContext {
    fun getTimestamp(): Long = API_TS

    /** 获取网络推荐响应的数据解密密钥。 */
    fun getDataDecryptKey(): String {
        return md5("${getTimestamp()}$APP_DATA_SECRET")
    }

    /** 对齐官方 APK 双盐解密顺序：主盐失败时回退备用盐。 */
    fun getDataDecryptKeys(): List<String> {
        val ts = getTimestamp()
        return listOf(
            md5("$ts${OfficialApiSignature.SECRET}"),
            md5("$ts${OfficialApiSignature.FALLBACK_SECRET}"),
        )
    }
}
