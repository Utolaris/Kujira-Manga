package com.par9uet.jm.dinner

import com.par9uet.jm.dinner.atom.ArticleHtmlParser
import com.par9uet.jm.dinner.model.ArticleBlock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DinnerRepositoryTest {
    @Test fun `list blocks explicit promotion while retaining unsigned articles without extra detail requests`() = runTest {
        val requests = mutableListOf<String>()
        val repository = DinnerRepository({ path, _ ->
            requests += path
            """{"count":36,"list":[
                {"id":"7","uid":"99","username":"普通上传者","title":"推广","content":"注册 VIP 观看"},
                {"id":"8","uid":"4682009","username":"another","title":"其他推广","content":"风月 AI"},
                {"id":"2178","uid":"10565656","username":"cos","title":"正常文章","content":"正文推荐\n食堂作者：木木火"},
                {"id":"12","uid":"99","username":"cos","title":"无正文的旧记录"}
            ]}"""
        }, { "https://img.example/" }, ArticleHtmlParser())
        val page = repository.list(2)
        assertEquals(listOf(2178, 12), page.items.map { it.id })
        assertEquals(listOf("blogs"), requests)
        assertEquals(4, page.rawItemCount)
        assertEquals(36, page.total)
    }

    @Test fun `direct links cannot bypass the promotion block`() = runTest {
        val repository = DinnerRepository({ _, _ ->
            """{"info":{"id":"2146","uid":"99","title":"推广","content":"<p>注<strong>册</strong> ＶＩＰ</p><p>食堂作者：木木火</p>"}}"""
        }, { "https://img.example/" }, ArticleHtmlParser())
        try {
            repository.article(2146)
            fail("expected promotion block")
        } catch (_: DinnerPromotionBlocked) { }
    }

    @Test fun `body attribution is preserved without separate uploader metadata`() = runTest {
        val repository = DinnerRepository({ _, _ ->
            """{"info":{"id":"2178","uid":"10565656","username":"cos","title":"正常文章","content":"<p>作者：木木火</p>"}}"""
        }, { "https://img.example/" }, ArticleHtmlParser())
        val article = repository.article(2178)
        assertEquals("作者：木木火", (article.blocks.single() as ArticleBlock.Text).runs.joinToString("") { it.text })
    }

    @Test fun `list uses dinner category page and count instead of comic totals or IDs for images`() = runTest {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val repository = DinnerRepository({ path, params ->
            requests += path to params
            """{"count":"25","list":[{"id":"7","title":"故事 &amp; 夜","photo":"/media/blogs/cover.jpg","date":"2026-10-18","content":"作者：折渡"}]}"""
        }, { "https://img.example/" }, ArticleHtmlParser())
        val result = repository.list(2)
        assertEquals("blogs" to mapOf("page" to "2", "blog_type" to "dinner"), requests.single())
        assertEquals(25, result.total)
        assertEquals("故事 & 夜", result.items.single().title)
        assertEquals("https://img.example/media/blogs/cover.jpg", result.items.single().imageUrl)
    }

    @Test fun `detail reads info HTML and resolves actual article images`() = runTest {
        val repository = DinnerRepository({ path, params ->
            assertEquals("blog", path); assertEquals("9", params["id"])
            """{"info":{"title":"文章","content":"<h1>正文</h1><p>文字<img src='//cdn.example/a.jpg'></p><p>食堂作者：DFS</p>"},"related_comics":[]}"""
        }, { "https://img.example/" }, ArticleHtmlParser())
        val result = repository.article(9)
        assertEquals(9, result.id)
        assertEquals(4, result.blocks.size)
        assertEquals("https://cdn.example/a.jpg", result.blocks.filterIsInstance<ArticleBlock.Image>().single().url)
    }
    @Test fun `unsigned articles unknown authors and uploader identities do not restrict access`() = runTest {
        var response = """{"count":12,"list":[
            {"id":1,"username":"木木火","content":"无正文署名"},
            {"id":2,"username":"折渡","content":"提到了食堂作者木木火，但没有署名"},
            {"id":3,"username":"DFS","content":"食堂作者：陌生作者"},
            {"id":4,"username":"推广上传者","content":"正文\n食堂作者：聪明的琪露诺"}
        ]}"""
        val repository = DinnerRepository({ _, _ -> response }, { "" }, ArticleHtmlParser())
        assertEquals(listOf(1, 2, 3, 4), repository.list(1).items.map { it.id })
        response = """{"info":{"username":"木木火","content":"正文没有作者"}}"""
        assertEquals(1, repository.article(1).id)
        response = """{"info":{"username":"木木火","content":"正文没有作者 <a href='https://789free.fun/pRW9Ur'>观看</a>"}}"""
        assertTrue(runCatching { repository.article(1) }.exceptionOrNull() is DinnerPromotionBlocked)
    }

    @Test fun `confirmed game promotion metadata is blocked without treating all game IDs as ads`() = runTest {
        val requests = mutableListOf<String>()
        val repository = DinnerRepository({ path, _ ->
            requests += path
            """{"count":24,"list":[
                {"id":2009,"gid":"1604","game_url":"https://www.hotkrowdplay.site/gr/index.html?ag=18game","content":"游戏介绍"},
                {"id":259,"gid":"253627","game_url":"","content":"正常作品赏析"}
            ]}"""
        }, { "" }, ArticleHtmlParser())
        val page = repository.list(1)
        assertEquals(listOf(259), page.items.map { it.id })
        assertEquals(2, page.rawItemCount)
        assertEquals(24, page.total)
        assertEquals(listOf("blogs"), requests)
    }

    @Test fun `only ambiguous articles request details and successful decisions are cached until body changes`() = runTest {
        val requests = mutableListOf<String>()
        var content = "下载链接请见正文"
        var detail = "<a href='/stray/'>下载链接</a>"
        val repository = DinnerRepository({ path, params ->
            requests += path
            if (path == "blogs") """{"count":36,"list":[
                {"id":1,"content":"正常旧文章"},
                {"id":2,"content":"$content"},
                {"id":3,"content":"cos app下载落地页"}
            ]}""" else {
                assertEquals("2", params["id"])
                """{"info":{"content":"$detail"}}"""
            }
        }, { "" }, ArticleHtmlParser())
        assertEquals(listOf(1, 2), repository.list(1).items.map { it.id })
        assertEquals(listOf(1, 2), repository.list(1).items.map { it.id })
        assertEquals(listOf("blogs", "blog", "blogs"), requests)
        detail = "<a href='https://789free.fun/pRW9Ur'>下载链接</a>"
        // A cached allow never replaces the current HTML check when opening an article.
        assertTrue(runCatching { repository.article(2) }.exceptionOrNull() is DinnerPromotionBlocked)
        content = "下载链接已更换"
        val page = repository.list(1)
        assertEquals(listOf(1), page.items.map { it.id })
        assertEquals(3, page.rawItemCount)
        assertEquals(36, page.total)
        assertEquals(listOf(1), repository.list(1).items.map { it.id })
        assertEquals(listOf("blogs", "blog", "blogs", "blog", "blogs", "blog", "blogs"), requests)
    }

    @Test fun `a failed supplemental check retains the card and retries rather than caching failure`() = runTest {
        var details = 0
        val repository = DinnerRepository({ path, _ ->
            if (path == "blogs") """{"count":12,"list":[{"id":9,"content":"游戏下载网址"}]}"""
            else {
                details++
                if (details == 1) throw java.io.IOException("offline")
                """{"info":{"content":"<a href='https://url365.club/pzx2Yv'>下载链接</a>"}}"""
            }
        }, { "" }, ArticleHtmlParser())
        assertEquals(listOf(9), repository.list(1).items.map { it.id })
        assertTrue(repository.list(1).items.isEmpty())
        assertTrue(repository.list(1).items.isEmpty())
        assertEquals(2, details)
    }

    @Test fun `cancellation of a supplemental request is propagated`() = runTest {
        val repository = DinnerRepository({ path, _ ->
            if (path == "blogs") """{"count":12,"list":[{"id":9,"content":"下载链接"}]}"""
            else throw kotlinx.coroutines.CancellationException("cancelled")
        }, { "" }, ArticleHtmlParser())
        assertTrue(runCatching { repository.list(1) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }

}
