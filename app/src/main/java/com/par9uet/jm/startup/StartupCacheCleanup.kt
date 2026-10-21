package com.par9uet.jm.startup

import com.par9uet.jm.cache.CacheBudget
import com.par9uet.jm.cache.atom.CacheFiles
import com.par9uet.jm.cache.molecule.CacheCleanupOperations
import com.par9uet.jm.storage.LocalSettingManager
import com.par9uet.jm.utils.log
import java.util.concurrent.atomic.AtomicBoolean

/** Runs once per process, after the first frame. Foreground resume never repeats the scan. */
class StartupCacheCleanup(
    private val files: CacheFiles,
    private val settings: LocalSettingManager,
    private val cleanup: CacheCleanupOperations,
) {
    private val checked = AtomicBoolean(false)

    suspend fun checkOnce() {
        if (!checked.compareAndSet(false, true)) return
        if (settings.securityLoadBlocked.value || CacheBudget.isUnlimited(settings.cacheBudgetMb.value)) return
        val scanned = files.scan()
        // A scan can take time (especially SAF). Honor the latest persisted settings before deleting.
        if (settings.securityLoadBlocked.value) return
        val budget = settings.cacheBudgetMb.value
        if (CacheBudget.isUnlimited(budget)) return
        val exempt = settings.downloadExemptFromCacheLimit.value
        val used = CacheBudget.controlledUsedBytes(scanned, exempt)
        if (used > CacheBudget.totalBytes(budget)) {
            cleanup.clean(exempt)
            log("缓存控制", "冷启动缓存超额，已执行一次清理")
        }
    }
}
