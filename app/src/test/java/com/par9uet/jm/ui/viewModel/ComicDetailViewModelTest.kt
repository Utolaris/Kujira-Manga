package com.par9uet.jm.ui.viewModel

import androidx.paging.PagingSource
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import androidx.paging.LoadState
import androidx.paging.PagingState
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicChapter
import com.par9uet.jm.data.models.ComicSearchOrderFilter
import com.par9uet.jm.data.models.TagFilterLogic
import com.par9uet.jm.download.DownloadWorkScheduler
import com.par9uet.jm.download.RecordingDownloadDao
import com.par9uet.jm.download.atom.DownloadFiles
import com.par9uet.jm.download.coordinator.DownloadManager
import com.par9uet.jm.download.molecule.DownloadTaskOperations
import com.par9uet.jm.download.testDownloadCoordinator
import com.par9uet.jm.favorites.data.FavoriteLocalMutation
import com.par9uet.jm.favorites.data.FavoriteRemoteMutation
import com.par9uet.jm.favorites.model.FavoriteLocalQuery
import com.par9uet.jm.favorites.model.FavoriteSession
import com.par9uet.jm.favorites.model.FavoriteSessionSnapshot
import com.par9uet.jm.favorites.usecase.CollectFavorite
import com.par9uet.jm.favorites.usecase.ObserveLocalFavorite
import com.par9uet.jm.favorites.usecase.UncollectFavorites
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.data.models.ActionResult
import com.par9uet.jm.data.models.ComicPage
import com.par9uet.jm.data.models.ComicPageList
import com.par9uet.jm.data.models.ComicSearchPage
import com.par9uet.jm.data.models.CommentPage
import com.par9uet.jm.data.models.Comment
import com.par9uet.jm.data.models.HomeComicSwiperItem
import com.par9uet.jm.data.models.WeekData
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.core.ToastManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComicDetailViewModelTest {
    private lateinit var scheduler: TestCoroutineScheduler

    @Before
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `slow or failed comic B comments never display comic A and retry stays on B`() = runTest(scheduler) {
        val environment = environment()
        val pendingB = CompletableDeferred<NetWorkResult<CommentPage>>()
        val commentA = commentFor(11)
        val commentB = commentFor(22)
        val requestedIds = mutableListOf<Int>()
        var failB = true
        environment.repository.comments = { id ->
            requestedIds += id
            when {
                id == 11 -> NetWorkResult.Success(CommentPage(listOf(commentA), 1))
                failB -> pendingB.await()
                else -> NetWorkResult.Success(CommentPage(listOf(commentB), 1))
            }
        }
        fun presenter() = object : PagingDataPresenter<Comment>(StandardTestDispatcher(scheduler)) {
            override suspend fun presentPagingDataEvent(event: PagingDataEvent<Comment>) = Unit
        }
        val a = presenter()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            environment.viewModel.commentPager(11).collectLatest(a::collectFrom)
        }
        runCurrent()
        assertEquals(listOf(commentA), a.snapshot().items)

        val b = presenter()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            environment.viewModel.commentPager(22).collectLatest(b::collectFrom)
        }
        runCurrent()
        assertTrue(b.loadStateFlow.value?.refresh is LoadState.Loading)
        assertTrue(b.snapshot().items.isEmpty())
        assertEquals(listOf(commentA), a.snapshot().items)

        pendingB.complete(NetWorkResult.Error("slow API failed"))
        runCurrent()
        assertTrue(b.loadStateFlow.value?.refresh is LoadState.Error)
        assertTrue(b.snapshot().items.isEmpty())

        failB = false
        b.retry()
        runCurrent()
        assertEquals(listOf(commentB), b.snapshot().items)
        assertEquals(listOf(11, 22, 22), requestedIds)
        assertEquals(listOf(commentA), a.snapshot().items)
    }

    @Test
    fun `downloadComic delegates to DownloadManager and queues the comic task`() = runTest(scheduler) {
        val environment = environment()
        val target = comic(isCollected = false)

        environment.viewModel.downloadComic(target)
        environment.downloadJob.children.toList().joinAll()
        runCurrent()

        assertEquals(setOf(COMIC_ID), environment.downloadDao.tasks.keys)
        assertEquals(listOf(listOf(COMIC_ID)), environment.enqueuedBatches)
    }

    @Test
    fun `downloadChapters delegates to DownloadManager and queues only selected chapters`() = runTest(scheduler) {
        val environment = environment()
        val target = comic(isCollected = false)
        val chapters = listOf(
            ComicChapter(id = 101, name = "1"),
            ComicChapter(id = 102, name = "2"),
        )

        environment.viewModel.downloadChapters(target, chapters)
        environment.downloadJob.children.toList().joinAll()
        runCurrent()

        assertEquals(setOf(101, 102), environment.downloadDao.tasks.keys)
        assertEquals(listOf(listOf(101, 102)), environment.enqueuedBatches)
        assertEquals(COMIC_ID, environment.downloadDao.tasks.getValue(101).groupId)
    }

    @Test
    fun `collect success followed by account switch cannot commit stale UI success`() = runTest(scheduler) {
        val environment = environment()
        val messages = collectToasts(environment.toastManager)
        environment.prepare(comic(isCollected = false))
        environment.session.afterNextBound = { environment.session.switchAccount(43) }

        environment.viewModel.collect(COMIC_ID)
        advanceUntilIdle()

        assertEquals(listOf(Triple(42, COMIC_ID, 0)), environment.local.added)
        assertTrue(environment.local.added.none { it.first == 43 })
        assertFalse(environment.viewModel.comicDetailState.value.data!!.isCollect)
        assertFalse("收藏成功" in messages)
        assertEquals("登录状态已变化，请重试", environment.viewModel.collectComicState.value.errorMsg)
    }

    @Test
    fun `uncollect stale action removes only A local data and does not report B success`() = runTest(scheduler) {
        val environment = environment()
        val messages = collectToasts(environment.toastManager)
        // 已收藏的判据是本地快照，所以「已收藏」的夹具必须同时把本地记录摆上。
        environment.local.setLocalFavorites(42, setOf(COMIC_ID))
        environment.prepare(comic(isCollected = true))
        environment.session.afterNextBound = { environment.session.switchAccount(43) }

        environment.viewModel.unCollect(COMIC_ID)
        advanceUntilIdle()

        assertEquals(listOf(42 to COMIC_ID), environment.local.removed)
        assertTrue(environment.local.removed.none { it.first == 43 })
        // 账号已切到 43：已收藏改由 43 的本地快照决定（里面没有这本）→ 显示未收藏。
        assertFalse(environment.viewModel.comicDetailState.value.data!!.isCollect)
        assertFalse("取消收藏成功" in messages)
        assertEquals("登录状态已变化，请重试", environment.viewModel.collectComicState.value.errorMsg)
    }

    @Test
    fun `collect exposes the real remote error to ComicDetail`() = runTest(scheduler) {
        val environment = environment()
        environment.prepare(comic(isCollected = false))
        val messages = collectToasts(environment.toastManager)
        environment.remote.collectResult = NetWorkResult.Error("server collect failed", code = 503)

        environment.viewModel.collect(COMIC_ID)
        advanceUntilIdle()

        assertEquals("server collect failed", environment.viewModel.collectComicState.value.errorMsg)
        assertTrue("server collect failed" in messages)
        assertTrue(environment.local.added.isEmpty())
        assertFalse(environment.viewModel.comicDetailState.value.data!!.isCollect)
    }

    @Test
    fun `collected flag follows the local snapshot instead of the cloud detail`() = runTest(scheduler) {
        val environment = environment()
        // 云端详情说已收藏，但本地快照里没有这条 → 必须显示未收藏。
        environment.prepare(comic(isCollected = true))
        assertFalse(environment.viewModel.comicDetailState.value.data!!.isCollect)

        // 本地快照出现这条（例如收藏页刚写入）→ 立刻变成已收藏。
        environment.local.setLocalFavorites(42, setOf(COMIC_ID))
        advanceUntilIdle()
        assertTrue(environment.viewModel.comicDetailState.value.data!!.isCollect)
    }

    @Test
    fun `collected flag drops when the local snapshot no longer has the comic`() = runTest(scheduler) {
        val environment = environment()
        environment.local.setLocalFavorites(42, setOf(COMIC_ID))
        environment.prepare(comic(isCollected = false))
        assertTrue(environment.viewModel.comicDetailState.value.data!!.isCollect)

        environment.local.setLocalFavorites(42, emptySet())
        advanceUntilIdle()
        assertFalse(environment.viewModel.comicDetailState.value.data!!.isCollect)
    }

    @Test
    fun `collected flag ignores another account's local snapshot`() = runTest(scheduler) {
        val environment = environment()
        environment.local.setLocalFavorites(43, setOf(COMIC_ID))
        environment.prepare(comic(isCollected = true))

        // 当前账号是 42，43 的本地收藏不算数。
        assertFalse(environment.viewModel.comicDetailState.value.data!!.isCollect)
    }

    private fun TestEnvironment.prepare(comic: Comic) {
        viewModel.prepareDetail(comic)
        scheduler.runCurrent()
    }

    private fun kotlinx.coroutines.test.TestScope.collectToasts(
        toastManager: ToastManager,
    ): MutableList<String> {
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            toastManager.message.collect { messages += it }
        }
        return messages
    }

    private fun environment(): TestEnvironment {
        val repository = StubComicRepository()
        val toastManager = ToastManager()
        val local = FakeFavoriteLocalData()
        val session = FakeFavoriteSession()
        val remote = FakeFavoriteRemoteMutation()
        val downloadDao = RecordingDownloadDao()
        val downloadJob = SupervisorJob()
        val enqueuedBatches = mutableListOf<List<Int>>()
        val downloadManager = DownloadManager(
            DownloadTaskOperations(downloadDao, DownloadFiles()),
            kotlinx.coroutines.CoroutineScope(downloadJob),
            toastManager,
            object : DownloadWorkScheduler {
                override suspend fun cancel(comicIds: Collection<Int>) = Unit
                override fun enqueue(comicIds: Collection<Int>) {
                    enqueuedBatches += comicIds.toList()
                }
            },
            testDownloadCoordinator(downloadDao),
        )
        val environment = TestEnvironment(
            repository = repository,
            viewModel = ComicDetailViewModel(
                comicRepository = repository,
                toastManager = toastManager,
                favoriteSession = session,
                collectFavorite = CollectFavorite(remote, local, session),
                uncollectFavorites = UncollectFavorites(remote, local, session),
                observeLocalFavorite = ObserveLocalFavorite(local, session),
                downloadManager = downloadManager,
                userManager = testUserManager(),
                readHistoryManager = com.par9uet.jm.storage.ReadHistoryManager(
                    object : com.par9uet.jm.storage.ReadHistoryStore {
                        private var data: Map<Int, com.par9uet.jm.storage.ComicReadHistory>? = emptyMap()
                        override fun getOrNull() = data
                        override fun set(history: Map<Int, com.par9uet.jm.storage.ComicReadHistory>) {
                            data = history
                        }
                    }
                ),
                contentPreferences = object : com.par9uet.jm.storage.ContentPreferences {
                    override val blockedTags = MutableStateFlow(emptyList<String>())
                },
            ),
            toastManager = toastManager,
            local = local,
            session = session,
            remote = remote,
            downloadDao = downloadDao,
            downloadJob = downloadJob,
            enqueuedBatches = enqueuedBatches,
        )
        return environment
    }

    private fun testUserManager(): com.par9uet.jm.session.UserManager {
        val readiness = com.par9uet.jm.session.SessionReadinessHolder()
        readiness.set(com.par9uet.jm.session.SessionReadiness.Authenticated)
        val repository = object : com.par9uet.jm.session.UserRepository {
            override suspend fun login(username: String, password: String) =
                com.par9uet.jm.core.network.NetWorkResult.Error("unused")

            override suspend fun probeActiveSession() =
                com.par9uet.jm.core.network.NetWorkResult.Error("unused")

            override fun activateVerifiedSession(verified: com.par9uet.jm.session.CandidateSession) = false
            override fun clearSession() = Unit
            override suspend fun getHistoryComicList(page: Int) =
                com.par9uet.jm.core.network.NetWorkResult.Error("unused")

            override suspend fun deleteHistoryComic(id: Int) =
                com.par9uet.jm.core.network.NetWorkResult.Error("unused")

            override suspend fun getHistoryCommentList(page: Int, userId: Int) =
                com.par9uet.jm.core.network.NetWorkResult.Error("unused")

            override suspend fun getSignData(userId: Int) =
                com.par9uet.jm.core.network.NetWorkResult.Error("unused")

            override suspend fun signIn(userId: Int, dailyId: Int) =
                com.par9uet.jm.core.network.NetWorkResult.Error("unused")
        }
        return com.par9uet.jm.session.UserManager(
            object : com.par9uet.jm.storage.UserStorage {
                private var stored: com.par9uet.jm.core.model.User = com.par9uet.jm.core.model.User.create()
                override fun get() = stored
                override fun set(user: com.par9uet.jm.core.model.User) { stored = user }
                override fun remove() { stored = com.par9uet.jm.core.model.User.create() }
            },
            object : com.par9uet.jm.storage.CookieStorage {
                override val state = MutableStateFlow<List<okhttp3.Cookie>?>(null)
                private var cookies: List<okhttp3.Cookie> = emptyList()
                override fun set(cookieStore: List<okhttp3.Cookie>): Boolean {
                    cookies = cookieStore
                    return true
                }
                override fun get(): List<okhttp3.Cookie> = cookies
                override fun remove() { cookies = emptyList() }
            },
            repository,
            readiness,
        )
    }

    private data class TestEnvironment(
        val repository: StubComicRepository,
        val viewModel: ComicDetailViewModel,
        val toastManager: ToastManager,
        val local: FakeFavoriteLocalData,
        val session: FakeFavoriteSession,
        val remote: FakeFavoriteRemoteMutation,
        val downloadDao: RecordingDownloadDao,
        val downloadJob: kotlinx.coroutines.CompletableJob,
        val enqueuedBatches: MutableList<List<Int>>,
    )

    private class FakeFavoriteSession : FavoriteSession {
        private val account = MutableStateFlow(42)
        private var generation = 0L
        var snapshotCalls = 0
        val boundAttempts = mutableListOf<FavoriteSessionSnapshot>()
        var afterNextBound: (() -> Unit)? = null

        private val _session = MutableStateFlow(FavoriteSessionSnapshot(42, 0L))
        override val sessionFlow = _session.asStateFlow()
        override val accountIdFlow: StateFlow<Int> = account.asStateFlow()
        override fun currentAccountId(): Int = account.value

        override fun snapshot(): FavoriteSessionSnapshot {
            snapshotCalls++
            return FavoriteSessionSnapshot(account.value, generation)
        }

        override fun isCurrent(snapshot: FavoriteSessionSnapshot): Boolean =
            snapshot.accountId == account.value && snapshot.generation == generation

        override suspend fun <T> withCurrentSession(
            snapshot: FavoriteSessionSnapshot,
            block: suspend () -> T,
        ): T? = if (isCurrent(snapshot)) block() else null

        override suspend fun <T> withBoundRemoteSession(
            snapshot: FavoriteSessionSnapshot,
            block: suspend () -> T,
        ): T? {
            boundAttempts += snapshot
            if (!isCurrent(snapshot)) return null
            val result = block()
            afterNextBound?.also {
                afterNextBound = null
                it()
            }
            return result
        }

        fun switchAccount(accountId: Int) {
            generation++
            account.value = accountId
            _session.value = FavoriteSessionSnapshot(accountId, generation)
        }
    }

    private class FakeFavoriteRemoteMutation : FavoriteRemoteMutation {
        val collectedIds = mutableListOf<Int>()
        val movedIds = mutableListOf<Pair<Int, Int>>()
        var collectResult: NetWorkResult<Unit> = NetWorkResult.Success(Unit)
        var uncollectResult: NetWorkResult<Unit> = NetWorkResult.Success(Unit)
        var moveResult: NetWorkResult<Unit> = NetWorkResult.Success(Unit)

        override suspend fun collectComic(comicId: Int): NetWorkResult<Unit> {
            collectedIds += comicId
            return collectResult
        }

        override suspend fun uncollectComic(comicId: Int): NetWorkResult<Unit> = uncollectResult
        override suspend fun createFolder(name: String): NetWorkResult<Unit> = NetWorkResult.Success(Unit)
        override suspend fun deleteFolder(folderId: Int): NetWorkResult<Unit> = NetWorkResult.Success(Unit)
        override suspend fun renameFolder(folderId: Int, name: String): NetWorkResult<Unit> =
            NetWorkResult.Success(Unit)

        override suspend fun moveComicToFolder(comicId: Int, folderId: Int): NetWorkResult<Unit> {
            movedIds += comicId to folderId
            return moveResult
        }
    }

    private class FakeFavoriteLocalData : FavoriteLocalQuery, FavoriteLocalMutation {
        private val folderFlows = mutableMapOf<Int, MutableStateFlow<Map<String, String>>>()
        val observedAccounts = mutableListOf<Int>()
        val added = mutableListOf<Triple<Int, Int, Int>>()
        val removed = mutableListOf<Pair<Int, Int>>()
        val moved = mutableListOf<Triple<Int, Int, Int>>()

        /** accountId → 本地已收藏的 albumId。「已收藏」判据就是它。 */
        private val localFavorites = MutableStateFlow<Map<Int, Set<Int>>>(emptyMap())

        fun setLocalFavorites(accountId: Int, albumIds: Set<Int>) {
            localFavorites.value = localFavorites.value + (accountId to albumIds)
        }

        override fun observeIsFavorite(accountId: Int, albumId: Int): Flow<Boolean> =
            localFavorites.map { ids -> ids[accountId]?.contains(albumId) == true }

        fun updateFolders(accountId: Int, folders: Map<String, String>) {
            folderFlows.getOrPut(accountId) { MutableStateFlow(emptyMap()) }.value = folders
        }

        override fun observeFolders(accountId: Int): Flow<Map<String, String>> {
            observedAccounts += accountId
            return folderFlows.getOrPut(accountId) { MutableStateFlow(emptyMap()) }
        }

        override suspend fun getCachedFolders(accountId: Int): Map<String, String> =
            folderFlows.getOrPut(accountId) { MutableStateFlow(emptyMap()) }.value

        override suspend fun addFromComic(accountId: Int, comic: Comic, folderId: Int) {
            added += Triple(accountId, comic.id, folderId)
        }

        override suspend fun remove(accountId: Int, albumIds: Collection<Int>) {
            removed += albumIds.map { accountId to it }
        }

        override suspend fun moveToFolder(accountId: Int, albumId: Int, folderId: Int) {
            moved += Triple(accountId, albumId, folderId)
        }

        override suspend fun cacheFolder(accountId: Int, folderId: Int, name: String) = Unit
        override suspend fun removeFolder(accountId: Int, folderId: Int) = Unit
        override suspend fun renameFolder(accountId: Int, folderId: Int, name: String) = Unit
        override fun observeTagCounts(accountId: Int, folderId: Int): Flow<Map<String, Int>> = flowOf(emptyMap())
        override fun observeAuthorCounts(accountId: Int, folderId: Int): Flow<Map<String, Int>> = flowOf(emptyMap())
        override suspend fun getComics(accountId: Int, albumIds: Collection<Int>): List<Comic> = emptyList()

        override fun pagingSource(
            accountId: Int,
            blockedTagList: List<String>,
            searchText: String,
            selectedTags: Set<String>,
            selectedAuthors: Set<String>,
            folderId: Int,
            tagLogic: TagFilterLogic,
        ): PagingSource<Int, Comic> = EmptyFavoritePagingSource()
    }

    private class EmptyFavoritePagingSource : PagingSource<Int, Comic>() {
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Comic> =
            LoadResult.Page(emptyList(), prevKey = null, nextKey = null)

        override fun getRefreshKey(state: PagingState<Int, Comic>): Int? = null
    }

    private class StubComicRepository : ComicRepository {
        var comments: suspend (Int) -> NetWorkResult<CommentPage> = { unused() }
        override suspend fun getComicDetail(id: Int): NetWorkResult<Comic> =
            NetWorkResult.Error("detail not needed")

        override suspend fun collectComic(id: Int): NetWorkResult<Unit> = unused()
        override suspend fun unCollectComic(id: Int): NetWorkResult<Unit> = unused()
        override suspend fun getEmbeddedHomeCategory(categoryId: String): NetWorkResult<List<Comic>> = unused()
        override suspend fun getNetworkHomePage(): NetWorkResult<List<HomeComicSwiperItem>> = unused()
        override suspend fun getComicPicList(id: Int): NetWorkResult<ComicPageList> = unused()
        override suspend fun downloadImageBytes(comicId: Int, imageIndex: Int): ByteArray? = null
        override suspend fun getComicList(page: Int, order: ComicSearchOrderFilter, searchContent: String): NetWorkResult<ComicSearchPage> = unused()
        override suspend fun getWeekData(): NetWorkResult<WeekData> = unused()
        override suspend fun getWeekRecommendComicList(page: Int, categoryId: String, typeId: String): NetWorkResult<ComicPage> = unused()
        override suspend fun getCommentList(page: Int, comicId: Int): NetWorkResult<CommentPage> = comments(comicId)
        override suspend fun comment(content: String, comicId: Int, commentId: Int?): NetWorkResult<ActionResult> = unused()
        override suspend fun getComicIdsByTag(tagName: String, maxPages: Int): Set<Int> = emptySet()

        private fun <T> unused(): NetWorkResult<T> = NetWorkResult.Error("unused")
    }

    private companion object {
        const val COMIC_ID = 11

        fun commentFor(comicId: Int) = Comment(
            userId = 1, comicId = comicId, id = comicId, time = "", content = "Comment $comicId",
            username = "Reader", nickname = "", avatar = "", parentId = 0,
            spoiler = false, replyCommentList = emptyList(),
        )

        fun comic(isCollected: Boolean): Comic =
            Comic.create(COMIC_ID, "Comic", listOf("Author")).copy(isCollect = isCollected)
    }
}
