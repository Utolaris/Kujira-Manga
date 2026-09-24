package com.par9uet.jm.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.Cookie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialAuthSessionPersistenceTest {
    @Test fun jwtAndAvsSurviveProcessStyleReconstructionInOneEncryptedRecord() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("official-auth-session-test", Context.MODE_PRIVATE)
        val startupPrefs = context.getSharedPreferences("official-auth-session-startup-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        startupPrefs.edit().clear().commit()
        try {
            fun storage() = SecureCookieStorage(SecureStorage(prefs, startupPrefs))
            val avs = Cookie.Builder().name("AVS").value("instrumented-avs")
                .hostOnlyDomain("api.example").secure().build()

            assertTrue(storage().setSession(listOf(avs), "instrumented-jwt"))
            val restored = storage()
            assertEquals(listOf(avs), restored.get())
            assertEquals("instrumented-jwt", restored.bearerToken())
            val ciphertext = prefs.getString("auth_session", null).orEmpty()
            assertTrue(ciphertext.startsWith("enc:"))
            assertFalse(ciphertext.contains("instrumented-jwt"))

            restored.remove()
            assertTrue(storage().get().isEmpty())
        } finally {
            prefs.edit().clear().commit()
            startupPrefs.edit().clear().commit()
        }
    }
}
