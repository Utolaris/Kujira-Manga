package com.par9uet.jm.startup

import com.par9uet.jm.core.ToastManager
import com.par9uet.jm.core.concurrent.ForegroundWork
import com.par9uet.jm.core.model.User
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.data.models.LocalSetting
import com.par9uet.jm.di.UserManagerFavoriteSession
import com.par9uet.jm.favorites.FakeConnectionMode
import com.par9uet.jm.favorites.fakeNightPrompt
import com.par9uet.jm.favorites.sync.FavoriteAutoSyncCoordinator
import com.par9uet.jm.favorites.sync.FavoriteSyncController
import com.par9uet.jm.favorites.model.FavoriteSyncReport
import com.par9uet.jm.favorites.sync.FavoriteSyncRequestKind
import com.par9uet.jm.favorites.sync.FavoriteSyncRequester
import com.par9uet.jm.launcher.LauncherIdentityApplier
import com.par9uet.jm.network.DohManager
import com.par9uet.jm.network.RemoteConfigManager
import com.par9uet.jm.network.RemoteConfigStore
import com.par9uet.jm.network.RemoteSettingFetch
import com.par9uet.jm.session.LocalModeGate
import com.par9uet.jm.session.SessionReadinessHolder
import com.par9uet.jm.session.UserManager
import com.par9uet.jm.session.UserRepository
import com.par9uet.jm.storage.CookieStorage
import com.par9uet.jm.storage.LocalSettingLoadResult
import com.par9uet.jm.storage.LocalSettingManager
import com.par9uet.jm.storage.LocalSettingPersistence
import com.par9uet.jm.storage.StorageWriteResult
import com.par9uet.jm.storage.UserStorage
import java.lang.reflect.Proxy
import java.lang.reflect.Type
import com.par9uet.jm.cache.CacheSize
import com.par9uet.jm.cache.atom.CacheFiles
import com.par9uet.jm.cache.molecule.CacheCleanupOperations
import com.par9uet.jm.notification.NotificationSource
import com.par9uet.jm.notification.NotificationUnreadCoordinator
import com.par9uet.jm.notification.model.NotificationUnreadCounts
import com.par9uet.jm.session.AccountRequestScope
import com.par9uet.jm.session.UserSessionSnapshot
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/** Exercises the real startup coordinator and sync controller without composing Favorites. */
@OptIn(ExperimentalCoroutinesApi::class)
class ColdStartFavoriteSyncTest {
    @Test fun `disk maintenance waits for first home load and any favorite sync`() = runTest {
        val homeReady = CompletableDeferred<Unit>()
        val syncReady = CompletableDeferred<Unit>()
        val environment = environment(homeReady = homeReady, syncReady = syncReady)
        try {
            environment.startup.start()
            runCurrent()
            assertEquals(0, environment.cacheScans[0])
            environment.probeReady.complete(Unit)
            runCurrent()
            // 冷启动不再发起收藏同步，同步只由进入收藏页触发。
            assertTrue(environment.requests.isEmpty())
            assertEquals(0, environment.cacheScans[0])
            environment.controller.request(FavoriteSyncRequestKind.AUTO, 3)
            homeReady.complete(Unit)
            runCurrent()
            // 首页首批内容已就绪，但用户触发的同步仍在进行，维护不能抢磁盘。
            assertEquals(listOf(3), environment.requests)
            assertEquals(0, environment.cacheScans[0])
            syncReady.complete(Unit)
            advanceTimeBy(3_001)
            runCurrent()
            assertEquals(1, environment.cacheScans[0])
        } finally { environment.application.close() }
    }

    @Test
    fun `startup probes the saved session without starting a favorite sync`() = runTest {
        val environment = environment()
        try {
            environment.startup.start()
            runCurrent()
            assertEquals(1, environment.probes[0])
            assertTrue(environment.requests.isEmpty())
            environment.startup.start()
            environment.probeReady.complete(Unit)
            advanceUntilIdle()
            // 冷启动不再预取收藏：同步只由进入收藏页（FavoritesIntent.Entered）发起。
            assertTrue(environment.requests.isEmpty())
            assertFalse(environment.controller.state.value.hasSynced)
            assertEquals(1, environment.probes[0])
            environment.controller.request(FavoriteSyncRequestKind.AUTO, 3)
            runCurrent()
            assertEquals(listOf(3), environment.requests)
        } finally { environment.application.close() }
    }

    @Test
    fun `local mode and missing saved account do not start a cold sync`() = runTest {
        for ((local, hasUser) in listOf(true to true, false to false)) {
            val environment = environment(local, hasUser)
            try {
                environment.startup.start()
                runCurrent()
                assertEquals(0, environment.probes[0])
                assertTrue(environment.requests.isEmpty())
            } finally { environment.application.close() }
        }
    }

    @Test
    fun `recent durable completion suppresses every folder until sixty seconds`() = runTest {
        val environment = environment(lastCompletedAt = 1_000_000L)
        try {
            environment.probeReady.complete(Unit)
            environment.startup.start()
            runCurrent()
            assertEquals(1, environment.probes[0])
            assertTrue(environment.requests.isEmpty())
            advanceTimeBy(59_999L)
            environment.controller.request(FavoriteSyncRequestKind.AUTO, 8)
            runCurrent()
            assertTrue(environment.requests.isEmpty())
            advanceTimeBy(1L)
            environment.controller.request(FavoriteSyncRequestKind.AUTO, 9)
            runCurrent()
            assertEquals(listOf(9), environment.requests)
            // 命中冷却的自动请求仍要恢复持久化的完成标记；已有同步错误不得被覆盖。
            environment.controller.request(FavoriteSyncRequestKind.AUTO, 10)
            runCurrent()
            assertEquals(listOf(9), environment.requests)
            assertTrue(environment.controller.state.value.hasSynced)
        } finally { environment.application.close() }
    }

    @Test fun `unread request is last after authentication cache and first home load and runs once`() = runTest {
        val syncReady = CompletableDeferred<Unit>()
        val cacheReady = CompletableDeferred<Unit>()
        val homeReady = CompletableDeferred<Unit>()
        val environment = environment(syncReady = syncReady, cacheReady = cacheReady, homeReady = homeReady)
        try {
            environment.startup.start(); runCurrent()
            assertEquals(0, environment.unreadRequests[0])
            environment.probeReady.complete(Unit); runCurrent()
            assertTrue(environment.requests.isEmpty())
            assertEquals(0, environment.unreadRequests[0])
            cacheReady.complete(Unit); runCurrent()
            assertEquals(0, environment.unreadRequests[0])
            syncReady.complete(Unit); runCurrent()
            assertEquals(0, environment.unreadRequests[0])
            homeReady.complete(Unit); runCurrent()
            advanceTimeBy(3_001); runCurrent()
            assertEquals(1, environment.unreadRequests[0])
            environment.startup.start(); runCurrent()
            assertEquals(1, environment.unreadRequests[0])
        } finally { environment.application.close() }
    }

    @Test fun `startup maintenance waits for visible covers and scrolling after data is ready`() = runTest {
        val foreground = ForegroundWork()
        foreground.update("visible-cover", true)
        val environment = environment(foregroundWork = foreground)
        try {
            environment.probeReady.complete(Unit)
            environment.startup.start()
            advanceTimeBy(10_000); runCurrent()
            assertEquals(0, environment.cacheScans[0])
            assertEquals(0, environment.unreadRequests[0])
            foreground.update("scroll", true)
            foreground.update("visible-cover", false)
            advanceTimeBy(2_000); runCurrent()
            assertEquals(0, environment.cacheScans[0])
            foreground.update("scroll", false)
            advanceTimeBy(3_001); runCurrent()
            assertEquals(1, environment.cacheScans[0])
            assertEquals(1, environment.unreadRequests[0])
        } finally { environment.application.close() }
    }

    private data class Environment(
        val application: org.koin.core.KoinApplication,
        val startup: PostStartupCoordinator,
        val controller: FavoriteSyncController,
        val probeReady: CompletableDeferred<Unit>,
        val probes: IntArray,
        val requests: MutableList<Int>,
        val unreadRequests: IntArray,
        val cacheScans: IntArray,
    )

    private fun TestScope.environment(
        local: Boolean = false, hasUser: Boolean = true, lastCompletedAt: Long? = null,
        syncReady: CompletableDeferred<Unit> = CompletableDeferred(Unit),
        cacheReady: CompletableDeferred<Unit> = CompletableDeferred(Unit),
        homeReady: CompletableDeferred<Unit> = CompletableDeferred(Unit),
        foregroundWork: ForegroundWork = ForegroundWork(),
    ): Environment {
        val settings = LocalSettingManager(object : LocalSettingPersistence {
            override fun load() = LocalSettingLoadResult.Success(LocalSetting(
                dohEnabled = false, dohAutoSelectFastest = false, cacheBudgetMb = 512,
            ))
            override fun persist(localSetting: LocalSetting) = StorageWriteResult.Success
        }, object : LauncherIdentityApplier {
            override fun apply(disguise: com.par9uet.jm.data.models.LauncherDisguise) = true
        })
        var storedUser = if (hasUser) User.create().copy(id = 7, username = "saved", password = "test") else User.create()
        val users = object : UserStorage {
            override fun get() = storedUser
            override fun set(user: User) { storedUser = user }
            override fun remove() { storedUser = User.create() }
        }
        val cookies = object : CookieStorage {
            override val state = MutableStateFlow<List<Cookie>?>(listOf(
                Cookie.Builder().name("AVS").value("saved").domain("example.com").build(),
            ))
            override fun get() = state.value.orEmpty()
            override fun set(cookieStore: List<Cookie>): Boolean { state.value = cookieStore; return true }
            override fun remove() { state.value = emptyList() }
        }
        val unused = Proxy.newProxyInstance(UserRepository::class.java.classLoader, arrayOf(UserRepository::class.java)) {
            _, method, _ -> error("Unexpected ${method.name}")
        } as UserRepository
        val probes = intArrayOf(0)
        val probeReady = CompletableDeferred<Unit>()
        val repository = object : UserRepository by unused {
            override suspend fun getSignData(userId: Int): NetWorkResult<com.par9uet.jm.core.model.SignInData> =
                NetWorkResult.Error("No sign-in data in this fixture")
            override suspend fun probeActiveSession(): NetWorkResult<Unit> {
                probes[0]++
                probeReady.await()
                return NetWorkResult.Success(Unit)
            }
        }
        val mode = FakeConnectionMode(initialLocal = local)
        val manager = UserManager(users, cookies, repository, SessionReadinessHolder(), fakeNightPrompt(mode), mode)
        val modeGate = LocalModeGate(mode, manager, backgroundScope)
        val requests = mutableListOf<Int>()
        val controller = FavoriteSyncController(
            UserManagerFavoriteSession(manager),
            { _, folder, _, _ -> requests += folder; syncReady.await(); NetWorkResult.Success(FavoriteSyncReport(0, 0, 0, 0, 0)) },
            backgroundScope, modeGate,
            autoSyncCoordinator = FavoriteAutoSyncCoordinator(
                timeSource = { testScheduler.currentTime }, wallTimeSource = { 1_000_000L + testScheduler.currentTime },
            ),
            lastSuccessfulSyncAt = { lastCompletedAt },
        )
        val remote = RemoteConfigManager(RemoteSettingFetch { NetWorkResult.Error("unused") }, object : RemoteConfigStore {
            override fun <T> get(key: String, type: Type): T? = null
            override fun <T> set(key: String, value: T) = Unit
        })
        val home = StartupHomeLoad().apply { track(backgroundScope.launch { homeReady.await() }) }
        val unreadRequests = intArrayOf(0)
        val source = Proxy.newProxyInstance(NotificationSource::class.java.classLoader, arrayOf(NotificationSource::class.java)) {
            _, method, _ ->
            check(method.name == "unreadCounts")
            unreadRequests[0]++
            NotificationUnreadCounts(1, 0)
        } as NotificationSource
        val account = object : AccountRequestScope {
            override val sessions = MutableStateFlow(UserSessionSnapshot(if (hasUser) 7 else 0, 1))
            override suspend fun <T> run(owner: UserSessionSnapshot, block: suspend () -> T): T = block()
        }
        val unread = NotificationUnreadCoordinator(source, account, modeGate, backgroundScope)
        val cacheScans = intArrayOf(0)
        val files = object : CacheFiles {
            override suspend fun scan(): List<CacheSize> { cacheScans[0]++; cacheReady.await(); return emptyList() }
            override suspend fun remove(areas: Set<com.par9uet.jm.cache.CacheArea>) = Unit
        }
        val cache = StartupCacheCleanup(files, settings, CacheCleanupOperations(files, {}, {}, {}))
        val application = koinApplication {
            modules(module {
                single { settings }
                single { unread }
                single { cache }
                single { home }
                single { manager }
                single { modeGate }
                single { DohManager(settings, settings) }
                single { remote }
                single { ToastManager() }
                single<FavoriteSyncRequester> { controller }
            })
        }
        return Environment(application, PostStartupCoordinator(backgroundScope, application.koin, foregroundWork = foregroundWork), controller, probeReady, probes, requests, unreadRequests, cacheScans)
    }
}
