package com.par9uet.jm.ui.screens

import com.par9uet.jm.ui.models.ListPositionMemory
import com.par9uet.jm.ui.interaction.restoreTargetIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「从详情返回列表偶发直接回到顶部」的回归防线。
 *
 * 成因形态：`LazyGridState` 的索引被一次布局夹进 `0 until itemCount`——目标页就位前
 * 网格先以少量条目（甚至 0 项）布局过一帧，40 就被夹成 0，而且那次夹取会永久生效。
 *
 * 现在恢复流程不再「等一帧就收工」：它只在 [restoreTargetIndex] 给出目标时落位，
 * 并隔帧确认后重试若干次。所以这个纯判据就是整条恢复流程的全部门槛，
 * 它必须同时满足两点——位置不会丢（等分页到位），也不会把「夹到末尾」写回记忆。
 */
class SearchViewportCollapseTest {

    private fun position(index: Int, offset: Int = 0) = ListPositionMemory.Position(index, offset)

    @Test
    fun targetBeyondLoadedPagesWaitsInsteadOfFallingBackToTop() {
        // 用户离开时在 40，回来只加载了首页 20 条：必须等，不能落位到顶部。
        assertNull(
            restoreTargetIndex(
                wanted = position(40),
                itemCount = 20,
                appendComplete = false,
            ),
        )
        // 一条都还没有时同理。
        assertNull(restoreTargetIndex(wanted = position(40), itemCount = 0, appendComplete = false))
    }

    @Test
    fun targetIsClampedIntoLoadedRangeOnceLoadingFinished() {
        // 分页确认到底了：目标页不会再来，夹进已加载范围，而不是放弃恢复回到顶部。
        assertEquals(20, restoreTargetIndex(position(40), itemCount = 21, appendComplete = true))
        assertEquals(40, restoreTargetIndex(position(40), itemCount = 41, appendComplete = false))
        assertEquals(40, restoreTargetIndex(position(40), itemCount = 61, appendComplete = false))
    }

    @Test
    fun targetStillInLaterPagesKeepsWaiting() {
        // 目标条目还在后面的分页里：必须等。既不退回顶部，也不夹到当前末尾
        // （夹到末尾后一旦落位，就会把那个中间值当成用户位置写进记录）。
        assertNull(restoreTargetIndex(position(40), itemCount = 21, appendComplete = false))
        assertNull(restoreTargetIndex(position(40), itemCount = 40, appendComplete = false))
    }

    @Test
    fun unreachableTargetNeverClampsToTheVeryTop() {
        // itemCount == 1 时夹取结果是 0，但只有当列表确实到底才允许——否则会变成「回到顶部」。
        assertEquals(0, restoreTargetIndex(position(40), itemCount = 1, appendComplete = true))
        assertNull(restoreTargetIndex(position(40), itemCount = 1, appendComplete = false))
    }

    @Test
    fun noSavedPositionMeansNoRestore() {
        assertNull(restoreTargetIndex(wanted = null, itemCount = 40, appendComplete = false))
    }
}
