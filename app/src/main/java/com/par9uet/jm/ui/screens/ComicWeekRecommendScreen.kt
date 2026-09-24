package com.par9uet.jm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.par9uet.jm.ui.components.Comic
import com.par9uet.jm.ui.components.ComicSkeleton
import com.par9uet.jm.ui.components.CommonScaffold
import com.par9uet.jm.ui.components.PullRefreshAndLoadMoreGrid
import com.par9uet.jm.ui.components.SelectDialog
import com.par9uet.jm.ui.components.SelectOption
import com.par9uet.jm.ui.components.adaptiveComicGridCells
import com.par9uet.jm.ui.viewModel.WeekViewModel
import org.koin.compose.viewmodel.koinActivityViewModel

/**
 * 与搜索结果骨架同构：18 个 [ComicSkeleton]、10.dp 间距，占位与真实网格同 padding，
 * 内容替换时不会跳版。
 */
@Composable
private fun ComicWeekRecommendSkeleton(
    topContentPadding: Dp,
    bottomContentPadding: Dp,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        modifier = modifier.fillMaxSize(),
        columns = adaptiveComicGridCells(),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Top),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(
            start = 10.dp,
            end = 10.dp,
            top = topContentPadding + 10.dp,
            bottom = bottomContentPadding + 10.dp,
        ),
        userScrollEnabled = false,
    ) {
        items(18) {
            ComicSkeleton()
        }
    }
}

/**
 * Weekly picks for one issue.
 *
 * The issue (刊号) is the only axis this screen filters on, so it lives as a single calendar
 * affordance in the top bar instead of a filter row: the upstream API has no per-type endpoint
 * (see the note in ComicWeekRecommendScreen's history — `getWeeklyPicksDetail` takes the issue
 * only), so the former 韩漫 / 日漫 / 其他 chips could never change the result. Every issue now
 * shows its full list, mixed categories included.
 */
@Composable
fun ComicWeekRecommendScreen(
    weekViewModel: WeekViewModel = koinActivityViewModel()
) {
    val weekDataState by weekViewModel.weekDataState.collectAsState()
    val weekFilterState by weekViewModel.weekFilterState.collectAsState()
    val weekRecommendComicPagingItems = weekViewModel.weekComicPager.collectAsLazyPagingItems()
    var showSelectDialog by remember { mutableStateOf(false) }
    val categoryList = weekDataState.data?.categoryList.orEmpty()
    val currentCategoryLabel = categoryList
        .firstOrNull { it.first == weekFilterState.categoryId }
        ?.second

    LaunchedEffect(Unit) {
        if (weekDataState.data != null) {
            return@LaunchedEffect
        }
        weekViewModel.getWeekData()
    }
    CommonScaffold(
        title = "每周推荐",
        titleContent = {
            // Two lines so the issue stays readable once the selection chip is gone.
            Text(
                text = "每周推荐",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (currentCategoryLabel != null) {
                Text(
                    text = currentCategoryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        actions = {
            IconButton(onClick = { showSelectDialog = true }) {
                Icon(
                    imageVector = Icons.Rounded.CalendarMonth,
                    contentDescription = "选择日期",
                )
            }
        },
        overlayContent = {
            SelectDialog(
                visible = showSelectDialog,
                title = "选择日期",
                value = weekFilterState.categoryId,
                modifier = Modifier.widthIn(max = 420.dp),
                selectOptionList = categoryList.map {
                    SelectOption(label = it.second, value = it.first)
                },
                onSelect = {
                    weekViewModel.changeWeekCategoryFilter(it)
                    showSelectDialog = false
                },
                onDismissRequest = { showSelectDialog = false },
            )
        },
    ) { topContentPadding, bottomContentPadding ->
        // 刊号未就绪时分页会立刻回空页（WeekComicPagingSource），只看 paging 会漏掉首屏加载；
        // 与搜索一致：空列表 + 加载中（分页刷新或刊号元数据）时展示骨架。
        val showSkeleton = weekRecommendComicPagingItems.itemCount == 0 && !weekDataState.isError &&
            (
                weekDataState.isLoading ||
                    weekDataState.data == null ||
                    weekRecommendComicPagingItems.loadState.refresh is LoadState.Loading
                )
        if (showSkeleton) {
            ComicWeekRecommendSkeleton(
                topContentPadding = topContentPadding,
                bottomContentPadding = bottomContentPadding,
            )
        } else {
            PullRefreshAndLoadMoreGrid(
                modifier = Modifier.fillMaxSize(),
                lazyPagingItems = weekRecommendComicPagingItems,
                key = { it.id },
                columns = adaptiveComicGridCells(),
                contentPadding = PaddingValues(
                    start = 10.dp,
                    end = 10.dp,
                    top = topContentPadding + 10.dp,
                    bottom = bottomContentPadding + 10.dp,
                ),
            ) {
                Comic(it)
            }
        }
    }
}
