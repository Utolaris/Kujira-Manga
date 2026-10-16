package com.par9uet.jm.ui.interaction

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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance

/**
 * 全局列表位置记录。
 *
 * 官方 App 用的是同一套模型（`Hooks/useScrollRestoration`）：每个可滚动页面按自己的
 * key 记下滚动位置，离开再回来维持原位置；详情类页面不记。
 *
 * 这里把 key 收敛成「目的地标识」，首页 / 收藏夹 / 历史浏览 / 搜索结果共用一份：
 * 用户从这些列表里点开一本漫画，返回时一定还在原来的位置，
 * 不会再出现「搜索页进了详情、点了作者、返回就回到顶部」这种丢位置的情况。
 *
 * 位置只在内存里（Activity 生命周期内），与官方把位置放 sessionStorage 的语义一致：
 * 进程重建后回到顶部是符合预期的。
 */
class ListPositionMemory {
    data class Position(val index: Int, val offset: Int)

    private val positions = HashMap<String, Position>()

    @Synchronized
    fun position(key: String): Position? = positions[key]

    @Synchronized
    fun save(key: String, index: Int, offset: Int) {
        positions[key] = Position(index.coerceAtLeast(0), offset.coerceAtLeast(0))
    }

    @Synchronized
    fun clear(key: String) {
        positions.remove(key)
    }

    /** 清掉某一类列表的全部位置（例如账号切换后收藏夹的所有文件夹位置都作废）。 */
    @Synchronized
    fun clearPrefix(prefix: String) {
        positions.keys.removeAll { it.startsWith(prefix) }
    }

    @Synchronized
    fun clearAll() = positions.clear()
}

/** 未显式提供时用的兜底实例：预览 / 独立组合也能正常读写。 */
private val fallbackListPositionMemory = ListPositionMemory()

val LocalListPositionMemory = staticCompositionLocalOf { fallbackListPositionMemory }

/** 历史列表位置：`history`。 */
const val ListPositionKeyHome: String = "home"
const val ListPositionKeyHistory: String = "history"
const val ListPositionKeyFavorites: String = "favorites"
const val ListPositionKeyWeek: String = "week"

/**
 * 搜索结果页的位置 key。
 *
 * 直接用分页源的查询键（排序 / 关键词 / 排除标签 / 年月），与 `matchesQuery` 同一相等语义：
 * 换查询或换排序 = 新 key（没有记忆 → 从顶部开始），同一查询返回则命中记忆。
 * 这样完全不需要 generation 归零那种「换代」逻辑——之前正是它在
 * 「搜索 → 详情 → 点作者 → 返回 → 返回」时被误判成换代，把列表滚回了顶部。
 */
fun searchListPositionKey(queryKey: String): String = "search|$queryKey"

/**
 * 收藏夹列表位置 key。只有「数据集真的换了」才应该换 key：
 * 文件夹、搜索词、筛选条件变化算换了；进入/退出搜索但没输入关键词不算。
 */
fun favoritesListPositionKey(
    folderId: Int,
    searchText: String,
    tags: Collection<String>,
    authors: Collection<String>,
    tagLogic: String,
): String = buildString {
    append("favorites|").append(folderId)
    append('|').append(searchText)
    append('|').append(tags.sorted().joinToString(","))
    append('|').append(authors.sorted().joinToString(","))
    append('|').append(tagLogic)
}

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
