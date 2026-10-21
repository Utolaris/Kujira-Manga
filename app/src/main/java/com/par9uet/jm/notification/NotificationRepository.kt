package com.par9uet.jm.notification

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.par9uet.jm.dinner.atom.ArticleHtmlParser
import com.par9uet.jm.dinner.model.ArticleBlock
import com.par9uet.jm.dinner.model.ArticleText
import com.par9uet.jm.notification.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

interface NotificationSource {
    suspend fun unreadCounts(): NotificationUnreadCounts
    suspend fun list(filter: NotificationFilter, page: Int): InboxPage
    suspend fun markRead(id: String)
    suspend fun markAllRead()
    suspend fun tracking(id: Int): Boolean
    suspend fun toggleTracking(id: Int)
}

/** 收口官方字段和图文解析；追踪更新由服务端生成，无本地轮询。 */
class NotificationRepository(
    private val request: suspend (String, String, Map<String, String>) -> String,
    private val imageHost: () -> String,
    private val parser: ArticleHtmlParser,
) : NotificationSource {
    override suspend fun unreadCounts(): NotificationUnreadCounts {
        val data = JsonParser.parseString(request("GET", "notifications/unreadCount", emptyMap())).asJsonObject
        require(data.has("comic_follow") || data.has("site_notice")) { "未读通知数量格式异常" }
        val tracking = data.get("comic_follow")?.takeUnless { it.isJsonNull }?.asInt ?: 0
        val site = data.get("site_notice")?.takeUnless { it.isJsonNull }?.asInt ?: 0
        require(tracking >= 0 && site >= 0) { "未读通知数量格式异常" }
        return NotificationUnreadCounts(tracking, site)
    }

    override suspend fun list(filter: NotificationFilter, page: Int): InboxPage = withContext(Dispatchers.Default) {
        require(page > 0)
        val data = JsonParser.parseString(request("GET", "notifications", mapOf("type" to filter.apiValue, "page" to "$page")))
        val rows = if (data.isJsonArray) data.asJsonArray else
            requireNotNull(data.asJsonObject.getAsJsonArray("list")) { "通知列表格式异常" }
        val total = if (data.isJsonObject) data.asJsonObject.get("total")?.takeUnless { it.isJsonNull }?.asInt else null
        require(total == null || total >= 0)
        val messages = rows.map { value ->
            val row = value.asJsonObject
            val type = row.string("type")
            val content = row.get("content")
            val updates = content?.takeIf { it.isJsonArray }?.asJsonArray
            val blocks = if (updates != null) updates.flatMap { update ->
                val item = update.asJsonObject
                val comicId = item.string("comicId").toIntOrNull()?.takeIf { it > 0 }
                listOf(ArticleBlock.Text(listOf(ArticleText(item.string("updateDate")))),
                    ArticleBlock.Text(listOf(ArticleText(item.string("comicTitle"), link = comicId?.let { "jm://comic/$it" }))))
            } else parser.parse(content?.takeUnless { it.isJsonNull }?.asString.orEmpty(), imageHost())
            InboxMessage(
                id = row.string("id").also { require(it.isNotBlank()) { "通知编号缺失" } },
                title = Jsoup.parseBodyFragment(row.string("title")).text().ifBlank {
                    if (type == "comic_follow") "漫画追踪更新 · ${updates?.size() ?: 0} 部漫画" else "站内通知"
                },
                date = row.string("date").ifBlank { row.string("time") }.ifBlank { "时间未提供" },
                type = type, read = row.get("read").readFlag(), blocks = blocks,
            )
        }
        InboxPage(messages.filter { filter == NotificationFilter.All || it.type == filter.apiValue }, rows.size() > 0 && (total?.let { page.toLong() * 20 < it } ?: (rows.size() == 20)))
    }

    override suspend fun markRead(id: String) {
        require(id.isNotBlank())
        request("POST", "notifications", mapOf("id" to id, "read" to "1"))
    }

    override suspend fun markAllRead() {
        // 官方只证实单封设为已读。先收集全部页，再写入，避免已读改变列表时漏项。
        val messages = linkedMapOf<String, InboxMessage>()
        var page = 1
        do {
            val result = list(NotificationFilter.All, page++)
            check(result.items.isEmpty() || result.items.any { it.id !in messages }) { "通知接口返回重复页，请重试" }
            result.items.forEach { messages[it.id] = it }
        } while (result.hasMore)
        messages.values.filterNot { it.read }.forEach { markRead(it.id) }
    }

    override suspend fun tracking(id: Int): Boolean {
        require(id > 0)
        val data = JsonParser.parseString(request("GET", "album_sertracking", mapOf("id" to "$id")))
        return when (data.takeIf { it.isJsonPrimitive }?.asString?.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> error("订阅状态格式异常")
        }
    }

    override suspend fun toggleTracking(id: Int) {
        require(id > 0)
        request("POST", "album_sertracking", mapOf("id" to "$id"))
    }

    private fun JsonObject.string(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    private fun JsonElement?.readFlag(): Boolean = when (this?.takeUnless { it.isJsonNull }?.asString?.lowercase()) {
        "true", "1" -> true
        "false", "0", null -> false
        else -> error("通知已读状态格式异常")
    }
}
