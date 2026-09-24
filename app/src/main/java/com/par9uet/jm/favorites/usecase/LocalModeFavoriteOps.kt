package com.par9uet.jm.favorites.usecase

import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.favorites.data.FavoriteRemoteMutation
import com.par9uet.jm.favorites.data.FavoriteRemoteQuery
import com.par9uet.jm.favorites.model.FavoriteSession
import com.par9uet.jm.favorites.model.FavoriteSessionSnapshot
import com.par9uet.jm.storage.LocalFavoriteChangeManager
import kotlinx.coroutines.CancellationException

data class LocalModeFavoriteSyncReport(
    val collected: Int = 0,
    val uncollected: Int = 0,
    val skippedAlreadyRemote: Int = 0,
    val failed: Int = 0,
)

/** Replays only explicit local-mode changes against a complete remote favorite list. */
class SyncLocalModeFavoritesOnExit(
    private val remoteQuery: FavoriteRemoteQuery,
    private val remoteMutation: FavoriteRemoteMutation,
    private val changes: LocalFavoriteChangeManager,
    private val session: FavoriteSession,
) {
    suspend operator fun invoke(snapshot: FavoriteSessionSnapshot): NetWorkResult<LocalModeFavoriteSyncReport> {
        if (snapshot.accountId <= 0) return NetWorkResult.Error("需要登录后才能同步收藏")
        return try {
            session.withBoundRemoteSession(snapshot) {
                val pending = changes.snapshot(snapshot.accountId)
                    ?: return@withBoundRemoteSession NetWorkResult.Error("本地收藏记录暂时不可读，请重试")
                if (pending.isEmpty()) return@withBoundRemoteSession NetWorkResult.Success(LocalModeFavoriteSyncReport())

                val remoteIds = mutableSetOf<Int>()
                var page = 1
                while (true) {
                    val data = remoteQuery.getFavorites(folderId = 0, page = page)
                    data.items.mapTo(remoteIds) { it.albumId }
                    val more = if (data.totalPages > 0) page < data.totalPages else data.items.size >= 20
                    if (!more) break
                    page++
                }

                var collected = 0
                var uncollected = 0
                var skipped = 0
                var failed = 0
                for (change in pending) {
                    val alreadySettled = (change.albumId in remoteIds) == change.collect
                    val success = if (alreadySettled) {
                        skipped++
                        true
                    } else if (change.collect) {
                        when (remoteMutation.collectComic(change.albumId)) {
                            is NetWorkResult.Success -> { collected++; true }
                            is NetWorkResult.Error -> false
                        }
                    } else {
                        when (remoteMutation.uncollectComic(change.albumId)) {
                            is NetWorkResult.Success -> { uncollected++; true }
                            is NetWorkResult.Error -> false
                        }
                    }
                    if (!success || !changes.removeIfUnchanged(change)) failed++
                }
                NetWorkResult.Success(LocalModeFavoriteSyncReport(collected, uncollected, skipped, failed))
            } ?: NetWorkResult.Error("登录账号已变化，请重试")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            NetWorkResult.Error(error.message ?: "收藏补偿失败")
        }
    }
}
