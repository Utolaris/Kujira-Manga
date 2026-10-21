package com.par9uet.jm.cache.molecule

import com.par9uet.jm.cache.CacheArea
import com.par9uet.jm.cache.atom.CacheFiles
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One deletion workflow shared by manual cleanup and the cold-start quota check. */
class CacheCleanupOperations(
    private val files: CacheFiles,
    private val clearCoverCache: suspend () -> Unit,
    private val clearReaderCache: suspend () -> Unit,
    private val clearDownloads: suspend (suspend () -> Unit) -> Unit,
) {
    private val mutex = Mutex()

    suspend fun clean(downloadExempt: Boolean) = mutex.withLock {
        suspend fun deleteFiles() {
            clearCoverCache()
            clearReaderCache()
            // Coil owns its journal, and the reader owns its active file leases.
            // Raw directory deletion is only used for stopped, non-exempt downloads.
            if (!downloadExempt) files.remove(setOf(CacheArea.DOWNLOAD))
        }
        if (downloadExempt) deleteFiles() else clearDownloads { deleteFiles() }
    }
}
