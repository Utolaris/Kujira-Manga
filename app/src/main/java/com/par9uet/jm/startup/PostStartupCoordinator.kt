package com.par9uet.jm.startup

import com.par9uet.jm.network.DohManager
import com.par9uet.jm.storage.HistorySearchManager
import com.par9uet.jm.storage.LocalSettingManager
import com.par9uet.jm.storage.ReadHistoryManager
import com.par9uet.jm.network.RemoteConfigManager
import com.par9uet.jm.core.ToastManager
import com.par9uet.jm.session.UserManager
import com.par9uet.jm.utils.ensureAppNotificationChannels
import com.par9uet.jm.utils.log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.koin.core.Koin

/** Coordinates work that improves later interactions without delaying the first safe screen. */
class PostStartupCoordinator(
    private val scope: CoroutineScope,
    private val koin: Koin,
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return

        launchTask("DoH 网络配置") {
            runAuthenticatedStartupTasks(
                initDoh = { koin.get<DohManager>().init() },
                refreshRemoteConfig = { koin.get<RemoteConfigManager>().refresh() },
                verifyUser = {
                    val userManager = koin.get<UserManager>()
                    if (koin.get<com.par9uet.jm.session.LocalModeGate>().isLocalMode) {
                        // 本地模式不做探活/自动签到，避免夜间 401 风暴。
                        return@runAuthenticatedStartupTasks
                    }
                    userManager.verifyStoredLogin()
                    userManager.autoSignInIfNeeded(
                        enabled = koin.get<LocalSettingManager>().currentAutoSignInEnabled(),
                        toastManager = koin.get<ToastManager>(),
                    )
                },
            )
        }
        launchTask("桌面图标入口") {
            koin.get<LocalSettingManager>().applyLauncherDisguiseIfNeeded()
        }
        // Runs beside the task above rather than after it: the probe is best-effort and multi-second
        // on a bad link, so nothing may wait on it. A switch retires the previous resolver, which is
        // why DohManager grants in-flight lookups a grace period instead of cancelling them.
        launchTask("DoH 自动选线") {
            koin.get<DohManager>().autoSelectFastest()
        }
        launchTask("搜索历史") {
            koin.get<HistorySearchManager>().load()
        }
        launchTask("阅读历史") {
            koin.get<ReadHistoryManager>().load()
        }
        launchTask("本地浏览历史") {
            koin.get<com.par9uet.jm.storage.LocalBrowseHistoryManager>().load()
        }
        launchTask("通知渠道") {
            ensureAppNotificationChannels(koin.get())
        }
        // 进程内只跑一次（AtomicBoolean）：从后台唤起不会再次检查。
        // 失败静默，由 AutoUpdateChecker 吞掉；有新版本才写 prompt。
        launchTask("自动检查更新") {
            koin.get<com.par9uet.jm.update.AutoUpdateChecker>().checkOnce()
        }
    }

    private fun launchTask(name: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                log("启动后任务", "$name 失败：${error.message}")
            }
        }
    }
}

/** Activates DoH before remote configuration and account verification start in parallel. */
internal suspend fun runAuthenticatedStartupTasks(
    initDoh: suspend () -> Unit,
    refreshRemoteConfig: suspend () -> Unit,
    verifyUser: suspend () -> Unit,
) {
    initDoh()
    supervisorScope {
        launch { refreshRemoteConfig() }
        launch { verifyUser() }
    }
}
