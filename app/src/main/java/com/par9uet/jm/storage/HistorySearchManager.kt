package com.par9uet.jm.storage
import com.par9uet.jm.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class HistorySearchManager(
    private val historySearchStorage: HistorySearchStore,
    private val persistScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    companion object {
        /** 搜索历史条数上限，避免长期使用后 UI 全量渲染与整表写盘膨胀。 */
        const val MAX_ITEMS = 50
    }

    private val _historySearchState = MutableStateFlow(listOf<String>())
    val historySearchState = _historySearchState.asStateFlow()

    private val persistMutex = Mutex()

    fun addItem(item: String) {
        if (item.isBlank()) return
        val ready = ensureLoaded()
        val list = (listOf(item) + _historySearchState.value.filterNot { it == item })
            .distinct()
            .take(MAX_ITEMS)
        _historySearchState.update { list }
        if (ready) schedulePersist(list)
    }

    fun clear() {
        historySearchStorage.remove()
        _historySearchState.update {
            historySearchStorage.getOrNull().orEmpty()
        }
    }

    suspend fun load() {
        log("加载历史搜索数据")
        ensureLoaded()
        log("已加载历史搜索数据")
    }

    @Synchronized
    private fun ensureLoaded(): Boolean {
        if (loaded) return true
        val disk = historySearchStorage.getOrNull() ?: return false
        val memory = _historySearchState.value
        // 会话内新词条更靠前；与磁盘合并去重后截断。写回由随后的 addItem 完成。
        _historySearchState.value = (memory + disk).distinct().take(MAX_ITEMS)
        loaded = true
        return true
    }

    private fun schedulePersist(list: List<String>) {
        val snapshot = list.toList()
        persistScope.launch {
            persistMutex.withLock {
                try {
                    historySearchStorage.set(snapshot)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    log("写入搜索历史失败：" + error.message)
                }
            }
        }
    }

    private var loaded = false
}
