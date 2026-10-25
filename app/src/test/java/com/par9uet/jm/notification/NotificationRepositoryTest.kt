package com.par9uet.jm.notification

import com.par9uet.jm.dinner.atom.ArticleHtmlParser
import com.par9uet.jm.dinner.model.ArticleBlock
import com.par9uet.jm.notification.model.NotificationFilter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NotificationRepositoryTest {
    @Test fun `unread counts use the lightweight official endpoint and accept numeric strings`() = runTest {
        var response = """{"comic_follow":"2","site_notice":1}"""
        val repository = NotificationRepository({ method, path, params ->
            assertEquals("GET", method)
            assertEquals("notifications/unreadCount", path)
            assertTrue(params.isEmpty())
            response
        }, { "" }, ArticleHtmlParser())
        assertEquals(com.par9uet.jm.notification.model.NotificationUnreadCounts(2, 1), repository.unreadCounts())
        response = """{"site_notice":0}"""
        assertFalse(repository.unreadCounts().hasUnread)
        for (invalid in listOf("{}", "[]", "null", """{"site_notice":-1}""", """{"site_notice":"invalid"}""")) {
            response = invalid
            assertTrue("Invalid counts must fail parsing: $invalid", runCatching { repository.unreadCounts() }.exceptionOrNull() is Exception)
        }
    }

    @Test fun `official notices and tracking arrays render as native article blocks`() = runTest {
        val requests = mutableListOf<Map<String, String>>()
        val repository = NotificationRepository({ method, path, params ->
            assertEquals("GET", method); assertEquals("notifications", path); requests += params
            """{"total":2,"list":[
                {"id":"a","title":"维护公告","date":"2026-10-19 08:30","type":"site_notice","read":1,"content":"<p>公告 <b>内容</b></p><img src='/media/news.jpg'><script>bad()</script>"},
                {"id":"b","title":"","date":"2026-10-19","type":"comic_follow","read":"0","content":[{"comicId":"12345","comicTitle":"更新漫画","updateDate":"2026-10-18"}]}
            ]}"""
        }, { "https://image.test" }, ArticleHtmlParser())
        val page = repository.list(NotificationFilter.All, 1)
        assertFalse(page.hasMore)
        assertTrue(page.items[0].read)
        assertFalse(page.items[1].read)
        assertEquals("维护公告", page.items[0].title)
        assertEquals("2026-10-19 08:30", page.items[0].date)
        assertTrue(page.items[0].blocks.any { it is ArticleBlock.Image && it.url == "https://image.test/media/news.jpg" })
        assertFalse(page.items[0].blocks.toString().contains("bad()"))
        val text = page.items[1].blocks.filterIsInstance<ArticleBlock.Text>().flatMap { it.runs }
        assertTrue(text.any { it.text == "更新漫画" && it.link == "jm://comic/12345" })
        assertTrue(page.items[1].title.contains("1 部漫画"))
        assertEquals(mapOf("type" to "all", "page" to "1"), requests.single())
    }

    @Test fun `filters and page parameters follow official protocol and read flags accept numeric and boolean forms`() = runTest {
        val repository = NotificationRepository({ _, _, params ->
            assertEquals("site_notice", params["type"]); assertEquals("2", params["page"])
            """{"total":"21","list":[{"id":"21","title":"公告","time":"昨天","type":"site_notice","read":true}]}"""
        }, { "" }, ArticleHtmlParser())
        val page = repository.list(NotificationFilter.Site, 2)
        assertTrue(page.items.single().read)
        assertEquals("昨天", page.items.single().date)
        assertFalse(page.hasMore)
    }

    @Test fun `filtering keeps only matching messages without changing remote pagination`() = runTest {
        val repository = NotificationRepository({ _, _, _ ->
            """{"total":40,"list":[{"id":"site","type":"site_notice"},{"id":"tracking","type":"comic_follow"}]}"""
        }, { "" }, ArticleHtmlParser())
        val result = repository.list(NotificationFilter.Tracking, 1)
        assertEquals(listOf("tracking"), result.items.map { it.id })
        assertTrue(result.hasMore)
    }

    @Test fun `mark all collects every category and unloaded page before marking unread messages`() = runTest {
        val requests = mutableListOf<String>()
        val repository = NotificationRepository({ method, path, params ->
            assertEquals("notifications", path)
            if (method == "GET") {
                assertEquals("all", params["type"])
                val page = params.getValue("page").toInt()
                requests += "list$page"
                val ids = if (page == 1) 1..20 else 21..21
                val rows = ids.joinToString(",") { """{"id":"$it","read":${if (it == 1) 1 else 0},"content":"正文"}""" }
                """{"total":21,"list":[$rows]}"""
            } else {
                assertEquals("1", params["read"])
                requests += "read${params["id"]}"
                "true"
            }
        }, { "" }, ArticleHtmlParser())
        repository.markAllRead()
        assertEquals(listOf("list1", "list2"), requests.take(2))
        assertEquals((2..21).map { "read$it" }, requests.drop(2))
    }

    @Test fun `repeated notification pages stop mark all before any writes`() = runTest {
        var writes = 0
        val repository = NotificationRepository({ method, _, _ ->
            if (method == "POST") writes++
            """{"total":50,"list":[{"id":"same","read":0}]}"""
        }, { "" }, ArticleHtmlParser())
        val error = runCatching { repository.markAllRead() }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(0, writes)
    }

    @Test fun `tracking reads official boolean and numeric values and toggle posts only an id`() = runTest {
        var value = "true"
        val repository = NotificationRepository({ method, path, params ->
            assertEquals("album_sertracking", path)
            assertEquals(mapOf("id" to "123"), params)
            if (method == "GET") value else "\"已订阅\""
        }, { "" }, ArticleHtmlParser())
        assertTrue(repository.tracking(123))
        value = "0"
        assertFalse(repository.tracking(123))
        repository.toggleTracking(123)
        value = "{}"
        assertTrue(runCatching { repository.tracking(123) }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun `malformed lists fail rather than pretending that the inbox is empty`() = runTest {
        val repository = NotificationRepository({ _, _, _ -> "{}" }, { "" }, ArticleHtmlParser())
        assertTrue(runCatching { repository.list(NotificationFilter.All, 1) }.exceptionOrNull() is IllegalArgumentException)
    }
}
