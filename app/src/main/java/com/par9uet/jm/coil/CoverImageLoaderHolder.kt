package com.par9uet.jm.coil

import android.content.Context
import coil.ImageLoader
import com.par9uet.jm.cache.CacheBudget
import okhttp3.Dns
import com.par9uet.jm.storage.CoverCachePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.ConnectionPool

/**
 * 持有当前封面 ImageLoader。磁盘缓存上限跟随「缓存控制」总预算，
 * 变更后重建 loader 并 shutdown 旧实例。
 *
 * 注意：重建只换上限。Coil 的 DiskLruCache 在下一次写入（`completeEdit` 发现 size > maxSize）
 * 或日志重写时才真正裁剪，所以调低额度后 common 目录要到下次封面写入才收敛；
 * 阅读侧 reader_pages 由 `ReaderImagePipeline` 当场回收，不依赖下一次写入。
 */
class CoverImageLoaderHolder(
    private val context: Context,
    private val dns: Dns,
    private val preferences: CoverCachePreferences,
    private val connectionPool: ConnectionPool,
    private val httpCache: Cache?,
    scope: CoroutineScope,
    val foregroundWork: com.par9uet.jm.core.concurrent.ForegroundWork,
) {
    private fun coverMb(): Int = CacheBudget.coverDiskCacheMb(
        totalMb = preferences.cacheBudgetMb.value,
        downloadExempt = preferences.downloadExemptFromCacheLimit.value,
    )

    private val _loader = MutableStateFlow(
        createAsyncImageLoader(
            context = context,
            dns = dns,
            coverDiskCacheMb = coverMb(),
            connectionPool = connectionPool,
            httpCache = httpCache,
        )
    )
    val loader: StateFlow<ImageLoader> = _loader.asStateFlow()

    init {
        scope.launch {
            kotlinx.coroutines.flow.combine(
                preferences.cacheBudgetMb,
                preferences.downloadExemptFromCacheLimit,
            ) { budget, exempt -> CacheBudget.coverDiskCacheMb(budget, exempt) }
                .drop(1)
                .distinctUntilChanged()
                .collect { mb -> replaceDiskCacheSize(mb) }
        }
    }

    fun current(): ImageLoader = _loader.value

    /** Clear through Coil's journal protocol instead of deleting its live directory. */
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun clearDiskCache() {
        _loader.value.diskCache?.clear()
    }

    private fun replaceDiskCacheSize(mb: Int) {
        val previous = _loader.value
        _loader.value = createAsyncImageLoader(
            context = context,
            dns = dns,
            coverDiskCacheMb = mb,
            connectionPool = connectionPool,
            httpCache = httpCache,
        )
        runCatching { previous.shutdown() }
    }
}
