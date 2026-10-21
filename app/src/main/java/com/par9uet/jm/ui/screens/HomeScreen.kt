package com.par9uet.jm.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import com.par9uet.jm.ui.components.MotionIconButton as IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.par9uet.jm.ui.components.MotionTextButton as TextButton
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.par9uet.jm.ui.components.Comic
import com.par9uet.jm.ui.components.ComicSkeleton
import com.par9uet.jm.ui.components.adaptiveComicGridCells
import com.par9uet.jm.ui.components.comicGridMinCellSize
import com.par9uet.jm.ui.glass.AppGlassTopBar
import com.par9uet.jm.ui.glass.GlassAnchoredMenuState
import com.par9uet.jm.ui.glass.glassMenuAnchor
import com.par9uet.jm.ui.interaction.pullDownToAction
import com.par9uet.jm.ui.interaction.PullDownActionState
import com.par9uet.jm.ui.models.ListPositionKeyHome
import com.par9uet.jm.ui.interaction.rememberPullDownActionState
import com.par9uet.jm.ui.interaction.rememberRestoringGridState
import com.par9uet.jm.ui.viewModel.HomeViewModel
import com.par9uet.jm.contentfilter.filterBlockedTags
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.compose.viewmodel.koinActivityViewModel

private const val TEXT_SEARCH = "\u641c\u7d22"
private const val TEXT_DOWNLOAD = "\u4e0b\u8f7d"
private const val CATEGORY_LOADING_SKELETON_COUNT = 18

internal fun resolveHomeCategoryTitle(
    categories: List<HomeViewModel.HomeCategoryInfo>,
    selectedCategoryId: String?,
): String {
    return categories.firstOrNull { it.id == selectedCategoryId }?.title ?: "首页"
}


@Composable
private fun HomeSkeleton(
    gridColumns: Int,
    // 与真实网格同源：平板布局用更大的最小单元格，否则自适应列数在冷启动骨架
    // 和内容加载后不一致，占位尺寸对不上。
    minSize: Dp,
    topContentPadding: Dp,
    bottomContentPadding: Dp,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        modifier = modifier.fillMaxSize(),
        state = gridState,
        columns = adaptiveComicGridCells(gridColumns, minSize = minSize),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = topContentPadding,
            bottom = 16.dp + bottomContentPadding,
        ),
    ) {
        items(CATEGORY_LOADING_SKELETON_COUNT) {
            ComicSkeleton()
        }
    }
}

@Composable
internal fun HomeScreen(
    homeViewModel: HomeViewModel = koinActivityViewModel(),
    topContentPadding: Dp = 0.dp,
    bottomContentPadding: Dp = 0.dp,
    pullDownState: PullDownActionState = rememberPullDownActionState(),
    onPullDownSearch: () -> Unit = {},
    isActive: Boolean = true,
) {
    val homeState by homeViewModel.homeState.collectAsState()
    val preferenceRecommendEnabled by homeViewModel.preferenceRecommendEnabled.collectAsState()
    val blockedTags by homeViewModel.blockedTags.collectAsState()
    val miscSettings by homeViewModel.misc.collectAsState()
    // 骨架与真实网格共用的自适应最小单元格：平板布局下更大，列数才一致。
    val gridMinCellSize = comicGridMinCellSize()
    val pullRevealPadding = 36.dp * pullDownState.progress
    val selectedCategoryId = homeState.selectedCategoryId
    val selectedState = selectedCategoryId?.let { homeState.states[it] }
    // Full-page skeleton is only for bootstrap, before the real category structure exists.
    val showSkeleton = homeState.categories.isEmpty() || selectedCategoryId == null
    val currentContent = selectedState?.content.orEmpty()
    val comicList = remember(currentContent, blockedTags) {
        currentContent.filterBlockedTags(blockedTags).distinctBy { it.id }
    }
    // 位置记录：从首页进入漫画再返回时维持原位置。按分类隔离 —— 换分类 = 新位置。
    val gridState = rememberRestoringGridState(
        key = "$ListPositionKeyHome|${selectedCategoryId ?: -1}",
        itemCount = comicList.size,
    )
    var isGridScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { isGridScrolling = it }
    }
    val pullDownModifier = Modifier.pullDownToAction(
        state = pullDownState,
        isAtTop = { !gridState.canScrollBackward },
        onTrigger = onPullDownSearch,
    )

    LaunchedEffect(preferenceRecommendEnabled) {
        homeViewModel.refreshHome()
    }
    LifecycleStartEffect(homeViewModel, isActive) {
        homeViewModel.setHomeActive(isActive)
        onStopOrDispose { homeViewModel.setHomeActive(false) }
    }

    if (showSkeleton) {
        HomeSkeleton(
            gridColumns = miscSettings.gridColumns.home,
            minSize = gridMinCellSize,
            topContentPadding = topContentPadding + pullRevealPadding,
            bottomContentPadding = bottomContentPadding,
            gridState = gridState,
            modifier = pullDownModifier,
        )
        return
    }

    LazyVerticalGrid(
        modifier = pullDownModifier.fillMaxSize(),
        state = gridState,
        columns = adaptiveComicGridCells(
            columns = miscSettings.gridColumns.home,
            minSize = gridMinCellSize,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = topContentPadding + pullRevealPadding,
            bottom = 16.dp + bottomContentPadding,
        )
    ) {
            if (selectedState?.isLoading == true && currentContent.isEmpty()) {
                items(CATEGORY_LOADING_SKELETON_COUNT) {
                    ComicSkeleton()
                }
            } else if (selectedState != null && selectedState.isError && comicList.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Text(
                            text = selectedState.errorMsg ?: "加载失败",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { homeViewModel.refreshSelectedHomeCategory() }) {
                            Text("重试")
                        }
                    }
                }
            }
            items(items = comicList, key = { it.id }) {
                Comic(comic = it, isScrolling = isGridScrolling)
            }
            if (comicList.isEmpty() && !(selectedState?.isLoading == true) &&
                !(selectedState?.isError == true)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Text(
                            text = if (blockedTags.isNotEmpty()) "当前分类的漫画均被标签排除过滤" else "暂无漫画",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (blockedTags.isNotEmpty()) {
                            Text(
                                text = "可在 设置 → 标签排除 中调整",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
}


@Composable
internal fun HomeGlassTopBar(
    title: String,
    statusBarInset: Dp,
    categoryMenuState: GlassAnchoredMenuState,
    moreMenuState: GlassAnchoredMenuState,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit,
    onDownload: () -> Unit,
    onNotifications: () -> Unit,
    hasUnreadNotifications: Boolean = false,
) {
    val hapticFeedback = LocalHapticFeedback.current
    AppGlassTopBar(
        surfaceId = "primary-home-top-bar",
        statusBarInset = statusBarInset,
        modifier = modifier,
        title = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .glassMenuAnchor(categoryMenuState)
                    .clickable {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        moreMenuState.dismiss()
                        categoryMenuState.open()
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    modifier = Modifier.padding(start = 4.dp),
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        actions = {
            IconButton(onClick = onNotifications) {
                Icon(Icons.Rounded.Notifications, contentDescription = "通知",
                    tint = if (hasUnreadNotifications) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { stateDescription = if (hasUnreadNotifications) "有未读通知" else "无未读通知" })
            }
            IconButton(onClick = onSearch) {
                Icon(Icons.Rounded.Search, contentDescription = TEXT_SEARCH)
            }
            IconButton(onClick = onDownload) {
                Icon(Icons.Rounded.Download, contentDescription = TEXT_DOWNLOAD)
            }
            IconButton(
                onClick = {
                    categoryMenuState.dismiss()
                    moreMenuState.open()
                },
                modifier = Modifier.glassMenuAnchor(moreMenuState),
            ) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "更多")
            }
        },
    )
}
