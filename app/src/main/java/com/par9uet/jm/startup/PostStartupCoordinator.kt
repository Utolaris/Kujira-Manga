package com.par9uet.jm.startup

import com.par9uet.jm.network.DohManager
import com.par9uet.jm.storage.HistorySearchManager
import com.par9uet.jm.storage.LocalSettingManager
import com.par9uet.jm.storage.ReadHistoryManager
import com.par9uet.jm.network.RemoteConfigManager
import com.par9uet.jm.core.ToastManager
import com.par9uet.jm.session.UserManager
import com.par9uet.jm.favorites.sync.FavoriteSyncRequester
import com.par9uet.jm.utils.ensureAppNotificationChannels
import com.par9uet.jm.utils.log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import org.koin.core.Koin

/** Coordinates work that improves later interactions without delaying the first safe screen. */
class PostStartupCoordinator(
    private val scope: CoroutineScope,
    private val koin: Koin,
    private val workContext: CoroutineContext = EmptyCoroutineContext,
    private val foregroundWork: com.par9uet.jm.core.concurrent.ForegroundWork = com.par9uet.jm.core.concurrent.ForegroundWork(),
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(workContext) {
            runStartupPhases(
                authenticated = {
                    // DoH、远端配置与只读探活/自动签到；不含收藏同步，收藏同步只在进入收藏页时触发。
                    task("会话与远端配置") {
                        runAuthenticatedStartupTasks(
                            initDoh = { koin.get<DohManager>().init() },
                            refreshRemoteConfig = { koin.get<RemoteConfigManager>().refresh() },
                            verifyUser = {
                                val userManager = koin.get<UserManager>()
                                val localMode = koin.get<com.par9uet.jm.session.LocalModeGate>()
                                if (localMode.isLocalMode) return@runAuthenticatedStartupTasks
                                // 收藏同步只在用户进入收藏页时发起（FavoritesIntent.Entered），冷启动不预取。
                                userManager.verifyStoredLogin()
                                userManager.autoSignInIfNeeded(
                                    enabled = koin.get<LocalSettingManager>().currentAutoSignInEnabled(),
                                    toastManager = koin.get<ToastManager>(),
                                )
                            },
                        )
                    }
                },
                local = {
                    // Local restoration shares one lane instead of opening multiple disk/parse jobs.
                    task("搜索历史") { koin.get<HistorySearchManager>().load() }
                    task("阅读历史") { koin.get<ReadHistoryManager>().load() }
                    task("本地浏览历史") { koin.get<com.par9uet.jm.storage.LocalBrowseHistoryManager>().load() }
                    task("桌面图标入口") { koin.get<LocalSettingManager>().applyLauncherDisguiseIfNeeded() }
                    task("通知渠道") { ensureAppNotificationChannels(koin.get()) }
                },
                awaitContent = {
                    koin.get<StartupHomeLoad>().awaitIdle()
                    // 恰好在这时用户已进入收藏页的同步仍算首屏工作；冷启动本身不发起同步。
                    koin.get<FavoriteSyncRequester>().awaitIdle()
                },
                maintenance = {
                    // 解析器探测、更新检查与缓存扫描都排在首屏内容之后，不与首批浏览争抢网络/磁盘。
                    idleTask("DoH 自动选线") { koin.get<DohManager>().autoSelectFastest() }
                    idleTask("自动检查更新") { koin.get<com.par9uet.jm.update.AutoUpdateChecker>().checkOnce() }
                    idleTask("缓存额度检查") { koin.get<StartupCacheCleanup>().checkOnce() }
                },
                unread = {
                    idleTask("未读通知检查") { koin.get<com.par9uet.jm.notification.NotificationUnreadCoordinator>().checkOnce() }
                },
            )
        }
    }

    private suspend fun idleTask(name: String, block: suspend () -> Unit) {
        foregroundWork.awaitIdle()
        task(name, block)
    }

    private suspend fun task(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log("启动后任务", "$name 失败：${error.message}")
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

/**
 * 认证/本地恢复/首页内容三条路径并行；维护与未读通知按固定顺序收尾。
 *
 * 维护阶段实际等的是「认证 + 首页首批内容 + 本地恢复」三者都结束：认证在这里显式 join，
 * 首页内容经 [awaitContent]、本地恢复经下面的 join 挡住。不要只依赖其中一处，
 * 否则某一阶段抛 CancellationException 或调整顺序后，磁盘维护就会和首屏抢资源。
 */
internal suspend fun runStartupPhases(
    authenticated: suspend () -> Unit,
    local: suspend () -> Unit,
    awaitContent: suspend () -> Unit,
    maintenance: suspend () -> Unit,
    unread: suspend () -> Unit,
) = coroutineScope {
    val authentication = launch { authenticated() }
    val restoration = launch { local() }
    authentication.join()
    awaitContent()
    restoration.join()
    maintenance()
    unread()
}
