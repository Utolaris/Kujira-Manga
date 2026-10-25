package com.par9uet.jm.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import com.par9uet.jm.ui.components.MotionButton as Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems

@Composable
fun <T : Any> PullRefreshAndLoadMoreGrid(
    modifier: Modifier = Modifier,
    lazyPagingItems: LazyPagingItems<T>,
    key: ((item: T) -> Any),
    columns: GridCells,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(10.dp, Alignment.Top),
    horizontalArrangement: Arrangement.HorizontalOrVertical = Arrangement.spacedBy(10.dp),
    contentPadding: PaddingValues = PaddingValues(10.dp),
    gridState: LazyGridState = rememberLazyGridState(),
    itemVisible: (item: T) -> Boolean = { true },
    enablePullRefresh: Boolean = true,
    animateItems: Boolean = false,
    itemContent: @Composable ((item: T) -> Unit),
) {
    val visibleCoverKeys = rememberVisibleCoverKeys(gridState)
    val refreshState = lazyPagingItems.loadState.refresh
    var userRefreshRequested by remember(lazyPagingItems) { mutableStateOf(false) }
    LaunchedEffect(refreshState) {
        if (refreshState !is LoadState.Loading) userRefreshRequested = false
    }
    // Paging 首次订阅、返回页面和重连也会进入 Loading；只有真实下拉才显示顶部转圈。
    val isRefreshing = userRefreshRequested && refreshState is LoadState.Loading
    val isGridScrolling = remember(gridState) { { gridState.isScrollInProgress } }
    val gridContent: @Composable () -> Unit = {
        LazyVerticalGrid(
            // 不要 fillMaxSize：在 heightIn(max) 父级里会把列表强行撑到上限，
            // 短内容底部就空出一块白底。fillMaxWidth + 有界 maxHeight 时 Lazy 会按内容收缩。
            modifier = Modifier.fillMaxWidth(),
            state = gridState,
            columns = columns,
            verticalArrangement = verticalArrangement,
            horizontalArrangement = horizontalArrangement,
            contentPadding = contentPadding
        ) {
            // 历史页回前台自动刷新失败时保留已有列表，并给首屏/刷新错误提供重试入口。
            val refreshError = lazyPagingItems.loadState.refresh as? LoadState.Error
            if (refreshError != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            refreshError.error.message ?: "加载失败，请重试",
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(onClick = { lazyPagingItems.retry() }) {
                            Text("重试")
                        }
                    }
                }
            }
            // 动画模式要求 key 稳定：插入新条目时旧条目的 key 不变，animateItem 才能把
            // 「新增」和「位移」区分开。业务 key 只在真的撞车时才追加序号（分页竞态
            // 下的偏移重复），避免默认的 "#index" 后缀让一次插入把所有条目都变成新 key。
            val stableKeys: List<Any?> = if (animateItems) {
                val seen = HashMap<Any, Int>()
                List(lazyPagingItems.itemCount) { index ->
                    val item = lazyPagingItems.peek(index)
                    if (item == null) {
                        null
                    } else {
                        val base = key(item)
                        val ordinal = seen.getOrDefault(base, 0)
                        seen[base] = ordinal + 1
                        if (ordinal == 0) base else "$base~dup$ordinal"
                    }
                }
            } else {
                emptyList()
            }
            // 同一 key 同时用于布局与封面可见性；顶部错误项不影响漫画的视口判断。
            val itemKey: (Int) -> Any = { index ->
                val item = lazyPagingItems.peek(index)
                when {
                    item == null -> "paging_placeholder_$index"
                    animateItems -> stableKeys[index]!!
                    else -> "${key(item)}#$index"
                }
            }
            items(
                lazyPagingItems.itemCount,
                // 业务 id 可能在分页膨胀/竞态下重复；LazyGrid 对重复 key 会直接崩溃。
                key = itemKey,
                contentType = { if (lazyPagingItems.peek(it) == null) "placeholder" else "content" },
            ) { index ->
                val item = lazyPagingItems[index]
                if (item != null && itemVisible(item)) {
                    CoverViewportItem(visibleCoverKeys, itemKey(index)) {
                        val itemModifier = if (animateItems) Modifier.animateItem() else Modifier
                        Box(modifier = itemModifier) {
                            itemContent(item)
                        }
                    }
                }
            }
            when (val appendState = lazyPagingItems.loadState.append) {
                is LoadState.Loading -> {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        // 页尾指示器只占用自身所需的高度。
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                }

                is LoadState.Error -> {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("\u52a0\u8f7d\u5931\u8d25", color = MaterialTheme.colorScheme.error)
                            Button(onClick = { lazyPagingItems.retry() }) {
                                Text("\u91cd\u8bd5")
                            }
                        }
                    }
                }

                is LoadState.NotLoading -> {
                    if (appendState.endOfPaginationReached && refreshError == null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "你仿佛来到了涩涩之海的尽头",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    CompositionLocalProvider(LocalComicGridScrolling provides isGridScrolling) {
        if (enablePullRefresh) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    userRefreshRequested = true
                    lazyPagingItems.refresh()
                },
                modifier = modifier
            ) {
                gridContent()
            }
        } else {
            Box(modifier = modifier) {
                gridContent()
            }
        }
    }
}
