package com.par9uet.jm.ui.viewModel

import androidx.paging.LoadState
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import com.par9uet.jm.core.ToastManager
import com.par9uet.jm.core.model.SignInData
import com.par9uet.jm.core.model.User
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.data.models.ActionResult
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicPage
import com.par9uet.jm.data.models.CommentPage
import com.par9uet.jm.database.dao.DownloadComicDao
import com.par9uet.jm.download.DownloadWorkScheduler
import com.par9uet.jm.download.atom.DownloadFileRemoval
import com.par9uet.jm.download.coordinator.DownloadExecutionControl
import com.par9uet.jm.download.coordinator.DownloadManager
import com.par9uet.jm.download.molecule.DownloadTaskOperations
import com.par9uet.jm.favorites.model.FavoriteSyncUiState
import com.par9uet.jm.favorites.sync.FavoriteSyncRequestKind
import com.par9uet.jm.favorites.sync.FavoriteSyncRequester
import com.par9uet.jm.retrofit.model.LoginResponse
import com.par9uet.jm.session.CandidateSession
import com.par9uet.jm.session.SessionReadiness
import com.par9uet.jm.session.SessionReadinessHolder
import com.par9uet.jm.session.UserManager
import com.par9uet.jm.session.UserRepository
import com.par9uet.jm.storage.ContentPreferences
import com.par9uet.jm.storage.CookieStorage
import com.par9uet.jm.storage.UserStorage
import java.lang.reflect.Proxy
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Cookie
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R08: history comic paging and multi-select must follow the same account+generation
 * lifecycle as history comments. A→B, logout, and refresh failure must clear A's display
 * and selection immediately; A's ids must never submit under B's session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserViewModelHistorySessionTest {
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `manual login requests account cache initialization`() = runTest(scheduler) {
        val environment = environment()
        environment.repository.currentAccountId = 2

        environment.viewModel.login("accountB", "pwd")
        runCurrent()

        assertEquals(1, environment.sync.initializationCalls)
    }

    @Test
    fun `account switch rebuilds history pager and clears selection`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()

        environment.viewModel.enterHistoryEdit(comicId = 101)
        assertTrue(environment.viewModel.historyEditState.value.editing)
        assertEquals(setOf(101), environment.viewModel.historyEditState.value.selectedComicIds)
        assertEquals(1, environment.viewModel.historyEditState.value.session?.accountId)

        val pageA = loadHistoryToSettled(environment.viewModel)
        assertEquals(listOf(101), pageA.items.map { it.id })
        val pagesForA = environment.repository.historyPageRequests.size

        environment.loginAs(id = 2, username = "accountB")
        runCurrent()

        val edit = environment.viewModel.historyEditState.value
        assertFalse(edit.editing)
        assertTrue(edit.selectedComicIds.isEmpty())
        assertNull(edit.session)

        // 会话切换必须清空 A 的列表；重新加载得到 B 的页，绝不复用 A 的缓存。
        assertTrue(environment.viewModel.historyPageState.value.items.isEmpty())
        val pageB = loadHistoryToSettled(environment.viewModel)
        assertEquals(listOf(201), pageB.items.map { it.id })
        assertTrue(environment.repository.historyPageRequests.size > pagesForA)
    }

    @Test
    fun `logout immediately clears history display and selection`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()

        environment.viewModel.enterHistoryEdit(comicId = 101)
        val pageA = loadHistoryToSettled(environment.viewModel)
        assertEquals(listOf(101), pageA.items.map { it.id })

        environment.repository.activeAccountIdForHistory = 0
        environment.viewModel.logout()
        runCurrent()

        val edit = environment.viewModel.historyEditState.value
        assertFalse(edit.editing)
        assertTrue(edit.selectedComicIds.isEmpty())
        assertNull(edit.session)

        // Unauthenticated state starts empty instead of reusing A's items.
        assertTrue(environment.viewModel.historyPageState.value.items.isEmpty())
    }

    @Test
    fun `refresh failure under B never reuses A's cached pages`() = runTest(scheduler) {
        val environment = environment(historyFailuresAfterSwitch = true)
        runCurrent()

        val pageA = loadHistoryToSettled(environment.viewModel)
        assertEquals(listOf(101), pageA.items.map { it.id })

        environment.loginAs(id = 2, username = "accountB")
        runCurrent()

        val pageB = loadHistoryToSettled(environment.viewModel)
        assertTrue(
            "B must not see A's cached history after a failed refresh, got ${pageB.items}",
            pageB.items.isEmpty(),
        )
        assertTrue("refresh failure must surface as error, got ${pageB.error}", pageB.error != null)
    }

    /**
     * 历史页只有一页：刷新只打第 1 页，且**不会再有任何后续请求**——
     * 这是「往下滑动不产生新请求」的回归防线（曾经有过上拉加载第二页的实现，已撤销）。
     */
    @Test
    fun `history only ever requests the first page`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()

        val state = loadHistoryToSettled(environment.viewModel)
        assertEquals(listOf(101), state.items.map { it.id })
        assertEquals(listOf(1), environment.repository.historyPageRequests)

        // 多次刷新仍然只碰第 1 页，页码不会推进。
        environment.viewModel.refreshHistory()
        runCurrent()
        environment.viewModel.refreshHistory()
        runCurrent()

        assertEquals(listOf(1, 1, 1), environment.repository.historyPageRequests)
        assertEquals(listOf(101), environment.viewModel.historyPageState.value.items.map { it.id })
    }

    @Test
    fun `in-flight refresh from a previous session is discarded`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()

        environment.repository.historyPageDelayMs = 50
        environment.viewModel.refreshHistory()
        runCurrent()

        // 请求还在飞的时候换号：旧会话的响应不许写进新会话的列表。
        environment.loginAs(id = 2, username = "accountB")
        runCurrent()
        environment.repository.historyPageDelayMs = 0
        advanceUntilIdle()
        runCurrent()

        assertTrue(
            "旧会话的刷新结果不得落进 B 的列表，实际=${environment.viewModel.historyPageState.value.items.map { it.id }}",
            environment.viewModel.historyPageState.value.items.isEmpty(),
        )
    }

    @Test
    fun `stale A selection is not submitted under B session`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()

        environment.viewModel.enterHistoryEdit(comicId = 101)
        environment.loginAs(id = 2, username = "accountB")
        runCurrent()

        // Dialog/selection race: A's comics are still held by the caller, but the
        // selection ownership snapshot no longer matches B's session.
        environment.viewModel.deleteHistoryComics(listOf(historyComic(101)))
        runCurrent()

        assertTrue(
            "deleteHistoryComic must not run for a stale selection, calls=${environment.repository.deletedIds}",
            environment.repository.deletedIds.isEmpty(),
        )
        assertTrue(environment.viewModel.historyEditState.value.selectedComicIds.isEmpty())
    }

    @Test
    fun `selection under the current session can still delete`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()

        environment.viewModel.enterHistoryEdit(comicId = 101)
        environment.viewModel.deleteHistoryComics(listOf(historyComic(101)))
        runCurrent()

        assertEquals(listOf(101), environment.repository.deletedIds)
        assertTrue(environment.viewModel.historyEditState.value.selectedComicIds.isEmpty())
    }

    @Test
    fun `logout before delete refuses the batch`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()

        environment.viewModel.enterHistoryEdit(comicId = 101)
        environment.viewModel.logout()
        runCurrent()

        environment.viewModel.deleteHistoryComics(listOf(historyComic(101)))
        runCurrent()

        assertTrue(environment.repository.deletedIds.isEmpty())
    }

    /** 触发历史首页加载并驱动调度器直到落定（成功或失败），返回当前分页状态。 */
    private fun TestScope.loadHistoryToSettled(
        viewModel: UserViewModel,
    ): UserViewModel.HistoryPageState {
        viewModel.refreshHistory()
        runCurrent()
        var guard = 0
        while (viewModel.historyPageState.value.loading && guard++ < 200) {
            runCurrent()
        }
        return viewModel.historyPageState.value
    }

    private class FakeUserStorage(initial: User = User.create()) : UserStorage {
        private val state = MutableStateFlow(initial)
        override fun get(): User = state.value
        override fun set(user: User) {
            state.value = user
        }

        override fun remove() {
            state.value = User.create()
        }
    }

    private class FakeCookieStorage(initial: List<Cookie> = emptyList()) : CookieStorage {
        private val _state = MutableStateFlow<List<Cookie>?>(initial)
        override val state: StateFlow<List<Cookie>?> = _state.asStateFlow()
        override fun set(cookieStore: List<Cookie>): Boolean {
            _state.value = cookieStore
            return true
        }

        override fun get(): List<Cookie> = _state.value ?: emptyList()
        override fun remove() {
            _state.value = emptyList()
        }
    }

    private class HistoryRepository : UserRepository {
        val historyPageRequests = mutableListOf<Int>()
        val deletedIds = mutableListOf<Int>()
        var currentAccountId: Int = 1
        var activeAccountIdForHistory: Int = 1
        var failHistoryAfterAccountSwitch = false

        /** 让请求挂起一段时间，用于复现「响应在飞时换号」的竞态。 */
        var historyPageDelayMs: Long = 0

        override suspend fun login(username: String, password: String): NetWorkResult<CandidateSession> {
            val id = currentAccountId
            return NetWorkResult.Success(
                CandidateSession(
                    loginResponse = testLoginResponse(id, username),
                    embeddedCookies = listOf(testAvsCookie("session-$id")),
                )
            )
        }

        override suspend fun probeActiveSession(): NetWorkResult<Unit> = error("unused")

        override fun activateVerifiedSession(verified: CandidateSession, identity: com.par9uet.jm.core.model.User) = true
        override fun clearSession() = Unit

        override suspend fun getHistoryComicList(page: Int): NetWorkResult<ComicPage> {
            historyPageRequests += page
            if (historyPageDelayMs > 0) kotlinx.coroutines.delay(historyPageDelayMs)
            val accountId = activeAccountIdForHistory
            if (accountId <= 0) {
                return NetWorkResult.Success(ComicPage(items = emptyList(), total = null))
            }
            if (failHistoryAfterAccountSwitch && accountId != 1) {
                return NetWorkResult.Error("refresh failed")
            }
            val id = if (accountId == 1) 101 else 201
            return NetWorkResult.Success(
                ComicPage(items = listOf(historyComic(id)), total = null)
            )
        }

        override suspend fun deleteHistoryComic(id: Int): NetWorkResult<Unit> {
            deletedIds += id
            return NetWorkResult.Success(Unit)
        }

        override suspend fun getHistoryCommentList(page: Int, userId: Int): NetWorkResult<CommentPage> =
            NetWorkResult.Success(CommentPage(emptyList(), total = 0))

        override suspend fun getSignData(userId: Int): NetWorkResult<SignInData> =
            NetWorkResult.Error("unused")

        override suspend fun signIn(userId: Int, dailyId: Int): NetWorkResult<ActionResult> =
            NetWorkResult.Error("unused")
    }

    private class FakeContentPreferences : ContentPreferences {
        override val blockedTags: StateFlow<List<String>> = MutableStateFlow(emptyList())
    }

    private class FakeMiscSettingsPreferences : com.par9uet.jm.storage.MiscSettingsPreferences {
        override val misc: StateFlow<com.par9uet.jm.storage.MiscSettingsState> =
            MutableStateFlow(com.par9uet.jm.storage.MiscSettingsState())
    }

    private class Environment(
        val viewModel: UserViewModel,
        val userManager: UserManager,
        val repository: HistoryRepository,
        val sync: FakeSyncRequester,
    ) {
        suspend fun loginAs(id: Int, username: String) {
            repository.currentAccountId = id
            repository.activeAccountIdForHistory = id
            userManager.login(username, "pwd")
        }
    }

    private class FakeSyncRequester : FavoriteSyncRequester {
        override val state: StateFlow<FavoriteSyncUiState> = MutableStateFlow(FavoriteSyncUiState())
        var initializationCalls = 0
        override fun request(kind: FavoriteSyncRequestKind, folderId: Int) = Unit
        override suspend fun initializeForLogin() { initializationCalls++ }
    }

    private fun environment(
        historyFailuresAfterSwitch: Boolean = false,
    ): Environment {
        val repository = HistoryRepository().apply {
            failHistoryAfterAccountSwitch = historyFailuresAfterSwitch
        }
        val cookies = FakeCookieStorage(listOf(testAvsCookie("session-1")))
        val readiness = SessionReadinessHolder()
        val userManager = UserManager(
            FakeUserStorage(testUser(1, "accountA")),
            cookies,
            repository,
            readiness,
            com.par9uet.jm.favorites.fakeNightPrompt(), com.par9uet.jm.favorites.FakeConnectionMode(),
        )
        readiness.set(SessionReadiness.Authenticated)
        val sync = FakeSyncRequester()
        val viewModel = UserViewModel(
            userManager = userManager,
            userRepository = repository,
            toastManager = ToastManager(),
            contentPreferences = FakeContentPreferences(),
            downloadManager = testDownloadManager(CoroutineScope(Dispatchers.Default)),
            miscSettingsPreferences = FakeMiscSettingsPreferences(),
            localMode = com.par9uet.jm.favorites.alwaysNetworkLocalModeStatus(),
            localModeExit = object : com.par9uet.jm.core.model.LocalModeExit {
                override suspend fun exitLocalMode() = Unit
                override suspend fun exitLocalModeAfterLogin(accountId: Int, generation: Long) = Unit
            },
            favoriteSyncRequester = sync,
            localBrowseHistory = com.par9uet.jm.storage.LocalBrowseHistoryManager(
                object : com.par9uet.jm.storage.LocalBrowseHistoryStore {
                    private var data: List<com.par9uet.jm.storage.LocalBrowseHistoryEntry> = emptyList()
                    override fun getOrNull() = data
                    override fun set(entries: List<com.par9uet.jm.storage.LocalBrowseHistoryEntry>): Boolean {
                        data = entries
                        return true
                    }
                }
            ),
        )
        return Environment(viewModel, userManager, repository, sync)
    }
}

private fun historyComic(id: Int): Comic = Comic(
    id = id,
    name = "comic-$id",
    authorList = listOf("author"),
    description = "",
    readCount = 0,
    likeCount = 0,
    commentCount = 0,
    tagList = emptyList(),
    roleList = emptyList(),
    workList = emptyList(),
    price = 0,
)

private fun testLoginResponse(id: Int, name: String): LoginResponse = LoginResponse(
    uid = id,
    username = name,
    email = "",
    photo = "",
    coin = "0",
    album_favorites = 0,
    level_name = "M",
    level = 1,
    nextLevelExp = 100,
    exp = 0,
    expPercent = 0.0,
    album_favorites_max = 100,
)

private fun testAvsCookie(value: String): Cookie = Cookie.Builder()
    .name("AVS")
    .value(value)
    .domain("18comic.vip")
    .path("/")
    .build()

private fun testUser(id: Int, name: String, password: String = "pwd"): User = User(
    id = id,
    username = name,
    password = password,
    avatar = "",
    level = 1,
    levelName = "M",
    currentLevelExp = 0,
    nextLevelExp = 100,
    currentCollectCount = 0,
    maxCollectCount = 100,
    jCoin = 0,
)

private fun testDownloadManager(scope: CoroutineScope): DownloadManager {
    val dao = Proxy.newProxyInstance(
        DownloadComicDao::class.java.classLoader,
        arrayOf(DownloadComicDao::class.java),
    ) { _, method, _ -> error("Unexpected DownloadComicDao.${method.name}") } as DownloadComicDao
    return DownloadManager(
        operations = DownloadTaskOperations(dao, DownloadFileRemoval { _, _ -> true }),
        scope = scope,
        toastManager = ToastManager(),
        downloadWorkScheduler = object : DownloadWorkScheduler {
            override fun enqueue(comicIds: Collection<Int>) = Unit
            override suspend fun cancel(comicIds: Collection<Int>) = Unit
        },
        coordinator = object : DownloadExecutionControl {
            override suspend fun <T> withStoppedDownloads(
                comicIds: Collection<Int>,
                block: suspend () -> T,
            ): T = block()
        },
    )
}
