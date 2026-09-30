package com.par9uet.jm.storage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Cookie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keystore TemporaryUnavailable 不得被当成空基线，否则下一次写回会抹掉持久化数据。
 */
class TemporaryUnavailableHistoryTest {

    private class FakeReadHistoryStore : ReadHistoryStore {
        var unavailable = false
        private var disk: Map<Int, ComicReadHistory> = emptyMap()
        val writes = mutableListOf<Map<Int, ComicReadHistory>>()

        override fun getOrNull(): Map<Int, ComicReadHistory>? =
            if (unavailable) null else disk

        override fun set(history: Map<Int, ComicReadHistory>) {
            writes += history
            if (!unavailable) disk = history
        }

        fun seed(history: Map<Int, ComicReadHistory>) {
            disk = history
        }
    }

    private class FakeHistorySearchStore : HistorySearchStore {
        var unavailable = false
        private var disk: List<String> = emptyList()
        val writes = mutableListOf<List<String>>()

        override fun getOrNull(): List<String>? =
            if (unavailable) null else disk

        override fun set(list: List<String>) {
            writes += list
            if (!unavailable) disk = list
        }

        override fun remove() {
            writes.add(emptyList())
            disk = emptyList()
        }

        fun seed(list: List<String>) {
            disk = list
        }
    }

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test
    fun `read history temporary unavailable does not wipe disk on markRead`() {
        val storage = FakeReadHistoryStore()
        storage.seed(
            mapOf(
                1 to ComicReadHistory(
                    lastChapterId = 10,
                    readChapterIds = listOf(10, 11),
                    lastPageIndex = 3,
                    lastChapterPageCount = 20,
                ),
            ),
        )
        storage.unavailable = true
        val manager = ReadHistoryManager(storage, testScope())

        manager.markRead(1, 12)
        assertTrue("must not write while Keystore is unavailable", storage.writes.isEmpty())
        assertEquals(12, manager.readHistoryState.value[1]?.lastChapterId)

        storage.unavailable = false
        manager.markRead(1, 13)

        assertEquals(1, storage.writes.size)
        val written = storage.writes.single()
        assertTrue(written.getValue(1).readChapterIds.containsAll(listOf(10, 11, 12, 13)))
        assertEquals(13, written.getValue(1).lastChapterId)
    }

    @Test
    fun `search history temporary unavailable does not wipe disk on addItem`() {
        val storage = FakeHistorySearchStore()
        storage.seed(listOf("alpha", "beta"))
        storage.unavailable = true
        val manager = HistorySearchManager(storage, testScope())

        manager.addItem("gamma")
        assertTrue(storage.writes.isEmpty())
        assertTrue(manager.historySearchState.value.contains("gamma"))

        storage.unavailable = false
        manager.addItem("delta")

        assertEquals(1, storage.writes.size)
        assertEquals(listOf("delta", "gamma", "alpha", "beta"), storage.writes.single())
    }

    @Test
    fun `search history caps at MAX_ITEMS`() {
        val storage = FakeHistorySearchStore()
        val manager = HistorySearchManager(storage, testScope())
        repeat(HistorySearchManager.MAX_ITEMS + 10) { manager.addItem("q$it") }
        assertEquals(HistorySearchManager.MAX_ITEMS, manager.historySearchState.value.size)
        assertEquals("q${HistorySearchManager.MAX_ITEMS + 9}", manager.historySearchState.value.first())
    }

    @Test
    fun `mergeHistories unions chapter ids and prefers memory progress`() {
        val disk = mapOf(
            7 to ComicReadHistory(
                lastChapterId = 1,
                readChapterIds = listOf(1, 2),
                lastPageIndex = 0,
                lastChapterPageCount = 10,
            ),
        )
        val memory = mapOf(
            7 to ComicReadHistory(
                lastChapterId = 3,
                readChapterIds = listOf(3),
                lastPageIndex = 4,
                lastChapterPageCount = 12,
            ),
            8 to ComicReadHistory(
                lastChapterId = 9,
                readChapterIds = listOf(9),
                lastPageIndex = 1,
                lastChapterPageCount = 5,
            ),
        )
        val merged = mergeHistories(disk, memory)
        assertEquals(listOf(1, 2, 3), merged.getValue(7).readChapterIds)
        assertEquals(3, merged.getValue(7).lastChapterId)
        assertEquals(4, merged.getValue(7).lastPageIndex)
        assertEquals(9, merged.getValue(8).lastChapterId)
    }

    @Test
    fun `cookie response merge preserves the unreadable session and merges after recovery`() {
        val cookies = listOf(
            Cookie.Builder().name("AVS").value("previous").domain("example.com").build(),
        )
        val writes = mutableListOf<List<Cookie>>()
        var disk = cookies
        var unavailable = true
        val storage = object : CookieStorage {
            override val state = kotlinx.coroutines.flow.MutableStateFlow<List<Cookie>?>(null)
            override fun set(cookieStore: List<Cookie>): Boolean {
                writes += cookieStore
                disk = cookieStore
                return true
            }
            override fun get(): List<Cookie> = disk
            override fun getOrNull(): List<Cookie>? = if (unavailable) null else disk
            override fun remove() { writes.add(emptyList()); disk = emptyList() }
        }
        val theme = Cookie.Builder().name("theme").value("dark").domain("example.com").build()
        val injectedToken = cookies.single().newBuilder().value("untrusted-response-token").build()
        val received = listOf(theme, injectedToken)

        com.par9uet.jm.network.persistEmbeddedResponseCookies(storage, received)
        assertTrue("The actual response persistence path must skip an unreadable baseline", writes.isEmpty())
        assertEquals(cookies, disk)

        unavailable = false
        com.par9uet.jm.network.persistEmbeddedResponseCookies(storage, received)
        assertEquals(listOf(cookies + theme), writes)
        assertEquals(cookies + theme, disk)
        // Repeating the same response must not rewrite an unchanged snapshot.
        com.par9uet.jm.network.persistEmbeddedResponseCookies(storage, received)
        assertEquals(1, writes.size)
    }
}
