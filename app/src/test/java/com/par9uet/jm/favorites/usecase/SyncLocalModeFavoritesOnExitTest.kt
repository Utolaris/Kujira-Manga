package com.par9uet.jm.favorites.usecase

import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.favorites.TestFavoriteSession
import com.par9uet.jm.favorites.data.FavoriteMetadataPayload
import com.par9uet.jm.favorites.data.FavoriteRemoteItem
import com.par9uet.jm.favorites.data.FavoriteRemoteMutation
import com.par9uet.jm.favorites.data.FavoriteRemotePage
import com.par9uet.jm.favorites.data.FavoriteRemoteQuery
import com.par9uet.jm.storage.LocalFavoriteChange
import com.par9uet.jm.storage.LocalFavoriteChangeManager
import com.par9uet.jm.storage.LocalFavoriteChangeStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLocalModeFavoritesOnExitTest {
    private class FakeStore : LocalFavoriteChangeStore {
        var items = emptyList<LocalFavoriteChange>()
        var writable = true
        override fun getOrNull(): List<LocalFavoriteChange> = items
        override fun set(items: List<LocalFavoriteChange>): Boolean {
            if (!writable) return false
            this.items = items
            return true
        }
    }

    private class FakeRemoteQuery(private val remoteIds: Set<Int>) : FavoriteRemoteQuery {
        override suspend fun getFavorites(folderId: Int, page: Int) = FavoriteRemotePage(
            items = remoteIds.map { FavoriteRemoteItem(albumId = it, title = "t$it") },
            folders = mapOf(0 to "全部"),
            totalItems = remoteIds.size,
            totalPages = 1,
        )
        override suspend fun getMetadata(albumId: Int): FavoriteMetadataPayload =
            FavoriteMetadataPayload(albumId, "", "", emptyList(), emptyList(), emptyList(), emptyList())
    }

    private class FakeRemoteMutation : FavoriteRemoteMutation {
        val collected = mutableListOf<Int>()
        val uncollected = mutableListOf<Int>()
        var failCollect = false
        override suspend fun collectComic(comicId: Int): NetWorkResult<Unit> {
            collected += comicId
            return if (failCollect) NetWorkResult.Error("busy") else NetWorkResult.Success(Unit)
        }
        override suspend fun uncollectComic(comicId: Int): NetWorkResult<Unit> {
            uncollected += comicId
            return NetWorkResult.Success(Unit)
        }
        override suspend fun createFolder(name: String) = NetWorkResult.Success(Unit)
        override suspend fun deleteFolder(folderId: Int) = NetWorkResult.Success(Unit)
        override suspend fun renameFolder(folderId: Int, name: String) = NetWorkResult.Success(Unit)
        override suspend fun moveComicToFolder(comicId: Int, folderId: Int) = NetWorkResult.Success(Unit)
    }

    @Test
    fun `only explicit changes replay, including uncollect, and other accounts stay untouched`() = runTest {
        val store = FakeStore().apply {
            items = listOf(
                LocalFavoriteChange(7, 3, true),
                LocalFavoriteChange(7, 2, false),
                LocalFavoriteChange(8, 9, true),
            )
        }
        val session = TestFavoriteSession(7)
        val remote = FakeRemoteMutation()
        val result = SyncLocalModeFavoritesOnExit(
            FakeRemoteQuery(setOf(1, 2)), remote, LocalFavoriteChangeManager(store), session,
        )(session.snapshot())

        assertTrue(result is NetWorkResult.Success)
        assertEquals(listOf(3), remote.collected)
        assertEquals(listOf(2), remote.uncollected)
        assertEquals(listOf(LocalFavoriteChange(8, 9, true)), store.items)
    }

    @Test
    fun `failed collect stays pending for retry`() = runTest {
        val store = FakeStore().apply { items = listOf(LocalFavoriteChange(7, 3, true)) }
        val session = TestFavoriteSession(7)
        val remote = FakeRemoteMutation().apply { failCollect = true }
        val result = SyncLocalModeFavoritesOnExit(
            FakeRemoteQuery(setOf(1)), remote, LocalFavoriteChangeManager(store), session,
        )(session.snapshot()) as NetWorkResult.Success

        assertEquals(1, result.data.failed)
        assertEquals(listOf(LocalFavoriteChange(7, 3, true)), store.items)
    }

    @Test
    fun `stale account cannot replay changes`() = runTest {
        val store = FakeStore().apply { items = listOf(LocalFavoriteChange(7, 3, true)) }
        val session = TestFavoriteSession(7)
        val snapshot = session.snapshot()
        session.switchAccount(8)
        val remote = FakeRemoteMutation()
        val result = SyncLocalModeFavoritesOnExit(
            FakeRemoteQuery(emptySet()), remote, LocalFavoriteChangeManager(store), session,
        )(snapshot)

        assertTrue(result is NetWorkResult.Error)
        assertTrue(remote.collected.isEmpty())
        assertEquals(1, store.items.size)
    }
}
