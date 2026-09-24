package com.par9uet.jm.worker

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.par9uet.jm.core.model.LocalModeExitScheduler
import com.par9uet.jm.favorites.model.FavoriteSession
import com.par9uet.jm.session.LocalModeCoordinator
import com.par9uet.jm.session.LocalModeTransition
import com.par9uet.jm.utils.LOCAL_MODE_SYNC_NOTIFICATION_ID
import com.par9uet.jm.utils.localModeSyncNotification
import com.par9uet.jm.utils.showLocalModeSyncResultNotification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Persists the confirmed exit beyond the settings screen and keeps Android foreground service active. */
class LocalModeExitWorker(
    appContext: Context,
    params: WorkerParameters,
    private val coordinator: LocalModeCoordinator,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = coroutineScope {
        val accountId = inputData.getInt(ACCOUNT_ID, 0)
        if (accountId <= 0 || !coordinator.isCurrentAccount(accountId)) return@coroutineScope Result.failure()
        setForeground(foreground("正在准备同步"))
        val progress = launch {
            coordinator.transition.collect { transition ->
                if (transition is LocalModeTransition.Exiting) {
                    setForeground(foreground(transition.stage))
                }
            }
        }
        try {
            coordinator.exitLocalModeForAccount(accountId)
            val success = coordinator.isCurrentAccount(accountId) && !coordinator.isLocalMode
            if (coordinator.isCurrentAccount(accountId)) {
                showLocalModeSyncResultNotification(applicationContext, success)
            }
            if (success) Result.success() else Result.failure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (coordinator.isCurrentAccount(accountId)) {
                showLocalModeSyncResultNotification(applicationContext, success = false)
            }
            Result.failure()
        } finally {
            progress.cancel()
        }
    }

    private fun foreground(stage: String) = ForegroundInfo(
        LOCAL_MODE_SYNC_NOTIFICATION_ID,
        localModeSyncNotification(applicationContext, stage),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    companion object {
        const val ACCOUNT_ID = "account_id"
    }
}

class WorkManagerLocalModeExitScheduler(
    context: Context,
    private val favoriteSession: FavoriteSession,
) : LocalModeExitScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun request(): Boolean = runCatching {
        val accountId = favoriteSession.currentAccountId().takeIf { it > 0 } ?: return false
        workManager.enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<LocalModeExitWorker>()
                .setInputData(workDataOf(LocalModeExitWorker.ACCOUNT_ID to accountId))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build(),
        )
        true
    }.getOrDefault(false)

    private companion object {
        const val UNIQUE_WORK_NAME = "local-mode-exit"
    }
}
