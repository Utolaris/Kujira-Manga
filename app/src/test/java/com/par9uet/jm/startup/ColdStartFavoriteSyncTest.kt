package com.par9uet.jm.startup

import com.par9uet.jm.core.ToastManager
import com.par9uet.jm.core.model.User
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.data.models.LocalSetting
import com.par9uet.jm.di.UserManagerFavoriteSession
import com.par9uet.jm.favorites.FakeConnectionMode
import com.par9uet.jm.favorites.fakeNightPrompt
import com.par9uet.jm.favorites.sync.FavoriteAutoSyncCoordinator
import com.par9uet.jm.favorites.sync.FavoriteSyncController
import com.par9uet.jm.favorites.sync.FavoriteSyncReport
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/** Exercises the real startup coordinator and sync controller without composing Favorites. */
@OptIn(ExperimentalCoroutinesApi::class)
class ColdStartFavoriteSyncTest {
    @Test
    fun `startup waits for the real saved session probe then syncs without visiting Favorites`() = runTest {
        val environment = environment()
        try {
            environment.startup.start()
            runCurrent()
            assertEquals(1, environment.probes[0])
            assertTrue(environment.requests.isEmpty())
            environment.startup.start()
            environment.probeReady.complete(Unit)
            runCurrent()
            assertEquals(listOf(0), environment.requests)
            environment.startup.start()
            environment.controller.request(FavoriteSyncRequestKind.AUTO, 3)
            runCurrent()
            assertEquals(1, environment.probes[0])
            assertEquals(listOf(0), environment.requests)
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
    fun `recent durable completion suppresses startup and every folder until sixty seconds`() = runTest {
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
        } finally { environment.application.close() }
    }

    private data class Environment(
        val application: org.koin.core.KoinApplication,
        val startup: PostStartupCoordinator,
        val controller: FavoriteSyncController,
        val probeReady: CompletableDeferred<Unit>,
        val probes: IntArray,
        val requests: MutableList<Int>,
    )

    private fun TestScope.environment(
        local: Boolean = false, hasUser: Boolean = true, lastCompletedAt: Long? = null,
    ): Environment {
        val settings = LocalSettingManager(object : LocalSettingPersistence {
            override fun load() = LocalSettingLoadResult.Success(LocalSetting(
                dohEnabled = false, dohAutoSelectFastest = false, autoSignInEnabled = false,
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
            { _, folder, _, _ -> requests += folder; NetWorkResult.Success(FavoriteSyncReport(0, 0, 0, 0, 0)) },
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
        val application = koinApplication {
            modules(module {
                single { settings }
                single { manager }
                single { modeGate }
                single { DohManager(settings, settings) }
                single { remote }
                single { ToastManager() }
                single<FavoriteSyncRequester> { controller }
            })
        }
        return Environment(application, PostStartupCoordinator(backgroundScope, application.koin), controller, probeReady, probes, requests)
    }
}
