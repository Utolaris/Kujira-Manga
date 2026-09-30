package com.par9uet.jm.favorites.usecase

import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.favorites.TestFavoriteSession
import com.par9uet.jm.favorites.data.FavoriteLocalMutation
import com.par9uet.jm.favorites.data.FavoriteRemoteMutation
import com.par9uet.jm.favorites.model.FavoriteSessionSnapshot
import com.par9uet.jm.core.network.NetworkErrorKind
import com.par9uet.jm.core.network.AuthFailure
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.favorites.alwaysNetworkLocalModeStatus
import com.par9uet.jm.favorites.inMemoryLocalChanges
import com.par9uet.jm.favorites.localModeFor
import com.par9uet.jm.favorites.localModeForOtherAccount
import com.par9uet.jm.favorites.InMemoryLocalChanges
import com.par9uet.jm.storage.LocalFavoriteChange
import com.par9uet.jm.storage.LocalFavoriteChangeManager
import com.par9uet.jm.storage.LocalFavoriteChangeStore
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteUseCasesTest {
    private val session = TestFavoriteSession(accountId = 42)
    private val sessionSnapshot = FavoriteSessionSnapshot(accountId = 42, generation = 0)

    @Test
    fun `collect renews outside bound session and commits only after successful retry`() = runTest {
        val remote = RecordingRemoteMutation().apply {
            collectResult = NetWorkResult.Error("expired", code = 401, kind = NetworkErrorKind.Authentication)
        }
        val local = RecordingLocalMutation()
        session.recovery = {
            assertTrue(!session.boundBatchOpen)
            assertTrue(local.addedFavorites.isEmpty())
            remote.collectResult = NetWorkResult.Success(Unit)
            NetWorkResult.Success(Unit)
        }

        val result = CollectFavorite(remote, local, session, alwaysNetworkLocalModeStatus(), inMemoryLocalChanges(), LocalFavoriteOperationGate())(
            sessionSnapshot, Comic.create(11, "Comic", emptyList()),
        )

        assertTrue(result is NetWorkResult.Success)
        assertEquals(1, session.recoveryCalls)
        assertEquals(listOf(11, 11), remote.collectedIds)
        assertEquals(1, local.addedFavorites.size)
    }

    @Test
    fun `persistent 401 retries only once and never commits locally`() = runTest {
        val expired = NetWorkResult.Error("expired", code = 401, kind = NetworkErrorKind.Authentication)
        val remote = RecordingRemoteMutation().apply { collectResult = expired }
        val local = RecordingLocalMutation()
        session.recovery = { NetWorkResult.Success(Unit) }

        val result = CollectFavorite(remote, local, session, alwaysNetworkLocalModeStatus(), inMemoryLocalChanges(), LocalFavoriteOperationGate())(
            sessionSnapshot, Comic.create(11, "Comic", emptyList()),
        )

        assertEquals(expired, result)
        assertEquals(1, session.recoveryCalls)
        assertEquals(listOf(11, 11), remote.collectedIds)
        assertTrue(local.addedFavorites.isEmpty())
    }

    @Test
    fun `failed recovery or account switch prevents collect replay`() = runTest {
        for (switchAccount in listOf(false, true)) {
            val testSession = TestFavoriteSession(accountId = 42)
            val expired = NetWorkResult.Error("expired", code = 401, kind = NetworkErrorKind.Authentication)
            val failure = NetWorkResult.Error("稍后重试", kind = NetworkErrorKind.Network)
            val remote = RecordingRemoteMutation().apply { collectResult = expired }
            val local = RecordingLocalMutation()
            testSession.recovery = {
                if (switchAccount) {
                    testSession.switchAccount(43)
                    NetWorkResult.Success(Unit)
                } else failure
            }
            val result = CollectFavorite(remote, local, testSession, alwaysNetworkLocalModeStatus(), inMemoryLocalChanges(), LocalFavoriteOperationGate())(
                sessionSnapshot, Comic.create(11, "Comic", emptyList()),
            )
            assertEquals(if (switchAccount) expired else failure, result)
            assertEquals(1, testSession.recoveryCalls)
            assertEquals(listOf(11), remote.collectedIds)
            assertTrue(local.addedFavorites.isEmpty())
        }
    }

    @Test
    fun `transition rejects favorite edits without recording intents or contacting remote`() = runTest {
        val gate = LocalFavoriteOperationGate()
        val mode = localModeFor(sessionSnapshot.accountId)
        val changes = inMemoryLocalChanges()
        val remote = RecordingRemoteMutation()
        val local = RecordingLocalMutation()
        assertTrue(gate.beginTransition())

        val collect = CollectFavorite(remote, local, session, mode, changes, gate)(
            sessionSnapshot, Comic.create(11, "Comic", emptyList()),
        )
        val uncollect = UncollectFavorites(remote, local, session, mode, changes, gate)(
            sessionSnapshot, listOf(11),
        )

        assertTrue(collect is NetWorkResult.Error)
        assertEquals("收藏同步期间暂不可修改收藏夹", uncollect.message)
        assertTrue(changes.snapshot(42).orEmpty().isEmpty())
        assertTrue(remote.collectedIds.isEmpty())
        assertTrue(remote.uncollectedIds.isEmpty())
        assertTrue(local.addedFavorites.isEmpty())
        assertTrue(local.removedIds.isEmpty())
        gate.endTransition()
    }

    @Test
    fun `local mode records the latest intent without calling remote mutation`() = runTest {
        val mode = localModeFor(sessionSnapshot.accountId)
        val changes = inMemoryLocalChanges()
        val remote = RecordingRemoteMutation()
        val local = RecordingLocalMutation()
        val comic = Comic.create(11, "Comic", emptyList())

        assertTrue(CollectFavorite(remote, local, session, mode, changes, LocalFavoriteOperationGate())(sessionSnapshot, comic) is NetWorkResult.Success)
        assertEquals(FavoritesBatchResult(1, 0), UncollectFavorites(remote, local, session, mode, changes, LocalFavoriteOperationGate())(sessionSnapshot, listOf(11)))
        assertEquals(listOf(11 to false), changes.snapshot(42)?.map { it.albumId to it.collect })
        assertTrue(remote.collectedIds.isEmpty())
        assertTrue(remote.uncollectedIds.isEmpty())
    }

    @Test
    fun `stale local mode snapshot does not record another account's action`() = runTest {
        val mode = localModeFor(sessionSnapshot.accountId)
        val changes = inMemoryLocalChanges()
        val local = RecordingLocalMutation()
        session.switchAccount(43)

        val result = CollectFavorite(RecordingRemoteMutation(), local, session, mode, changes, LocalFavoriteOperationGate())(
            sessionSnapshot,
            Comic.create(11, "Comic", emptyList()),
        )

        assertTrue(result is NetWorkResult.Error)
        assertTrue(changes.snapshot(42).orEmpty().isEmpty())
        assertTrue(local.addedFavorites.isEmpty())
    }

    @Test
    fun `another account's local mode never hijacks this account's edit`() = runTest {
        // 生产判定是「当前账号 ∈ 本地模式名单」：账号 7 处于本地模式，不代表账号 42 是。
        // 替身原先写死号码 7，这条会被误判成本地模式并悄悄记成本地待同步。
        val mode = localModeForOtherAccount(localAccountId = 7, activeAccountId = sessionSnapshot.accountId)
        val remote = RecordingRemoteMutation()
        val local = RecordingLocalMutation()

        val result = CollectFavorite(remote, local, session, mode, inMemoryLocalChanges(), LocalFavoriteOperationGate())(
            sessionSnapshot,
            Comic.create(11, "Comic", emptyList()),
        )

        assertTrue(result is NetWorkResult.Success)
        // 走的是远端路径，且本地快照只在远端成功后才更新。
        assertEquals(listOf(11), remote.collectedIds)
        assertEquals(listOf(42 to 11), local.addedFavorites)
    }

    @Test
    fun `an unreadable local store refuses the edit instead of dropping it`() = runTest {
        // Corrupted / TemporaryUnavailable 映射成 null。当成空队列会把用户这次收藏静默丢掉，
        // 所以必须报错、且一个字节都不写。
        val store = InMemoryLocalChanges().apply { readUnavailable = true }
        val local = RecordingLocalMutation()
        val remote = RecordingRemoteMutation()

        val collect = CollectFavorite(
            remote, local, session, localModeFor(sessionSnapshot.accountId),
            LocalFavoriteChangeManager(store), LocalFavoriteOperationGate(),
        )(sessionSnapshot, Comic.create(11, "Comic", emptyList()))

        assertTrue(collect is NetWorkResult.Error)
        assertEquals("本地收藏保存失败，请重试", (collect as NetWorkResult.Error).message)
        assertTrue(local.addedFavorites.isEmpty())
        assertEquals(0, store.writeAttempts)
    }

    @Test
    fun `failed intent persistence leaves local snapshot untouched`() = runTest {
        val mode = localModeFor(sessionSnapshot.accountId)
        val changes = LocalFavoriteChangeManager(object : LocalFavoriteChangeStore {
            override fun getOrNull(): List<LocalFavoriteChange> = emptyList()
            override fun set(items: List<LocalFavoriteChange>): Boolean = false
        })
        val local = RecordingLocalMutation()
        val remote = RecordingRemoteMutation()
        val comic = Comic.create(11, "Comic", emptyList())

        assertTrue(CollectFavorite(remote, local, session, mode, changes, LocalFavoriteOperationGate())(sessionSnapshot, comic) is NetWorkResult.Error)
        assertEquals(FavoritesBatchResult(0, 1), UncollectFavorites(remote, local, session, mode, changes, LocalFavoriteOperationGate())(sessionSnapshot, listOf(11)))
        assertTrue(local.addedFavorites.isEmpty())
        assertTrue(local.removedIds.isEmpty())
    }

    @Test
    fun `collect preserves the real remote error and does not write local state`() = runTest {
        val expected = NetWorkResult.Error(
            message = "server rejected collect",
            code = 503,
            authFailure = AuthFailure.TemporaryFailure,
        )
        val remote = RecordingRemoteMutation().apply { collectResult = expected }
        val local = RecordingLocalMutation()
        val comic = Comic.create(id = 11, name = "Comic", authorList = listOf("Author"))

        val result = CollectFavorite(remote, local, session, alwaysNetworkLocalModeStatus(), inMemoryLocalChanges(), LocalFavoriteOperationGate())(sessionSnapshot, comic)

        assertEquals(0, session.recoveryCalls)
        assertEquals(expected, result)
        assertEquals(listOf(11), remote.collectedIds)
        assertTrue(local.addedFavorites.isEmpty())
    }

    @Test
    fun `stale snapshot before remote execution never starts the bound batch`() = runTest {
        val remote = RecordingRemoteMutation()
        val local = RecordingLocalMutation()
        val staleSnapshot = FavoriteSessionSnapshot(accountId = 7, generation = 3)

        val result = MoveFavorites(remote, local, session, alwaysNetworkLocalModeStatus(), LocalFavoriteOperationGate())(staleSnapshot, listOf(1), folderId = 7)

        assertEquals(FavoritesBatchResult(succeeded = 0, failed = 1), result)
        assertTrue(remote.movedIds.isEmpty())
        assertTrue(session.boundBatchesStarted.isEmpty())
    }

    @Test
    fun `a session change requested during a bound batch waits for the batch to finish`() = runTest {
        // 真实实现里整个批次都跑在 withBoundRemoteSession 的会话锁内，会话转换必须排队。
        // 因此「远程调用进行中账号就切到 B」在生产里**不可能发生**：旧用例正是这么假设的，
        // 它靠弱替身（bound 退化成 current）才成立，属假信心。
        // 这里断言真正的契约：批次进行中转换拿不到会话锁（超时即证明被推迟），
        // 批次完整跑在 A 上，锁释放后转换立刻可完成。
        val remote = RecordingRemoteMutation()
        val local = RecordingLocalMutation()
        var deferredSwitchAttempted = false
        remote.uncollectHandler = {
            if (!deferredSwitchAttempted) {
                deferredSwitchAttempted = true
                val switchedDuringBatch = withTimeoutOrNull(50) { session.switchAccount(43) }
                assertNull("批次持有会话锁期间不得完成会话转换", switchedDuringBatch)
            }
            NetWorkResult.Success(Unit)
        }

        val result = UncollectFavorites(remote, local, session, alwaysNetworkLocalModeStatus(), inMemoryLocalChanges(), LocalFavoriteOperationGate())(
            sessionSnapshot,
            listOf(1, 2, 3),
        )

        // 三项都完整跑在账号 42 上：若转换中途生效，循环里的 isCurrent 守卫会提前中断。
        assertEquals(FavoritesBatchResult(succeeded = 3, failed = 0), result)
        assertEquals(listOf(1, 2, 3), remote.uncollectedIds)
        assertEquals(listOf(1, 2, 3), local.removedIds)
        assertEquals(42, session.currentAccountId())

        // 批次结束后锁已释放，转换不再被阻塞。
        session.switchAccount(43)
        assertEquals(43, session.currentAccountId())
    }

    @Test
    fun `folder mutations run inside the bound remote session and commit locally only on success`() = runTest {
        val remote = RecordingRemoteMutation()
        val local = RecordingLocalMutation()

        assertEquals(
            NetWorkResult.Success(Unit),
            CreateFavoriteFolder(remote, session, alwaysNetworkLocalModeStatus(), LocalFavoriteOperationGate())(sessionSnapshot, "New"),
        )
        assertEquals(
            NetWorkResult.Success(Unit),
            DeleteFavoriteFolder(remote, local, session, alwaysNetworkLocalModeStatus(), LocalFavoriteOperationGate())(sessionSnapshot, 7),
        )
        assertEquals(
            NetWorkResult.Success(Unit),
            RenameFavoriteFolder(remote, local, session, alwaysNetworkLocalModeStatus(), LocalFavoriteOperationGate())(sessionSnapshot, 7, "Renamed"),
        )

        // Each folder operation opened exactly one bound batch against the captured account.
        assertEquals(3, session.boundBatchesStarted.size)
        assertTrue(session.boundBatchesStarted.all { it.accountId == 42 })

        // 参数必须真的到达远端与本地；只断言「返回值 == 替身硬编码的 Success」等于在测替身。
        assertEquals(listOf("New"), remote.createdFolderNames)
        assertEquals(listOf(7), remote.deletedFolderIds)
        assertEquals(listOf(7 to "Renamed"), remote.renamedFolderArgs)
        assertEquals(listOf(7), local.removedFolderIds)
        assertEquals(listOf(7 to "Renamed"), local.renamedFolders)

        // A snapshot from another account never reaches the remote mutation.
        val staleCreate = CreateFavoriteFolder(remote, session, alwaysNetworkLocalModeStatus(), LocalFavoriteOperationGate())(
            FavoriteSessionSnapshot(accountId = 43, generation = 9),
            "Never",
        )
        assertTrue(staleCreate is NetWorkResult.Error)
        assertEquals(listOf("New"), remote.createdFolderNames)
        // 陈旧快照连绑定批次都不该开。
        assertEquals(3, session.boundBatchesStarted.size)
    }

    @Test
    fun `uncollect removes local state only after remote success`() = runTest {
        val remote = RecordingRemoteMutation().apply {
            uncollectResults[2] = NetWorkResult.Error("remote failed")
        }
        val local = RecordingLocalMutation()

        val result = UncollectFavorites(remote, local, session, alwaysNetworkLocalModeStatus(), inMemoryLocalChanges(), LocalFavoriteOperationGate())(sessionSnapshot, listOf(1, 2, 1))

        assertEquals(FavoritesBatchResult(succeeded = 1, failed = 1), result)
        assertEquals(listOf(1, 2), remote.uncollectedIds)
        assertEquals(listOf(1), local.removedIds)
    }

    @Test
    fun `moving selected favorites updates local membership only after each remote success`() = runTest {
        val remote = RecordingRemoteMutation().apply {
            moveResults[2] = NetWorkResult.Error("remote failed")
        }
        val local = RecordingLocalMutation()

        val result = MoveFavorites(remote, local, session, alwaysNetworkLocalModeStatus(), LocalFavoriteOperationGate())(sessionSnapshot, listOf(1, 2, 1), folderId = 7)

        assertEquals(FavoritesBatchResult(succeeded = 1, failed = 1), result)
        assertEquals(listOf(1, 2), remote.movedIds)
        assertEquals(listOf(1 to 7), local.movedIds)
    }

    private class RecordingRemoteMutation : FavoriteRemoteMutation {
        val collectedIds = mutableListOf<Int>()
        val uncollectedIds = mutableListOf<Int>()
        val movedIds = mutableListOf<Int>()
        val createdFolderNames = mutableListOf<String>()
        val deletedFolderIds = mutableListOf<Int>()
        val renamedFolderArgs = mutableListOf<Pair<Int, String>>()
        val uncollectResults = mutableMapOf<Int, NetWorkResult<Unit>>()
        val moveResults = mutableMapOf<Int, NetWorkResult<Unit>>()
        var uncollectHandler: (suspend (Int) -> NetWorkResult<Unit>)? = null
        var moveHandler: (suspend (Int, Int) -> NetWorkResult<Unit>)? = null
        var collectResult: NetWorkResult<Unit> = NetWorkResult.Success(Unit)

        override suspend fun collectComic(comicId: Int): NetWorkResult<Unit> {
            collectedIds += comicId
            return collectResult
        }

        override suspend fun uncollectComic(comicId: Int): NetWorkResult<Unit> {
            uncollectedIds += comicId
            return uncollectHandler?.invoke(comicId)
                ?: uncollectResults[comicId]
                ?: NetWorkResult.Success(Unit)
        }

        override suspend fun createFolder(name: String): NetWorkResult<Unit> {
            createdFolderNames += name
            return NetWorkResult.Success(Unit)
        }

        override suspend fun deleteFolder(folderId: Int): NetWorkResult<Unit> {
            deletedFolderIds += folderId
            return NetWorkResult.Success(Unit)
        }

        override suspend fun renameFolder(folderId: Int, name: String): NetWorkResult<Unit> {
            renamedFolderArgs += folderId to name
            return NetWorkResult.Success(Unit)
        }

        override suspend fun moveComicToFolder(comicId: Int, folderId: Int): NetWorkResult<Unit> {
            movedIds += comicId
            return moveHandler?.invoke(comicId, folderId)
                ?: moveResults[comicId]
                ?: NetWorkResult.Success(Unit)
        }
    }

    private class RecordingLocalMutation : FavoriteLocalMutation {
        val addedFavorites = mutableListOf<Pair<Int, Int>>()
        val removedIds = mutableListOf<Int>()
        val movedIds = mutableListOf<Pair<Int, Int>>()
        val removedFolderIds = mutableListOf<Int>()
        val renamedFolders = mutableListOf<Pair<Int, String>>()

        override suspend fun addFromComic(accountId: Int, comic: Comic, folderId: Int) {
            addedFavorites += accountId to comic.id
        }

        override suspend fun remove(accountId: Int, albumIds: Collection<Int>) {
            removedIds += albumIds
        }

        override suspend fun moveToFolder(accountId: Int, albumId: Int, folderId: Int) {
            movedIds += albumId to folderId
        }

        override suspend fun cacheFolder(accountId: Int, folderId: Int, name: String) = Unit

        override suspend fun removeFolder(accountId: Int, folderId: Int) {
            removedFolderIds += folderId
        }

        override suspend fun renameFolder(accountId: Int, folderId: Int, name: String) {
            renamedFolders += folderId to name
        }
    }
}
