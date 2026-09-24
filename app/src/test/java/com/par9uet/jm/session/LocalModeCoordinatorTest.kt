package com.par9uet.jm.session

import com.par9uet.jm.core.ToastManager
import com.par9uet.jm.core.model.SignInData
import com.par9uet.jm.core.model.User
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.data.models.ActionResult
import com.par9uet.jm.data.models.ComicPage
import com.par9uet.jm.data.models.CommentPage
import com.par9uet.jm.di.UserManagerFavoriteSession
import com.par9uet.jm.favorites.FakeConnectionMode
import com.par9uet.jm.favorites.data.FavoriteMetadataPayload
import com.par9uet.jm.favorites.data.FavoriteRemoteMutation
import com.par9uet.jm.favorites.data.FavoriteRemotePage
import com.par9uet.jm.favorites.data.FavoriteRemoteQuery
import com.par9uet.jm.favorites.sync.FavoriteSyncReport
import com.par9uet.jm.favorites.usecase.SyncLocalModeFavoritesOnExit
import com.par9uet.jm.retrofit.model.LoginResponse
import com.par9uet.jm.storage.CookieStorage
import com.par9uet.jm.storage.LocalBrowseHistoryEntry
import com.par9uet.jm.storage.LocalBrowseHistoryManager
import com.par9uet.jm.storage.LocalBrowseHistoryStore
import com.par9uet.jm.storage.LocalFavoriteChange
import com.par9uet.jm.storage.LocalFavoriteChangeManager
import com.par9uet.jm.storage.LocalFavoriteChangeStore
import com.par9uet.jm.storage.UserStorage
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalModeCoordinatorTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class Repository : UserRepository {
        var logins = 0
        var failLogin = false
        var loginBarrier: CompletableDeferred<Unit>? = null
        override suspend fun login(username: String, password: String): NetWorkResult<CandidateSession> {
            logins++
            loginBarrier?.await()
            if (failLogin) return NetWorkResult.Error("登录态失效")
            val id = if (username == "B") 8 else 7
            return NetWorkResult.Success(CandidateSession(
                LoginResponse(id, username, "", "", "0", 0, "M", 1, 100, 0, 0.0, 100),
                listOf(cookie()),
            ))
        }
        override suspend fun probeActiveSession() = NetWorkResult.Success(Unit)
        override fun activateVerifiedSession(verified: CandidateSession) = true
        override fun clearSession() = Unit
        override suspend fun getHistoryComicList(page: Int): NetWorkResult<ComicPage> = NetWorkResult.Error("unused")
        override suspend fun deleteHistoryComic(id: Int): NetWorkResult<Unit> = NetWorkResult.Error("unused")
        override suspend fun getHistoryCommentList(page: Int, userId: Int): NetWorkResult<CommentPage> = NetWorkResult.Error("unused")
        override suspend fun getSignData(userId: Int): NetWorkResult<SignInData> = NetWorkResult.Error("unused")
        override suspend fun signIn(userId: Int, dailyId: Int): NetWorkResult<ActionResult> = NetWorkResult.Error("unused")
    }

    private class Changes : LocalFavoriteChangeStore {
        var items = listOf(LocalFavoriteChange(7, 11, true))
        override fun getOrNull(): List<LocalFavoriteChange> = items
        override fun set(items: List<LocalFavoriteChange>): Boolean { this.items = items; return true }
    }

    private class History : LocalBrowseHistoryStore {
        var items = listOf(
            LocalBrowseHistoryEntry(7, 11, "A"),
            LocalBrowseHistoryEntry(8, 22, "B"),
        )
        override fun getOrNull(): List<LocalBrowseHistoryEntry> = items
        override fun set(entries: List<LocalBrowseHistoryEntry>): Boolean { items = entries; return true }
    }

    private class Remote(private val failCollect: Boolean) : FavoriteRemoteQuery, FavoriteRemoteMutation {
        override suspend fun getFavorites(folderId: Int, page: Int) = FavoriteRemotePage(emptyList(), emptyMap(), 0, 1)
        override suspend fun getMetadata(albumId: Int): FavoriteMetadataPayload =
            FavoriteMetadataPayload(albumId, "", "", emptyList(), emptyList(), emptyList(), emptyList())
        override suspend fun collectComic(comicId: Int): NetWorkResult<Unit> =
            if (failCollect) NetWorkResult.Error("busy") else NetWorkResult.Success(Unit)
        override suspend fun uncollectComic(comicId: Int) = NetWorkResult.Success(Unit)
        override suspend fun createFolder(name: String) = NetWorkResult.Success(Unit)
        override suspend fun deleteFolder(folderId: Int) = NetWorkResult.Success(Unit)
        override suspend fun renameFolder(folderId: Int, name: String) = NetWorkResult.Success(Unit)
        override suspend fun moveComicToFolder(comicId: Int, folderId: Int) = NetWorkResult.Success(Unit)
    }

    private data class Fixture(
        val coordinator: LocalModeCoordinator,
        val mode: FakeConnectionMode,
        val repository: Repository,
        val changes: Changes,
        val history: History,
        val userManager: UserManager,
        val operationGate: com.par9uet.jm.favorites.usecase.LocalFavoriteOperationGate,
        val refreshes: () -> Int,
    )

    private fun fixture(failCollect: Boolean = false, failRefresh: Boolean = false): Fixture {
        val mode = FakeConnectionMode(true)
        val repository = Repository()
        val userStorage = object : UserStorage {
            private var user = User(7, "A", "pw", "", 1, "M", 0, 100, 0, 100, 0)
            override fun get() = user
            override fun set(user: User) { this.user = user }
            override fun remove() { user = User.create() }
        }
        val cookies = object : CookieStorage {
            override val state = MutableStateFlow<List<Cookie>?>(listOf(cookie()))
            override fun set(cookieStore: List<Cookie>): Boolean { state.value = cookieStore; return true }
            override fun get(): List<Cookie> = state.value.orEmpty()
            override fun remove() { state.value = emptyList() }
        }
        val userManager = UserManager(userStorage, cookies, repository, SessionReadinessHolder(), NightLocalModePrompt(mode, mode), mode)
        val favoriteSession = UserManagerFavoriteSession(userManager)
        val gate = LocalModeGate(mode, userManager, kotlinx.coroutines.CoroutineScope(Dispatchers.Main))
        val changes = Changes()
        val history = History()
        val remote = Remote(failCollect)
        val operationGate = com.par9uet.jm.favorites.usecase.LocalFavoriteOperationGate()
        var refreshes = 0
        val coordinator = LocalModeCoordinator(
            connectionModeEditor = mode,
            connectionModePreferences = mode,
            localModeStatus = gate,
            userManager = userManager,
            browseHistory = LocalBrowseHistoryManager(history),
            exitFavoriteSync = SyncLocalModeFavoritesOnExit(remote, remote, LocalFavoriteChangeManager(changes), favoriteSession),
            localFavoriteOperationGate = operationGate,
            favoriteSession = favoriteSession,
            localFavoriteChanges = LocalFavoriteChangeManager(changes),
            refreshFavorites = {
                refreshes++
                // 全量刷新期间必须持有编辑锁；exit/force-align 成功前都不得先解锁或先关模式。
                assertTrue(operationGate.isTransitioning())
                if (failRefresh) NetWorkResult.Error("refresh failed")
                else NetWorkResult.Success(FavoriteSyncReport(0, 0, 0, 0, 0))
            },
            toastManager = ToastManager(),
        )
        return Fixture(coordinator, mode, repository, changes, history, userManager, operationGate) { refreshes }
    }

    @Test fun `failed replay keeps local mode and pending changes`() = runTest {
        val f = fixture(failCollect = true)
        f.coordinator.exitLocalMode()
        assertTrue(f.coordinator.isLocalMode)
        assertEquals(0, f.refreshes())
        assertEquals(1, f.changes.items.size)
        assertEquals(2, f.history.items.size)
    }

    @Test fun `mode closes after remote sync and current account history clears`() = runTest {
        val f = fixture()
        f.coordinator.exitLocalMode()
        assertFalse(f.coordinator.isLocalMode)
        assertEquals(1, f.refreshes())
        assertTrue(f.changes.items.isEmpty())
        assertEquals(listOf(8), f.history.items.map { it.accountId })
    }

    @Test fun `manual login exits local mode without a second login request`() = runTest {
        val f = fixture()
        f.userManager.login("A", "pw")
        val session = f.userManager.currentSessionSnapshot()

        f.coordinator.exitLocalModeAfterLogin(session.accountId, session.generation)

        assertEquals(1, f.repository.logins)
        assertFalse(f.coordinator.isLocalMode)
        assertEquals(1, f.refreshes())
        assertTrue(f.changes.items.isEmpty())
    }

    @Test fun `stale manual login session cannot bypass relogin`() = runTest {
        val f = fixture()
        f.userManager.login("A", "pw")
        val stale = f.userManager.currentSessionSnapshot()
        f.userManager.login("A", "pw")

        f.coordinator.exitLocalModeAfterLogin(stale.accountId, stale.generation)

        assertEquals(2, f.repository.logins)
        assertTrue(f.coordinator.isLocalMode)
        assertEquals(0, f.refreshes())
    }

    @Test fun `uncommitted login session cannot exit local mode`() = runTest {
        val f = fixture()
        val barrier = CompletableDeferred<Unit>()
        f.repository.loginBarrier = barrier
        val pendingLogin = async { f.userManager.login("A", "pw") }
        runCurrent()
        val uncommitted = f.userManager.currentSessionSnapshot()
        assertTrue(f.userManager.userState.value.isLoading)

        f.coordinator.exitLocalModeAfterLogin(uncommitted.accountId, uncommitted.generation)

        assertTrue(f.coordinator.isLocalMode)
        assertEquals(0, f.refreshes())
        barrier.complete(Unit)
        pendingLogin.await()
    }

    @Test fun `failed full refresh still leaves local mode`() = runTest {
        val f = fixture(failRefresh = true)
        f.coordinator.exitLocalMode()
        assertTrue(f.coordinator.isLocalMode)
        assertEquals(1, f.refreshes())
        assertEquals(2, f.history.items.size)
    }

    @Test fun `force align keeps pending changes when relogin fails`() = runTest {
        val f = fixture()
        f.repository.failLogin = true
        f.coordinator.forceAlignFavoritesWithRemote()
        assertTrue(f.coordinator.isLocalMode)
        assertEquals(0, f.refreshes())
        assertEquals(1, f.changes.items.size)
    }

    @Test fun `force align keeps local mode and pending when refresh fails`() = runTest {
        val f = fixture(failRefresh = true)
        f.coordinator.forceAlignFavoritesWithRemote()
        assertTrue(f.coordinator.isLocalMode)
        assertEquals(1, f.refreshes())
        assertEquals(1, f.changes.items.size)
    }

    @Test fun `force align clears pending and closes mode only after successful refresh`() = runTest {
        val f = fixture()
        f.coordinator.forceAlignFavoritesWithRemote()
        assertFalse(f.coordinator.isLocalMode)
        assertEquals(1, f.refreshes())
        assertTrue(f.changes.items.isEmpty())
    }

    @Test fun `failed mode write stays local and skips remote sync`() = runTest {
        val f = fixture()
        f.mode.modeWritable = false
        f.coordinator.exitLocalMode()
        assertTrue(f.coordinator.isLocalMode)
        assertEquals(1, f.refreshes())
        assertEquals(2, f.history.items.size)
    }

    @Test fun `exit locks edits while login is pending and releases lock on failure`() = runTest {
        val f = fixture()
        val barrier = CompletableDeferred<Unit>()
        f.repository.loginBarrier = barrier
        f.repository.failLogin = true
        val exit = async { f.coordinator.exitLocalMode() }
        runCurrent()

        assertTrue(f.coordinator.isLocalMode)
        assertTrue(f.operationGate.isTransitioning())
        assertTrue(f.coordinator.transition.value is LocalModeTransition.Exiting)

        barrier.complete(Unit)
        exit.await()
        assertTrue(f.coordinator.isLocalMode)
        assertFalse(f.operationGate.isTransitioning())
    }

    @Test fun `only the matching exit session may use authenticated requests before mode switch`() = runTest {
        val f = fixture()
        f.userManager.login("A", "pw")
        val snapshot = f.userManager.currentSessionSnapshot()
        var calls = 0

        runCatching { f.userManager.execute { calls++ } }
        assertEquals(0, calls)
        val result = withContext(LocalModeSyncRequest(snapshot.accountId, snapshot.generation)) {
            f.userManager.execute { calls++; "allowed" }
        }
        assertEquals("allowed", result)
        assertEquals(1, calls)
        runCatching {
            withContext(LocalModeSyncRequest(snapshot.accountId, snapshot.generation - 1)) {
                f.userManager.execute { calls++ }
            }
        }
        assertEquals(1, calls)
    }

    @Test fun `cancelled background exit unlocks favorites and keeps local mode`() = runTest {
        val f = fixture()
        f.repository.loginBarrier = CompletableDeferred()
        val exit = async { f.coordinator.exitLocalMode() }
        runCurrent()
        assertTrue(f.operationGate.isTransitioning())

        exit.cancelAndJoin()

        assertFalse(f.operationGate.isTransitioning())
        assertTrue(f.coordinator.isLocalMode)
    }

    @Test fun `background exit is bound to the account that requested it`() = runTest {
        val f = fixture()
        f.coordinator.exitLocalModeForAccount(8)
        assertTrue(f.coordinator.isLocalMode)
        assertEquals(0, f.repository.logins)
        assertFalse(f.operationGate.isTransitioning())
    }

    @Test fun `off peak retry starts at six and respects cooldown`() = runTest {
        val f = fixture()
        f.repository.failLogin = true
        fun at(hour: Int) = LocalDateTime.of(2026, 9, 24, hour, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        f.coordinator.exitLocalModeIfOffPeak(at(5))
        assertEquals(0, f.repository.logins)
        f.coordinator.exitLocalModeIfOffPeak(at(6))
        f.coordinator.exitLocalModeIfOffPeak(at(6) + 10 * 60_000L)
        assertEquals(1, f.repository.logins)
        f.coordinator.exitLocalModeIfOffPeak(at(6) + 31 * 60_000L)
        assertEquals(2, f.repository.logins)
    }

    @Test fun `daytime manual entry is not reversed until the next day`() = runTest {
        val f = fixture()
        f.repository.failLogin = true
        fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 9, day, hour, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        f.mode.localModeEnteredAtByAccount.value = mapOf(7 to at(23, 10))
        f.coordinator.exitLocalModeIfOffPeak(at(23, 11))
        assertEquals(0, f.repository.logins)
        f.coordinator.exitLocalModeIfOffPeak(at(24, 6))
        assertEquals(1, f.repository.logins)
    }

    @Test fun `another account uses network mode until original account returns`() = runTest {
        val f = fixture()
        f.userManager.login("B", "pw")
        assertFalse(f.coordinator.isLocalMode)
        f.userManager.login("A", "pw")
        assertTrue(f.coordinator.isLocalMode)
    }

    private companion object {
        fun cookie(): Cookie = Cookie.Builder().name("AVS").value("session").domain("18comic.vip").path("/").build()
    }
}
