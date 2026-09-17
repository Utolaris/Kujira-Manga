package com.par9uet.jm.favorites.model

import androidx.paging.PagingSource
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.TagFilterLogic
import kotlinx.coroutines.flow.Flow

/** L4 query capabilities for the Room-backed local Favorites snapshot. */
interface FavoriteLocalQuery {
    fun pagingSource(
        accountId: Int,
        blockedTagList: List<String>,
        searchText: String,
        selectedTags: Set<String>,
        selectedAuthors: Set<String>,
        folderId: Int,
        tagLogic: TagFilterLogic,
    ): PagingSource<Int, Comic>

    fun observeFolders(accountId: Int): Flow<Map<String, String>>

    /**
     * 本地收藏快照里是否有这本漫画。
     *
     * 「已收藏」的唯一判据就是它：云端详情里的 `is_favorite` 在多端互踢、换设备或同步
     * 落后时都会与本地不一致，照它显示会出现「明明收藏了却显示未收藏」。
     */
    fun observeIsFavorite(accountId: Int, albumId: Int): Flow<Boolean>

    fun observeTagCounts(accountId: Int, folderId: Int): Flow<Map<String, Int>>

    fun observeAuthorCounts(accountId: Int, folderId: Int): Flow<Map<String, Int>>

    suspend fun getCachedFolders(accountId: Int): Map<String, String>

    suspend fun getComics(accountId: Int, albumIds: Collection<Int>): List<Comic>
}
