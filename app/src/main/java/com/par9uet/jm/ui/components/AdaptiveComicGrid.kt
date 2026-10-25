package com.par9uet.jm.ui.components

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.par9uet.jm.ui.models.LocalTabletLayoutEnabled

/**
 * 稳定的滚动状态读取端口，只在封面组件内调用，避免滚动起止重组整张卡片。
 * 宿主传递读取函数，不在网格层订阅布尔值；封面据此延迟网络结果上屏。
 */
val LocalComicGridScrolling = compositionLocalOf<() -> Boolean> { { false } }

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
