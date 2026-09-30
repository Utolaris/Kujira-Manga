package com.par9uet.jm.favorites.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 标签/作者的归一化与分词：搜索与内容屏蔽都走 `normalizedValue`，
 * 少一次 lowercase 就会让非中文标签的搜索与排除静默失效。
 */
class FavoriteMappersTest {

    @Test
    fun `normalized trims drops blanks and removes duplicates`() {
        assertEquals(
            listOf("a", "b"),
            listOf(" a ", "", "   ", "a", "b").normalized(),
        )
        assertEquals(emptyList<String>(), listOf("", "  ").normalized())
    }

    @Test
    fun `terms collapse case variants into one lowercased row per term type`() {
        val terms = buildTerms(
            accountId = 7,
            albumId = 11,
            tags = listOf(" Tag ", "tag"),
            authors = listOf("Author"),
        )

        // 同一个标签的两种大小写共用一个 `termType:normalizedValue` key。
        val tags = terms.filter { it.termType == FAVORITE_TERM_TAG }
        assertEquals(1, tags.size)
        assertEquals("tag", tags.single().normalizedValue)
        assertEquals(7, tags.single().accountId)
        assertEquals(11, tags.single().albumId)

        val authors = terms.filter { it.termType == FAVORITE_TERM_AUTHOR }
        assertEquals(listOf("author"), authors.map { it.normalizedValue })
    }

    @Test
    fun `remote items without tags fall back to their category titles`() {
        val item = FavoriteRemoteItem(
            albumId = 11,
            title = "comic",
            tags = emptyList(),
            categoryTitle = "单行本",
            subCategoryTitle = "恋爱",
        )

        val tags = item.toTerms(accountId = 7).filter { it.termType == FAVORITE_TERM_TAG }

        assertEquals(listOf("单行本", "恋爱"), tags.map { it.value })
    }
}
