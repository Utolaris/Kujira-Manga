package com.par9uet.jm.storage

import com.google.gson.reflect.TypeToken
import com.par9uet.jm.core.model.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Cookie

/** 活动认证会话 cookie 的持久化读写。[SecureCookieStorage] 提供加密实现，测试可用内存替身。 */
interface CookieStorage {
    val state: StateFlow<List<Cookie>?>

    /** @return false 表示未能写入（含 Keystore 临时不可用）；调用方不得把未持久化的会话当成成功。 */
    fun set(cookieStore: List<Cookie>): Boolean
    /** Atomically promote the cookies and optional official JWT from one verified login. */
    fun setSession(cookieStore: List<Cookie>, bearerToken: String?): Boolean = set(cookieStore)
    /** Production implementations must commit identity and credentials together. */
    fun setAuthenticatedSession(cookieStore: List<Cookie>, bearerToken: String?, identity: User): Boolean =
        setSession(cookieStore, bearerToken)
    fun isBoundToAccount(accountId: Int): Boolean = true
    fun get(): List<Cookie>
    fun bearerToken(): String? = null

    /**
     * `null` 表示 Keystore 暂时不可读。merge 写回等路径必须跳过，避免用空列表覆盖完整会话快照。
     * 默认实现委托给 [get]，供内存测试替身使用。
     */
    fun getOrNull(): List<Cookie>? = get()

    fun remove()
    /** Only true after durable revocation. In-memory test stores have no disk boundary. */
    fun clearSession(): Boolean { remove(); return true }
}

class SecureCookieStorage(
    private val secureStorage: SecureStorage,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : CookieStorage {
    companion object {
        private const val STORAGE_KEY = "auth_session"
        private const val LEGACY_COOKIE_KEY = "cookie"
        private const val JWT_LIFETIME_MS = 60 * 60 * 1_000L
    }

    private var _state = MutableStateFlow<List<Cookie>?>(null)
    override val state = _state.asStateFlow()
    private var cachedSession: AuthSessionRecord? = null

    @Synchronized
    override fun set(cookieStore: List<Cookie>): Boolean {
        if (secureStorage.isLoggedOut()) return false
        val previous = loadSession() ?: return false
        return writeSession(previous.copy(cookies = cookieStore))
    }

    @Synchronized
    override fun setSession(cookieStore: List<Cookie>, bearerToken: String?): Boolean =
        writeSession(AuthSessionRecord(
            cookies = cookieStore,
            bearerToken = bearerToken?.takeIf { it.isNotBlank() },
            bearerExpiryMillis = if (bearerToken.isNullOrBlank()) 0L else nowMillis() + JWT_LIFETIME_MS,
        ))

    @Synchronized
    override fun setAuthenticatedSession(cookieStore: List<Cookie>, bearerToken: String?, identity: User): Boolean =
        writeSession(AuthSessionRecord(
            cookies = cookieStore,
            bearerToken = bearerToken?.takeIf { it.isNotBlank() },
            bearerExpiryMillis = if (bearerToken.isNullOrBlank()) 0L else nowMillis() + JWT_LIFETIME_MS,
            identity = identity,
        ))

    @Synchronized
    override fun isBoundToAccount(accountId: Int): Boolean = loadSession()?.identity?.id == accountId

    private fun writeSession(session: AuthSessionRecord): Boolean {
        // Publish the pair only after one durable encrypted write succeeds.
        return when (secureStorage.setAuthSession(session)) {
            is StorageWriteResult.Success -> {
                cachedSession = session
                _state.update { session.cookies }
                true
            }
            is StorageWriteResult.TemporaryUnavailable -> false
        }
    }

    override fun get(): List<Cookie> = getOrNull() ?: emptyList()

    @Synchronized
    override fun getOrNull(): List<Cookie>? = loadSession()?.cookies

    @Synchronized
    override fun bearerToken(): String? = loadSession()?.let { session ->
        session.bearerToken?.takeIf { nowMillis() < session.bearerExpiryMillis }
    }

    private fun loadSession(): AuthSessionRecord? {
        if (secureStorage.isLoggedOut()) return AuthSessionRecord(emptyList()).also {
            cachedSession = it
            _state.value = it.cookies
        }
        cachedSession?.let { return it }
        when (val current = secureStorage.getStartup<AuthSessionRecord>(AUTH_SESSION_KEY, object : TypeToken<AuthSessionRecord>() {}.type)) {
            is StorageReadResult.Success -> return current.value.also { cachedSession = it; _state.value = it.cookies }
            is StorageReadResult.TemporaryUnavailable -> return null
            is StorageReadResult.Corrupted -> return AuthSessionRecord(emptyList())
            is StorageReadResult.Missing -> Unit
        }
        val session = when (
            val result = secureStorage.get<AuthSessionRecord>(
                STORAGE_KEY,
                object : TypeToken<AuthSessionRecord>() {}.type,
            )
        ) {
            is StorageReadResult.Success -> result.value
            is StorageReadResult.TemporaryUnavailable -> null
            // A corrupt new record must not resurrect an older account from the legacy key.
            is StorageReadResult.Corrupted -> AuthSessionRecord(emptyList())
            is StorageReadResult.Missing -> when (val legacy = secureStorage.get<List<Cookie>>(
                LEGACY_COOKIE_KEY,
                object : TypeToken<List<Cookie>>() {}.type,
            )) {
                is StorageReadResult.Success -> AuthSessionRecord(legacy.value)
                is StorageReadResult.TemporaryUnavailable -> null
                is StorageReadResult.Missing,
                is StorageReadResult.Corrupted,
                -> AuthSessionRecord(emptyList())
            }
        }
        if (session != null) {
            cachedSession = session
            _state.value = session.cookies
        }
        return session
    }

    @Synchronized
    override fun remove() { clearSession() }

    @Synchronized
    override fun clearSession(): Boolean {
        if (!secureStorage.clearAuthSession()) return false
        cachedSession = AuthSessionRecord(emptyList())
        _state.update { emptyList() }
        // The confirmed startup tombstone masks these older records even if cleanup fails.
        secureStorage.remove(STORAGE_KEY)
        secureStorage.remove(LEGACY_COOKIE_KEY)
        secureStorage.remove("user")
        return true
    }
}
