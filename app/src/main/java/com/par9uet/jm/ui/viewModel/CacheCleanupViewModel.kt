package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.par9uet.jm.cache.CacheArea
import com.par9uet.jm.cache.CacheBudget
import com.par9uet.jm.cache.atom.CacheFiles
import com.par9uet.jm.cache.CacheSize
import com.par9uet.jm.core.ToastManager
import com.par9uet.jm.storage.LocalSettingManager
import com.par9uet.jm.utils.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

/**
 * 调低额度后需要用户拍板的清理询问。数字取自最后一次扫描，弹窗直接展示。
 */
data class CacheCleanPrompt(
    val usedBytes: Long,
    val budgetBytes: Long,
) {
    val overflowBytes: Long get() = (usedBytes - budgetBytes).coerceAtLeast(0L)
}

data class CacheControlState(
    val loading: Boolean = true,
    val cleaning: Boolean = false,
    val budgetMb: Int = CacheBudget.DEFAULT_TOTAL_MB,
    val downloadExempt: Boolean = true,
    val controlledUsedBytes: Long = 0L,
    val budgetBytes: Long = CacheBudget.totalBytes(CacheBudget.DEFAULT_TOTAL_MB),
    val pieSlices: List<CachePieSlice> = emptyList(),
    val compositionTotalBytes: Long = 0L,
    val budgetStops: List<CacheBudgetStop> = defaultBudgetStops(),
    val cleanPrompt: CacheCleanPrompt? = null,
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
    private val toastManager: ToastManager,
) : ViewModel() {
    private val _state = MutableStateFlow(CacheControlState(budgetStops = defaultBudgetStops()))
    val state = _state.asStateFlow()

    /** 额度变更后的重扫任务；连续拖档位只保留最后一次。 */
    private var budgetRescanJob: Job? = null

    init {
        viewModelScope.launch {
            localSettingManager.cacheBudgetMb.collect { budget ->
                _state.update {
                    it.copy(budgetMb = budget, budgetBytes = CacheBudget.totalBytes(budget))
                }
                scheduleBudgetRescan()
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

    /**
     * 用户调档。
     *
     * 额度先落盘——受控组件的自裁剪全都订阅这条设置流，晚一步它们就还挂在旧上限上。
     * 但落盘不等于收尾：组件只会把**自己那一份**压回新份额（Coil 还要等下一次写入才真裁），
     * common / decode / pdf 与残留目录没人替用户删。所以调低到当前受控占用之下时，
     * 再挂一个 [CacheCleanPrompt] 把删除与否交回用户；其余情况（调高、调平、无限制）
     * 一律清空，顺带把上一轮遗留的询问收掉。
     */
    fun setBudgetMb(mb: Int) {
        val previousMb = localSettingManager.cacheBudgetMb.value
        localSettingManager.setCacheBudgetMb(mb)
        // 读回实际生效值而不是入参：写盘失败时 LocalSettingManager 不会 publish，额度没变就不该弹窗。
        val newMb = localSettingManager.cacheBudgetMb.value
        _state.update {
            it.copy(cleanPrompt = budgetCleanPrompt(previousMb, newMb, it.controlledUsedBytes))
        }
    }

    /**
     * 只有「把额度调到当前占用之下」才需要询问：额度调低到 `newMb`，受控已用仍超过它，
     * 用户要么现在删，要么继续超着跑。占用取最后一次扫描值——弹窗要展示的正是界面上那个数字。
     */
    private fun budgetCleanPrompt(
        previousMb: Int,
        newMb: Int,
        usedBytes: Long,
    ): CacheCleanPrompt? {
        if (CacheBudget.isUnlimited(newMb)) return null
        val lowered = CacheBudget.isUnlimited(previousMb) || newMb < previousMb
        if (!lowered) return null
        val budgetBytes = CacheBudget.totalBytes(newMb)
        return if (usedBytes > budgetBytes) CacheCleanPrompt(usedBytes, budgetBytes) else null
    }

    /** 确认询问：走与手动「清理缓存」完全相同的删除路径，豁免语义一致。 */
    fun confirmCleanPrompt() {
        if (_state.value.cleanPrompt == null) return
        _state.update { it.copy(cleanPrompt = null) }
        cleanCache()
    }

    /** 放弃询问：额度照新值生效，清理交给用户自己挑时机。 */
    fun dismissCleanPrompt() {
        _state.update { it.copy(cleanPrompt = null) }
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
                toastManager.showAsync(error.message ?: "读取缓存失败")
                _state.update { it.copy(loading = false) }
            }
        }
    }

    private fun rebuildItems() {
        viewModelScope.launch { scanIntoState() }
    }

    /**
     * 额度变更后延迟重扫。
     *
     * 调低额度会触发各受控组件按新份额自裁剪（阅读磁盘缓存由 ReaderImagePipeline 立即回收、
     * Coil 由 CoverImageLoaderHolder 重建 loader），这些都是异步的；立刻扫描只会读到旧占用，
     * 与 [CacheControlState.overBudget] 一起造成「调了额度却没反应」的错觉。
     * 顺带把连续拖档位合并成一次扫描。
     */
    private fun scheduleBudgetRescan() {
        budgetRescanJob?.cancel()
        budgetRescanJob = viewModelScope.launch {
            delay(BUDGET_RESCAN_SETTLE_MILLIS)
            scanIntoState()
        }
    }

    /**
     * 后台重扫的公共入口。
     *
     * 扫描会触碰用户自定义下载目录（SAF），授权可能已经失效。这类失败必须让用户看见：
     * 早先两处后台重扫都用 `runCatching` 静默吞掉，切一下豁免开关就能把「数不到」
     * 变成看起来正常的旧数字。自定义目录失效时把原因透出，不假装成功。
     */
    private suspend fun scanIntoState() {
        try {
            applyScanned(files.scan())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            toastManager.showAsync(error.message ?: "读取缓存失败")
        }
    }

    private fun applyScanned(scanned: List<CacheSize>) {
        val current = _state.value
        val exempt = current.downloadExempt
        val byArea = scanned.associateBy { it.area }
        val ordered = listOf(
            CacheArea.COMMON,
            CacheArea.READER,
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

    /** 清理缓存：是否动下载由豁免开关决定。结果走全局 toast，与下载完成等消息一致。 */
    fun cleanCache() {
        val current = _state.value
        if (current.cleaning) return
        _state.update { it.copy(cleaning = true) }
        viewModelScope.launch { runClean(current) }
    }

    private suspend fun runClean(snapshot: CacheControlState) {
        try {
            val before = snapshot.controlledUsedBytes
            val exempt = snapshot.downloadExempt
            suspend fun deleteFiles() {
                clearReaderCache()
                val areas = buildSet {
                    add(CacheArea.COMMON)
                    add(CacheArea.READER)
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
            val cleanedBytes = before - _state.value.controlledUsedBytes
            toastManager.showAsync("已清理约 ${formatBytes(cleanedBytes)}")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            toastManager.showAsync(error.message ?: "缓存清理失败，请重试")
        } finally {
            _state.update { it.copy(cleaning = false) }
        }
    }

    private companion object {
        /** 组件按新份额回收是异步的，留一拍再重扫。 */
        const val BUDGET_RESCAN_SETTLE_MILLIS = 400L
    }
}
