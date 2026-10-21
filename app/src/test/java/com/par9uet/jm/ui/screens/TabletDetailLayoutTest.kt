package com.par9uet.jm.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class TabletDetailLayoutTest {
    @Test fun `tablet portrait and narrow split use compact detail while wide landscape keeps two columns`() {
        assertFalse(useDetailColumns(true, 914.dp, 1463.dp))
        assertTrue(useDetailColumns(true, 1463.dp, 914.dp))
        assertFalse(useDetailColumns(true, 650.dp, 500.dp))
        assertFalse(useDetailColumns(true, 900.dp, 900.dp))
        assertFalse(useDetailColumns(false, 1463.dp, 914.dp))
        assertEquals(240.dp, compactDetailCoverWidth(true, 840.dp))
        assertTrue(compactDetailCoverWidth(true, 400.dp) < 180.dp)
        assertEquals(180.dp, compactDetailCoverWidth(false, 400.dp))
    }

    /** 分屏/半窗虽宽大于高，也必须留够右侧阅读列，否则封面会吃掉内容区。 */
    @Test fun `landscape split that cannot fit the reading column stays single column`() {
        // 699dp 刚好差一点：仍需 420dp 阅读列。
        assertFalse(useDetailColumns(true, 1119.dp, 700.dp))
        assertTrue(useDetailColumns(true, 1120.dp, 700.dp))
        // 浏览器分屏常见的 900x800 横窗同样保持单栏。
        assertFalse(useDetailColumns(true, 900.dp, 800.dp))
    }

    /** 平板单栏不再满屏：竖屏/分屏限宽 840dp 居中，手机原样满宽。 */
    @Test fun `tablet single column keeps the reading column centred while phones stay full width`() {
        assertEquals(840.dp, detailReadingColumnWidth(true, 1463.dp))
        assertEquals(700.dp, detailReadingColumnWidth(true, 700.dp))
        assertEquals(400.dp, detailReadingColumnWidth(false, 400.dp))
        assertEquals(1463.dp, detailReadingColumnWidth(false, 1463.dp))
    }

    /** 横屏右列至少留 420dp；这正是下载详情此前右侧三分之一空白被吃掉的原因。 */
    @Test fun `wide layout keeps a usable reading column next to the cover`() {
        for (windowWidth in listOf(1120, 1200, 1463, 1600)) {
            val width = tabletDetailCoverWidth(windowWidth.dp, 900.dp, 100.dp, 100.dp)
            val contentLeft = 44.dp + width
            val contentWidth = windowWidth.dp - contentLeft - 26.dp
            assertTrue("$windowWidth", contentWidth >= 420.dp)
        }
    }

    /** 3:4 封面面积 +50%：宽度 320 → 392（392/320 ≈ √1.5）。 */
    @Test fun `large tablet cover stays within its declared maximum`() {
        assertEquals(392.dp, tabletDetailCoverWidth(1400.dp, 1000.dp, 100.dp, 100.dp))
        assertEquals(392.dp, tabletDetailCoverWidth(2560.dp, 1600.dp, 100.dp, 100.dp))
    }

    @Test fun `short landscape windows reserve room for both floating bars`() {
        val width = tabletDetailCoverWidth(1100.dp, 500.dp, 100.dp, 100.dp)
        assertEquals(225f, width.value, .001f)
        assertEquals(500f, 100f + width.value / .75f + 100f, .001f)
    }

    @Test fun `split windows keep the cover and right column inside the available width`() {
        for (windowWidth in listOf(360, 540, 700, 900, 1400)) {
            for (windowHeight in listOf(360, 500, 900, 1400)) {
                val width = tabletDetailCoverWidth(windowWidth.dp, windowHeight.dp, 100.dp, 100.dp)
                assertTrue(width <= 392.dp)
                assertTrue(width.value / .75f + 200f <= windowHeight + .01f)
                // 双栏左右外边距各 24dp，封面与内容列之间 28dp。
                val rightColumn = windowWidth - (24f + width.value + 28f + 24f)
                assertTrue(rightColumn > width.value)
            }
        }
    }

    @Test fun `rotation and resize recompute targets without carrying portrait dimensions`() {
        val portrait = tabletDetailCoverWidth(900.dp, 1400.dp, 100.dp, 100.dp)
        val landscape = tabletDetailCoverWidth(1400.dp, 500.dp, 100.dp, 100.dp)
        val split = tabletDetailCoverWidth(700.dp, 1400.dp, 100.dp, 100.dp)
        assertEquals(357f, portrait.value, .001f)
        assertEquals(225.dp, landscape)
        assertEquals(273f, split.value, .001f)
    }

    @Test fun `larger insets shrink the target and unavailable space never creates negative bounds`() {
        val regular = tabletDetailCoverWidth(1000.dp, 600.dp, 80.dp, 80.dp)
        val inset = tabletDetailCoverWidth(1000.dp, 600.dp, 120.dp, 120.dp)
        assertTrue(inset < regular)
        assertEquals(0.dp, tabletDetailCoverWidth(1000.dp, 180.dp, 100.dp, 100.dp))
        assertEquals(0.dp, tabletDetailCoverWidth(40.dp, 1000.dp, 100.dp, 100.dp))
    }
}
