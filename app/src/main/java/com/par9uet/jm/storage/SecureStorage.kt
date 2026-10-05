package com.par9uet.jm.storage

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.GsonBuilder

/** Distinguishes “no value” from “value exists but cannot be read right now”. */
sealed class StorageReadResult<out T> {
    data class Success<T>(val value: T) : StorageReadResult<T>()
    data object Missing : StorageReadResult<Nothing>()
    data object TemporaryUnavailable : StorageReadResult<Nothing>()
    data object Corrupted : StorageReadResult<Nothing>()
}

sealed class StorageWriteResult {
    data object Success : StorageWriteResult()
    data object TemporaryUnavailable : StorageWriteResult()
}

class SecureStorage(
    private val sharedPreferences: SharedPreferences,
    private val startupPreferences: SharedPreferences,
    gson: Gson = GsonBuilder().create(),
    private val cryptoManager: CryptoManager = CryptoManager(),
) {
    constructor(
        context: Context,
        gson: Gson = GsonBuilder().create(),
        cryptoManager: CryptoManager = CryptoManager(),
    ) : this(
        sharedPreferences = context.getSharedPreferences(DATA_PREFERENCES_NAME, Context.MODE_PRIVATE),
        startupPreferences = context.getSharedPreferences(STARTUP_PREFERENCES_NAME, Context.MODE_PRIVATE),
        gson = gson,
        cryptoManager = cryptoManager,
    )

    private val gson = gson.newBuilder().registerTypeAdapter(okhttp3.Cookie::class.java, CookieTypeAdapter()).create()

    fun <T> set(key: String, t: T): StorageWriteResult {
        val json = gson.toJson(t)
        return writeEncrypted(sharedPreferences, key, json)
    }

    /** Stores small first-frame values separately from history and download metadata. */
    fun <T> setStartup(key: String, t: T): StorageWriteResult {
        val json = gson.toJson(t)
        return setStartupString(key, json)
    }

    fun setStartupString(key: String, json: String): StorageWriteResult =
        writeEncrypted(startupPreferences, key, json)

    internal fun setAuthSession(session: AuthSessionRecord): StorageWriteResult =
        writeEncrypted(startupPreferences, AUTH_SESSION_KEY, gson.toJson(session), AUTH_LOGGED_OUT_KEY)

    internal fun isLoggedOut(): Boolean = startupPreferences.getBoolean(AUTH_LOGGED_OUT_KEY, false)

    /** No Keystore access is needed to durably revoke a session, including legacy records. */
    @android.annotation.SuppressLint("ApplySharedPref", "UseKtx")
    internal fun clearAuthSession(): Boolean {
        val previous = runCatching {
            listOf(AUTH_SESSION_KEY, "user").associateWith { startupPreferences.getString(it, null) }
        }.getOrElse { return false }
        val wasLoggedOut = runCatching { isLoggedOut() }.getOrElse { return false }
        val committed = try {
            startupPreferences.edit()
                .remove(AUTH_SESSION_KEY)
                .remove("user")
                .putBoolean(AUTH_LOGGED_OUT_KEY, true)
                .commit()
        } catch (_: Exception) { false }
        if (!committed) {
            runCatching {
                val rollback = startupPreferences.edit()
                previous.forEach { (key, value) -> rollback.putString(key, value) }
                rollback.putBoolean(AUTH_LOGGED_OUT_KEY, wasLoggedOut).commit()
            }
        }
        return committed
    }

    fun <T> get(key: String, type: java.lang.reflect.Type): StorageReadResult<T> =
        decodeResult(getString(key), type)

    /**
     * Decodes an already decrypted JSON value. This avoids reading and decrypting the same
     * SharedPreferences entry twice when a caller also needs to inspect the raw JSON.
     */
    fun <T> decode(json: String?, type: java.lang.reflect.Type): T? {
        return try {
            json?.let { gson.fromJson(it, type) }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun <T> decodeResult(json: StorageReadResult<String>, type: java.lang.reflect.Type): StorageReadResult<T> {
        return when (json) {
            is StorageReadResult.Missing -> StorageReadResult.Missing
            is StorageReadResult.TemporaryUnavailable -> StorageReadResult.TemporaryUnavailable
            is StorageReadResult.Corrupted -> StorageReadResult.Corrupted
            is StorageReadResult.Success -> {
                try {
                    val value = gson.fromJson<T>(json.value, type)
                    if (value == null) StorageReadResult.Corrupted
                    else StorageReadResult.Success(value)
                } catch (_: Exception) {
                    StorageReadResult.Corrupted
                }
            }
        }
    }

    fun getString(key: String): StorageReadResult<String> = readEncrypted(sharedPreferences, key)

    fun getStartupString(key: String): StorageReadResult<String> = readEncrypted(startupPreferences, key)

    // Keystore-backed prefs must use Editor.commit() so a failed disk write is observable.
    @android.annotation.SuppressLint("ApplySharedPref", "UseKtx")
    private fun writeEncrypted(
        preferences: SharedPreferences,
        key: String,
        json: String,
        clearFlag: String? = null,
    ): StorageWriteResult {
        // Encrypt before opening the editor. Failure preserves the last durable value and does
        // not invalidate the current in-memory identity during a temporary Keystore outage.
        val encrypted = try {
            cryptoManager.encrypt(json)
        } catch (_: Exception) {
            return StorageWriteResult.TemporaryUnavailable
        }
        // androidx edit{} returns Unit and defaults to apply(), which cannot report a failed
        // disk write. Call Editor.commit() so a false result maps to TemporaryUnavailable.
        val previous = runCatching { preferences.getString(key, null) }
            .getOrElse { return StorageWriteResult.TemporaryUnavailable }
        val hadFlag = runCatching { clearFlag != null && preferences.contains(clearFlag) }
            .getOrElse { return StorageWriteResult.TemporaryUnavailable }
        val flagValue = runCatching { clearFlag?.let { preferences.getBoolean(it, false) } }
            .getOrElse { return StorageWriteResult.TemporaryUnavailable }
        val committed = try {
            val editor = preferences.edit().putString(key, encrypted)
            if (clearFlag != null) editor.remove(clearFlag)
            editor.commit()
        } catch (_: Exception) {
            false
        }
        if (!committed) {
            // Android updates its memory map even when the disk commit fails or throws.
            runCatching {
                val rollback = preferences.edit().putString(key, previous)
                if (clearFlag != null) {
                    if (hadFlag) rollback.putBoolean(clearFlag, flagValue == true) else rollback.remove(clearFlag)
                }
                rollback.commit()
            }
        }
        return if (committed) StorageWriteResult.Success
        else StorageWriteResult.TemporaryUnavailable
    }

    private fun readEncrypted(preferences: SharedPreferences, key: String): StorageReadResult<String> {
        val stored = preferences.getString(key, null) ?: return StorageReadResult.Missing
        return when (val decrypted = cryptoManager.decrypt(stored)) {
            is DecryptResult.Success -> {
                if (stored.startsWith("plain:")) writeEncrypted(preferences, key, decrypted.value)
                StorageReadResult.Success(decrypted.value)
            }
            is DecryptResult.TemporaryUnavailable -> StorageReadResult.TemporaryUnavailable
            is DecryptResult.Corrupted -> StorageReadResult.Corrupted
        }
    }

    fun <T> getStartup(key: String, type: java.lang.reflect.Type): StorageReadResult<T> =
        decodeResult(getStartupString(key), type)

    @android.annotation.SuppressLint("ApplySharedPref", "UseKtx")
    fun remove(key: String): Boolean = try {
        sharedPreferences.edit().remove(key).commit()
    } catch (_: Exception) { false }

    @android.annotation.SuppressLint("ApplySharedPref", "UseKtx")
    fun removeStartup(key: String): Boolean = try {
        startupPreferences.edit().remove(key).commit()
    } catch (_: Exception) { false }

    companion object {
        /** Names of the files that hold encoded values, for callers that inspect stored ciphertext. */
        const val DATA_PREFERENCES_NAME = "kujira-manga-g-data"
        const val STARTUP_PREFERENCES_NAME = "kujira-manga-startup"
    }
}
