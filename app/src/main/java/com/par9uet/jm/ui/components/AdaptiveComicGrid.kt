package com.par9uet.jm.ui.components

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.par9uet.jm.ui.models.LocalTabletLayoutEnabled

/**
 * 分页/首页网格滚动中标志。由 [PullRefreshAndLoadMoreGrid] 等列表宿主写入，
 * [Comic]/封面据此延迟网络结果上屏，避免 fling 时逐张替换 painter 掉帧。
 */
val LocalComicGridScrolling = compositionLocalOf { false }

/**
 * 当前布局下漫画网格的自适应最小单元格：平板布局用更大格子，
 * 避免横屏挤出过多列导致解码与玻璃捕获叠加掉帧。
 * 所有漫画网格（含骨架）都应经此取值——骨架与真实网格列数不一致正是冷启动错位的来源。
 */
@Composable
fun comicGridMinCellSize(): Dp =
    if (LocalTabletLayoutEnabled.current) TabletComicGridMinCellSize else 118.dp

/**
 * @param columns 用户显式列数；0 = 自适应。
 * @param minSize 自适应时的单元格最小边长。平板大屏若仍用手机的 118dp，
 *   横屏会挤出过多列，封面解码与玻璃捕获叠加容易掉帧。
 */
fun adaptiveComicGridCells(
    columns: Int = 0,
    minSize: Dp = 118.dp,
): GridCells {
    return if (columns > 0) {
        GridCells.Fixed(columns)
    } else {
        GridCells.Adaptive(minSize = minSize)
    }
}

/** Waterfall articles share the same explicit-column / adaptive-width policy as comic grids. */
fun adaptiveComicStaggeredGridCells(columns: Int = 0, minSize: Dp = 118.dp): StaggeredGridCells =
    if (columns > 0) StaggeredGridCells.Fixed(columns) else StaggeredGridCells.Adaptive(minSize)

/** 平板自适应网格的默认最小单元格。 */
val TabletComicGridMinCellSize: Dp = 160.dp
