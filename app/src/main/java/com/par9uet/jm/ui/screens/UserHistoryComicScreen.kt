package com.par9uet.jm.ui.screens

import com.par9uet.jm.ui.navigation.HierarchicalBackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import com.par9uet.jm.ui.components.MotionIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.par9uet.jm.ui.components.MotionTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.par9uet.jm.ui.components.BackIconButton
import com.par9uet.jm.ui.components.Comic
import com.par9uet.jm.ui.components.ComicSkeleton
import com.par9uet.jm.ui.components.LocalComicGridScrolling
import com.par9uet.jm.ui.components.CommonScaffold
import com.par9uet.jm.ui.components.adaptiveComicGridCells
import com.par9uet.jm.ui.components.comicGridMinCellSize
import com.par9uet.jm.ui.glass.AppGlassTopBar
import com.par9uet.jm.ui.glass.ChromeMode
import com.par9uet.jm.ui.glass.GlassConfirmDialog
import com.par9uet.jm.ui.glass.GlassTopBarModeTransition
import com.par9uet.jm.ui.models.ListPositionKeyHistory
import com.par9uet.jm.ui.interaction.rememberRestoringGridState
import com.par9uet.jm.ui.navigation.LocalMainNavController
import com.par9uet.jm.ui.viewModel.UserViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.compose.viewmodel.koinActivityViewModel

@Composable
private fun UserHistoryComicSkeleton(gridColumns: Int) {
    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        LazyVerticalGrid(
            modifier = Modifier.fillMaxSize(),
            // 与真实网格同源：用户设置了固定列数时骨架必须一致，自适应列数只在未设置时用。
            columns = adaptiveComicGridCells(gridColumns, minSize = comicGridMinCellSize()),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 12.dp,
                bottom = 10.dp,
            ),
        ) {
            items(18) {
                ComicSkeleton()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserHistoryComicScreen(
    userViewModel: UserViewModel = koinActivityViewModel(),
) {
    val historyState by userViewModel.historyState.collectAsState()
    val historyEditState by userViewModel.historyEditState.collectAsState()
    val miscSettings by userViewModel.misc.collectAsState()
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val isLocalMode by userViewModel.isLocalMode.collectAsState()
    val localHistory by userViewModel.localHistoryEntries.collectAsState()
    val selectedComics: List<com.par9uet.jm.data.models.Comic> = if (isLocalMode) {
        remember(localHistory, historyEditState.selectedComicIds) {
            localHistory.map { it.toComic() }.filter { it.id in historyEditState.selectedComicIds }
        }
    } else {
        remember(historyState.items, historyEditState.selectedComicIds) {
            historyState.items.filter { it.id in historyEditState.selectedComicIds }
        }
    }
    // Session switch clears the selection (and therefore editing); dismiss any open
    // confirm dialog so a stale A-session dialog cannot confirm under B.
    LaunchedEffect(historyEditState.editing) {
        if (!historyEditState.editing) showDeleteConfirmDialog = false
    }
    HierarchicalBackHandler(enabled = historyEditState.editing) {
        userViewModel.clearHistorySelection()
    }

    // 每次进入重新获取 API 返回的完整历史列表。
    LifecycleStartEffect(Unit) {
        userViewModel.refreshHistory()
        onStopOrDispose { }
    }

    CommonScaffold(
        title = "历史浏览",
        variableTopBar = { statusBarInset ->
            HistoryVariableTopBar(
                statusBarInset = statusBarInset,
                editing = historyEditState.editing,
                selectedCount = historyEditState.selectedComicIds.size,
                onExitSelection = userViewModel::clearHistorySelection,
                onDownload = { userViewModel.cacheHistoryComics(selectedComics) },
                onDelete = { showDeleteConfirmDialog = true },
            )
        },
        overlayContent = {
            HistoryDeleteGlassConfirm(
                visible = showDeleteConfirmDialog,
                selectedCount = historyEditState.selectedComicIds.size,
                onConfirm = {
                    userViewModel.deleteHistoryComics(selectedComics)
                    showDeleteConfirmDialog = false
                },
                onDismiss = { showDeleteConfirmDialog = false },
            )
        },
    ) { topContentPadding, bottomContentPadding ->
        if (isLocalMode) {
            val comics = localHistory.map { it.toComic() }
            if (comics.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = topContentPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "暂无本地历史",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    state = rememberRestoringGridState(
                        key = "$ListPositionKeyHistory|local",
                        itemCount = comics.size,
                    ),
                    modifier = Modifier.fillMaxSize(),
                    columns = adaptiveComicGridCells(
                        columns = miscSettings.gridColumns.history,
                        minSize = comicGridMinCellSize(),
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = topContentPadding + 12.dp,
                        bottom = bottomContentPadding,
                    ),
                ) {
                    items(
                        count = comics.size,
                        key = { comics[it].id },
                    ) { index ->
                        val comic = comics[index]
                        Comic(
                            comic = comic,
                            editing = historyEditState.editing,
                            selected = comic.id in historyEditState.selectedComicIds,
                            onLongClick = {
                                if (historyEditState.editing) {
                                    userViewModel.toggleHistorySelected(comic.id)
                                } else {
                                    userViewModel.enterHistoryEdit(comic.id)
                                }
                            },
                            onToggleSelected = {
                                userViewModel.toggleHistorySelected(comic.id)
                            },
                        )
                    }
                }
            }
        } else if (historyState.loading && historyState.items.isEmpty()) {
            Box(modifier = Modifier.padding(top = topContentPadding)) {
                UserHistoryComicSkeleton(gridColumns = miscSettings.gridColumns.history)
            }
        } else if (historyState.items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topContentPadding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = historyState.error ?: "暂无历史记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (historyState.error != null) {
                        TextButton(onClick = userViewModel::refreshHistory) { Text("重试") }
                    }
                }
            }
        } else {
            HistoryComicGrid(
                state = historyState,
                gridColumns = miscSettings.gridColumns.history,
                topContentPadding = topContentPadding,
                bottomContentPadding = bottomContentPadding,
                editing = historyEditState.editing,
                selectedComicIds = historyEditState.selectedComicIds,
                onRetry = userViewModel::refreshHistory,
                onComicLongClick = { comic ->
                    if (historyEditState.editing) {
                        userViewModel.toggleHistorySelected(comic.id)
                    } else {
                        userViewModel.enterHistoryEdit(comic.id)
                    }
                },
                onComicToggleSelected = { userViewModel.toggleHistorySelected(it.id) },
            )
        }
    }

}

/** 远端历史直接展示 API 返回的列表，保留多选行为。 */
@Composable
private fun HistoryComicGrid(
    state: com.par9uet.jm.ui.viewModel.UserViewModel.HistoryState,
    gridColumns: Int,
    topContentPadding: Dp,
    bottomContentPadding: Dp,
    editing: Boolean,
    selectedComicIds: Set<Int>,
    onRetry: () -> Unit,
    onComicLongClick: (com.par9uet.jm.data.models.Comic) -> Unit,
    onComicToggleSelected: (com.par9uet.jm.data.models.Comic) -> Unit,
) {
    val gridState = rememberRestoringGridState(
        key = "$ListPositionKeyHistory|remote",
        itemCount = state.items.size,
    )
    var isGridScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { isGridScrolling = it }
    }
    CompositionLocalProvider(LocalComicGridScrolling provides isGridScrolling) {
        LazyVerticalGrid(
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            columns = adaptiveComicGridCells(gridColumns, minSize = comicGridMinCellSize()),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = topContentPadding + 12.dp,
                bottom = bottomContentPadding + 12.dp,
            ),
        ) {
            if (state.error != null) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.error, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = onRetry) { Text("重试") }
                    }
                }
            }
            items(state.items, key = { it.id }) { comic ->
                Comic(
                    comic = comic,
                    editing = editing,
                    selected = comic.id in selectedComicIds,
                    onLongClick = { onComicLongClick(comic) },
                    onToggleSelected = { onComicToggleSelected(comic) },
                )
            }
        }
    }
}

/** Reference implementation for the shared glass confirmation design. */
@Composable
private fun HistoryDeleteGlassConfirm(
    visible: Boolean,
    selectedCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    GlassConfirmDialog(
        visible = visible,
        title = "删除历史记录",
        message = "确定要删除选中的 $selectedCount 条历史记录吗？",
        confirmText = "删除",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = true,
        surfaceId = "history-delete-glass-confirm",
    )
}

@Composable
private fun HistoryVariableTopBar(
    statusBarInset: Dp,
    editing: Boolean,
    selectedCount: Int,
    onExitSelection: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val mainNavController = LocalMainNavController.current
    val mode = if (editing) ChromeMode.SELECTION else ChromeMode.NORMAL
    GlassTopBarModeTransition(
        targetState = mode,
        statusBarInset = statusBarInset,
        modifier = Modifier.fillMaxWidth(),
    ) { targetMode, surfaceAlpha ->
        when (targetMode) {
            ChromeMode.NORMAL -> AppGlassTopBar(
                surfaceId = "history-top-bar-normal",
                statusBarInset = statusBarInset,
                surfaceAlpha = surfaceAlpha,
                navigationIcon = {
                    BackIconButton()
                },
                title = {
                    Text(
                        text = "历史浏览",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                },
            )

            ChromeMode.SELECTION -> AppGlassTopBar(
                surfaceId = "history-top-bar-selection",
                statusBarInset = statusBarInset,
                surfaceAlpha = surfaceAlpha,
                navigationIcon = {
                    IconButton(onClick = onExitSelection) {
                        Icon(Icons.Rounded.Close, contentDescription = "退出编辑")
                    }
                },
                title = {
                    Text(
                        text = "已选择 $selectedCount 项",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(onClick = onDownload) {
                        Icon(Icons.Rounded.Download, contentDescription = "缓存")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        }
    }
}
