package com.par9uet.jm.retrofit

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiContextTest {
    @Test
    fun `Retrofit token version matches official client`() {
        assertEquals("2.1.8", API_VERSION)
        assertEquals("${API_TS},2.1.8", com.par9uet.jm.core.network.OfficialApiSignature.tokenParam(API_TS.toString()))
        assertEquals(com.par9uet.jm.utils.md5("${API_TS}185Hcomic3PAPP7R"), API_TOKEN_HASH)
    }

    @Test
    fun `timestamp is the process-fixed API_TS`() {
        assertEquals(API_TS, ApiContext.getTimestamp())
    }

    @Test
    fun `decrypt key is derived from process timestamp and secret`() {
        assertEquals(
            com.par9uet.jm.utils.md5("${API_TS}$APP_DATA_SECRET"),
            ApiContext.getDataDecryptKey(),
        )
    }
}
