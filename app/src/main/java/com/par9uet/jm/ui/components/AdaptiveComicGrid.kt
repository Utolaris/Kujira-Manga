package com.par9uet.jm.ui.components

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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

/** 平板自适应网格的默认最小单元格。 */
val TabletComicGridMinCellSize: Dp = 160.dp
