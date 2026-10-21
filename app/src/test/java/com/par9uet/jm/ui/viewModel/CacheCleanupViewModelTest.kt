package com.par9uet.jm.ui.viewModel

import com.par9uet.jm.cache.CacheArea
import com.par9uet.jm.cache.CacheBudget
import com.par9uet.jm.cache.CacheSize
import com.par9uet.jm.cache.atom.CacheFiles
import com.par9uet.jm.storage.LocalSettingLoadResult
import com.par9uet.jm.storage.LocalSettingManager
import com.par9uet.jm.storage.LocalSettingPersistence
import com.par9uet.jm.storage.StorageWriteResult
import com.par9uet.jm.core.ToastManager
import com.par9uet.jm.data.models.LocalSetting
import com.par9uet.jm.data.models.LauncherDisguise
import com.par9uet.jm.launcher.LauncherIdentityApplier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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

    private fun viewModel(
        files: CacheFiles, manager: LocalSettingManager,
        clearReader: suspend () -> Unit, clearDownloads: suspend (suspend () -> Unit) -> Unit,
        toast: ToastManager,
    ) = CacheCleanupViewModel(files, manager,
        com.par9uet.jm.cache.molecule.CacheCleanupOperations(files, {}, clearReader, clearDownloads), toast)


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
        val vm = viewModel(files, manager, {}, {}, ToastManager())
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
        val vm = viewModel(files, manager, {}, {}, ToastManager())
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
        val vm = viewModel(files, manager, {}, {}, ToastManager())
        runCurrent()
        vm.setBudgetMb(CacheBudget.UNLIMITED_MB)
        runCurrent()
        assertTrue(vm.state.value.budgetUnlimited)
        assertEquals(CacheBudget.UNLIMITED_MB, vm.state.value.budgetMb)
        assertFalse(vm.state.value.overBudget)
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
        val vm = viewModel(files, manager, {}, {}, ToastManager())
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
    fun `budget change rescans after the settle window so the pie reflects the trim`() = runTest(scheduler) {
        val manager = manager()
        val mb = 1024L * 1024L
        var scanned = sampleScan()
        val files = object : CacheFiles {
            override suspend fun scan() = scanned
            override suspend fun remove(areas: Set<CacheArea>) { }
        }
        val vm = viewModel(files, manager, {}, {}, ToastManager())
        runCurrent()
        assertEquals(280L * mb, vm.state.value.controlledUsedBytes)

        // 额度调到 512MB 之下后，组件按新份额自裁剪：阅读缓存 200MB → 100MB。
        scanned = listOf(
            CacheSize(CacheArea.COMMON, 80L * mb),
            CacheSize(CacheArea.READER, 100L * mb),
            CacheSize(CacheArea.DOWNLOAD, 900L * mb),
            CacheSize(CacheArea.ALL, 1080L * mb),
        )
        vm.setBudgetMb(512)
        runCurrent()
        assertEquals(512, vm.state.value.budgetMb)
        // 还在收敛窗口内：不能立刻用旧结果覆盖（旧值仍是裁剪前的大小）。
        assertEquals(280L * mb, vm.state.value.controlledUsedBytes)

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(180L * mb, vm.state.value.controlledUsedBytes)
        assertFalse(vm.state.value.overBudget)
    }

    @Test
    fun `rapid budget changes collapse into a single rescan`() = runTest(scheduler) {
        val manager = manager()
        var scans = 0
        val files = object : CacheFiles {
            override suspend fun scan(): List<CacheSize> {
                scans++
                return sampleScan()
            }

            override suspend fun remove(areas: Set<CacheArea>) { }
        }
        val vm = viewModel(files, manager, {}, {}, ToastManager())
        runCurrent()
        scans = 0

        vm.setBudgetMb(2048)
        runCurrent()
        vm.setBudgetMb(4096)
        runCurrent()
        advanceUntilIdle()

        assertEquals(1, scans)
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

    /** 700MB 受控占用 + 512MB 新额度，正是「1G → 512MB」那个场景。 */
    private fun overQuotaScan() = listOf(
        CacheSize(CacheArea.COMMON, 300L * MB),
        CacheSize(CacheArea.READER, 400L * MB),
        CacheSize(CacheArea.DOWNLOAD, 900L * MB),
        CacheSize(CacheArea.ALL, 1600L * MB),
    )

    @Test
    fun `lowering budget below current usage asks before cleaning`() = runTest(scheduler) {
        val manager = manager()
        var readerCleared = false
        val files = object : CacheFiles {
            override suspend fun scan() = overQuotaScan()
            override suspend fun remove(areas: Set<CacheArea>) { }
        }
        val vm = viewModel(files, manager, { readerCleared = true }, {}, ToastManager())
        runCurrent()
        assertEquals(700L * MB, vm.state.value.controlledUsedBytes)
        assertNull(vm.state.value.cleanPrompt)

        vm.setBudgetMb(512)
        runCurrent()

        val prompt = vm.state.value.cleanPrompt
        assertNotNull(prompt)
        assertEquals(700L * MB, prompt!!.usedBytes)
        assertEquals(512L * MB, prompt.budgetBytes)
        assertEquals(188L * MB, prompt.overflowBytes)
        // 询问阶段一个字节都不能动。
        assertFalse(readerCleared)
        assertFalse(vm.state.value.cleaning)
    }

    @Test
    fun `confirming the prompt runs the same clean as the manual button`() = runTest(scheduler) {
        val manager = manager()
        var readerCleared = false
        var removed: Set<CacheArea>? = null
        var cleared = false
        val files = object : CacheFiles {
            override suspend fun scan() = if (cleared) emptyList() else overQuotaScan()
            override suspend fun remove(areas: Set<CacheArea>) {
                removed = areas
                cleared = true
            }
        }
        val vm = viewModel(files, manager, { readerCleared = true; cleared = true }, {}, ToastManager())
        runCurrent()
        vm.setBudgetMb(512)
        runCurrent()
        assertNotNull(vm.state.value.cleanPrompt)

        vm.confirmCleanPrompt()
        advanceUntilIdle()

        assertNull(vm.state.value.cleanPrompt)
        assertTrue(readerCleared)
        // 豁免开启时下载不参与删除。
        assertNull(removed) // Owner APIs clear cover/reader; raw deletion must not touch them.
        assertEquals(0L, vm.state.value.controlledUsedBytes)
        assertFalse(vm.state.value.overBudget)
    }

    @Test
    fun `dismissing the prompt keeps files and stops re-asking`() = runTest(scheduler) {
        val manager = manager()
        var readerCleared = false
        val files = object : CacheFiles {
            override suspend fun scan() = overQuotaScan()
            override suspend fun remove(areas: Set<CacheArea>) { }
        }
        val vm = viewModel(files, manager, { readerCleared = true }, {}, ToastManager())
        runCurrent()
        vm.setBudgetMb(512)
        runCurrent()
        assertNotNull(vm.state.value.cleanPrompt)

        vm.dismissCleanPrompt()
        runCurrent()
        assertNull(vm.state.value.cleanPrompt)
        assertFalse(readerCleared)
        // 额度已经落盘，越界事实照旧留在状态里给用户看。
        assertEquals(512, vm.state.value.budgetMb)
        assertTrue(vm.state.value.overBudget)

        // 停在同一档位再点一次不应重复弹窗。
        vm.setBudgetMb(512)
        runCurrent()
        assertNull(vm.state.value.cleanPrompt)
    }

    @Test
    fun `raising or keeping the budget never prompts`() = runTest(scheduler) {
        val manager = manager()
        val files = object : CacheFiles {
            override suspend fun scan() = overQuotaScan()
            override suspend fun remove(areas: Set<CacheArea>) { }
        }
        val vm = viewModel(files, manager, {}, {}, ToastManager())
        runCurrent()

        vm.setBudgetMb(2048)
        runCurrent()
        assertNull(vm.state.value.cleanPrompt)

        vm.setBudgetMb(4096)
        runCurrent()
        assertNull(vm.state.value.cleanPrompt)

        vm.setBudgetMb(CacheBudget.UNLIMITED_MB)
        runCurrent()
        assertNull(vm.state.value.cleanPrompt)
        assertTrue(vm.state.value.budgetUnlimited)

        // 无限制是「没有上限」，回到有限额度且仍超量时必须重新询问。
        vm.setBudgetMb(512)
        runCurrent()
        assertEquals(700L * MB, vm.state.value.cleanPrompt?.usedBytes)
    }

    @Test
    fun `lowering above current usage does not prompt`() = runTest(scheduler) {
        val manager = manager()
        val files = object : CacheFiles {
            override suspend fun scan() = sampleScan() // 受控仅 280MB
            override suspend fun remove(areas: Set<CacheArea>) { }
        }
        val vm = viewModel(files, manager, {}, {}, ToastManager())
        runCurrent()

        vm.setBudgetMb(512)
        runCurrent()

        assertEquals(512, vm.state.value.budgetMb)
        assertNull(vm.state.value.cleanPrompt)
        assertFalse(vm.state.value.overBudget)
    }

    @Test
    fun `new prompt replaces a stale one when budget moves again`() = runTest(scheduler) {
        val manager = manager()
        val files = object : CacheFiles {
            override suspend fun scan() = overQuotaScan()
            override suspend fun remove(areas: Set<CacheArea>) { }
        }
        val vm = viewModel(files, manager, {}, {}, ToastManager())
        runCurrent()
        vm.setBudgetMb(512)
        runCurrent()
        assertEquals(512L * MB, vm.state.value.cleanPrompt?.budgetBytes)

        // 抬到够用即销掉上一轮询问，不留悬空弹窗。
        vm.setBudgetMb(1024)
        runCurrent()
        assertNull(vm.state.value.cleanPrompt)

        // 再压回 512：重新询问，数字仍是当前那次扫描的。
        vm.setBudgetMb(512)
        runCurrent()
        assertEquals(700L * MB, vm.state.value.cleanPrompt?.usedBytes)
    }

    @Test
    fun `a background rescan that loses the download directory reports instead of faking`() =
        runTest(scheduler) {
            val manager = manager()
            val toasts = mutableListOf<String>()
            val toastManager = ToastManager()
            backgroundScope.launch(Dispatchers.Main) {
                toastManager.message.collect { toasts.add(it) }
            }
            var failNext = false
            val files = object : CacheFiles {
                override suspend fun scan(): List<CacheSize> =
                    if (failNext) error("无法读取自定义下载目录，请检查目录授权") else sampleScan()

                override suspend fun remove(areas: Set<CacheArea>) { }
            }
            val vm = viewModel(files, manager, {}, {}, toastManager)
            runCurrent()
            assertEquals(280L * 1024 * 1024, vm.state.value.controlledUsedBytes)

            failNext = true
            vm.setDownloadExempt(false)
            runCurrent()

            // 失败原因改走全局 toast（与下载完成等消息一致），不再落 state。
            assertEquals("无法读取自定义下载目录，请检查目录授权", toasts.single())
            // 授权失效不该让页面变成空白：旧统计保留，只是把原因摆出来。
            assertEquals(280L * 1024 * 1024, vm.state.value.controlledUsedBytes)
        }

    private companion object {
        const val MB = 1024L * 1024L
    }
}
