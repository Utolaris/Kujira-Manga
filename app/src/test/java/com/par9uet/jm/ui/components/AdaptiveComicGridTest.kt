package com.par9uet.jm.ui.components

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveComicGridTest {
    @Test fun `waterfall and regular grids choose identical column widths at phone tablet and fixed settings`() {
        for (scale in listOf(1f, 2f, 2.75f)) {
            val density = Density(scale)
            for (minimum in listOf(118.dp, TabletComicGridMinCellSize)) {
                for (columns in 0..6) {
                    for (widthDp in listOf(100, 118, 247, 248, 360, 387, 800, 1200)) {
                        val width = with(density) { widthDp.dp.roundToPx() }
                        val spacing = with(density) { 12.dp.roundToPx() }
                        val regular = with(adaptiveComicGridCells(columns, minimum)) {
                            density.calculateCrossAxisCellSizes(width, spacing)
                        }
                        val waterfall = with(adaptiveComicStaggeredGridCells(columns, minimum)) {
                            density.calculateCrossAxisCellSizes(width, spacing).toList()
                        }
                        assertEquals("scale=$scale minimum=$minimum columns=$columns width=$widthDp", regular, waterfall)
                        val minimumPx = with(density) { minimum.roundToPx() }
                        val expectedColumns = if (columns > 0) columns else
                            ((width + spacing) / (minimumPx + spacing)).coerceAtLeast(1)
                        assertEquals(expectedColumns, regular.size)
                        assertEquals(width, regular.sum() + spacing * (regular.size - 1))
                        org.junit.Assert.assertTrue(regular.all { it > 0 })
                        org.junit.Assert.assertTrue(regular.max() - regular.min() <= 1)
                    }
                }
            }
        }
    }
}
