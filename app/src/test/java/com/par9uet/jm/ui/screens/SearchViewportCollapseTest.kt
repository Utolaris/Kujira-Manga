package com.par9uet.jm.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「从详情返回搜索页偶发直接回到顶部」的回归防线。
 *
 * 成因形态：`LazyGridState` 的索引在结果页就位之前被一次布局夹进 `0 until itemCount`，
 * 40 被夹成 0；而一次性的恢复流程此时已经收工，于是这次夹取永久生效。
 * 看门狗只在「顶部」这一种形态上干预 —— 这两个纯判定就是它的全部判据。
 */
class SearchViewportCollapseTest {

    @Test
    fun topWhileTargetIsNotTopCountsAsCollapsed() {
        assertTrue(
            isSearchViewportCollapsed(
                savedIndex = 40,
                savedOffset = 0,
                currentIndex = 0,
                currentOffset = 0,
            ),
        )
        // 目标索引是 0，但用户当时停在页内偏移上：也算塌回。
        assertTrue(
            isSearchViewportCollapsed(
                savedIndex = 0,
                savedOffset = 120,
                currentIndex = 0,
                currentOffset = 0,
            ),
        )
    }

    @Test
    fun targetTopIsNotACollapse() {
        // 目标本来就是顶部（新查询、或用户自己停在顶部）：合法位置，不能干预。
        assertFalse(isSearchViewportCollapsed(0, 0, 0, 0))
    }

    @Test
    fun midListPositionsAreNeverTreatedAsCollapse() {
        // 只在顶部形态上干预，否则会跟用户在列表中间的滚动抢位置。
        assertFalse(
            isSearchViewportCollapsed(
                savedIndex = 40,
                savedOffset = 0,
                currentIndex = 12,
                currentOffset = 0,
            ),
        )
        assertFalse(
            isSearchViewportCollapsed(
                savedIndex = 40,
                savedOffset = 0,
                currentIndex = 40,
                currentOffset = 0,
            ),
        )
    }

    @Test
    fun recoveryIndexStaysInsideLoadedRange() {
        assertEquals(40, searchViewportCollapseRecoveryIndex(savedIndex = 40, itemCount = 41))
        assertEquals(40, searchViewportCollapseRecoveryIndex(savedIndex = 40, itemCount = 61))
        // 只加载了部分页时夹到已加载范围内，而不是放弃恢复。
        assertEquals(20, searchViewportCollapseRecoveryIndex(savedIndex = 40, itemCount = 21))
        assertEquals(1, searchViewportCollapseRecoveryIndex(savedIndex = 40, itemCount = 2))
    }

    @Test
    fun recoveryIsSkippedWhenNothingCanBeScrolled() {
        assertNull(searchViewportCollapseRecoveryIndex(savedIndex = 40, itemCount = 0))
        assertNull(searchViewportCollapseRecoveryIndex(savedIndex = 40, itemCount = 1))
    }
}
