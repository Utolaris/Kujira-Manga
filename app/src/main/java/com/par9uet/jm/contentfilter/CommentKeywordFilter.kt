package com.par9uet.jm.contentfilter

import androidx.paging.PagingData
import androidx.paging.filter
import androidx.paging.map
import com.par9uet.jm.data.models.Comment
import org.jsoup.Jsoup

fun normalizeCommentKeywords(keywords: List<String>): List<String> = keywords
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinctBy { it.lowercase() }

/** Match visible body text only: HTML attributes and author names are not comment content. */
class CommentKeywordFilter(keywords: List<String>) {
    private val keywords = normalizeCommentKeywords(keywords)

    fun matches(content: String): Boolean {
        if (keywords.isEmpty()) return false
        val text = Jsoup.parseBodyFragment(content).text()
        return keywords.any { text.contains(it, ignoreCase = true) }
    }

    fun filterReplies(comment: Comment): Comment {
        if (keywords.isEmpty() || comment.replyCommentList.isEmpty()) return comment
        val replies = comment.replyCommentList.filterNot { matches(it.content) }.map(::filterReplies)
        return if (replies == comment.replyCommentList) comment else comment.copy(replyCommentList = replies)
    }
}

/** Filter after the raw pager cache so editing keywords can also restore previously hidden rows. */
fun PagingData<Comment>.filterBlockedComments(keywords: List<String>): PagingData<Comment> {
    if (keywords.isEmpty()) return this
    val matcher = CommentKeywordFilter(keywords)
    return filter { !matcher.matches(it.content) }.map(matcher::filterReplies)
}
