package com.par9uet.jm.storage

import javax.crypto.spec.SecretKeySpec
import okhttp3.Cookie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureCookieSessionTest {
    private val prefs = InMemorySharedPreferences()
    private val startupPrefs = InMemorySharedPreferences()
    private val secret = SecretKeySpec(ByteArray(32) { 4 }, "AES")
    private val secureStorage = SecureStorage(prefs, startupPrefs, cryptoManager = CryptoManager { secret })
    private var now = 1_700_000_000_000L

    private fun store() = SecureCookieStorage(secureStorage) { now }
    private fun cookie(value: String) = Cookie.Builder().name("AVS").value(value)
        .domain("18comic.vip").path("/").build()

    @Test fun `JWT and AVS persist together and non-session cookie updates keep JWT`() {
        val first = store()
        assertTrue(first.setSession(listOf(cookie("one")), "jwt-one"))
        val restarted = store()
        assertEquals("one", restarted.get().single().value)
        assertEquals("jwt-one", restarted.bearerToken())

        assertTrue(restarted.set(listOf(cookie("two"))))
        assertEquals("jwt-one", store().bearerToken())
        assertEquals("two", store().get().single().value)

        now += 60 * 60 * 1_000L
        assertNull(store().bearerToken())
        store().remove()
        assertTrue(store().get().isEmpty())
        assertNull(store().bearerToken())
    }

    @Test fun `legacy cookie session remains readable without a JWT`() {
        assertTrue(secureStorage.set("cookie", listOf(cookie("legacy"))) is StorageWriteResult.Success)
        val migrated = store()
        assertEquals("legacy", migrated.get().single().value)
        assertNull(migrated.bearerToken())
        assertTrue(migrated.setSession(listOf(cookie("new")), "jwt-new"))
        assertEquals("jwt-new", store().bearerToken())
    }

    @Test fun `corrupt new session does not revive a legacy account`() {
        assertTrue(secureStorage.set("cookie", listOf(cookie("old-account"))) is StorageWriteResult.Success)
        prefs.edit().putString("auth_session", "corrupt").commit()

        assertTrue(store().get().isEmpty())
        assertNull(store().bearerToken())
    }
}
