package com.par9uet.jm.notification.model

import com.par9uet.jm.dinner.model.ArticleBlock
import com.par9uet.jm.session.UserSessionSnapshot
import kotlinx.coroutines.flow.StateFlow

data class NotificationUnreadCounts(val tracking: Int = 0, val site: Int = 0) {
    val hasUnread: Boolean get() = tracking > 0 || site > 0
}

interface NotificationUnreadStatus { val hasUnread: StateFlow<Boolean> }
interface NotificationUnreadUpdates {
    fun read(owner: UserSessionSnapshot, id: String, type: String)
    fun allRead(owner: UserSessionSnapshot)
}

enum class NotificationFilter(val apiValue: String, val label: String) {
    All("all", "全部通知"), Tracking("comic_follow", "漫画追踪"), Site("site_notice", "站内通知"),
}

data class InboxMessage(
    val id: String,
    val title: String,
    val date: String,
    val type: String,
    val read: Boolean,
    val blocks: List<ArticleBlock>,
)
data class InboxPage(val items: List<InboxMessage>, val hasMore: Boolean)
