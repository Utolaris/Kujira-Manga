package com.par9uet.jm.storage

import com.google.gson.reflect.TypeToken
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

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
    private val revision = MutableStateFlow(0L)

    /** Count comics, not taps; an unreadable queue must never be presented as zero. */
    fun observePendingCount(accountId: Int): Flow<Int?> = if (accountId <= 0) {
        flowOf(0)
    } else {
        revision.map { snapshot(accountId)?.distinctBy { it.albumId }?.size }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
    }

    fun record(accountId: Int, albumId: Int, collect: Boolean): Boolean = synchronized(lock) {
        if (accountId <= 0) return@synchronized false
        val current = store.getOrNull() ?: return@synchronized false
        val saved = store.set(current.filterNot { it.accountId == accountId && it.albumId == albumId } +
            LocalFavoriteChange(accountId, albumId, collect, UUID.randomUUID().toString()))
        if (saved) revision.value++
        saved
    }

    fun snapshot(accountId: Int): List<LocalFavoriteChange>? = synchronized(lock) {
        store.getOrNull()?.filter { it.accountId == accountId }
    }

    /** Keep a newer opposite action when a sync was in flight. */
    fun removeIfUnchanged(change: LocalFavoriteChange): Boolean = synchronized(lock) {
        val current = store.getOrNull() ?: return@synchronized false
        val latest = current.find { it.accountId == change.accountId && it.albumId == change.albumId }
        if (latest != change) return@synchronized false
        val saved = store.set(current.filterNot { it.accountId == change.accountId && it.albumId == change.albumId })
        if (saved) revision.value++
        saved
    }

    /** Discard only the account whose remote snapshot has been applied. */
    fun clear(accountId: Int): Boolean = synchronized(lock) {
        val current = store.getOrNull() ?: return@synchronized false
        if (current.none { it.accountId == accountId }) return@synchronized true
        val saved = store.set(current.filterNot { it.accountId == accountId })
        if (saved) revision.value++
        saved
    }
}
