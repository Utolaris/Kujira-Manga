package com.par9uet.jm.favorites.model

import com.par9uet.jm.data.models.TagFilterLogic
import com.par9uet.jm.core.network.NetworkErrorKind

data class FavoritesFilter(
    val searchText: String = "",
    val selectedTags: Set<String> = emptySet(),
    val selectedAuthors: Set<String> = emptySet(),
    val tagLogic: TagFilterLogic = TagFilterLogic.AND,
)

data class FavoritesSelectionState(
    val editing: Boolean = false,
    val selectedComicIds: Set<Int> = emptySet(),
)

/**
 * 收藏夹不再自己保存滚动位置：位置统一由全局 `ListPositionMemory` 按
 * 「文件夹 + 筛选条件」的 key 记录，换筛选即换 key、自然回到顶部。
 */
data class FavoriteSyncUiState(
    val isSyncing: Boolean = false,
    val isForceRefresh: Boolean = false,
    /**
     * 本次同步是账号的**首次全量缓存构建**（登录后从未完成过一次全量同步）。
     * 收藏页据此显示「初次构建收藏夹缓存」的等待动画；普通手动/自动同步不置位。
     */
    val isInitialBuild: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val phase: String = "",
    val errorMessage: String? = null,
    val errorKind: NetworkErrorKind? = null,
)

sealed interface FavoritesModal {
    data object Filter : FavoritesModal
    data object FolderManagement : FavoritesModal
    data object Move : FavoritesModal
    data object Uncollect : FavoritesModal
    data object CreateFolder : FavoritesModal
    data class RenameFolder(val folderId: Int, val folderName: String) : FavoritesModal
    data class DeleteFolder(val folderId: Int, val folderName: String) : FavoritesModal
}

data class FavoritesUiState(
    val selectedFolderId: Int = 0,
    val folders: Map<String, String> = emptyMap(),
    val filter: FavoritesFilter = FavoritesFilter(),
    val selection: FavoritesSelectionState = FavoritesSelectionState(),
    val searchActive: Boolean = false,
    val modal: FavoritesModal? = null,
    val tagCounts: Map<String, Int> = emptyMap(),
    val authorCounts: Map<String, Int> = emptyMap(),
    val sync: FavoriteSyncUiState = FavoriteSyncUiState(),
    val syncErrorVisible: Boolean = false,
)

sealed interface FavoritesIntent {
    data object Entered : FavoritesIntent
    data object Left : FavoritesIntent
    data class AccountStateChanged(val authenticated: Boolean) : FavoritesIntent
    data object ManualSync : FavoritesIntent
    data object ForceRefresh : FavoritesIntent
    data object SyncErrorDismissed : FavoritesIntent
    data object SyncRetried : FavoritesIntent

    data class FolderSelected(val folderId: Int) : FavoritesIntent
    data object SearchEntered : FavoritesIntent
    data object SearchExited : FavoritesIntent
    data class SearchChanged(val query: String) : FavoritesIntent
    data class FilterApplied(
        val selectedTags: Set<String>,
        val selectedAuthors: Set<String>,
        val tagLogic: TagFilterLogic,
    ) : FavoritesIntent
    data object FilterOpened : FavoritesIntent
    data object FilterCleared : FavoritesIntent
    data object FilterDismissed : FavoritesIntent

    data class ComicLongPressed(val comicId: Int) : FavoritesIntent
    data class ComicSelectionToggled(val comicId: Int) : FavoritesIntent
    data object SelectionCleared : FavoritesIntent
    data object DownloadSelected : FavoritesIntent
    data object MoveSelected : FavoritesIntent
    data object UncollectSelected : FavoritesIntent
    data class MoveConfirmed(val folderId: Int) : FavoritesIntent
    data object UncollectConfirmed : FavoritesIntent
    data object ModalDismissed : FavoritesIntent

    data object FolderManagementOpened : FavoritesIntent
    data object FolderManagementDismissed : FavoritesIntent
    data object CreateFolderOpened : FavoritesIntent
    data class RenameFolderOpened(val folderId: Int, val folderName: String) : FavoritesIntent
    data class DeleteFolderOpened(val folderId: Int, val folderName: String) : FavoritesIntent
    data object FolderActionDismissed : FavoritesIntent
    data class CreateFolderSubmitted(val name: String) : FavoritesIntent
    data class RenameFolderSubmitted(val folderId: Int, val name: String) : FavoritesIntent
    data object DeleteFolderConfirmed : FavoritesIntent
}
