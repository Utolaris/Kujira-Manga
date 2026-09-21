package com.par9uet.jm.coil

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import coil.ImageLoader
import com.par9uet.jm.cache.CacheBudget
import com.par9uet.jm.network.DohManager
import com.par9uet.jm.storage.LocalSettingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.ConnectionPool
import org.koin.compose.getKoin

/**
 * 持有当前封面 ImageLoader。磁盘缓存上限跟随「缓存控制」总预算，
 * 变更后重建 loader 并 shutdown 旧实例。
 */
class CoverImageLoaderHolder(
    private val context: Context,
    private val dohManager: DohManager,
    private val localSettingManager: LocalSettingManager,
    private val connectionPool: ConnectionPool,
    private val httpCache: Cache?,
    scope: CoroutineScope,
) {
    private fun coverMb(): Int = CacheBudget.coverDiskCacheMb(
        totalMb = localSettingManager.cacheBudgetMb.value,
        downloadExempt = localSettingManager.downloadExemptFromCacheLimit.value,
    )

    private val _loader = MutableStateFlow(
        createAsyncImageLoader(
            context = context,
            dohManager = dohManager,
            coverDiskCacheMb = coverMb(),
            connectionPool = connectionPool,
            httpCache = httpCache,
        )
    )
    val loader: StateFlow<ImageLoader> = _loader.asStateFlow()

    init {
        scope.launch {
            kotlinx.coroutines.flow.combine(
                localSettingManager.cacheBudgetMb,
                localSettingManager.downloadExemptFromCacheLimit,
            ) { budget, exempt -> CacheBudget.coverDiskCacheMb(budget, exempt) }
                .drop(1)
                .distinctUntilChanged()
                .collect { mb -> replaceDiskCacheSize(mb) }
        }
    }

    fun current(): ImageLoader = _loader.value

    private fun replaceDiskCacheSize(mb: Int) {
        val previous = _loader.value
        _loader.value = createAsyncImageLoader(
            context = context,
            dohManager = dohManager,
            coverDiskCacheMb = mb,
            connectionPool = connectionPool,
            httpCache = httpCache,
        )
        runCatching { previous.shutdown() }
    }
}

/** Compose 侧取当前封面 ImageLoader（磁盘缓存设置变更后自动换实例）。 */
@Composable
fun currentCoverImageLoader(): ImageLoader {
    val holder: CoverImageLoaderHolder = getKoin().get()
    val loader by holder.loader.collectAsState()
    return loader
}
