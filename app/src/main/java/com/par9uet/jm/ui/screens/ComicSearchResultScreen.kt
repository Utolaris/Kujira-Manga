package com.par9uet.jm.ui.screens

import android.net.Uri
import com.par9uet.jm.ui.navigation.HierarchicalBackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.FilterList
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
import com.par9uet.jm.ui.components.YearMonthSelectDialog
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
import com.par9uet.jm.utils.log
import com.par9uet.jm.utils.logError
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
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

/** 视口管道日志标签；筛日志时用这个。 */
internal const val SEARCH_VIEWPORT_TAG = "SearchViewport"

/** 连续「塌回顶部」修复尝试上限，避免在无法到达的位置上与布局互相拉扯。 */
internal const val MAX_SEARCH_VIEWPORT_COLLAPSE_RECOVERIES = 8

/**
 * 网格是否「塌回顶部」：当前位置在顶部，而保存的目标并不在顶部。
 *
 * 这是「从详情返回搜索页偶发回到顶部」的可观测形态 —— `LazyGridState` 的索引会被
 * 一次布局夹进 `0 until itemCount`，只要在结果页就位之前网格曾被以 0 项（或更少的项）
 * 布局过一次，40 就会被夹成 0；而原来的恢复流程是**一次性 + 等一帧就收工**，
 * 收工之后再没有任何机制把位置修回来。
 *
 * 只在「顶部」这一种形态上干预，是为了不跟用户在列表中间的正常滚动抢位置：
 * 用户拖动会先一步把整个机制关掉（见 `DragInteraction`）。
 */
internal fun isSearchViewportCollapsed(
    savedIndex: Int,
    savedOffset: Int,
    currentIndex: Int,
    currentOffset: Int,
): Boolean = currentIndex == 0 && currentOffset == 0 && (savedIndex > 0 || savedOffset > 0)

/**
 * 塌回顶部后的修复目标索引；`null` 表示当前没有可修的位置（无内容 / 只有一项）。
 * 目标一律夹进 `1 until itemCount`：只夹到有效范围内，绝不等价于「当作目标不存在」。
 */
internal fun searchViewportCollapseRecoveryIndex(savedIndex: Int, itemCount: Int): Int? {
    if (itemCount <= 1) return null
    return savedIndex.coerceIn(1, itemCount - 1)
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
) {
    val mainNavController = LocalMainNavController.current
    val miscSettings by searchViewModel.misc.collectAsState()
    val comicSearchFilterState by searchViewModel.searchComicFilterState.collectAsState()
    // 按 revision 重新收集：新查询立刻丢掉上一次的 PagingData 展示，
    // 避免 cachedIn 在加载中/失败时把旧列表留在屏幕上。
    val comicSearchLazyPagingItems = androidx.compose.runtime.key(comicSearchFilterState.revision) {
        searchViewModel.searchComicPager.collectAsLazyPagingItems()
    }
    val searchComicIdState by searchViewModel.searchComicIdState.collectAsState()
    val savedViewport by searchViewModel.searchViewportState.collectAsState()
    val sortMenuState = rememberGlassAnchoredMenuState()
    var showDateFilterDialog by remember { mutableStateOf(false) }
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
    /**
     * 用户是否已经主动拖动过。
     *
     * 拖动 = 接管视口：此后既不再自动恢复，也不再由本屏决定位置。
     * 只有真实手势会产生 `DragInteraction`，程序化的 `scrollToItem` 不会，
     * 所以这个判定不会被我们自己的恢复动作误触发。
     */
    var viewportTakenOverByUser by remember { mutableStateOf(false) }
    var viewportCollapseRecoveries by remember { mutableStateOf(0) }

    LaunchedEffect(gridState) {
        gridState.interactionSource.interactions
            .filterIsInstance<DragInteraction.Start>()
            .collect {
                if (!viewportTakenOverByUser) {
                    log(SEARCH_VIEWPORT_TAG, "用户开始拖动，视口控制权交还用户")
                }
                viewportTakenOverByUser = true
            }
    }

    LaunchedEffect(savedViewport.resetGeneration, searchItemCount, searchAppendComplete) {
        if (!initialViewportRestorePending || searchItemCount <= 0 || viewportTakenOverByUser) {
            return@LaunchedEffect
        }
        val savedIndex = savedViewport.firstVisibleItemIndex
        val savedOffset = savedViewport.firstVisibleItemScrollOffset
        if (!searchAppendComplete && searchItemCount <= savedIndex) {
            log(
                SEARCH_VIEWPORT_TAG,
                "等待目标页就位：savedIndex=$savedIndex count=$searchItemCount appendComplete=false",
            )
            return@LaunchedEffect
        }

        val targetIndex = savedIndex.coerceAtMost(searchItemCount - 1)
        if (gridState.firstVisibleItemIndex != targetIndex ||
            gridState.firstVisibleItemScrollOffset != savedOffset
        ) {
            log(
                SEARCH_VIEWPORT_TAG,
                "首次恢复视口 → index=$targetIndex offset=$savedOffset" +
                    "（当前 index=${gridState.firstVisibleItemIndex} count=$searchItemCount）",
            )
            gridState.scrollToItem(targetIndex, savedOffset)
        }
        androidx.compose.runtime.withFrameNanos { }
        initialViewportRestorePending = false
    }

    /**
     * 塌回顶部的看门狗。
     *
     * 一次性恢复收工之后，**布局仍可能把索引夹回 0**（例如目标页就位前网格先以 0 项布局过一帧，
     * 或刷新期间列表短暂清空）。原实现收工即失能，于是这一次夹取就永久生效 ——
     * 表现就是「偶发直接回到顶部」。这里只在「观测到顶部、而保存的目标不是顶部」时补一次，
     * 因此不会与用户在列表中间的滚动抢位置。
     */
    LaunchedEffect(gridState, savedViewport.resetGeneration, viewportTakenOverByUser) {
        if (viewportTakenOverByUser) return@LaunchedEffect
        val savedIndex = savedViewport.firstVisibleItemIndex
        val savedOffset = savedViewport.firstVisibleItemScrollOffset
        if (savedIndex == 0 && savedOffset == 0) return@LaunchedEffect
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                if (viewportTakenOverByUser || suppressViewportPersistence) return@collect
                if (!isSearchViewportCollapsed(savedIndex, savedOffset, index, offset)) return@collect
                // 必须实时读：进入本屏时 itemCount 还是 0，用组合期的快照会让看门狗直接失效。
                val liveItemCount = comicSearchLazyPagingItems.itemCount
                val target = searchViewportCollapseRecoveryIndex(savedIndex, liveItemCount) ?: return@collect
                if (viewportCollapseRecoveries >= MAX_SEARCH_VIEWPORT_COLLAPSE_RECOVERIES) {
                    logError(
                        SEARCH_VIEWPORT_TAG,
                        "网格反复塌回顶部，已重试 $viewportCollapseRecoveries 次仍失败，放弃" +
                            "（savedIndex=$savedIndex count=$liveItemCount）",
                    )
                    viewportTakenOverByUser = true
                    return@collect
                }
                viewportCollapseRecoveries++
                logError(
                    SEARCH_VIEWPORT_TAG,
                    "网格塌回顶部（期望 index=$savedIndex），第 $viewportCollapseRecoveries 次修回 index=$target" +
                        "（count=$liveItemCount）",
                )
                gridState.scrollToItem(target, savedOffset)
            }
    }

    LaunchedEffect(savedViewport.resetGeneration) {
        if (savedViewport.resetGeneration == initialResetGeneration) return@LaunchedEffect
        log(SEARCH_VIEWPORT_TAG, "查询换代（gen=${savedViewport.resetGeneration}），视口归零")
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
        var firstSaveLogged = false
        snapshotFlow {
            Triple(
                comicSearchLazyPagingItems.itemCount,
                gridState.firstVisibleItemIndex,
                gridState.firstVisibleItemScrollOffset,
            )
        }.distinctUntilChanged().collect { (itemCount, index, offset) ->
            // 恢复期间不落盘（避免把中间态写成用户位置）；用户一旦接管就立刻恢复持久化。
            val persistenceAllowed = !initialViewportRestorePending || viewportTakenOverByUser
            if (itemCount > 0 && persistenceAllowed && !suppressViewportPersistence) {
                if (!firstSaveLogged) {
                    firstSaveLogged = true
                    log(SEARCH_VIEWPORT_TAG, "视口恢复完成，交回持久化：index=$index offset=$offset")
                }
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
            // 与搜索起始页同一套入口：年月 / 排序可在此调整，不覆盖关键词与排除标签。
            IconButton(onClick = { showDateFilterDialog = true }) {
                val dateActive = comicSearchFilterState.year.isNotBlank() ||
                    comicSearchFilterState.month.isNotBlank()
                Icon(
                    imageVector = Icons.Rounded.CalendarMonth,
                    contentDescription = if (dateActive) "按年月筛选（已启用）" else "按年月筛选",
                    tint = if (dateActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(
                onClick = { sortMenuState.open() },
                modifier = Modifier.glassMenuAnchor(sortMenuState),
            ) {
                val orderActive = comicSearchFilterState.order != ComicSearchOrderFilter.NEWEST
                Icon(
                    imageVector = Icons.Rounded.FilterList,
                    contentDescription = "排序筛选",
                    tint = if (orderActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        overlayContent = {
            YearMonthSelectDialog(
                visible = showDateFilterDialog,
                year = comicSearchFilterState.year,
                month = comicSearchFilterState.month,
                modifier = Modifier.widthIn(max = 420.dp),
                onSelect = { year, month ->
                    searchViewModel.changeSearchComicDateFilter(year, month)
                },
                onClear = {
                    searchViewModel.changeSearchComicDateFilter("", "")
                },
                onDismissRequest = { showDateFilterDialog = false },
            )
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
