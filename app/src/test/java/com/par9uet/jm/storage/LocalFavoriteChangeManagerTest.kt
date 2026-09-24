package com.par9uet.jm.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFavoriteChangeManagerTest {
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
