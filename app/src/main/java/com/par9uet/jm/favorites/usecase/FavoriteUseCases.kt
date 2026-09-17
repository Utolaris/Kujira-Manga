package com.par9uet.jm.favorites.usecase

import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.favorites.data.FavoriteLocalMutation
import com.par9uet.jm.favorites.data.FavoriteRemoteMutation
import com.par9uet.jm.favorites.data.FavoriteDownloader
import com.par9uet.jm.favorites.model.FavoriteLocalQuery
import com.par9uet.jm.favorites.model.FavoriteSession
import com.par9uet.jm.favorites.model.FavoriteSessionSnapshot
import com.par9uet.jm.core.network.NetWorkResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

data class FavoritesBatchResult(
    val succeeded: Int,
    val failed: Int,
)

/** Canonical "collect this comic into favorites" operation, session-bound end to end. */
class CollectFavorite(
    private val remoteMutation: FavoriteRemoteMutation,
    private val localMutation: FavoriteLocalMutation,
    private val session: FavoriteSession,
) {
    suspend operator fun invoke(
        sessionSnapshot: FavoriteSessionSnapshot,
        comic: Comic,
    ): NetWorkResult<Unit> {
        return session.withBoundRemoteSession(sessionSnapshot) {
            when (val result = remoteMutation.collectComic(comic.id)) {
                // Preserve the real server/network failure verbatim. A stale-session failure is
                // synthesized only when the bound capability or guarded local commit is refused.
                is NetWorkResult.Error -> result
                is NetWorkResult.Success -> {
                    val committed = session.withCurrentSession(sessionSnapshot) {
                        localMutation.addFromComic(sessionSnapshot.accountId, comic)
                    }
                    if (committed == null) staleSessionError() else result
                }
            }
        } ?: staleSessionError()
    }
}

class UncollectFavorites(
    private val remoteMutation: FavoriteRemoteMutation,
    private val localMutation: FavoriteLocalMutation,
    private val session: FavoriteSession,
) {
    suspend operator fun invoke(
        sessionSnapshot: FavoriteSessionSnapshot,
        comicIds: Collection<Int>,
    ): FavoritesBatchResult {
        val distinctIds = comicIds.distinct()
        var succeeded = 0
        var failed = 0
        val batchStarted = session.withBoundRemoteSession(sessionSnapshot) {
            for (index in distinctIds.indices) {
                if (!session.isCurrent(sessionSnapshot)) {
                    failed += distinctIds.size - index
                    break
                }
                val comicId = distinctIds[index]
                when (remoteMutation.uncollectComic(comicId)) {
                    is NetWorkResult.Error -> failed++
                    is NetWorkResult.Success -> {
                        val committed = session.withCurrentSession(sessionSnapshot) {
                            localMutation.remove(sessionSnapshot.accountId, listOf(comicId))
                        }
                        if (committed == null) {
                            failed += distinctIds.size - index
                            break
                        }
                        succeeded++
                    }
                }
            }
            true
        }
        if (batchStarted != true) {
            // The whole batch was refused before any remote call: nothing was processed.
            failed += distinctIds.size
        }
        return FavoritesBatchResult(succeeded = succeeded, failed = failed)
    }
}

class MoveFavorites(
    private val remoteMutation: FavoriteRemoteMutation,
    private val localMutation: FavoriteLocalMutation,
    private val session: FavoriteSession,
) {
    suspend operator fun invoke(
        sessionSnapshot: FavoriteSessionSnapshot,
        comicIds: Collection<Int>,
        folderId: Int,
    ): FavoritesBatchResult {
        val distinctIds = comicIds.distinct()
        var succeeded = 0
        var failed = 0
        val batchStarted = session.withBoundRemoteSession(sessionSnapshot) {
            for (index in distinctIds.indices) {
                if (!session.isCurrent(sessionSnapshot)) {
                    failed += distinctIds.size - index
                    break
                }
                val comicId = distinctIds[index]
                when (remoteMutation.moveComicToFolder(comicId, folderId)) {
                    is NetWorkResult.Error -> failed++
                    is NetWorkResult.Success -> {
                        val committed = session.withCurrentSession(sessionSnapshot) {
                            localMutation.moveToFolder(sessionSnapshot.accountId, comicId, folderId)
                        }
                        if (committed == null) {
                            failed += distinctIds.size - index
                            break
                        }
                        succeeded++
                    }
                }
            }
            true
        }
        if (batchStarted != true) {
            // The whole batch was refused before any remote call: nothing was processed.
            failed += distinctIds.size
        }
        return FavoritesBatchResult(succeeded = succeeded, failed = failed)
    }
}

class CreateFavoriteFolder(
    private val remoteMutation: FavoriteRemoteMutation,
    private val session: FavoriteSession,
) {
    suspend operator fun invoke(
        sessionSnapshot: FavoriteSessionSnapshot,
        name: String,
    ): NetWorkResult<Unit> {
        return session.withBoundRemoteSession(sessionSnapshot) {
            when (val result = remoteMutation.createFolder(name)) {
                is NetWorkResult.Error -> result
                is NetWorkResult.Success -> {
                    val committed = session.withCurrentSession(sessionSnapshot) {}
                    if (committed == null) staleSessionError() else result
                }
            }
        } ?: staleSessionError()
    }
}

class DeleteFavoriteFolder(
    private val remoteMutation: FavoriteRemoteMutation,
    private val localMutation: FavoriteLocalMutation,
    private val session: FavoriteSession,
) {
    suspend operator fun invoke(
        sessionSnapshot: FavoriteSessionSnapshot,
        folderId: Int,
    ): NetWorkResult<Unit> {
        return session.withBoundRemoteSession(sessionSnapshot) {
            when (val result = remoteMutation.deleteFolder(folderId)) {
                is NetWorkResult.Error -> result
                is NetWorkResult.Success -> {
                    val committed = session.withCurrentSession(sessionSnapshot) {
                        localMutation.removeFolder(sessionSnapshot.accountId, folderId)
                    }
                    if (committed == null) staleSessionError() else result
                }
            }
        } ?: staleSessionError()
    }
}

class RenameFavoriteFolder(
    private val remoteMutation: FavoriteRemoteMutation,
    private val localMutation: FavoriteLocalMutation,
    private val session: FavoriteSession,
) {
    suspend operator fun invoke(
        sessionSnapshot: FavoriteSessionSnapshot,
        folderId: Int,
        name: String,
    ): NetWorkResult<Unit> {
        return session.withBoundRemoteSession(sessionSnapshot) {
            when (val result = remoteMutation.renameFolder(folderId, name)) {
                is NetWorkResult.Error -> result
                is NetWorkResult.Success -> {
                    val committed = session.withCurrentSession(sessionSnapshot) {
                        localMutation.renameFolder(sessionSnapshot.accountId, folderId, name)
                    }
                    if (committed == null) staleSessionError() else result
                }
            }
        } ?: staleSessionError()
    }
}

class DownloadSelectedFavorites(
    private val localQuery: FavoriteLocalQuery,
    private val downloader: FavoriteDownloader,
) {
    suspend operator fun invoke(accountId: Int, comicIds: Collection<Int>) {
        downloader.downloadComics(localQuery.getComics(accountId, comicIds))
    }
}

/**
 * 「这本收藏了吗」的唯一判据：只看本地收藏快照，不看云端 `is_favorite`。
 *
 * 云端标志在多端互踢、换设备、同步落后时都会与本地不一致，照它显示就会出现
 * 「明明已收藏，进详情页却显示未收藏」。未登录（accountId <= 0）视为未收藏，
 * 账号切换时会重绑到新账号的查询，不会把 A 的状态漏给 B。
 */
class ObserveLocalFavorite(
    private val localQuery: FavoriteLocalQuery,
    private val session: FavoriteSession,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(albumId: Int): Flow<Boolean> =
        session.accountIdFlow
            .distinctUntilChanged()
            .flatMapLatest { accountId ->
                if (accountId <= 0) {
                    flowOf(false)
                } else {
                    localQuery.observeIsFavorite(accountId, albumId)
                }
            }
            .distinctUntilChanged()
}

private fun staleSessionError(): NetWorkResult.Error =
    NetWorkResult.Error("登录状态已变化，请重试")
