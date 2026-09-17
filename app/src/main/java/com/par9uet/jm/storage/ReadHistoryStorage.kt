package com.par9uet.jm.storage

import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ComicReadHistory(
    val lastChapterId: Int = 0,
    val readChapterIds: List<Int> = emptyList(),
    val lastPageIndex: Int = 0,
    val lastChapterPageCount: Int = 0,
)

/** 阅读历史持久化端口；`null` 读结果表示 Keystore 暂时不可读。 */
interface ReadHistoryStore {
    fun getOrNull(): Map<Int, ComicReadHistory>?
    fun set(history: Map<Int, ComicReadHistory>)
}

class ReadHistoryStorage(
    private val secureStorage: SecureStorage
) : ReadHistoryStore {
    companion object {
        private const val STORAGE_KEY = "comicReadHistory"
    }

    private val _state = MutableStateFlow<Map<Int, ComicReadHistory>?>(null)
    val state = _state.asStateFlow()

    override fun set(history: Map<Int, ComicReadHistory>) {
        when (secureStorage.set(STORAGE_KEY, history)) {
            is StorageWriteResult.Success -> _state.update { history }
            is StorageWriteResult.TemporaryUnavailable -> Unit
        }
    }

    /**
     * @return 历史快照；`null` 表示 Keystore 暂时不可读。调用方不得把 `null` 当成空历史，
     * 也不得基于空基线做全量写回。
     */
    override fun getOrNull(): Map<Int, ComicReadHistory>? {
        _state.value?.let { return it }
        return when (
            val result = secureStorage.get<Map<Int, ComicReadHistory>>(
                STORAGE_KEY,
                object : TypeToken<Map<Int, ComicReadHistory>>() {}.type
            )
        ) {
            is StorageReadResult.Success -> result.value.also { _state.value = it }
            // Permanent failures may cache empty; temporary Keystore outage must retry.
            is StorageReadResult.Missing,
            is StorageReadResult.Corrupted,
            -> emptyMap<Int, ComicReadHistory>().also { _state.value = it }
            is StorageReadResult.TemporaryUnavailable -> null
        }
    }

    fun remove() {
        _state.update { emptyMap() }
        secureStorage.remove(STORAGE_KEY)
    }
}
