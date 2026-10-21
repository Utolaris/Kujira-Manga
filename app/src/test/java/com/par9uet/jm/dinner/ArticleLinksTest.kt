package com.par9uet.jm.dinner

import com.par9uet.jm.dinner.atom.ArticleHtmlParser
import com.par9uet.jm.dinner.atom.normalizeArticleLink
import com.par9uet.jm.dinner.model.ArticleBlock
import com.par9uet.jm.ui.navigation.articleNavigationRoute
import org.junit.Assert.*
import org.junit.Test

class ArticleLinksTest {
    @Test fun `comic portal URLs and JM ids route to existing comic details`() {
        for (link in listOf("JM483322", "jm 483322", "https://18comic.vip/album/483322/", "/photo/483322", "https://example.com/comic/detail?id=483322")) {
            assertEquals(link, "jm://comic/483322", normalizeArticleLink(link))
            assertEquals("comicDetail/483322", articleNavigationRoute(normalizeArticleLink(link)!!))
        }
    }

    @Test fun `blog URLs with arbitrary domains trailing slashes or queries stay in app`() {
        for (link in listOf("blog/2177", "/blog/2177/", "https://example.com/blog/2177?utm_source=test", "https://example.com/blogs/detail?tab=1&id=2177")) {
            assertEquals(link, "jm://article/2177", normalizeArticleLink(link))
            assertEquals("dinnerArticle/2177", articleNavigationRoute(normalizeArticleLink(link)!!))
        }
    }

    @Test fun `plain and split-span portals become clickable while style is kept`() {
        val result = ArticleHtmlParser().parse("""<p><b>JM</b><span>483322</span>，传送门：1243239。文章 https://example.com/blog/2177 和 blog/2210</p>""", "https://img.example/")
        val runs = (result.single() as ArticleBlock.Text).runs
        assertEquals("JM483322，传送门：1243239。文章 https://example.com/blog/2177 和 blog/2210", runs.joinToString("") { it.text })
        assertTrue(runs.first().bold)
        assertEquals("jm://comic/483322", runs.first().link)
        assertTrue(runs.any { it.text == "483322" && it.link == "jm://comic/483322" })
        assertTrue(runs.any { it.link == "jm://comic/1243239" })
        assertTrue(runs.any { it.link == "jm://article/2177" })
        assertTrue(runs.any { it.link == "jm://article/2210" })
    }

    @Test fun `ordinary numbers stay plain and linked comic images retain their destinations`() {
        val result = ArticleHtmlParser().parse("""<p>2026年7月8日 123456</p><a href='/album/483322/'><img src='/media/albums/483322_3x4.jpg'></a>""", "https://img.example/")
        assertTrue((result[0] as ArticleBlock.Text).runs.all { it.link == null })
        assertEquals("jm://comic/483322", (result[1] as ArticleBlock.Image).link)
        assertNull(normalizeArticleLink("javascript:alert(1)"))
        assertNull(normalizeArticleLink("file:///private"))
        assertNull(articleNavigationRoute("jm://comic/-1"))
        assertNull(articleNavigationRoute("https://example.com"))
    }
}
