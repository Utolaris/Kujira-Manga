package com.par9uet.jm.ui.interaction

import androidx.compose.ui.unit.dp
import com.par9uet.jm.ui.screens.detailLayoutTargets
import com.par9uet.jm.ui.screens.constrainDetailContent
import org.junit.Assert.*
import org.junit.Test

class DetailLayoutMotionTest {
    @Test fun `portrait metadata leaves room for the cover while comments span the reading column`() {
        val target = detailLayoutTargets(900.dp, 1400.dp, 100.dp, 100.dp, false)
        assertEquals(240.dp, target.coverWidth)
        assertEquals(46.dp, target.coverLeft)
        assertEquals(40.dp, target.contentLeft)
        assertEquals(820.dp, target.contentWidth)
        assertEquals(260.dp, target.metadataInset)
        assertEquals(target.coverLeft + target.coverWidth + 14.dp, target.contentLeft + target.metadataInset)
    }

    @Test fun `landscape cover is pinned beside a single right hand scroll column`() {
        val target = detailLayoutTargets(1400.dp, 900.dp, 100.dp, 100.dp, true)
        // 左右外边距必须相等，视觉重心才不会偏左。
        assertEquals(24.dp, target.coverLeft)
        assertEquals(392.dp, target.coverWidth)
        assertEquals(444.dp, target.contentLeft)
        assertEquals(932.dp, target.contentWidth)
        assertEquals(0.dp, target.metadataInset)
        // 左右外边距相等：视觉重心不再偏左。
        assertEquals(target.coverLeft, 1400.dp - (target.contentLeft + target.contentWidth))
    }

    @Test fun `compact split screen still leaves a positive metadata column`() {
        for (width in listOf(320, 400, 600, 840, 1000)) {
            val target = detailLayoutTargets(width.dp, 1400.dp, 100.dp, 100.dp, false)
            assertTrue(target.contentWidth > target.metadataInset)
            assertTrue(target.coverLeft >= 0.dp)
            assertTrue(target.contentLeft + target.contentWidth <= width.dp)
        }
    }

    @Test fun `shrinking windows contain the animated column at every progress value`() {
        for (viewport in listOf(0, 280, 600, 900)) {
            for (step in 0..100) {
                val progress = step / 100f
                val left = (364f + (40f - 364f) * progress).dp
                val width = (1010f + (820f - 1010f) * progress).dp
                val (visibleLeft, visibleWidth) = constrainDetailContent(viewport.dp, left, width, 10.dp)
                assertTrue(visibleLeft >= 0.dp)
                assertTrue(visibleWidth >= 0.dp)
                assertTrue(visibleLeft + visibleWidth <= viewport.dp)
                if (viewport >= 280) assertTrue(visibleWidth >= 160.dp)
            }
        }
    }

    @Test fun `entry and exit are twenty five percent faster while drag rebound stays unchanged`() {
        assertEquals(448, CoverEnterMillis)
        assertEquals(224, DetailContentEnterMillis)
        assertEquals(150, DetailContentExitMillis)
        assertEquals(256, CoverExitMillis)
        assertEquals(440, CoverReturnMillis)
    }
}
