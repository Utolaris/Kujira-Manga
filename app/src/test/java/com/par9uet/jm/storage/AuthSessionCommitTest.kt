package com.par9uet.jm.storage

import android.content.SharedPreferences
import com.par9uet.jm.core.model.User
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.favorites.FakeConnectionMode
import com.par9uet.jm.favorites.fakeNightPrompt
import com.par9uet.jm.retrofit.model.LoginResponse
import com.par9uet.jm.session.CandidateSession
import com.par9uet.jm.session.SessionReadinessHolder
import com.par9uet.jm.session.UserManager
import com.par9uet.jm.session.UserRepository
import java.lang.reflect.Proxy
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After

@OptIn(ExperimentalCoroutinesApi::class)
class AuthSessionCommitTest {
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun teardown() { Dispatchers.resetMain() }
    /** Model Android's memory update before commit returns false, separately from durable data. */
    private class DiskPreferences : SharedPreferences by InMemorySharedPreferences() {
        private val memory = InMemorySharedPreferences()
        private var disk: Map<String, *> = emptyMap<String, Any>()
        var writable = true
        var throwOnCommit = false
        override fun getString(key: String?, defValue: String?) = memory.getString(key, defValue)
        override fun getBoolean(key: String?, defValue: Boolean) = memory.getBoolean(key, defValue)
        override fun contains(key: String?) = memory.contains(key)
        override fun edit(): SharedPreferences.Editor {
            val editor = memory.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { editor.putBoolean(key, value); return this }
                override fun remove(key: String?): SharedPreferences.Editor { editor.remove(key); return this }
                override fun commit(): Boolean {
                    editor.commit()
                    if (throwOnCommit) error("synthetic disk failure")
                    if (writable) disk = memory.all
                    return writable
                }
                override fun apply() { commit() }
            }
        }
        fun restart(): SharedPreferences = InMemorySharedPreferences().also { prefs ->
            val editor = prefs.edit()
            disk.forEach { (key, value) -> when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
            } }
            editor.commit()
        }
    }

    private val key = SecretKeySpec(ByteArray(32) { 5 }, "AES")
    private val data = DiskPreferences()
    private val startup = DiskPreferences()
    private var keystoreAvailable = true
    private var logins = 0
    private var probes = 0
    private val secure = SecureStorage(data, startup, cryptoManager = CryptoManager {
        check(keystoreAvailable); key
    })
    private val users = SecureUserStorage(secure)
    private val cookies = SecureCookieStorage(secure)

    private fun user(id: Int) = User.create().copy(id = id, username = if (id == 7) "A" else "B", password = "synthetic")
    private fun cookie(value: String) = Cookie.Builder().name("AVS").value(value).domain("example.com").build()
    private fun seed() { assertTrue(cookies.setAuthenticatedSession(listOf(cookie("A")), "jwt-a", user(7))) }
    private fun reopened() = SecureStorage(data.restart(), startup.restart(), cryptoManager = CryptoManager { key })
    private fun manager(): UserManager {
        val unused = Proxy.newProxyInstance(UserRepository::class.java.classLoader, arrayOf(UserRepository::class.java)) {
            _, method, _ -> error("Unexpected ${method.name}")
        } as UserRepository
        val repo = object : UserRepository by unused {
            override suspend fun login(username: String, password: String): NetWorkResult<CandidateSession> {
                logins++
                return NetWorkResult.Success(CandidateSession(
                    LoginResponse(if (username == "B") 8 else 7, username, "", "", "0", 0, "M", 1, 100, 0, 0.0, 100),
                    listOf(cookie(username)), "jwt-${username.lowercase()}",
                ))
            }
            override suspend fun probeActiveSession(): NetWorkResult<Unit> { probes++; return NetWorkResult.Success(Unit) }
            override suspend fun verifyLogin(username: String, password: String, origin: com.par9uet.jm.core.network.AuthAttemptOrigin) =
                login(username, password)
            override fun activateVerifiedSession(verified: CandidateSession, identity: User) =
                cookies.setAuthenticatedSession(verified.embeddedCookies, verified.jwtToken, identity)
            override fun clearSession() = Unit
        }
        val mode = FakeConnectionMode()
        return UserManager(users, cookies, repo, SessionReadinessHolder(), fakeNightPrompt(mode), mode)
    }

    @Test fun `failed login commit keeps A in UI memory and durable identity plus credentials`() = runTest {
        seed()
        val manager = manager()
        startup.writable = false
        assertTrue(manager.login("B", "synthetic") is NetWorkResult.Error)
        assertEquals(7, manager.currentSessionSnapshot().accountId)
        assertEquals(7, users.get().id)
        assertEquals("A", cookies.get().single().value)
        val restarted = reopened()
        assertEquals(7, SecureUserStorage(restarted).get().id)
        assertEquals("A", SecureCookieStorage(restarted).get().single().value)
        assertEquals("jwt-a", SecureCookieStorage(restarted).bearerToken())
    }

    @Test fun `login commits B identity AVS and JWT in one value and cookie rotation retains identity`() = runTest {
        seed()
        assertTrue(manager().login("B", "synthetic") is NetWorkResult.Success)
        assertTrue(cookies.set(listOf(cookie("B-rotated"))))
        val restarted = reopened()
        assertEquals(8, SecureUserStorage(restarted).get().id)
        assertEquals("B-rotated", SecureCookieStorage(restarted).get().single().value)
        assertEquals("jwt-b", SecureCookieStorage(restarted).bearerToken())
        assertTrue(SecureCookieStorage(restarted).isBoundToAccount(8))
        assertFalse(SecureCookieStorage(restarted).isBoundToAccount(7))
    }

    @Test fun `Keystore failure cannot partially switch an account`() = runTest {
        seed()
        val manager = manager()
        keystoreAvailable = false
        assertTrue(manager.login("B", "synthetic") is NetWorkResult.Error)
        assertEquals(7, manager.currentSessionSnapshot().accountId)
        val restarted = reopened()
        assertEquals(7, SecureUserStorage(restarted).get().id)
        assertEquals("A", SecureCookieStorage(restarted).get().single().value)
    }

    @Test fun `failed logout reports failure and retains the current account for retry`() = runTest {
        seed()
        val manager = manager()
        startup.writable = false
        assertFalse(manager.clearUser())
        assertEquals(7, manager.currentSessionSnapshot().accountId)
        assertTrue(manager.userState.value.isError)
        assertEquals(7, users.get().id)
        assertEquals("A", cookies.get().single().value)
        assertEquals(7, SecureUserStorage(reopened()).get().id)
        startup.writable = true
        assertTrue(manager.clearUser())
        assertEquals(0, manager.currentSessionSnapshot().accountId)
        assertEquals(0, SecureUserStorage(reopened()).get().id)
        assertTrue(SecureCookieStorage(reopened()).get().isEmpty())
    }

    @Test fun `logout tombstone works without Keystore and masks failed legacy deletion after restart`() = runTest {
        assertTrue(secure.set("user", user(7)) is StorageWriteResult.Success)
        assertTrue(secure.set("cookie", listOf(cookie("legacy-a"))) is StorageWriteResult.Success)
        seed()
        val manager = manager()
        keystoreAvailable = false
        data.writable = false
        assertTrue(manager.clearUser())
        assertFalse(cookies.set(listOf(cookie("late-a"))))
        assertEquals(0, SecureUserStorage(reopened()).get().id)
        assertTrue(SecureCookieStorage(reopened()).get().isEmpty())
        assertNull(SecureCookieStorage(reopened()).bearerToken())
        keystoreAvailable = true
        assertTrue(manager.login("B", "synthetic") is NetWorkResult.Success)
        assertEquals(8, SecureUserStorage(reopened()).get().id)
        assertEquals("B", SecureCookieStorage(reopened()).get().single().value)
    }

    @Test fun `legacy credentials are readable but cannot prove their account binding`() {
        assertTrue(secure.setStartup("user", user(7)) is StorageWriteResult.Success)
        assertTrue(secure.set("cookie", listOf(cookie("legacy-a"))) is StorageWriteResult.Success)
        assertEquals(7, users.get().id)
        assertEquals("legacy-a", cookies.get().single().value)
        assertFalse(cookies.isBoundToAccount(7))
    }

    @Test fun `legacy session binds once by login and later cold checks only probe`() = runTest {
        assertTrue(secure.setStartup("user", user(7)) is StorageWriteResult.Success)
        assertTrue(secure.set("cookie", listOf(cookie("unbound-cookie"))) is StorageWriteResult.Success)
        val manager = manager()
        manager.verifyStoredLogin()
        assertEquals(1, logins)
        assertEquals(0, probes)
        assertTrue(cookies.isBoundToAccount(7))
        assertEquals(7, SecureUserStorage(reopened()).get().id)
        manager.verifyStoredLogin()
        assertEquals(1, logins)
        assertEquals(1, probes)
    }

    @Test fun `throwing disk commit restores memory for both login and logout`() = runTest {
        seed()
        val manager = manager()
        startup.throwOnCommit = true
        assertTrue(manager.login("B", "synthetic") is NetWorkResult.Error)
        assertEquals(7, users.get().id)
        assertFalse(manager.clearUser())
        assertEquals(7, manager.currentSessionSnapshot().accountId)
        assertEquals(7, users.get().id)
        assertEquals("A", cookies.get().single().value)
        assertEquals(7, SecureUserStorage(reopened()).get().id)
    }
}
