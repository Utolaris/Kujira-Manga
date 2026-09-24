package com.par9uet.jm.storage

import com.google.gson.reflect.TypeToken
import java.util.UUID

/** The latest local-mode intent for one comic in one remote account. */
data class LocalFavoriteChange(
    val accountId: Int,
    val albumId: Int,
    val collect: Boolean,
    /** Distinguishes a new same-direction tap from an older in-flight sync. */
    val changeId: String? = null,
)

interface LocalFavoriteChangeStore {
    /** null means the encrypted store cannot be read; callers must not infer an empty queue. */
    fun getOrNull(): List<LocalFavoriteChange>?
    fun set(items: List<LocalFavoriteChange>): Boolean
}

class LocalFavoriteChangeStorage(
    private val secureStorage: SecureStorage,
) : LocalFavoriteChangeStore {
    private companion object {
        const val STORAGE_KEY = "localFavoriteChanges"
    }

    override fun getOrNull(): List<LocalFavoriteChange>? = when (
        val result = secureStorage.get<List<LocalFavoriteChange>>(
            STORAGE_KEY,
            object : TypeToken<List<LocalFavoriteChange>>() {}.type,
        )
    ) {
        is StorageReadResult.Success -> result.value
        is StorageReadResult.Missing -> emptyList()
        is StorageReadResult.Corrupted,
        is StorageReadResult.TemporaryUnavailable -> null
    }

    override fun set(items: List<LocalFavoriteChange>): Boolean =
        secureStorage.set(STORAGE_KEY, items) is StorageWriteResult.Success
}

/** Writes are acknowledged only after persistence; failed writes leave the previous intent intact. */
class LocalFavoriteChangeManager(
    private val store: LocalFavoriteChangeStore,
) {
    private val lock = Any()

    fun record(accountId: Int, albumId: Int, collect: Boolean): Boolean = synchronized(lock) {
        if (accountId <= 0) return@synchronized false
        val current = store.getOrNull() ?: return@synchronized false
        store.set(current.filterNot { it.accountId == accountId && it.albumId == albumId } +
            LocalFavoriteChange(accountId, albumId, collect, UUID.randomUUID().toString()))
    }

    fun snapshot(accountId: Int): List<LocalFavoriteChange>? = synchronized(lock) {
        store.getOrNull()?.filter { it.accountId == accountId }
    }

    /** Keep a newer opposite action when a sync was in flight. */
    fun removeIfUnchanged(change: LocalFavoriteChange): Boolean = synchronized(lock) {
        val current = store.getOrNull() ?: return@synchronized false
        val latest = current.find { it.accountId == change.accountId && it.albumId == change.albumId }
        if (latest != change) return@synchronized false
        store.set(current.filterNot { it.accountId == change.accountId && it.albumId == change.albumId })
    }

    /** 强制与远端对齐时丢弃全部待同步意图（可能包含未上云的收藏）。 */
    fun clearAll(): Boolean = synchronized(lock) {
        val current = store.getOrNull() ?: return@synchronized false
        if (current.isEmpty()) return@synchronized true
        store.set(emptyList())
    }
}
