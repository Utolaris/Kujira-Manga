package com.par9uet.jm.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import com.par9uet.jm.ui.components.MotionButton as Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import com.par9uet.jm.ui.components.MotionIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import com.par9uet.jm.ui.components.MotionOutlinedButton as OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.par9uet.jm.ui.components.MotionTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.par9uet.jm.data.models.TagFilterLogic
import com.par9uet.jm.favorites.model.FavoritesIntent
import com.par9uet.jm.favorites.model.FavoritesModal
import com.par9uet.jm.favorites.model.FavoritesFilterPreview
import com.par9uet.jm.favorites.presentation.FavoritesViewModel
import com.par9uet.jm.core.network.NetworkErrorKind
import com.par9uet.jm.ui.glass.GlassConfirmDialog
import com.par9uet.jm.ui.glass.GlassModal
import com.par9uet.jm.ui.navigation.LocalMainNavController

/** 移动收藏夹列表：固定行高，最多可见 5 行，其余靠滑动。 */
private val MoveFolderRowHeight = 52.dp
private const val MoveFolderVisibleRows = 5

/** 管理收藏夹列表：限制可见高度，夹多时滚动，避免居中弹窗被撑满全屏。 */
private val ManageFolderVisibleHeight = 360.dp

/** Renders every Favorites modal from the single ViewModel-owned modal state. */
@Composable
internal fun FavoritesModalHost(favoritesViewModel: FavoritesViewModel) {
    val navController = LocalMainNavController.current
    val favoritesState by favoritesViewModel.uiState.collectAsState()
    val collectComicFilter = favoritesState.filter
    val tagCountMap = favoritesState.tagCounts
    val authorCountMap = favoritesState.authorCounts
    val selectedFolderId = favoritesState.selectedFolderId
    val folders = remember(favoritesState.folders) {
        buildMap {
            put("0", favoritesState.folders["0"] ?: "全部")
            favoritesState.folders
                .filterKeys { it != "0" }
                .forEach { (id, name) -> put(id, name) }
        }
    }
    val collectEditState = favoritesState.selection
    var draftSelectedTags by remember { mutableStateOf<Set<String>>(emptySet()) }
    var draftSelectedAuthors by remember { mutableStateOf<Set<String>>(emptySet()) }
    var draftTagLogic by remember { mutableStateOf(TagFilterLogic.AND) }
    var newFolderName by remember { mutableStateOf("") }
    var renameFolderName by remember { mutableStateOf("") }

    LaunchedEffect(favoritesState.modal) {
        if (favoritesState.modal is FavoritesModal.Filter) {
            draftSelectedTags = collectComicFilter.selectedTags
            draftSelectedAuthors = collectComicFilter.selectedAuthors
            draftTagLogic = collectComicFilter.tagLogic
        }
    }

    if (favoritesState.modal is FavoritesModal.Filter) {
        val preview = key(draftSelectedTags, draftSelectedAuthors, draftTagLogic) {
            val value by remember {
                favoritesViewModel.observeFilterPreview(draftSelectedTags, draftSelectedAuthors, draftTagLogic)
            }.collectAsState(initial = FavoritesFilterPreview(isLoading = true))
            value
        }
        FavoritesFilterSheet(
            tagCountMap = tagCountMap,
            authorCountMap = authorCountMap,
            selectedTags = draftSelectedTags,
            selectedAuthors = draftSelectedAuthors,
            tagLogic = draftTagLogic,
            preview = preview,
            onTagToggle = { tag ->
                draftSelectedTags = if (tag in draftSelectedTags) {
                    draftSelectedTags - tag
                } else {
                    draftSelectedTags + tag
                }
            },
            onAuthorToggle = { author ->
                draftSelectedAuthors = if (author in draftSelectedAuthors) {
                    draftSelectedAuthors - author
                } else {
                    draftSelectedAuthors + author
                }
            },
            onTagLogicChange = { draftTagLogic = it },
            onConfirm = {
                favoritesViewModel.onIntent(
                    FavoritesIntent.FilterApplied(
                        selectedTags = draftSelectedTags,
                        selectedAuthors = draftSelectedAuthors,
                        tagLogic = draftTagLogic,
                    )
                )
            },
            onClear = {
                draftSelectedTags = emptySet()
                draftSelectedAuthors = emptySet()
                draftTagLogic = TagFilterLogic.AND
                favoritesViewModel.onIntent(FavoritesIntent.FilterCleared)
            },
            onDismiss = {
                favoritesViewModel.onIntent(FavoritesIntent.FilterDismissed)
            },
        )
    }

    val activeModal = favoritesState.modal

    GlassConfirmDialog(
        visible = activeModal is FavoritesModal.LocalSync,
        title = "本地收藏模式",
        message = favoritesState.pendingComicCount?.let {
            "当前有 $it 部漫画的收藏更改尚未同步到云端。开始同步将退出本地收藏模式；同步失败时保留本地更改和模式，可稍后重试。"
        } ?: "暂时无法读取未同步的漫画数量。可以尝试同步并退出本地收藏模式；同步失败时保留本地更改和模式。",
        confirmText = "同步并退出",
        onConfirm = { favoritesViewModel.onIntent(FavoritesIntent.LocalSyncConfirmed) },
        onDismiss = { favoritesViewModel.onIntent(FavoritesIntent.ModalDismissed) },
        surfaceId = "favorites-local-sync-confirm",
    )

    val needsLogin = favoritesState.sync.errorKind == NetworkErrorKind.Authentication
    GlassConfirmDialog(
        visible = favoritesState.syncErrorVisible && activeModal == null,
        title = "收藏同步失败",
        message = favoritesState.sync.errorMessage.orEmpty(),
        confirmText = if (needsLogin) "重新登录" else "重试",
        dismissText = "关闭",
        onConfirm = {
            if (needsLogin) {
                favoritesViewModel.onIntent(FavoritesIntent.SyncErrorDismissed)
                navController.navigate("login?reauthenticate=true")
            } else {
                favoritesViewModel.onIntent(FavoritesIntent.SyncRetried)
            }
        },
        onDismiss = { favoritesViewModel.onIntent(FavoritesIntent.SyncErrorDismissed) },
        surfaceId = "favorites-sync-error",
    )

    GlassModal(
        visible = activeModal is FavoritesModal.CreateFolder,
        onDismissRequest = {
            newFolderName = ""
            favoritesViewModel.onIntent(FavoritesIntent.FolderActionDismissed)
        },
        surfaceId = "favorites-create-folder-glass-modal",
    ) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text("新建收藏夹", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = newFolderName,
                onValueChange = { newFolderName = it },
                singleLine = true,
                label = { Text("文件夹名称") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    newFolderName = ""
                    favoritesViewModel.onIntent(FavoritesIntent.FolderActionDismissed)
                }) { Text("取消") }
                TextButton(onClick = {
                    if (newFolderName.isNotBlank()) {
                        favoritesViewModel.onIntent(
                            FavoritesIntent.CreateFolderSubmitted(newFolderName)
                        )
                        newFolderName = ""
                    }
                }) { Text("创建") }
            }
        }
    }

    // 管理弹窗内的「选中」只用于改名/删除，不切换当前收藏夹。
    var manageSelectedFolderId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activeModal, folders) {
        if (activeModal !is FavoritesModal.FolderManagement) {
            manageSelectedFolderId = null
        } else if (manageSelectedFolderId != null && manageSelectedFolderId !in folders) {
            manageSelectedFolderId = null
        }
    }
    val manageSelectedNumericId = manageSelectedFolderId?.toIntOrNull()
    val canManageSelected = manageSelectedNumericId != null && manageSelectedNumericId != 0

    GlassModal(
        visible = activeModal is FavoritesModal.FolderManagement,
        onDismissRequest = {
            favoritesViewModel.onIntent(FavoritesIntent.FolderManagementDismissed)
        },
        surfaceId = "favorites-manage-folder-glass-modal",
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Text(
                "管理收藏夹",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(16.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = ManageFolderVisibleHeight),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(folders.entries.toList(), key = { it.key }) { (folderId, folderName) ->
                    val numericFolderId = folderId.toIntOrNull()
                    val isManagedSelection = manageSelectedFolderId == folderId
                    val isActiveFolder = selectedFolderId == numericFolderId
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (isManagedSelection) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            Color.Transparent
                        },
                        onClick = { manageSelectedFolderId = folderId },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = if (folderId == "0") {
                                    Icons.Rounded.Bookmarks
                                } else {
                                    Icons.Rounded.Folder
                                },
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                folderName,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (isActiveFolder) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "当前收藏夹",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    newFolderName = ""
                    favoritesViewModel.onIntent(FavoritesIntent.CreateFolderOpened)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("新建收藏夹")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val folderId = manageSelectedNumericId ?: return@OutlinedButton
                        val folderName = folders[manageSelectedFolderId].orEmpty()
                        favoritesViewModel.onIntent(
                            FavoritesIntent.RenameFolderOpened(
                                folderId = folderId,
                                folderName = folderName,
                            )
                        )
                    },
                    enabled = canManageSelected,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("改名")
                }
                OutlinedButton(
                    onClick = {
                        val folderId = manageSelectedNumericId ?: return@OutlinedButton
                        val folderName = folders[manageSelectedFolderId].orEmpty()
                        favoritesViewModel.onIntent(
                            FavoritesIntent.DeleteFolderOpened(
                                folderId = folderId,
                                folderName = folderName,
                            )
                        )
                    },
                    enabled = canManageSelected,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("删除")
                }
            }
        }
    }

    val renameModal = activeModal as? FavoritesModal.RenameFolder
    LaunchedEffect(renameModal) {
        renameFolderName = renameModal?.folderName.orEmpty()
    }
    GlassModal(
        visible = renameModal != null,
        onDismissRequest = {
            favoritesViewModel.onIntent(FavoritesIntent.FolderActionDismissed)
        },
        surfaceId = "favorites-rename-folder-glass-modal",
    ) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text("重命名收藏夹", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = renameFolderName,
                onValueChange = { renameFolderName = it },
                singleLine = true,
                label = { Text("新名称") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    favoritesViewModel.onIntent(FavoritesIntent.FolderActionDismissed)
                }) { Text("取消") }
                TextButton(onClick = {
                    renameModal?.let { modal ->
                        if (renameFolderName.isNotBlank()) {
                            favoritesViewModel.onIntent(
                                FavoritesIntent.RenameFolderSubmitted(
                                    folderId = modal.folderId,
                                    name = renameFolderName,
                                )
                            )
                        }
                    }
                }) { Text("确定") }
            }
        }
    }

    val deleteModal = activeModal as? FavoritesModal.DeleteFolder
    GlassConfirmDialog(
        visible = deleteModal != null,
        title = "删除收藏夹",
        message = "确定删除「${deleteModal?.folderName.orEmpty()}」吗？\n注意：删除收藏夹不会删除其中的漫画，漫画会移至「全部」。",
        confirmText = "删除",
        destructive = true,
        surfaceId = "favorites-delete-folder-glass-confirm",
        onConfirm = { favoritesViewModel.onIntent(FavoritesIntent.DeleteFolderConfirmed) },
        onDismiss = { favoritesViewModel.onIntent(FavoritesIntent.FolderActionDismissed) },
    )

    GlassConfirmDialog(
        visible = activeModal is FavoritesModal.Uncollect,
        title = "取消收藏",
        message = "确定取消收藏 ${collectEditState.selectedComicIds.size} 部漫画吗？",
        confirmText = "取消收藏",
        surfaceId = "favorites-uncollect-glass-confirm",
        onConfirm = { favoritesViewModel.onIntent(FavoritesIntent.UncollectConfirmed) },
        onDismiss = { favoritesViewModel.onIntent(FavoritesIntent.ModalDismissed) },
    )

    if (activeModal is FavoritesModal.Move) {
        MoveFolderDialog(
            folders = folders,
            currentFolderId = selectedFolderId,
            onMove = { folderId ->
                favoritesViewModel.onIntent(
                    FavoritesIntent.MoveConfirmed(folderId.toIntOrNull() ?: 0)
                )
            },
            onDismiss = { favoritesViewModel.onIntent(FavoritesIntent.ModalDismissed) },
        )
    }
}

/**
 * 移动到收藏夹。
 *
 * 与新建/重命名等同级弹窗同一套版面：居中、宽度交给 [GlassModal] 默认值
 * （手机 480dp 收口，平板自动放宽到窗口的 3/4），取消/移动放右下角两个小按钮。
 *
 * 列表固定行高、最多可见 5 行：收藏夹再多也靠手指滑动查看，不会把弹窗撑高。
 */
@Composable
private fun MoveFolderDialog(
    folders: Map<String, String>,
    currentFolderId: Int?,
    onMove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedFolderId by remember { mutableStateOf<String?>(null) }
    val availableFolders = remember(folders, currentFolderId) {
        folders.filterKeys { it != "0" && it.toIntOrNull() != currentFolderId }
    }

    GlassModal(
        visible = true,
        onDismissRequest = onDismiss,
        surfaceId = "favorites-move-folder-glass-modal",
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Text(
                text = "移动到收藏夹",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (availableFolders.isEmpty()) {
                Text(
                    text = "暂无其他收藏夹，请先创建",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = MoveFolderRowHeight * MoveFolderVisibleRows),
                ) {
                    items(availableFolders.entries.toList(), key = { it.key }) { (folderId, folderName) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(MoveFolderRowHeight)
                                .clickable { selectedFolderId = folderId },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedFolderId == folderId,
                                onClick = { selectedFolderId = folderId },
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Rounded.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = folderName,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(
                    onClick = { selectedFolderId?.let(onMove) },
                    enabled = selectedFolderId != null,
                ) { Text("移动") }
            }
        }
    }
}
