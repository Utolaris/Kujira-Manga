package com.par9uet.jm.favorites.data

import androidx.room.withTransaction
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.database.AppDatabase
import com.par9uet.jm.database.dao.FavoriteComicDao
import com.par9uet.jm.database.dao.FavoriteFolderDao
import com.par9uet.jm.database.dao.FavoriteFolderMembershipDao
import com.par9uet.jm.database.dao.FavoriteMetadataDao
import com.par9uet.jm.database.dao.FavoriteMetadataTermDao
import com.par9uet.jm.database.model.FavoriteFolderEntity
import com.par9uet.jm.database.model.FavoriteFolderMembershipEntity

/** Write-side local mutations for favorites membership and folder cache. */
internal class FavoriteLocalMutationStore(
    private val database: AppDatabase,
    private val comicDao: FavoriteComicDao,
    private val folderDao: FavoriteFolderDao,
    private val membershipDao: FavoriteFolderMembershipDao,
    private val metadataDao: FavoriteMetadataDao,
    private val termDao: FavoriteMetadataTermDao,
) : FavoriteLocalMutation {
    override suspend fun addFromComic(accountId: Int, comic: Comic, folderId: Int) {
        val now = System.currentTimeMillis()
        val item = comic.toRemoteItem()
        val metadata = FavoriteMetadataPayload(
            albumId = comic.id,
            title = comic.name,
            description = comic.description,
            authors = comic.authorList,
            tags = comic.tagList,
            roles = comic.roleList,
            works = comic.workList,
        )
        database.withTransaction {
            comicDao.upsert(item.toComicEntity(accountId, 0, now, null, keepFullMetadata = true).copy(
                metadataComplete = true,
                metadataUpdatedAt = now,
            ))
            metadataDao.upsert(metadata.toEntity(accountId, now))
            termDao.replaceTerms(accountId, comic.id, metadata.toTerms(accountId, item))
            // 新收藏置顶：云端同步后服务端把最新收藏放在列表首位（remoteOrder 从 0 起）。
            // 本地预览用 MIN(remoteOrder) - 1 抢占队首，与硬编码 0 不同——0 会与云端 0 号位
            // 并列，再被 albumId ASC 顶到第二位，直到下一次同步才归位。连续本地收藏依次
            // 递减，顺序仍然正确；下一次同步按服务端真实顺序整表重写归位。
            val allOrder = membershipDao.minRemoteOrder(accountId, FAVORITE_SCOPE_ALL) - 1
            val folderOrder = if (folderId == FAVORITE_SCOPE_ALL) {
                allOrder
            } else {
                membershipDao.minRemoteOrder(accountId, folderId) - 1
            }
            membershipDao.upsertAll(
                buildList {
                    add(FavoriteFolderMembershipEntity(accountId, FAVORITE_SCOPE_ALL, comic.id, allOrder, now))
                    if (folderId != FAVORITE_SCOPE_ALL) {
                        add(FavoriteFolderMembershipEntity(accountId, folderId, comic.id, folderOrder, now))
                    }
                }
            )
        }
    }

    override suspend fun remove(accountId: Int, albumIds: Collection<Int>) {
        if (albumIds.isEmpty()) return
        database.withTransaction {
            membershipDao.deleteForAlbums(accountId, albumIds.toList())
            comicDao.deleteByIds(accountId, albumIds.toList())
            metadataDao.deleteByIds(accountId, albumIds.toList())
            albumIds.forEach { termDao.deleteForAlbum(accountId, it) }
        }
    }

    override suspend fun moveToFolder(accountId: Int, albumId: Int, folderId: Int) {
        if (folderId == FAVORITE_SCOPE_ALL) return
        val now = System.currentTimeMillis()
        database.withTransaction {
            membershipDao.deleteNonDefaultForAlbum(accountId, albumId)
            membershipDao.upsertAll(
                listOf(
                    FavoriteFolderMembershipEntity(
                        accountId = accountId,
                        folderId = folderId,
                        albumId = albumId,
                        // Do not fake 'newest/order 0': append after MAX(remoteOrder) of the
                        // folder items we currently know until the next server sync confirms
                        // the real order. Computed inside the same transaction as the insert.
                        remoteOrder = nextTemporaryRemoteOrder(
                            membershipDao.maxRemoteOrder(accountId, folderId)
                        ),
                        lastSyncedAt = now,
                    )
                )
            )
        }
    }

    override suspend fun cacheFolder(accountId: Int, folderId: Int, name: String) {
        if (accountId <= 0 || folderId <= FAVORITE_SCOPE_ALL || name.isBlank()) return
        folderDao.upsertAll(
            listOf(FavoriteFolderEntity(accountId, folderId, name, System.currentTimeMillis()))
        )
    }

    override suspend fun renameFolder(accountId: Int, folderId: Int, name: String) {
        if (accountId <= 0 || folderId <= FAVORITE_SCOPE_ALL || name.isBlank()) return
        cacheFolder(accountId, folderId, name)
    }

    override suspend fun removeFolder(accountId: Int, folderId: Int) {
        if (accountId <= 0 || folderId <= FAVORITE_SCOPE_ALL) return
        database.withTransaction {
            membershipDao.deleteForScope(accountId, folderId)
            folderDao.deleteByIds(accountId, listOf(folderId))
        }
    }
}
