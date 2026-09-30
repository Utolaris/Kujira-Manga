package com.par9uet.jm.favorites.data

import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import com.par9uet.jm.data.models.TagFilterLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收藏分页 SQL 的契约：范围永远收在单一账号 + 单一文件夹内，屏蔽标签永远排除，
 * `?` 的绑定顺序必须与子句出现顺序一致（错位不会编译失败，只会静默查出错的行）。
 */
class FavoriteQueriesTest {

    /** 记录 `?` 的绑定顺序与类型。Int 会被 SimpleSQLiteQuery 转成 Long 绑定。 */
    private class RecordingProgram : SupportSQLiteProgram {
        val args = mutableListOf<Any?>()
        override fun bindNull(index: Int) {
            args += null
        }

        override fun bindLong(index: Int, value: Long) {
            args += value
        }

        override fun bindDouble(index: Int, value: Double) {
            args += value
        }

        override fun bindString(index: Int, value: String) {
            args += value
        }

        override fun bindBlob(index: Int, value: ByteArray) {
            args += value
        }

        override fun clearBindings() {
            args.clear()
        }

        override fun close() = Unit
    }

    private fun SupportSQLiteQuery.boundArgs(): List<Any?> =
        RecordingProgram().also { bindTo(it) }.args

    private val existsOnTags = Regex(
        """EXISTS \(SELECT 1 FROM favorite_metadata_terms t WHERE""",
    )
    private val existsOnAuthors = Regex(
        """EXISTS \(SELECT 1 FROM favorite_metadata_terms a WHERE""",
    )

    @Test
    fun `every query is scoped to one account and one folder and dedupes rows`() {
        val query = buildFavoritePagingQuery(
            accountId = 7,
            blockedTagList = emptyList(),
            searchText = "",
            selectedTags = emptySet(),
            selectedAuthors = emptySet(),
            folderId = 3,
            tagLogic = TagFilterLogic.AND,
        )

        assertEquals(listOf<Any?>(7L, 3L), query.boundArgs())
        assertTrue(query.sql.contains("c.accountId = ?"))
        assertTrue(query.sql.contains("m.folderId = ?"))
        // GROUP BY 是「重复行撞 LazyGrid key 直接抛异常」的唯一防线。
        assertTrue(query.sql.contains("GROUP BY c.accountId, c.albumId"))
        assertTrue(query.sql.contains("ORDER BY MIN(m.remoteOrder) ASC, c.albumId ASC"))
    }

    @Test
    fun `blocked tags are lowercased deduped and always excluded`() {
        val query = buildFavoritePagingQuery(
            accountId = 7,
            blockedTagList = listOf(" Blocked ", "", "blocked", "  "),
            searchText = "",
            selectedTags = emptySet(),
            selectedAuthors = emptySet(),
            folderId = 0,
            tagLogic = TagFilterLogic.AND,
        )

        // 归一化后只剩一个标签 -> 一个 NOT EXISTS，参数是 (termType, value)。
        assertEquals(listOf<Any?>(7L, 0L, FAVORITE_TERM_TAG, "blocked"), query.boundArgs())
        assertEquals(1, Regex("NOT EXISTS \\(SELECT 1 FROM favorite_metadata_terms b").findAll(query.sql).count())
    }

    @Test
    fun `search matches the title or any term and lowercases the pattern`() {
        val query = buildFavoritePagingQuery(
            accountId = 7,
            blockedTagList = emptyList(),
            searchText = "  Query  ",
            selectedTags = emptySet(),
            selectedAuthors = emptySet(),
            folderId = 0,
            tagLogic = TagFilterLogic.AND,
        )

        assertEquals(listOf<Any?>(7L, 0L, "%query%", "%query%"), query.boundArgs())
        assertTrue(query.sql.contains("LOWER(c.title) LIKE ?"))
        assertTrue(query.sql.contains("s.normalizedValue LIKE ?"))
    }

    @Test
    fun `AND requires every selected tag as its own clause`() {
        val query = buildFavoritePagingQuery(
            accountId = 7,
            blockedTagList = emptyList(),
            searchText = "",
            selectedTags = setOf(" A ", "b"),
            selectedAuthors = emptySet(),
            folderId = 0,
            tagLogic = TagFilterLogic.AND,
        )

        assertEquals(listOf<Any?>(7L, 0L, "a", "b"), query.boundArgs())
        assertEquals(2, existsOnTags.findAll(query.sql).count())
        assertFalse("AND 不应把标签拼成 OR：" + query.sql, query.sql.contains(" OR "))
    }

    @Test
    fun `OR joins the selected tags into one parenthesised clause`() {
        val query = buildFavoritePagingQuery(
            accountId = 7,
            blockedTagList = emptyList(),
            searchText = "",
            selectedTags = setOf("a", "b"),
            selectedAuthors = emptySet(),
            folderId = 0,
            tagLogic = TagFilterLogic.OR,
        )

        assertEquals(listOf<Any?>(7L, 0L, "a", "b"), query.boundArgs())
        assertEquals(2, existsOnTags.findAll(query.sql).count())
        assertTrue("OR 分支必须把子句括起来：" + query.sql, query.sql.contains(") AND ") || query.sql.contains("))"))
        assertTrue(query.sql.contains(" OR "))
    }

    @Test
    fun `NOT negates every selected tag`() {
        val query = buildFavoritePagingQuery(
            accountId = 7,
            blockedTagList = emptyList(),
            searchText = "",
            selectedTags = setOf("a", "b"),
            selectedAuthors = emptySet(),
            folderId = 0,
            tagLogic = TagFilterLogic.NOT,
        )

        assertEquals(listOf<Any?>(7L, 0L, "a", "b"), query.boundArgs())
        assertEquals(
            2,
            Regex("""NOT EXISTS \(SELECT 1 FROM favorite_metadata_terms t WHERE""")
                .findAll(query.sql).count(),
        )
    }

    @Test
    fun `selected authors are always an OR set on their own term type`() {
        val query = buildFavoritePagingQuery(
            accountId = 7,
            blockedTagList = emptyList(),
            searchText = "",
            selectedTags = emptySet(),
            selectedAuthors = setOf(" Author "),
            folderId = 0,
            tagLogic = TagFilterLogic.AND,
        )

        assertEquals(listOf<Any?>(7L, 0L, "author"), query.boundArgs())
        assertEquals(1, existsOnAuthors.findAll(query.sql).count())
        assertTrue(query.sql.contains("'$FAVORITE_TERM_AUTHOR'"))
    }

    @Test
    fun `binding order follows the clause order when every filter is active`() {
        val query = buildFavoritePagingQuery(
            accountId = 7,
            blockedTagList = listOf("blocked"),
            searchText = "Query",
            selectedTags = setOf("tag1"),
            selectedAuthors = setOf("author1"),
            folderId = 3,
            tagLogic = TagFilterLogic.AND,
        )

        // 顺序即契约：account, folder, search×2, blocked(类型,值), tag, author。
        assertEquals(
            listOf<Any?>(7L, 3L, "%query%", "%query%", FAVORITE_TERM_TAG, "blocked", "tag1", "author1"),
            query.boundArgs(),
        )
        assertEquals(query.boundArgs().size, query.argCount)
    }

    @Test
    fun `blank selections add no clauses`() {
        val query = buildFavoritePagingQuery(
            accountId = 7,
            blockedTagList = emptyList(),
            searchText = "   ",
            selectedTags = setOf("  "),
            selectedAuthors = setOf(""),
            folderId = 0,
            tagLogic = TagFilterLogic.OR,
        )

        assertEquals(listOf<Any?>(7L, 0L), query.boundArgs())
        assertEquals(0, existsOnTags.findAll(query.sql).count())
        assertEquals(0, existsOnAuthors.findAll(query.sql).count())
    }
}
