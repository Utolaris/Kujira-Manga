package com.par9uet.jm.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import coil.compose.AsyncImage
import com.par9uet.jm.R
import com.par9uet.jm.ui.components.Comic
import com.par9uet.jm.ui.components.PullRefreshAndLoadMoreGrid
import com.par9uet.jm.ui.components.adaptiveComicGridCells
import com.par9uet.jm.ui.components.comicGridMinCellSize
import com.par9uet.jm.ui.interaction.pullDownToAction
import com.par9uet.jm.ui.interaction.PullDownActionState
import com.par9uet.jm.ui.interaction.favoritesListPositionKey
import com.par9uet.jm.ui.interaction.rememberPullDownActionState
import com.par9uet.jm.ui.interaction.rememberRestoringGridState
import com.par9uet.jm.favorites.model.FavoritesIntent
import com.par9uet.jm.favorites.presentation.FavoritesViewModel
import com.par9uet.jm.ui.viewModel.UserViewModel
import com.par9uet.jm.utils.log
import org.koin.compose.viewmodel.koinActivityViewModel

@Composable
internal fun UserCollectComicScreen(
    favoritesViewModel: FavoritesViewModel = koinActivityViewModel(),
    userViewModel: UserViewModel = koinActivityViewModel(),
    pullDownState: PullDownActionState = rememberPullDownActionState(),
    topContentPadding: Dp = 0.dp,
    bottomContentPadding: Dp = 0.dp,
) {
    val favoritesState by favoritesViewModel.uiState.collectAsState()
    val collectComicLazyPagingItems = favoritesViewModel.collectComicPager.collectAsLazyPagingItems()
    val selectedFolderId = favoritesState.selectedFolderId
    val collectEditState = favoritesState.selection
    val miscSettings by userViewModel.misc.collectAsState()
    var hasLoggedFirstLocalContent by remember { mutableStateOf(false) }
    val favoritesOpenedAt = remember { SystemClock.elapsedRealtime() }

    LaunchedEffect(collectComicLazyPagingItems.itemCount, selectedFolderId) {
        if (!hasLoggedFirstLocalContent && collectComicLazyPagingItems.itemCount > 0) {
            log(
                "FavoritesUI",
                "first local content visible folder=$selectedFolderId " +
                    "count=${collectComicLazyPagingItems.itemCount} " +
                    "duration=${SystemClock.elapsedRealtime() - favoritesOpenedAt}ms",
            )
            hasLoggedFirstLocalContent = true
        }
    }

    val favoriteItemCount = collectComicLazyPagingItems.itemCount
    val favoriteAppendComplete = collectComicLazyPagingItems.loadState.append.let {
        it is LoadState.NotLoading && it.endOfPaginationReached
    }
    // 滚动位置走全局位置记录，key = 文件夹 + 有效筛选条件：
    // 换文件夹 / 换关键词 / 换筛选条件 = 新 key = 从顶部开始；同一列表返回 = 命中记忆。
    // 首帧就用记忆值构造，不做可见的回跳。
    val gridState = rememberRestoringGridState(
        key = favoritesListPositionKey(
            folderId = favoritesState.selectedFolderId,
            searchText = favoritesState.filter.searchText,
            tags = favoritesState.filter.selectedTags,
            authors = favoritesState.filter.selectedAuthors,
            tagLogic = favoritesState.filter.tagLogic.name,
        ),
        itemCount = favoriteItemCount,
        appendComplete = favoriteAppendComplete,
    )
    var isGridScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { isGridScrolling = it }
    }
    val pullRevealPadding = 36.dp * pullDownState.progress
    val gridModifier = Modifier
        .fillMaxSize()
        .pullDownToAction(
            state = pullDownState,
            enabled = !collectEditState.editing && !favoritesState.searchActive,
            isAtTop = { !gridState.canScrollBackward },
            onTrigger = {
                favoritesViewModel.onIntent(FavoritesIntent.SearchEntered)
            },
        )

    val mainContent: @Composable () -> Unit = {
        PullRefreshAndLoadMoreGrid(
            modifier = gridModifier,
            lazyPagingItems = collectComicLazyPagingItems,
            key = { it.id },
            columns = adaptiveComicGridCells(
                columns = miscSettings.gridColumns.collect,
                minSize = comicGridMinCellSize(),
            ),
            gridState = gridState,
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Top),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(
                start = 14.dp,
                top = topContentPadding + pullRevealPadding + 14.dp,
                end = 14.dp,
                bottom = 14.dp + bottomContentPadding,
            ),
            enablePullRefresh = false,
        ) { comic ->
            Comic(
                comic = comic,
                editing = collectEditState.editing,
                selected = comic.id in collectEditState.selectedComicIds,
                isScrolling = isGridScrolling,
                onLongClick = {
                    favoritesViewModel.onIntent(FavoritesIntent.ComicLongPressed(comic.id))
                },
                onToggleSelected = {
                    favoritesViewModel.onIntent(FavoritesIntent.ComicSelectionToggled(comic.id))
                },
            )
        }
    }

    // 首次登录的全量缓存构建期间（列表尚未提交任何条目）显示等待动画，
    // 普通手动/自动同步不打扰列表。构建完成或已有内容时照常显示网格。
    val showInitialCacheLoader =
        favoritesState.sync.isSyncing &&
            favoritesState.sync.isInitialBuild &&
            collectComicLazyPagingItems.itemCount == 0

    Box(modifier = Modifier.fillMaxSize()) {
        if (showInitialCacheLoader) {
            InitialFavoritesCacheLoader(
                modifier = Modifier.fillMaxSize(),
                topContentPadding = topContentPadding,
            )
        } else {
            mainContent()
        }
    }
}

/** 首次构建收藏夹缓存的等待动画：GIF 居中，下方固定提示文案。 */
@Composable
private fun InitialFavoritesCacheLoader(
    modifier: Modifier = Modifier,
    topContentPadding: Dp = 0.dp,
) {
    Column(
        modifier = modifier.padding(top = topContentPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AsyncImage(
            model = R.drawable.reimu_loading,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            // 48x48 像素风源图放大一倍；最近邻插值保持像素边缘干净。
            filterQuality = FilterQuality.None,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "初次构建收藏夹缓存需要较长时间，请稍后。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
