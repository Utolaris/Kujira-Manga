package com.par9uet.jm.retrofit

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiContextTest {
    @Test
    fun `Retrofit token version matches official client`() {
        assertEquals("2.1.14", com.par9uet.jm.core.network.OfficialApiSignature.VERSION)
        assertEquals("${API_TS},2.1.14", com.par9uet.jm.core.network.OfficialApiSignature.tokenParam(API_TS.toString()))
        assertEquals(com.par9uet.jm.utils.md5("${API_TS}185Hcomic3PAPP7R"), API_TOKEN_HASH)
    }

    @Test
    fun `decrypt keys include primary and fallback official secrets`() {
        assertEquals(
            listOf(
                com.par9uet.jm.utils.md5("${API_TS}185Hcomic3PAPP7R"),
                com.par9uet.jm.utils.md5("${API_TS}18comicAPPContent"),
            ),
            ApiContext.getDataDecryptKeys(),
        )
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
