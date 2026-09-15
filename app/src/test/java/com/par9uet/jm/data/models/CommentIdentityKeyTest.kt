package com.par9uet.jm.data.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * `Comment.identityKey` 是详情评论区、我的评论以及两边分页去重器共用的唯一身份。
 * 它同时充当 LazyList/LazyGrid 的 key（重复即抛 IllegalArgumentException），
 * 所以这里的每条断言都对应一个真实崩溃/丢数据的场景。
 */
class CommentIdentityKeyTest {

    private fun comment(
        id: Int,
        comicId: Int = 100,
        time: String = "2026-09-15",
        content: String = "正文",
        sourceChapterId: String = "",
    ) = Comment(
        userId = 1,
        comicId = comicId,
        id = id,
        time = time,
        content = content,
        username = "u",
        nickname = "n",
        avatar = "",
        parentId = 0,
        spoiler = false,
        replyCommentList = emptyList(),
        sourceComicName = "书",
        sourceChapterId = sourceChapterId,
        sourceBlogId = sourceChapterId,
    )

    @Test
    fun `CID 存在时身份就是 CID`() {
        val a = comment(id = 536299, time = "3小时前")
        val b = comment(id = 536299, time = "4小时前", content = "正文改写")

        assertEquals(536299, a.identityKey)
        // 相对时间会随刷新变化、正文可能被编辑；同一条评论的身份不能跟着变。
        assertEquals(a.identityKey, b.identityKey)
    }

    @Test
    fun `同一条评论跨分页重复出现时身份相同`() {
        val pageOne = comment(id = 510795, comicId = 42)
        val pageTwo = comment(id = 510795, comicId = 42, time = "昨天")

        assertEquals(pageOne.identityKey, pageTwo.identityKey)
    }

    @Test
    fun `不同 CID 身份不同`() {
        assertNotEquals(comment(id = 1).identityKey, comment(id = 2).identityKey)
    }

    @Test
    fun `CID 缺失（0）时退回内容组合而不是全部塌成同一个身份`() {
        val first = comment(id = 0, content = "第一条", time = "刚刚")
        val second = comment(id = 0, content = "第二条", time = "刚刚")

        assertNotEquals(first.identityKey, second.identityKey)
        // 内容不同但其余相同，必须仍然是两条。
        assertEquals(2, listOf(first, second).distinctBy { it.identityKey }.size)
    }

    @Test
    fun `CID 缺失时身份包含漫画与章节维度`() {
        val onComicA = comment(id = 0, comicId = 1, sourceChapterId = "10")
        val onComicB = comment(id = 0, comicId = 2, sourceChapterId = "10")
        val onChapterB = comment(id = 0, comicId = 1, sourceChapterId = "11")

        assertNotEquals(onComicA.identityKey, onComicB.identityKey)
        assertNotEquals(onComicA.identityKey, onChapterB.identityKey)
    }

    @Test
    fun `CID 缺失且内容完全相同才视为同一条`() {
        val a = comment(id = 0, comicId = 7, time = "昨天", content = "重复内容")
        val b = comment(id = 0, comicId = 7, time = "昨天", content = "重复内容")

        assertEquals(a.identityKey, b.identityKey)
        // 同一页里出现两次时，UI 的 key 会由网格补上 `#$index` 后缀兜住；
        // 分页去重器则靠这里把它们收敛成一条。
        assertEquals(1, listOf(a, b).distinctBy { it.identityKey }.size)
    }
}
