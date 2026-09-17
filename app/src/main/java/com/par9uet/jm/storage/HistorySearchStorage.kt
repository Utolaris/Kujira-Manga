package com.par9uet.jm.storage

import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 搜索历史持久化端口；`null` 读结果表示 Keystore 暂时不可读。 */
interface HistorySearchStore {
    fun getOrNull(): List<String>?
    fun set(list: List<String>)
    fun remove()
}

class HistorySearchStorage(
    private val secureStorage: SecureStorage
) : HistorySearchStore {
    companion object {
        private const val STORAGE_KEY = "historySearch"
    }

    private var _state = MutableStateFlow<List<String>?>(null)
    val state = _state.asStateFlow()

    override fun set(list: List<String>) {
        when (secureStorage.set(STORAGE_KEY, list)) {
            is StorageWriteResult.Success -> _state.update { list }
            is StorageWriteResult.TemporaryUnavailable -> Unit
        }
    }

    /**
     * @return 搜索历史快照；`null` 表示 Keystore 暂时不可读。调用方不得把 `null` 当成空列表，
     * 也不得基于空基线做全量写回。
     */
    override fun getOrNull(): List<String>? {
        _state.value?.let { return it }
        return when (
            val result = secureStorage.get<List<String>>(
                STORAGE_KEY,
                object : TypeToken<List<String>>() {}.type,
            )
        ) {
            is StorageReadResult.Success -> result.value.also { _state.value = it }
            // Permanent failures may cache empty; temporary Keystore outage must retry.
            is StorageReadResult.Missing,
            is StorageReadResult.Corrupted,
            -> emptyList<String>().also { _state.value = it }
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
