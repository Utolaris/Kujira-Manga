package com.par9uet.jm.ui.models

/**
 * 列表滚动位置的进程内存储与 key 约定。
 *
 * 只有数据，不 import Compose：ui/interaction 负责读写状态的组合函数，
 * L2（ui/viewModel、favorites/presentation）只清位置，不得反向依赖 L1 组合层。
 */

class ListPositionMemory {
    data class Position(val index: Int, val offset: Int)

    private val positions = HashMap<String, Position>()
    // Opaque Compose Saver payload: per-lane indices/offsets, never a state, View or composition.
    private val staggeredPositions = HashMap<String, Any>()

    @Synchronized
    fun staggeredPosition(key: String): Any? = staggeredPositions[key]

    @Synchronized
    fun saveStaggered(key: String, position: Any) { staggeredPositions[key] = position }

    @Synchronized
    fun position(key: String): Position? = positions[key]

    @Synchronized
    fun save(key: String, index: Int, offset: Int) {
        positions[key] = Position(index.coerceAtLeast(0), offset.coerceAtLeast(0))
    }

    @Synchronized
    fun clear(key: String) {
        positions.remove(key)
        staggeredPositions.remove(key)
    }

    /** 清掉某一类列表的全部位置（例如账号切换后收藏夹的所有文件夹位置都作废）。 */
    @Synchronized
    fun clearPrefix(prefix: String) {
        positions.keys.removeAll { it.startsWith(prefix) }
        staggeredPositions.keys.removeAll { it.startsWith(prefix) }
    }
}

/** 首页 / 历史 / 收藏夹 / 周推荐 / 食堂列表的位置 key。 */
const val ListPositionKeyHome: String = "home"
const val ListPositionKeyHistory: String = "history"
const val ListPositionKeyFavorites: String = "favorites"
const val ListPositionKeyWeek: String = "week"
const val ListPositionKeyDinner: String = "dinner"

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
