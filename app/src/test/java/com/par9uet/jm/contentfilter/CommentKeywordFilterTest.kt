package com.par9uet.jm.contentfilter

import com.par9uet.jm.data.models.Comment
import com.par9uet.jm.data.models.DEFAULT_COMMENT_FILTER_KEYWORDS
import org.junit.Assert.*
import org.junit.Test

class CommentKeywordFilterTest {
    @Test fun `any default keyword is enough and Latin matching ignores case`() {
        val filter = CommentKeywordFilter(DEFAULT_COMMENT_FILTER_KEYWORDS)
        DEFAULT_COMMENT_FILTER_KEYWORDS.forEach { assertTrue(filter.matches("前文${it}后文")) }
        assertTrue(filter.matches("注册 vip 会员"))
        assertFalse(filter.matches("这章很好看"))
    }

    @Test fun `match rendered HTML and entities without matching invisible attributes`() {
        val filter = CommentKeywordFilter(listOf("VIP", "加群"))
        assertTrue(filter.matches("<b>V</b>IP会员"))
        assertTrue(filter.matches("加&#x7fa4;一起聊"))
        assertFalse(filter.matches("<a href='https://vip.example'>查看漫画</a>"))
    }

    @Test fun `blank keywords do not hide everything and normalization trims deduplicates`() {
        assertEquals(listOf("VIP", "可约"), normalizeCommentKeywords(listOf(" VIP ", "vip", "", "  ", "可约")))
        assertFalse(CommentKeywordFilter(listOf(" ", "")).matches("任意评论"))
        assertFalse(CommentKeywordFilter(emptyList()).matches("加群VIP"))
    }

    @Test fun `remove matching replies at every depth without hiding a clean parent or changing metadata`() {
        val clean = comment(1, "喜欢这本").copy(username = "VIP", nickname = "加群", replyCommentList = listOf(
            comment(2, "加群"),
            comment(3, "谢谢分享").copy(replyCommentList = listOf(comment(4, "可约"), comment(5, "赞"))),
        ))
        val filter = CommentKeywordFilter(DEFAULT_COMMENT_FILTER_KEYWORDS)
        assertFalse(filter.matches(clean.content))
        val filtered = filter.filterReplies(clean)
        assertEquals(clean.copy(replyCommentList = listOf(clean.replyCommentList[1].copy(
            replyCommentList = listOf(comment(5, "赞")),
        ))), filtered)
        assertSame(clean, CommentKeywordFilter(emptyList()).filterReplies(clean))
        assertEquals(2, clean.replyCommentList.size)
    }

    private fun comment(id: Int, content: String) = Comment(
        1, 10, id, "2026-10-19", content, "reader", "", "", 0, false, emptyList(),
    )
}
