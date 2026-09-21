package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.par9uet.jm.cache.CacheArea
import com.par9uet.jm.cache.CacheBudget
import com.par9uet.jm.cache.atom.CacheFiles
import com.par9uet.jm.cache.CacheSize
import com.par9uet.jm.storage.LocalSettingManager
import com.par9uet.jm.utils.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** UI 档位；Screen 不 import cache 包。 */
data class CacheBudgetStop(
    val mb: Int,
    val label: String,
)

/** 明细行模型；不含 CacheArea，避免 Screen 依赖 cache。 */
data class CachePieSlice(
    val title: String,
    val sizeBytes: Long,
    val fraction: Float,
    val isDownload: Boolean,
    val isResidual: Boolean = false,
)

data class CacheControlState(
    val loading: Boolean = true,
    val cleaning: Boolean = false,
    val budgetMb: Int = CacheBudget.DEFAULT_TOTAL_MB,
    val downloadExempt: Boolean = true,
    val controlledUsedBytes: Long = 0L,
    val budgetBytes: Long = CacheBudget.totalBytes(CacheBudget.DEFAULT_TOTAL_MB),
    val result: String? = null,
    val pieSlices: List<CachePieSlice> = emptyList(),
    val compositionTotalBytes: Long = 0L,
    val budgetStops: List<CacheBudgetStop> = defaultBudgetStops(),
) {
    val budgetUnlimited: Boolean
        get() = CacheBudget.isUnlimited(budgetMb)

    val displayUsedBytes: Long
        get() = controlledUsedBytes

    val usageRatio: Float
        get() = if (!budgetUnlimited && budgetBytes > 0L) {
            (controlledUsedBytes.toFloat() / budgetBytes).coerceIn(0f, 1.5f)
        } else {
            0f
        }

    val overBudget: Boolean
        get() = !budgetUnlimited && controlledUsedBytes > budgetBytes
}

internal fun formatCacheBudgetStopLabel(totalMb: Int): String = when {
    CacheBudget.isUnlimited(totalMb) -> "无限制"
    totalMb >= 1024 -> {
        val gb = totalMb / 1024
        if (totalMb % 1024 == 0) "${gb} GB" else "${totalMb} MB"
    }
    else -> "${totalMb} MB"
}

internal fun defaultBudgetStops(): List<CacheBudgetStop> {
    val mbs = CacheBudget.DISCRETE_STOPS_MB + CacheBudget.UNLIMITED_MB
    return mbs.map { CacheBudgetStop(mb = it, label = formatCacheBudgetStopLabel(it)) }
}

/** 缓存控制：总额度 + 下载豁免 + 明细；分项份额仅作组件技术上限，不对用户展示。 */
class CacheCleanupViewModel(
    private val files: CacheFiles,
    private val localSettingManager: LocalSettingManager,
    private val clearReaderCache: suspend () -> Unit,
    private val clearDownloads: suspend (suspend () -> Unit) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(CacheControlState(budgetStops = defaultBudgetStops()))
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            localSettingManager.cacheBudgetMb.collect { budget ->
                _state.update {
                    it.copy(budgetMb = budget, budgetBytes = CacheBudget.totalBytes(budget))
                }
                rebuildItems()
            }
        }
        viewModelScope.launch {
            localSettingManager.downloadExemptFromCacheLimit.collect { exempt ->
                _state.update { it.copy(downloadExempt = exempt) }
                rebuildItems()
            }
        }
        refresh()
    }

    fun setBudgetMb(mb: Int) {
        localSettingManager.setCacheBudgetMb(mb)
    }

    fun setDownloadExempt(exempt: Boolean) {
        localSettingManager.setDownloadExemptFromCacheLimit(exempt)
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val items = files.scan()
                _state.update { it.copy(loading = false) }
                applyScanned(items)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(loading = false, result = error.message ?: "读取缓存失败") }
            }
        }
    }

    private fun rebuildItems() {
        viewModelScope.launch {
            runCatching { applyScanned(files.scan()) }
        }
    }

    private fun applyScanned(scanned: List<CacheSize>) {
        val current = _state.value
        val exempt = current.downloadExempt
        val byArea = scanned.associateBy { it.area }
        val ordered = listOf(
            CacheArea.COMMON,
            CacheArea.READER,
            CacheArea.DECODE,
            CacheArea.PDF,
            CacheArea.DOWNLOAD,
        )
        val namedSum = ordered.sumOf { byArea[it]?.sizeBytes ?: 0L }
        val downloadBytes = byArea[CacheArea.DOWNLOAD]?.sizeBytes ?: 0L
        val allBytes = byArea[CacheArea.ALL]?.sizeBytes?.takeIf { it > 0L } ?: namedSum
        val residual = (allBytes - namedSum).coerceAtLeast(0L)

        val pieRaw = buildList {
            ordered.forEach { area ->
                val size = byArea[area]?.sizeBytes ?: 0L
                if (size > 0L) add(area to size)
            }
            if (residual > 0L) add(CacheArea.ALL to residual)
        }
        val compositionTotal = if (allBytes > 0L) allBytes else namedSum
        val pieSlices = pieRaw
            .map { (area, size) ->
                CachePieSlice(
                    title = if (area == CacheArea.ALL) "其他" else area.title,
                    sizeBytes = size,
                    fraction = if (compositionTotal > 0L) size.toFloat() / compositionTotal else 0f,
                    isDownload = area == CacheArea.DOWNLOAD,
                    isResidual = area == CacheArea.ALL,
                )
            }
            .sortedWith(
                compareByDescending<CachePieSlice> { it.sizeBytes }.thenBy { it.title },
            )

        val controlled = if (exempt) {
            (allBytes - downloadBytes).coerceAtLeast(0L)
        } else {
            allBytes
        }

        _state.update {
            it.copy(
                controlledUsedBytes = controlled,
                pieSlices = pieSlices,
                compositionTotalBytes = compositionTotal,
                budgetStops = defaultBudgetStops(),
                loading = false,
            )
        }
    }

    /** 清理缓存：是否动下载由豁免开关决定。 */
    fun cleanCache() {
        val current = _state.value
        if (current.cleaning) return
        _state.update { it.copy(cleaning = true, result = null) }
        viewModelScope.launch {
            try {
                val before = current.controlledUsedBytes
                val exempt = current.downloadExempt
                suspend fun deleteFiles() {
                    clearReaderCache()
                    val areas = buildSet {
                        add(CacheArea.COMMON)
                        add(CacheArea.READER)
                        add(CacheArea.DECODE)
                        add(CacheArea.PDF)
                        if (!exempt) add(CacheArea.DOWNLOAD)
                    }
                    files.remove(areas)
                }
                if (!exempt) {
                    clearDownloads { deleteFiles() }
                } else {
                    deleteFiles()
                }
                applyScanned(files.scan())
                _state.update {
                    it.copy(result = "已清理约 ${formatBytes(before - it.controlledUsedBytes)}")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(result = error.message ?: "缓存清理失败，请重试") }
            } finally {
                _state.update { it.copy(cleaning = false) }
            }
        }
    }
}
