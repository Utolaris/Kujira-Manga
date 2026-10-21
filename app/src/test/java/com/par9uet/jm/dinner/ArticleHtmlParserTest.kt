package com.par9uet.jm.dinner

import com.par9uet.jm.dinner.atom.ArticleHtmlParser
import com.par9uet.jm.dinner.atom.safeArticleUrl
import com.par9uet.jm.dinner.model.ArticleBlock
import org.junit.Assert.*
import org.junit.Test

class ArticleHtmlParserTest {
    private val parser = ArticleHtmlParser()
    private val base = "https://images.example/"

    @Test fun `mixed paragraphs preserve text on both sides of lazy images in source order`() {
        val result = parser.parse("""<p>前文<strong>加粗</strong><img src="data:image/gif;base64,x" data-src="/media/blogs/1.jpg" alt="图一">后文<a href="/help">链接</a></p>""", base)
        assertEquals(3, result.size)
        val first = result[0] as ArticleBlock.Text
        assertEquals("前文加粗", first.runs.joinToString("") { it.text })
        assertTrue(first.runs.last().bold)
        assertEquals(ArticleBlock.Image("https://images.example/media/blogs/1.jpg", "图一"), result[1])
        val last = result[2] as ArticleBlock.Text
        assertEquals("后文链接", last.runs.joinToString("") { it.text })
        assertEquals("https://images.example/help", last.runs.last().link)
    }

    @Test fun `unsafe content is removed while entities lists quotes code and tables remain readable`() {
        val result = parser.parse("""<script>bad()</script><style>bad css</style><h2>标题&amp;实体</h2><blockquote><p>引用</p></blockquote><ol start="3"><li><p>第一条</p></li><li>第二条</li></ol><pre><code>&lt;x&gt;</code></pre><table><tr><th>列</th></tr><tr><td>值</td></tr></table><p><a href="javascript:alert(1)">安全文字</a><img src="file:///secret"></p>""", base)
        val texts = result.filterIsInstance<ArticleBlock.Text>()
        assertEquals("标题&实体", texts[0].runs.joinToString("") { it.text })
        assertEquals(2, texts[0].heading)
        assertTrue(texts[1].quote)
        assertEquals("3. 第一条", texts[2].runs.joinToString("") { it.text })
        assertEquals("4. 第二条", texts[3].runs.joinToString("") { it.text })
        assertNull(texts.last().runs.last().link)
        assertEquals("<x>", result.filterIsInstance<ArticleBlock.Code>().single().text)
        assertEquals(listOf(listOf("列"), listOf("值")), result.filterIsInstance<ArticleBlock.Table>().single().rows)
        assertTrue(result.none { it is ArticleBlock.Image })
    }

    @Test fun `image URLs handle protocol relative paths and reject active or local schemes`() {
        assertEquals("https://cdn.example/a.jpg", safeArticleUrl("//cdn.example/a.jpg", base))
        assertEquals("https://images.example/media/a.jpg", safeArticleUrl("media/a.jpg", base))
        for (url in listOf("javascript:alert(1)", "data:image/png,x", "file:///a", "content://private/a", "#local", "")) {
            assertNull(url, safeArticleUrl(url, base))
        }
    }
}
