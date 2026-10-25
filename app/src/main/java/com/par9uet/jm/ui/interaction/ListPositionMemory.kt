package com.par9uet.jm.ui.interaction

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import com.par9uet.jm.ui.models.ListPositionMemory
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance

/** 未显式提供时用的兜底实例：预览 / 独立组合也能正常读写。 */
private val fallbackListPositionMemory = ListPositionMemory()

val LocalListPositionMemory = staticCompositionLocalOf { fallbackListPositionMemory }

/**
 * 记住 [key] 对应列表的滚动位置。
 *
 * 三件事：
 * 1. 首帧用记忆值构造状态，不产生可见回跳；
 * 2. 内容尚未到位时等待，到位后一次性滚过去（用户一旦拖动立即放弃自动恢复）；
 * 3. 用户滚动时持续写回记忆（恢复完成前不写，避免把中间态当成用户位置）。
 *
 * [key] 变化会重建滚动状态——这正是「换查询/换筛选从顶部开始」的实现方式，
 * 不需要额外的 reset 代际。
 */
@Composable
fun rememberRestoringGridState(
    key: String,
    itemCount: Int,
    appendComplete: Boolean = true,
    memory: ListPositionMemory = LocalListPositionMemory.current,
): LazyGridState {
    val target = remember(key) { memory.position(key) }
    val state = remember(key, target) {
        LazyGridState(
            firstVisibleItemIndex = target?.index ?: 0,
            firstVisibleItemScrollOffset = target?.offset ?: 0,
        )
    }
    var restorePending by remember(key) {
        mutableStateOf((target?.index ?: 0) > 0 || (target?.offset ?: 0) > 0)
    }
    var takenOverByUser by remember(key) { mutableStateOf(false) }

    // 真实手势才算接管：程序化 scrollToItem 不会产生 DragInteraction。
    LaunchedEffect(state) {
        state.interactionSource.interactions
            .filterIsInstance<DragInteraction.Start>()
            .collect { takenOverByUser = true }
    }

    LaunchedEffect(key, state, itemCount, appendComplete, restorePending) {
        if (!restorePending || takenOverByUser) return@LaunchedEffect
        val targetIndex = restoreTargetIndex(target, itemCount, appendComplete) ?: return@LaunchedEffect
        val wanted = target ?: return@LaunchedEffect

        if (state.firstVisibleItemIndex != targetIndex ||
            state.firstVisibleItemScrollOffset != wanted.offset
        ) {
            state.scrollToItem(targetIndex, wanted.offset)
        }
        // 布局可能在同一帧里把索引夹回去（目标页就位前先以少量条目布局过），
        // 所以隔帧确认一次，最多补 3 次，之后把控制权交给用户。
        var attempts = 0
        while (attempts < MAX_RESTORE_ATTEMPTS && !takenOverByUser) {
            withFrameNanos { }
            if (state.firstVisibleItemIndex == targetIndex &&
                state.firstVisibleItemScrollOffset == wanted.offset
            ) {
                break
            }
            if (itemCount <= targetIndex) break
            state.scrollToItem(targetIndex, wanted.offset)
            attempts++
        }
        restorePending = false
    }

    LaunchedEffect(key, state, restorePending, takenOverByUser) {
        val persistenceAllowed = !restorePending || takenOverByUser
        if (!persistenceAllowed) return@LaunchedEffect
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) -> memory.save(key, index, offset) }
    }

    return state
}

private const val MAX_RESTORE_ATTEMPTS = 3

/**
 * 恢复流程的唯一判据：目标滚动位置现在能不能落位？
 *
 * `null` = 还不行，必须继续等。两种情况要区分开：
 * - 目标条目在**后面的分页里**（`itemCount <= index` 且列表还没加载完）→ 等它到位，
 *   绝不能退回顶部，也不能夹到当前最后一项（那会把「位置跑到末尾」写回记忆）；
 * - 目标条目已经加载进来（或列表已到底）→ 夹进已加载范围后即可落位。
 *
 * 之所以单独抽出来：`LazyGridState` 会把索引夹进 `0 until itemCount`，
 * 而「先以少量条目布局过一帧」正是历史上偶发回到顶部的原因，
 * 这里用纯函数把判据固定住，方便回归测试。
 */
internal fun restoreTargetIndex(
    wanted: ListPositionMemory.Position?,
    itemCount: Int,
    appendComplete: Boolean,
): Int? {
    if (wanted == null || itemCount <= 0) return null
    if (!appendComplete && itemCount <= wanted.index) return null
    return wanted.index.coerceAtMost(itemCount - 1)
}

/** Static settings pages restore immediately, using the same process memory as the comic grids. */
@Composable
fun rememberRestoringScrollState(
    key: String,
    memory: ListPositionMemory = LocalListPositionMemory.current,
): ScrollState {
    val state = remember(key, memory) { ScrollState(memory.position(key)?.offset ?: 0) }
    LaunchedEffect(key, memory, state) {
        snapshotFlow { state.value }.collect { memory.save(key, 0, it) }
    }
    DisposableEffect(key, memory, state) {
        onDispose { memory.save(key, 0, state.value) }
    }
    return state
}

@Composable
fun rememberRestoringListState(
    key: String,
    memory: ListPositionMemory = LocalListPositionMemory.current,
): LazyListState {
    val state = remember(key, memory) {
        val position = memory.position(key)
        LazyListState(position?.index ?: 0, position?.offset ?: 0)
    }
    LaunchedEffect(key, memory, state) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> memory.save(key, index, offset) }
    }
    DisposableEffect(key, memory, state) {
        onDispose { memory.save(key, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) }
    }
    return state
}
