package com.par9uet.jm.storage

import com.google.gson.reflect.TypeToken
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
    fun get(): List<Cookie>

    /**
     * `null` 表示 Keystore 暂时不可读。merge 写回等路径必须跳过，避免用空列表覆盖完整会话快照。
     * 默认实现委托给 [get]，供内存测试替身使用。
     */
    fun getOrNull(): List<Cookie>? = get()

    fun remove()
}

class SecureCookieStorage(
    private val secureStorage: SecureStorage
) : CookieStorage {
    companion object {
        private const val STORAGE_KEY = "cookie"
    }

    private var _state = MutableStateFlow<List<Cookie>?>(null)
    override val state = _state.asStateFlow()

    override fun set(cookieStore: List<Cookie>): Boolean {
        // Only publish in-memory after the durable write succeeds, so memory cannot diverge from disk.
        return when (secureStorage.set(STORAGE_KEY, cookieStore)) {
            is StorageWriteResult.Success -> {
                _state.update { cookieStore }
                true
            }
            is StorageWriteResult.TemporaryUnavailable -> false
        }
    }

    override fun get(): List<Cookie> = getOrNull() ?: emptyList()

    override fun getOrNull(): List<Cookie>? {
        _state.value?.let { return it }
        return when (
            val result = secureStorage.get<List<Cookie>>(
                STORAGE_KEY,
                object : TypeToken<List<Cookie>>() {}.type,
            )
        ) {
            is StorageReadResult.Success -> result.value.also { _state.value = it }
            // Permanent failures may cache empty; temporary Keystore outage must retry.
            is StorageReadResult.Missing,
            is StorageReadResult.Corrupted,
            -> emptyList<Cookie>().also { _state.value = it }
            is StorageReadResult.TemporaryUnavailable -> null
        }
    }

    override fun remove() {
        _state.update {
            listOf()
        }
        secureStorage.remove(STORAGE_KEY)
    }
}
