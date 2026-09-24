package com.par9uet.jm.storage

import com.par9uet.jm.data.models.Comic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBrowseHistoryManagerTest {
    private class Store : LocalBrowseHistoryStore {
        var items = emptyList<LocalBrowseHistoryEntry>()
        var writable = true
        override fun getOrNull(): List<LocalBrowseHistoryEntry> = items
        override fun set(entries: List<LocalBrowseHistoryEntry>): Boolean {
            if (!writable) return false
            items = entries
            return true
        }
    }

    @Test
    fun `switching accounts hides old history and clearing one account preserves the other`() {
        val store = Store()
        val history = LocalBrowseHistoryManager(store)
        val comic = Comic.create(id = 10, name = "Comic", authorList = emptyList())
        history.record(7, comic, 1)
        history.record(8, comic, 2)
        assertEquals(setOf(7, 8), history.entries.value.map { it.accountId }.toSet())
        assertTrue(history.clear(8))
        assertEquals(listOf(7), history.entries.value.map { it.accountId })
        store.writable = false
        assertFalse(history.clear(7))
        assertEquals(listOf(7), history.entries.value.map { it.accountId })
    }

    @Test
    fun `failed delete reports failure and preserves both accounts`() {
        val store = Store()
        val history = LocalBrowseHistoryManager(store)
        val comic = Comic.create(id = 10, name = "Comic", authorList = emptyList())
        history.record(7, comic, 1)
        history.record(8, comic, 2)
        store.writable = false
        assertFalse(history.delete(7, listOf(10)))
        assertEquals(setOf(7, 8), history.entries.value.map { it.accountId }.toSet())
        store.writable = true
        assertTrue(history.delete(7, listOf(10)))
        assertEquals(listOf(8), history.entries.value.map { it.accountId })
    }
}
