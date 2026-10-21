package com.par9uet.jm.ui.viewModel

import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import com.par9uet.jm.data.models.Comment
import com.par9uet.jm.core.ToastManager
import com.par9uet.jm.core.model.SignInData
import com.par9uet.jm.core.model.User
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.data.models.ActionResult
import com.par9uet.jm.data.models.Comic
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
 * R08: history comic display and multi-select must follow the same account+generation
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
    fun `my comments re-filter cached pages when keywords change`() = runTest(scheduler) {
        val preferences = FakeContentPreferences()
        preferences.blockedCommentKeywords.value = listOf("VIP")
        val environment = environment(contentPreferences = preferences)
        val clean = Comment(1, 11, 1, "", "正常评论", "reader", "", "", 0, false, emptyList())
        val blocked = clean.copy(id = 2, content = "注册 vip")
        environment.repository.comments = listOf(clean, blocked)
        val presenter = object : PagingDataPresenter<Comment>(StandardTestDispatcher(scheduler)) {
            override suspend fun presentPagingDataEvent(event: PagingDataEvent<Comment>) = Unit
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            environment.viewModel.historyCommentPager.collectLatest(presenter::collectFrom)
        }
        runCurrent()
        assertEquals(listOf(clean), presenter.snapshot().items)
        preferences.blockedCommentKeywords.value = emptyList()
        runCurrent()
        assertEquals(listOf(clean, blocked), presenter.snapshot().items)
        assertEquals(1, environment.repository.commentRequests)
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
    fun `account switch clears history list and clears selection`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()

        environment.viewModel.enterHistoryEdit(comicId = 101)
        assertTrue(environment.viewModel.historyEditState.value.editing)
        assertEquals(setOf(101), environment.viewModel.historyEditState.value.selectedComicIds)
        assertEquals(1, environment.viewModel.historyEditState.value.session?.accountId)

        val pageA = loadHistoryToSettled(environment.viewModel)
        assertEquals(listOf(101), pageA.items.map { it.id })
        val pagesForA = environment.repository.historyRequests.size

        environment.loginAs(id = 2, username = "accountB")
        runCurrent()

        val edit = environment.viewModel.historyEditState.value
        assertFalse(edit.editing)
        assertTrue(edit.selectedComicIds.isEmpty())
        assertNull(edit.session)

        // 会话切换必须清空 A 的列表；重新加载得到 B 的页，绝不复用 A 的缓存。
        assertTrue(environment.viewModel.historyState.value.items.isEmpty())
        val pageB = loadHistoryToSettled(environment.viewModel)
        assertEquals(listOf(201), pageB.items.map { it.id })
        assertTrue(environment.repository.historyRequests.size > pagesForA)
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
        assertTrue(environment.viewModel.historyState.value.items.isEmpty())
    }

    @Test
    fun `refresh failure under B never reuses A's cached history`() = runTest(scheduler) {
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

    @Test
    fun `history maps a chapter entry to its original collection`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()
        val parent = historyComic(255485).copy(
            name = "原漫画合集",
            authorList = listOf("合集作者"),
            tagList = listOf("合集标签"),
        )
        environment.readHistory.recordSeries(parent)
        environment.readHistory.markRead(parent.id, 266898)
        environment.repository.historyHandler = {
            NetWorkResult.Success(
                listOf(
                    historyComic(266898).copy(
                        name = "章节标题",
                        authorList = listOf("章节作者"),
                        tagList = listOf("章节标签"),
                    ),
                ),
            )
        }

        val page = loadHistoryToSettled(environment.viewModel)

        assertEquals(listOf(255485), page.items.map { it.id })
        assertEquals("原漫画合集", page.items.single().name)
        assertEquals(listOf("合集作者"), page.items.single().authorList)
        assertEquals(listOf("合集标签"), page.items.single().tagList)
    }

    @Test
    fun `legacy chapter progress retains server id but supplies collection id for the cover`() = runTest(scheduler) {
        val env = environment()
        runCurrent()
        env.readHistory.markRead(255485, 266898)
        env.repository.historyHandler = {
            NetWorkResult.Success(listOf(historyComic(266898)))
        }
        val first = loadHistoryToSettled(env.viewModel)
        assertEquals(266898, first.items.single().id)
        assertEquals("255485", first.items.single().seriesId)
        env.viewModel.enterHistoryEdit(266898)
        env.viewModel.deleteHistoryComics(first.items)
        runCurrent()
        assertEquals(listOf(266898), env.repository.deletedIds)
    }

    @Test
    fun `deleting a mapped history entry submits the original server chapter id`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()
        environment.readHistory.recordSeries(historyComic(255485).copy(name = "原漫画合集"))
        environment.readHistory.markRead(255485, 266898)
        environment.repository.historyHandler = {
            NetWorkResult.Success(listOf(historyComic(266898)))
        }
        val page = loadHistoryToSettled(environment.viewModel)
        environment.viewModel.enterHistoryEdit(page.items.single().id)

        environment.viewModel.deleteHistoryComics(page.items)
        runCurrent()

        assertEquals(listOf(266898), environment.repository.deletedIds)
    }

    /** 历史列表每次进入只请求一次，刷新重新取 API 返回的完整列表。 */
    @Test
    fun `history requests one list per refresh`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()

        val state = loadHistoryToSettled(environment.viewModel)
        assertEquals(listOf(101), state.items.map { it.id })
        assertEquals(listOf(1), environment.repository.historyRequests)

        // 每次刷新只发送一个历史请求。
        environment.viewModel.refreshHistory()
        runCurrent()
        environment.viewModel.refreshHistory()
        runCurrent()

        assertEquals(listOf(1, 1, 1), environment.repository.historyRequests)
        assertEquals(listOf(101), environment.viewModel.historyState.value.items.map { it.id })
    }

    @Test
    fun `twenty chapter records merge into nineteen cards and delete all source chapters`() = runTest(scheduler) {
        val env = environment()
        runCurrent()
        env.readHistory.recordSeries(historyComic(100))
        env.readHistory.markRead(100, 1)
        env.readHistory.markRead(100, 2)
        env.repository.historyHandler = {
            NetWorkResult.Success((1..20).map(::historyComic))
        }
        val state = loadHistoryToSettled(env.viewModel)
        assertEquals(19, state.items.size)
        assertEquals(listOf(1), env.repository.historyRequests)
        env.viewModel.enterHistoryEdit(100)
        env.viewModel.deleteHistoryComics(state.items.filter { it.id == 100 })
        runCurrent()
        assertEquals(listOf(1, 2), env.repository.deletedIds)
    }

    @Test
    fun `history displays the complete API list without a client side limit`() = runTest(scheduler) {
        val env = environment()
        runCurrent()
        env.repository.historyHandler = {
            NetWorkResult.Success((1..25).map(::historyComic))
        }
        val state = loadHistoryToSettled(env.viewModel)
        assertEquals((1..25).toList(), state.items.map { it.id })
        assertEquals(listOf(1), env.repository.historyRequests)
    }

    @Test
    fun `refresh failure keeps same account history and can retry`() = runTest(scheduler) {
        val env = environment()
        runCurrent()
        val first = loadHistoryToSettled(env.viewModel)
        env.repository.historyHandler = { NetWorkResult.Error("网络连接失败") }
        val failed = loadHistoryToSettled(env.viewModel)
        assertEquals(first.items, failed.items)
        assertEquals("网络连接失败", failed.error)
        assertFalse(failed.loading)
        env.repository.historyHandler = { NetWorkResult.Success(emptyList()) }
        val retried = loadHistoryToSettled(env.viewModel)
        assertTrue(retried.items.isEmpty())
        assertNull(retried.error)
    }

    @Test
    fun `in-flight refresh from a previous session is discarded`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()

        environment.repository.historyDelayMs = 50
        environment.viewModel.refreshHistory()
        runCurrent()

        // 请求还在飞的时候换号：旧会话的响应不许写进新会话的列表。
        environment.loginAs(id = 2, username = "accountB")
        runCurrent()
        environment.repository.historyDelayMs = 0
        advanceUntilIdle()
        runCurrent()

        assertTrue(
            "旧会话的刷新结果不得落进 B 的列表，实际=${environment.viewModel.historyState.value.items.map { it.id }}",
            environment.viewModel.historyState.value.items.isEmpty(),
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

    /** 触发历史列表加载并驱动调度器直到落定（成功或失败），返回当前列表状态。 */
    private fun TestScope.loadHistoryToSettled(
        viewModel: UserViewModel,
    ): UserViewModel.HistoryState {
        viewModel.refreshHistory()
        runCurrent()
        var guard = 0
        while (viewModel.historyState.value.loading && guard++ < 200) {
            runCurrent()
        }
        return viewModel.historyState.value
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
        val historyRequests = mutableListOf<Int>()
        val deletedIds = mutableListOf<Int>()
        var currentAccountId: Int = 1
        var activeAccountIdForHistory: Int = 1
        var failHistoryAfterAccountSwitch = false
        var historyHandler: (suspend () -> NetWorkResult<List<Comic>>)? = null

        /** 让请求挂起一段时间，用于复现「响应在飞时换号」的竞态。 */
        var historyDelayMs: Long = 0

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

        override suspend fun getHistoryComicList(): NetWorkResult<List<Comic>> {
            historyRequests += 1
            if (historyDelayMs > 0) kotlinx.coroutines.delay(historyDelayMs)
            historyHandler?.let { return it() }
            val accountId = activeAccountIdForHistory
            if (accountId <= 0) {
                return NetWorkResult.Success(emptyList())
            }
            if (failHistoryAfterAccountSwitch && accountId != 1) {
                return NetWorkResult.Error("refresh failed")
            }
            val id = if (accountId == 1) 101 else 201
            return NetWorkResult.Success(
                listOf(historyComic(id))
            )
        }

        override suspend fun deleteHistoryComic(id: Int): NetWorkResult<Unit> {
            deletedIds += id
            return NetWorkResult.Success(Unit)
        }

        var comments = emptyList<Comment>()
        var commentRequests = 0
        override suspend fun getHistoryCommentList(page: Int, userId: Int): NetWorkResult<CommentPage> {
            commentRequests++
            return NetWorkResult.Success(CommentPage(comments, total = comments.size))
        }

        override suspend fun getSignData(userId: Int): NetWorkResult<SignInData> =
            NetWorkResult.Error("unused")

        override suspend fun signIn(userId: Int, dailyId: Int): NetWorkResult<ActionResult> =
            NetWorkResult.Error("unused")
    }

    private class FakeContentPreferences : ContentPreferences {
        override val blockedTags = MutableStateFlow<List<String>>(emptyList())
        override val blockedCommentKeywords = kotlinx.coroutines.flow.MutableStateFlow(emptyList<String>())
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
        val readHistory: com.par9uet.jm.storage.ReadHistoryManager,
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
        contentPreferences: ContentPreferences = FakeContentPreferences(),
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
        val readHistory = com.par9uet.jm.storage.ReadHistoryManager(
            object : com.par9uet.jm.storage.ReadHistoryStore {
                private var data: Map<Int, com.par9uet.jm.storage.ComicReadHistory> = emptyMap()
                override fun getOrNull() = data
                override fun set(history: Map<Int, com.par9uet.jm.storage.ComicReadHistory>) {
                    data = history
                }
            }
        )
        val viewModel = UserViewModel(
            userManager = userManager,
            userRepository = repository,
            toastManager = ToastManager(),
            contentPreferences = contentPreferences,
            downloadManager = testDownloadManager(CoroutineScope(Dispatchers.Default)),
            miscSettingsPreferences = FakeMiscSettingsPreferences(),
            localMode = com.par9uet.jm.favorites.alwaysNetworkLocalModeStatus(),
            localModeExit = object : com.par9uet.jm.core.model.LocalModeExit {
                override suspend fun exitLocalMode() = Unit
                override suspend fun exitLocalModeAfterLogin(accountId: Int, generation: Long) = Unit
            },
            favoriteSyncRequester = sync,
            readHistoryManager = readHistory,
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
        return Environment(viewModel, userManager, repository, sync, readHistory)
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
