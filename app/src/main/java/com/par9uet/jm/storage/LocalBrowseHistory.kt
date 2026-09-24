package com.par9uet.jm.storage

import com.google.gson.reflect.TypeToken
import com.par9uet.jm.data.models.Comic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 本地模式下的「历史观看」条目。只保留列表卡片所需字段，封面仍按 [id] 取图。
 */
data class LocalBrowseHistoryEntry(
    val accountId: Int,
    val id: Int,
    val name: String,
    val authorList: List<String> = emptyList(),
    val viewedAt: Long = 0L,
) {
    fun toComic(): Comic = Comic.create(id = id, name = name, authorList = authorList)
}

/** 本地浏览历史持久化端口；`null` 读结果表示 Keystore 暂时不可读。 */
interface LocalBrowseHistoryStore {
    fun getOrNull(): List<LocalBrowseHistoryEntry>?
    fun set(entries: List<LocalBrowseHistoryEntry>): Boolean
}

class LocalBrowseHistoryStorage(
    private val secureStorage: SecureStorage,
) : LocalBrowseHistoryStore {
    companion object {
        private const val STORAGE_KEY = "localBrowseHistory"
        private const val MAX_ENTRIES = 500
    }

    private val _state = MutableStateFlow<List<LocalBrowseHistoryEntry>?>(null)

    override fun set(entries: List<LocalBrowseHistoryEntry>): Boolean {
        val counts = mutableMapOf<Int, Int>()
        val trimmed = entries.filter { entry ->
            val count = counts[entry.accountId] ?: 0
            if (count >= MAX_ENTRIES) false else {
                counts[entry.accountId] = count + 1
                true
            }
        }
        return when (secureStorage.set(STORAGE_KEY, trimmed)) {
            is StorageWriteResult.Success -> {
                _state.update { trimmed }
                true
            }
            is StorageWriteResult.TemporaryUnavailable -> false
        }
    }

    override fun getOrNull(): List<LocalBrowseHistoryEntry>? {
        _state.value?.let { return it }
        return when (
            val result = secureStorage.get<List<LocalBrowseHistoryEntry>>(
                STORAGE_KEY,
                object : TypeToken<List<LocalBrowseHistoryEntry>>() {}.type,
            )
        ) {
            is StorageReadResult.Success -> result.value.also { _state.value = it }
            is StorageReadResult.Missing -> emptyList<LocalBrowseHistoryEntry>().also { _state.value = it }
            is StorageReadResult.Corrupted,
            is StorageReadResult.TemporaryUnavailable -> null
        }
    }
}

/**
 * 本地模式浏览历史协调：写入去重置顶，列表按时间倒序。
 * 切回网络时由 LocalModeCoordinator 清除当前账号的记录（远端覆盖）。
 */
class LocalBrowseHistoryManager(
    private val store: LocalBrowseHistoryStore,
) {
    private val _entries = MutableStateFlow<List<LocalBrowseHistoryEntry>>(emptyList())
    val entries = _entries.asStateFlow()

    fun load() {
        store.getOrNull()?.let { _entries.value = it }
    }

    fun record(accountId: Int, comic: Comic, viewedAt: Long = System.currentTimeMillis()) {
        if (accountId <= 0) return
        val current = store.getOrNull() ?: return
        val next = buildList {
            add(
                LocalBrowseHistoryEntry(
                    accountId = accountId,
                    id = comic.id,
                    name = comic.name,
                    authorList = comic.authorList,
                    viewedAt = viewedAt,
                )
            )
            current.filterTo(this) { it.accountId != accountId || it.id != comic.id }
        }.sortedByDescending { it.viewedAt }
        if (store.set(next)) _entries.value = store.getOrNull().orEmpty()
    }

    fun delete(accountId: Int, albumIds: Collection<Int>): Boolean {
        if (accountId <= 0 || albumIds.isEmpty()) return false
        val removeIds = albumIds.toSet()
        val current = store.getOrNull() ?: return false
        val next = current.filterNot { it.accountId == accountId && it.id in removeIds }
        if (!store.set(next)) return false
        _entries.value = store.getOrNull().orEmpty()
        return true
    }

    /** 切回网络模式：丢弃本地模式期间的浏览记录，以远端为基线。 */
    fun clear(accountId: Int): Boolean {
        val current = store.getOrNull() ?: return false
        val next = current.filterNot { it.accountId == accountId }
        if (!store.set(next)) return false
        _entries.value = store.getOrNull().orEmpty()
        return true
    }
}
