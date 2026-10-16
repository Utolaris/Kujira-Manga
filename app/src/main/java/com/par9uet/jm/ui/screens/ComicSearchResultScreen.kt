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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.par9uet.jm.ui.interaction.rememberRestoringGridState
import com.par9uet.jm.ui.interaction.searchListPositionKey
import com.par9uet.jm.ui.navigation.LocalMainNavController
import com.par9uet.jm.ui.viewModel.ComicDetailViewModel
import com.par9uet.jm.ui.viewModel.SearchViewModel
import com.par9uet.jm.contentfilter.serializeExcludedTags
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
    val sortMenuState = rememberGlassAnchoredMenuState()
    var showDateFilterDialog by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val sortMenuMaxHeight = with(density) {
        LocalWindowInfo.current.containerSize.height.toDp() * 0.56f
    }
    val searchItemCount = comicSearchLazyPagingItems.itemCount
    val searchAppendComplete = comicSearchLazyPagingItems.loadState.append.let {
        it is LoadState.NotLoading && it.endOfPaginationReached
    }
    /**
     * 滚动位置走全局位置记录，key = 当前查询键（排序 / 关键词 / 排除标签 / 年月）。
     *
     * 换查询或换排序 → 新 key → 没有记忆 → 从顶部开始；同一查询返回 → 命中记忆。
     * 「搜索 → 详情 → 点作者 → 新详情 → 返回 → 返回」时，回到旧查询用的还是它自己的
     * key，位置原样恢复。之前那套 generation 归零逻辑会在这里误判成「换代」，
     * 表现就是回到顶部。
     */
    val gridState = rememberRestoringGridState(
        key = searchListPositionKey(comicSearchFilterState.queryKey()),
        itemCount = searchItemCount,
        appendComplete = searchAppendComplete,
    )
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
