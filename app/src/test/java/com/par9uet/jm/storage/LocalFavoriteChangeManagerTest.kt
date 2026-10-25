package com.par9uet.jm.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class LocalFavoriteChangeManagerTest {
    @Test fun `pending count observes persisted edits replay and account isolation`() = runTest {
        val store = Store()
        val changes = LocalFavoriteChangeManager(store)
        val counts = Channel<Int?>(Channel.UNLIMITED)
        backgroundScope.launch { changes.observePendingCount(7).collect { counts.send(it) } }
        assertEquals(0, counts.receive())
        assertTrue(changes.record(7, 11, collect = true))
        assertEquals(1, counts.receive())
        assertTrue(changes.record(7, 12, collect = false))
        assertEquals(2, counts.receive())
        assertTrue(changes.record(8, 13, collect = true))
        assertEquals(1, changes.observePendingCount(8).first())
        assertTrue(changes.removeIfUnchanged(changes.snapshot(7)!!.first()))
        assertEquals(1, counts.receive())
        assertTrue(changes.clear(7))
        assertEquals(0, counts.receive())
        assertEquals(1, changes.observePendingCount(8).first())
    }

    @Test fun `count is per comic and failed writes preserve its last persisted value`() = runTest {
        val store = Store().apply {
            items = listOf(LocalFavoriteChange(7, 11, true), LocalFavoriteChange(7, 11, false))
        }
        val changes = LocalFavoriteChangeManager(store)
        assertEquals(1, changes.observePendingCount(7).first())
        store.writable = false
        assertFalse(changes.record(7, 12, collect = true))
        assertFalse(changes.clear(7))
        assertEquals(1, changes.observePendingCount(7).first())
    }

    @Test fun `unreadable pending count is unknown instead of zero`() = runTest {
        val changes = LocalFavoriteChangeManager(Store().apply { readable = false })
        assertEquals(null, changes.observePendingCount(7).first())
        assertEquals(0, changes.observePendingCount(0).first())
    }

    private class Store : LocalFavoriteChangeStore {
        var items = emptyList<LocalFavoriteChange>()
        var readable = true
        var writable = true
        override fun getOrNull(): List<LocalFavoriteChange>? = if (readable) items else null
        override fun set(items: List<LocalFavoriteChange>): Boolean {
            if (!writable) return false
            this.items = items
            return true
        }
    }

    @Test
    fun `latest action replaces earlier action only for the same account and comic`() {
        val store = Store()
        val changes = LocalFavoriteChangeManager(store)
        assertTrue(changes.record(7, 11, collect = true))
        assertTrue(changes.record(8, 11, collect = true))
        assertTrue(changes.record(7, 11, collect = false))
        assertEquals(listOf(7 to false), changes.snapshot(7)?.map { it.accountId to it.collect })
        assertEquals(listOf(8 to true), changes.snapshot(8)?.map { it.accountId to it.collect })
    }

    @Test
    fun `sync never clears a newer identical action`() {
        val changes = LocalFavoriteChangeManager(Store())
        assertTrue(changes.record(7, 11, collect = true))
        val inFlight = changes.snapshot(7)!!.single()
        assertTrue(changes.record(7, 11, collect = true))
        assertFalse(changes.removeIfUnchanged(inFlight))
        assertEquals(1, changes.snapshot(7)?.size)
    }

    @Test
    fun `unavailable encrypted store never acknowledges a changed intent`() {
        val store = Store().apply { items = listOf(LocalFavoriteChange(7, 11, true)) }
        val changes = LocalFavoriteChangeManager(store)
        store.writable = false
        assertFalse(changes.record(7, 11, collect = false))
        assertEquals(listOf(LocalFavoriteChange(7, 11, true)), store.items)
        store.readable = false
        assertFalse(changes.record(7, 12, collect = true))
        assertEquals(null, changes.snapshot(7))
    }
}
