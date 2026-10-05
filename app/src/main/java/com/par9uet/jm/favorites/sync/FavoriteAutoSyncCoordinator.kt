package com.par9uet.jm.favorites.sync

import android.os.SystemClock

/** All folders share one cooldown after a successful automatic or explicit sync. */
const val FAVORITE_AUTO_SYNC_INTERVAL_MILLIS = 60_000L

sealed class FavoriteAutoRequestResult {
    data class StartNow(val folderId: Int) : FavoriteAutoRequestResult()
    data object Skipped : FavoriteAutoRequestResult()
}

/** Automatic requests during a sync or its completion cooldown are dropped, never queued. */
class FavoriteAutoSyncCoordinator(
    private val intervalMillis: Long = FAVORITE_AUTO_SYNC_INTERVAL_MILLIS,
    private val timeSource: () -> Long = SystemClock::elapsedRealtime,
    private val wallTimeSource: () -> Long = System::currentTimeMillis,
) {
    private var lastSuccessElapsedMillis: Long? = null

    fun request(
        folderId: Int,
        isSyncing: Boolean,
        lastSuccessfulSyncAt: Long? = null,
    ): FavoriteAutoRequestResult {
        if (isSyncing) return FavoriteAutoRequestResult.Skipped
        val elapsed = lastSuccessElapsedMillis?.let { timeSource() - it }
        val persistedAge = lastSuccessfulSyncAt?.let { wallTimeSource() - it }
        if (elapsed != null && elapsed in 0 until intervalMillis ||
            persistedAge != null && persistedAge in 0 until intervalMillis
        ) return FavoriteAutoRequestResult.Skipped
        return FavoriteAutoRequestResult.StartNow(folderId)
    }

    fun onSyncSucceeded() {
        lastSuccessElapsedMillis = timeSource()
    }

    /** The next account uses its own durable completion time. */
    fun reset() {
        lastSuccessElapsedMillis = null
    }
}
