package com.par9uet.jm.worker

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.par9uet.jm.cache.migration.CacheMigrationWork
import com.par9uet.jm.cache.migration.CacheMigrationWorkState
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** WorkManager history is unordered; only unfinished work or the recorded request is current. */
@RunWith(Parameterized::class)
class WorkManagerCacheMigrationSchedulerTest(
    private val name: String,
    private val history: List<WorkInfo>,
    private val lastRequestId: UUID?,
    private val expected: CacheMigrationWorkState,
) {
    @Test fun selectsCurrentRun() {
        assertEquals(name, expected, currentMigrationState(history, lastRequestId))
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any?>> {
            val old = work(WorkInfo.State.SUCCEEDED)
            val failed = work(WorkInfo.State.FAILED, output = workDataOf(CacheMigrationWork.ERROR to "无法读取缓存路径"))
            val cancelled = work(WorkInfo.State.CANCELLED)
            val noMessage = work(WorkInfo.State.FAILED)
            return listOf(
                arrayOf("unfinished wins", listOf(old, work(WorkInfo.State.RUNNING,
                    progress = workDataOf(CacheMigrationWork.PROGRESS to 42))), null,
                    CacheMigrationWorkState.Running(42, null)),
                arrayOf("recorded failure", listOf(old, failed), failed.id,
                    CacheMigrationWorkState.Failed("无法读取缓存路径")),
                arrayOf("recorded older success", listOf(old, failed), old.id, CacheMigrationWorkState.Succeeded),
                arrayOf("no recorded id", listOf(old, failed), null, CacheMigrationWorkState.Idle),
                arrayOf("id absent from history", listOf(old), UUID.randomUUID(), CacheMigrationWorkState.Idle),
                arrayOf("failure without message", listOf(noMessage), noMessage.id, CacheMigrationWorkState.Failed(null)),
                arrayOf("cancelled current run", listOf(old, cancelled), cancelled.id, CacheMigrationWorkState.Idle),
                arrayOf("enqueued stage", listOf(work(WorkInfo.State.ENQUEUED, progress = workDataOf(
                    CacheMigrationWork.PROGRESS to 99, CacheMigrationWork.STAGE to "正在更新缓存索引"))), null,
                    CacheMigrationWorkState.Running(99, "正在更新缓存索引")),
                arrayOf("empty history", emptyList<WorkInfo>(), null, CacheMigrationWorkState.Idle),
            )
        }

        private fun work(
            state: WorkInfo.State,
            id: UUID = UUID.randomUUID(),
            progress: Data = Data.EMPTY,
            output: Data = Data.EMPTY,
        ) = WorkInfo(
            id = id,
            state = state,
            tags = emptySet(),
            outputData = output,
            progress = progress,
        )
    }
}
