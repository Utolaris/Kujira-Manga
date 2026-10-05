package com.par9uet.jm.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.par9uet.jm.core.model.User
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
            fun secure() = SecureStorage(prefs, startupPrefs)
            fun storage() = SecureCookieStorage(secure())
            val avs = Cookie.Builder().name("AVS").value("instrumented-avs")
                .hostOnlyDomain("api.example").secure().build()

            val identity = User.create().copy(id = 7, username = "instrumented", password = "synthetic")
            assertTrue(storage().setAuthenticatedSession(listOf(avs), "instrumented-jwt", identity))
            val restored = storage()
            assertEquals(listOf(avs), restored.get())
            assertEquals("instrumented-jwt", restored.bearerToken())
            assertEquals(identity, SecureUserStorage(secure()).get())
            val ciphertext = startupPrefs.getString("auth_session_v2", null).orEmpty()
            assertTrue(ciphertext.startsWith("enc:"))
            assertFalse(ciphertext.contains("instrumented-jwt"))

            restored.remove()
            assertTrue(storage().get().isEmpty())
            assertEquals(0, SecureUserStorage(secure()).get().id)
        } finally {
            prefs.edit().clear().commit()
            startupPrefs.edit().clear().commit()
        }
    }
}
