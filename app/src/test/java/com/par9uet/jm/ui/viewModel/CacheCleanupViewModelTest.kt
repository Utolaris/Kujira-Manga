package com.par9uet.jm.ui.viewModel

import com.par9uet.jm.cache.CacheArea
import com.par9uet.jm.cache.CacheBudget
import com.par9uet.jm.cache.CacheSize
import com.par9uet.jm.cache.atom.CacheFiles
import com.par9uet.jm.storage.LocalSettingLoadResult
import com.par9uet.jm.storage.LocalSettingManager
import com.par9uet.jm.storage.LocalSettingPersistence
import com.par9uet.jm.storage.StorageWriteResult
import com.par9uet.jm.data.models.LocalSetting
import com.par9uet.jm.data.models.LauncherDisguise
import com.par9uet.jm.launcher.LauncherIdentityApplier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CacheCleanupViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher(scheduler)) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class MemPersistence : LocalSettingPersistence {
        var stored: LocalSetting? = null
        override fun load() = stored?.let { LocalSettingLoadResult.Success(it) } ?: LocalSettingLoadResult.Missing
        override fun persist(localSetting: LocalSetting): StorageWriteResult {
            stored = localSetting
            return StorageWriteResult.Success
        }
    }

    private class Applier : LauncherIdentityApplier {
        override fun apply(disguise: LauncherDisguise) = true
    }

    private fun manager() = LocalSettingManager(MemPersistence(), Applier())

    private fun sampleScan() = listOf(
        CacheSize(CacheArea.COMMON, 80L * 1024 * 1024),
        CacheSize(CacheArea.READER, 200L * 1024 * 1024),
        CacheSize(CacheArea.DOWNLOAD, 900L * 1024 * 1024),
        CacheSize(CacheArea.ALL, 1180L * 1024 * 1024),
    )

    @Test
    fun `statistics use one caliber for pie and controlled usage`() = runTest(scheduler) {
        val manager = manager()
        val scan = sampleScan()
        val files = object : CacheFiles {
            override suspend fun scan() = scan
            override suspend fun remove(areas: Set<CacheArea>) { }
        }
        val vm = CacheCleanupViewModel(files, manager, {}, {})
        runCurrent()
        assertEquals(CacheBudget.DEFAULT_TOTAL_MB, vm.state.value.budgetMb)
        assertEquals(1180L * 1024 * 1024, vm.state.value.compositionTotalBytes)
        val expectedControlled = 280L * 1024 * 1024
        assertEquals(expectedControlled, vm.state.value.controlledUsedBytes)
        assertEquals(expectedControlled, vm.state.value.displayUsedBytes)
        assertTrue(vm.state.value.downloadExempt)
        assertFalse(vm.state.value.overBudget)
        assertTrue(vm.state.value.budgetStops.isNotEmpty())
        assertTrue(vm.state.value.budgetStops.any { it.label == "无限制" })
        val sizes = vm.state.value.pieSlices.map { it.sizeBytes }
        assertEquals(sizes.sortedDescending(), sizes)
        assertTrue(vm.state.value.pieSlices.first().isDownload)
        val sumFractions = vm.state.value.pieSlices.sumOf { it.fraction.toDouble() }
        assertEquals(1.0, sumFractions, 0.02)

        vm.setBudgetMb(2048)
        runCurrent()
        assertEquals(2048, vm.state.value.budgetMb)

        vm.setDownloadExempt(false)
        runCurrent()
        assertFalse(vm.state.value.downloadExempt)
        assertEquals(1180L * 1024 * 1024, vm.state.value.controlledUsedBytes)
        assertEquals(1180L * 1024 * 1024, vm.state.value.displayUsedBytes)
    }

    @Test
    fun `residual files outside named areas join other slice and controlled usage`() = runTest(scheduler) {
        val manager = manager()
        val scan = listOf(
            CacheSize(CacheArea.COMMON, 100L),
            CacheSize(CacheArea.READER, 200L),
            CacheSize(CacheArea.DOWNLOAD, 50L),
            CacheSize(CacheArea.ALL, 400L),
        )
        val files = object : CacheFiles {
            override suspend fun scan() = scan
            override suspend fun remove(areas: Set<CacheArea>) { }
        }
        val vm = CacheCleanupViewModel(files, manager, {}, {})
        runCurrent()
        assertEquals(400L, vm.state.value.compositionTotalBytes)
        assertEquals(400L, vm.state.value.pieSlices.sumOf { it.sizeBytes })
        assertTrue(vm.state.value.pieSlices.any { it.isResidual && it.sizeBytes == 50L && it.title == "其他" })
        assertEquals(350L, vm.state.value.controlledUsedBytes)
    }

    @Test
    fun `unlimited budget disables over-budget`() = runTest(scheduler) {
        val manager = manager()
        val files = object : CacheFiles {
            override suspend fun scan() = sampleScan()
            override suspend fun remove(areas: Set<CacheArea>) { }
        }
        val vm = CacheCleanupViewModel(files, manager, {}, {})
        runCurrent()
        vm.setBudgetMb(CacheBudget.UNLIMITED_MB)
        runCurrent()
        assertTrue(vm.state.value.budgetUnlimited)
        assertEquals(CacheBudget.UNLIMITED_MB, vm.state.value.budgetMb)
        assertFalse(vm.state.value.overBudget)
        assertEquals(0f, vm.state.value.usageRatio, 0f)
    }

    @Test
    fun `over budget when finite quota exceeded and not when unlimited`() = runTest(scheduler) {
        val manager = manager()
        val huge = listOf(
            CacheSize(CacheArea.COMMON, 400L * 1024 * 1024),
            CacheSize(CacheArea.READER, 400L * 1024 * 1024),
            CacheSize(CacheArea.DOWNLOAD, 10L * 1024 * 1024),
            CacheSize(CacheArea.ALL, 810L * 1024 * 1024),
        )
        val files = object : CacheFiles {
            override suspend fun scan() = huge
            override suspend fun remove(areas: Set<CacheArea>) { }
        }
        val vm = CacheCleanupViewModel(files, manager, {}, {})
        runCurrent()
        vm.setBudgetMb(512)
        runCurrent()
        assertTrue(vm.state.value.overBudget)
        assertEquals(800L * 1024 * 1024, vm.state.value.controlledUsedBytes)
        vm.setBudgetMb(CacheBudget.UNLIMITED_MB)
        runCurrent()
        assertFalse(vm.state.value.overBudget)
    }

    @Test
    fun `manager load projects unlimited cache budget and set round-trips`() = runTest(scheduler) {
        val persistence = object : LocalSettingPersistence {
            override fun load() = LocalSettingLoadResult.Success(
                LocalSetting(cacheBudgetMb = CacheBudget.UNLIMITED_MB),
            )
            override fun persist(localSetting: LocalSetting): StorageWriteResult {
                return StorageWriteResult.Success
            }
        }
        val manager = LocalSettingManager(persistence, Applier())
        assertEquals(CacheBudget.UNLIMITED_MB, manager.cacheBudgetMb.value)
        manager.setCacheBudgetMb(2048)
        assertEquals(2048, manager.cacheBudgetMb.value)
        manager.setCacheBudgetMb(CacheBudget.UNLIMITED_MB)
        assertEquals(CacheBudget.UNLIMITED_MB, manager.cacheBudgetMb.value)
        manager.setCacheBudgetMb(256)
        assertEquals(512, manager.cacheBudgetMb.value)
    }
}
