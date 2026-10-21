package com.par9uet.jm.startup

import com.par9uet.jm.cache.CacheArea
import com.par9uet.jm.cache.CacheBudget
import com.par9uet.jm.cache.CacheSize
import com.par9uet.jm.cache.atom.CacheFiles
import com.par9uet.jm.cache.molecule.CacheCleanupOperations
import com.par9uet.jm.data.models.LauncherDisguise
import com.par9uet.jm.data.models.LocalSetting
import com.par9uet.jm.launcher.LauncherIdentityApplier
import com.par9uet.jm.storage.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class StartupCacheCleanupTest {
    private val mb = 1024L * 1024L
    private fun sizes(controlled: Long, downloads: Long = 900L) = listOf(
        CacheSize(CacheArea.COMMON, controlled * mb), CacheSize(CacheArea.READER, 0),
        CacheSize(CacheArea.DOWNLOAD, downloads * mb), CacheSize(CacheArea.ALL, (controlled + downloads) * mb),
    )
    private class Files(var sizes: List<CacheSize>) : CacheFiles {
        var scans = 0
        val removals = mutableListOf<Set<CacheArea>>()
        var beforeScan: () -> Unit = {}
        override suspend fun scan(): List<CacheSize> { scans++; beforeScan(); return sizes }
        override suspend fun remove(areas: Set<CacheArea>) { removals += areas }
    }
    private fun settings(value: LocalSetting = LocalSetting(cacheBudgetMb = 512), unavailable: Boolean = false) =
        LocalSettingManager(object : LocalSettingPersistence {
            override fun load() = if (unavailable) LocalSettingLoadResult.TemporaryUnavailable else LocalSettingLoadResult.Success(value)
            override fun persist(localSetting: LocalSetting) = StorageWriteResult.Success
        }, object : LauncherIdentityApplier {
            override fun apply(disguise: LauncherDisguise) = true
        })

    @Test fun `cold startup checks once and over quota cleanup respects exempt downloads`() = runTest {
        val files = Files(sizes(700))
        var readerClears = 0
        var coverClears = 0
        val operations = CacheCleanupOperations(files, { coverClears++ }, { readerClears++ }, { fail("Exempt downloads must stay untouched") })
        val check = StartupCacheCleanup(files, settings(), operations)
        val application = koinApplication { modules(module { single { check } }) }
        try {
            val coordinator = PostStartupCoordinator(backgroundScope, application.koin)
            coordinator.start()
            runCurrent()
            coordinator.start()
            check.checkOnce()
            runCurrent()
            assertEquals(1, files.scans)
            assertEquals(1, readerClears)
            assertEquals(1, coverClears)
            assertTrue(files.removals.isEmpty())
        } finally { application.close() }
    }

    @Test fun `at or below quota does not clean and unlimited does not scan`() = runTest {
        for (usage in listOf(10L, 512L)) {
            val files = Files(sizes(usage))
            val operations = CacheCleanupOperations(files, {}, { fail("Within quota") }, {})
            StartupCacheCleanup(files, settings(), operations).checkOnce()
            assertEquals(1, files.scans)
            assertTrue(files.removals.isEmpty())
        }
        val files = Files(sizes(9000))
        StartupCacheCleanup(files, settings(LocalSetting(cacheBudgetMb = CacheBudget.UNLIMITED_MB)),
            CacheCleanupOperations(files, {}, { fail("Unlimited") }, {})).checkOnce()
        assertEquals(0, files.scans)
    }

    @Test fun `non exempt downloads count towards quota and use the stopped download deletion protocol`() = runTest {
        val files = Files(sizes(100))
        val events = mutableListOf<String>()
        val operations = CacheCleanupOperations(files, { events += "cover" }, { events += "reader" }, { delete ->
            events += "stop-downloads"
            delete()
            assertTrue(CacheArea.DOWNLOAD in files.removals.single())
            events += "finish-downloads"
        })
        StartupCacheCleanup(files, settings(LocalSetting(cacheBudgetMb = 512, downloadExemptFromCacheLimit = false)), operations).checkOnce()
        assertEquals(listOf("stop-downloads", "cover", "reader", "finish-downloads"), events)
        assertEquals(setOf(CacheArea.DOWNLOAD), files.removals.single())
    }

    @Test fun `quota changed to unlimited during a slow scan prevents deletion`() = runTest {
        val settings = settings()
        val files = Files(sizes(700)).apply { beforeScan = { settings.setCacheBudgetMb(CacheBudget.UNLIMITED_MB) } }
        StartupCacheCleanup(files, settings, CacheCleanupOperations(files, {}, { fail("Latest settings must win") }, {})).checkOnce()
        assertTrue(files.removals.isEmpty())
    }

    @Test fun `unreadable settings and failed scans never delete or retry within the process`() = runTest {
        val files = Files(sizes(700))
        val operations = CacheCleanupOperations(files, {}, { fail("Cannot safely decide") }, {})
        StartupCacheCleanup(files, settings(unavailable = true), operations).checkOnce()
        assertEquals(0, files.scans)
        files.beforeScan = { throw IOException("SAF permission missing") }
        val check = StartupCacheCleanup(files, settings(), operations)
        assertTrue(runCatching { check.checkOnce() }.exceptionOrNull() is IOException)
        check.checkOnce()
        assertEquals(1, files.scans)
        assertTrue(files.removals.isEmpty())
    }

    @Test fun `manual and automatic cleanup share one deletion lock`() = runTest {
        val files = Files(sizes(700))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var active = 0
        val operations = CacheCleanupOperations(files, {}, {
            active++
            assertEquals(1, active)
            entered.complete(Unit)
            release.await()
            active--
        }, {})
        val automatic = launch { operations.clean(true) }
        entered.await()
        val manual = launch { operations.clean(true) }
        runCurrent()
        assertEquals(1, active)
        release.complete(Unit)
        automatic.join(); manual.join()
        assertEquals(0, active)
        assertTrue(files.removals.isEmpty())
    }
}
