package com.par9uet.jm.ui.screens

import android.net.Uri
import com.par9uet.jm.ui.navigation.HierarchicalBackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.par9uet.jm.data.models.ComicSearchOrderFilter
import com.par9uet.jm.ui.components.Comic
import com.par9uet.jm.ui.components.ComicSkeleton
import com.par9uet.jm.ui.components.CommonScaffold
import com.par9uet.jm.ui.components.PullRefreshAndLoadMoreGrid
import com.par9uet.jm.ui.components.adaptiveComicGridCells
import com.par9uet.jm.ui.glass.GlassAnchoredMenu
import com.par9uet.jm.ui.glass.GlassMenuAlignment
import com.par9uet.jm.ui.glass.GlassMenuItem
import com.par9uet.jm.ui.glass.glassMenuAnchor
import com.par9uet.jm.ui.glass.rememberGlassAnchoredMenuState
import com.par9uet.jm.ui.navigation.LocalMainNavController
import com.par9uet.jm.ui.viewModel.ComicDetailViewModel
import com.par9uet.jm.ui.viewModel.SearchViewModel
import com.par9uet.jm.contentfilter.serializeExcludedTags
import com.par9uet.jm.storage.LocalSettingManager
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinActivityViewModel

internal enum class SearchResultBackTarget {
    PREVIOUS_SCREEN,
    SEARCH_EDITOR,
}

internal fun searchResultBackTarget(previousRoute: String?): SearchResultBackTarget =
    if (previousRoute.isNullOrBlank()) {
        SearchResultBackTarget.SEARCH_EDITOR
    } else {
        SearchResultBackTarget.PREVIOUS_SCREEN
    }

@Composable
private fun ComicSearchResultSkeleton(
    gridColumns: Int,
    modifier: Modifier = Modifier,
) {
    // 骨架与结果列表共用同一套 GridCells，避免 3 列占位换成 N 列内容时的布局跳变。
    LazyVerticalGrid(
        modifier = modifier.fillMaxSize(),
        columns = adaptiveComicGridCells(gridColumns),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Top),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(10.dp),
        userScrollEnabled = false,
    ) {
        items(18) {
            ComicSkeleton()
        }
    }
}

/** Paging retains old items during a failed refresh; only successful refreshes show results. */
@Composable
internal fun SearchResultRefreshContent(
    refreshState: LoadState,
    itemCount: Int,
    searchGridColumns: Int = 0,
    topContentPadding: Dp,
    bottomContentPadding: Dp,
    onRetry: () -> Unit,
    content: @Composable () -> Unit,
) {
    when {
        // 已有缓存页时 Loading 只发生在下拉刷新/重连：继续展示内容，避免从详情返回闪骨架。
        refreshState is LoadState.Loading && itemCount == 0 -> ComicSearchResultSkeleton(
            gridColumns = searchGridColumns,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topContentPadding),
        )
        refreshState is LoadState.Error -> Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topContentPadding, bottom = bottomContentPadding)
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = refreshState.error.message ?: "搜索失败，请重试",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRetry) { Text("重试") }
        }
        itemCount == 0 -> Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topContentPadding, bottom = bottomContentPadding)
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "没有找到相关漫画",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicSearchResultScreen(
    searchViewModel: SearchViewModel = koinActivityViewModel(),
    comicDetailViewModel: ComicDetailViewModel = koinActivityViewModel(),
    localSettingManager: LocalSettingManager = getKoin().get(),
) {
    val mainNavController = LocalMainNavController.current
    val miscSettings by localSettingManager.misc.collectAsState()
    val comicSearchFilterState by searchViewModel.searchComicFilterState.collectAsState()
    // 按 revision 重新收集：新查询立刻丢掉上一次的 PagingData 展示，
    // 避免 cachedIn 在加载中/失败时把旧列表留在屏幕上。
    val comicSearchLazyPagingItems = androidx.compose.runtime.key(comicSearchFilterState.revision) {
        searchViewModel.searchComicPager.collectAsLazyPagingItems()
    }
    val searchComicIdState by searchViewModel.searchComicIdState.collectAsState()
    val savedViewport by searchViewModel.searchViewportState.collectAsState()
    val sortMenuState = rememberGlassAnchoredMenuState()
    val density = LocalDensity.current
    val sortMenuMaxHeight = with(density) {
        LocalWindowInfo.current.containerSize.height.toDp() * 0.56f
    }
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = savedViewport.firstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = savedViewport.firstVisibleItemScrollOffset,
    )
    val searchItemCount = comicSearchLazyPagingItems.itemCount
    val searchAppendComplete = comicSearchLazyPagingItems.loadState.append.let {
        it is LoadState.NotLoading && it.endOfPaginationReached
    }
    val initialResetGeneration = remember { savedViewport.resetGeneration }
    var initialViewportRestorePending by remember { mutableStateOf(true) }
    var suppressViewportPersistence by remember { mutableStateOf(false) }

    LaunchedEffect(savedViewport.resetGeneration, searchItemCount, searchAppendComplete) {
        if (!initialViewportRestorePending || searchItemCount <= 0) return@LaunchedEffect
        val savedIndex = savedViewport.firstVisibleItemIndex
        if (!searchAppendComplete && searchItemCount <= savedIndex) return@LaunchedEffect

        val targetIndex = savedIndex.coerceAtMost(searchItemCount - 1)
        if (gridState.firstVisibleItemIndex != targetIndex ||
            gridState.firstVisibleItemScrollOffset != savedViewport.firstVisibleItemScrollOffset
        ) {
            gridState.scrollToItem(targetIndex, savedViewport.firstVisibleItemScrollOffset)
        }
        androidx.compose.runtime.withFrameNanos { }
        initialViewportRestorePending = false
    }

    LaunchedEffect(savedViewport.resetGeneration) {
        if (savedViewport.resetGeneration == initialResetGeneration) return@LaunchedEffect
        suppressViewportPersistence = true
        try {
            gridState.scrollToItem(0, 0)
            androidx.compose.runtime.withFrameNanos { }
            initialViewportRestorePending = false
        } finally {
            suppressViewportPersistence = false
        }
    }

    LaunchedEffect(gridState, savedViewport.resetGeneration, initialViewportRestorePending) {
        val resetGeneration = savedViewport.resetGeneration
        snapshotFlow {
            Triple(
                comicSearchLazyPagingItems.itemCount,
                gridState.firstVisibleItemIndex,
                gridState.firstVisibleItemScrollOffset,
            )
        }.distinctUntilChanged().collect { (itemCount, index, offset) ->
            if (itemCount > 0 && !initialViewportRestorePending && !suppressViewportPersistence) {
                searchViewModel.saveSearchViewport(index, offset, resetGeneration)
            }
        }
    }

    fun editRoute(): String {
        val encodedSearchContent = Uri.encode(comicSearchFilterState.searchContent)
        val encodedExcludedTags = Uri.encode(serializeExcludedTags(comicSearchFilterState.excludedTags))
        return "comicSearch?searchContent=$encodedSearchContent&excludedTags=$encodedExcludedTags"
    }

    fun navigateToSearchEditor() {
        val previousRoute = mainNavController.previousBackStackEntry?.destination?.route.orEmpty()
        val previousIsSearchEditor = previousRoute == "comicSearch" || previousRoute.startsWith("comicSearch?")
        if (previousIsSearchEditor && mainNavController.popBackStack()) return

        mainNavController.popBackStack()
        mainNavController.navigate(editRoute()) {
            launchSingleTop = true
        }
    }

    fun navigateBackToOrigin() {
        val previousRoute = mainNavController.previousBackStackEntry?.destination?.route
        if (
            searchResultBackTarget(previousRoute) == SearchResultBackTarget.PREVIOUS_SCREEN &&
            mainNavController.popBackStack()
        ) {
            return
        }
        navigateToSearchEditor()
    }

    HierarchicalBackHandler {
        navigateBackToOrigin()
    }

    LaunchedEffect(searchComicIdState) {
        val comicId = searchComicIdState ?: return@LaunchedEffect
        comicDetailViewModel.reset(comicId)
        searchViewModel.consumeSearchComicId()
        mainNavController.navigate("comicDetail/$comicId") {
            launchSingleTop = true
        }
    }

    CommonScaffold(
        title = comicSearchFilterState.searchContent.ifBlank { "搜索" },
        onNavigateBack = { navigateBackToOrigin() },
        titleContent = {
            val title = comicSearchFilterState.searchContent.ifBlank { "搜索" }
            Text(
                text = title,
                modifier = Modifier.clickable { navigateToSearchEditor() },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        actions = {
            IconButton(
                onClick = { sortMenuState.open() },
                modifier = Modifier.glassMenuAnchor(sortMenuState),
            ) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "排序")
            }
        },
        overlayContent = {
            GlassAnchoredMenu(
                state = sortMenuState,
                surfaceId = "search-sort-glass-menu",
                alignment = GlassMenuAlignment.END,
                width = 190.dp,
                menuMaxHeight = sortMenuMaxHeight,
            ) {
                ComicSearchOrderFilter.entries.forEach { order ->
                    GlassMenuItem(
                        text = order.label,
                        selected = order.value == comicSearchFilterState.order.value,
                        onClick = {
                            sortMenuState.dismiss()
                            searchViewModel.changeSearchComicOrderFilter(order)
                        },
                    )
                }
            }
        },
    ) { topContentPadding, bottomContentPadding ->
        SearchResultRefreshContent(
            refreshState = comicSearchLazyPagingItems.loadState.refresh,
            itemCount = comicSearchLazyPagingItems.itemCount,
            searchGridColumns = miscSettings.gridColumns.search,
            topContentPadding = topContentPadding,
            bottomContentPadding = bottomContentPadding,
            onRetry = { comicSearchLazyPagingItems.retry() },
        ) {
            PullRefreshAndLoadMoreGrid(
                modifier = Modifier.fillMaxSize(),
                lazyPagingItems = comicSearchLazyPagingItems,
                key = { it.id },
                columns = adaptiveComicGridCells(miscSettings.gridColumns.search),
                contentPadding = PaddingValues(
                    start = 10.dp,
                    end = 10.dp,
                    top = topContentPadding + 10.dp,
                    bottom = bottomContentPadding + 10.dp,
                ),
                gridState = gridState,
            ) {
                Comic(it)
            }
        }
    }
}
