package com.par9uet.jm.session

import com.par9uet.jm.core.ToastManager
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.favorites.model.FavoriteSession
import com.par9uet.jm.favorites.model.FavoriteSessionSnapshot
import com.par9uet.jm.favorites.sync.FavoriteSyncReport
import com.par9uet.jm.favorites.usecase.SyncLocalModeFavoritesOnExit
import com.par9uet.jm.favorites.usecase.LocalFavoriteOperationGate
import com.par9uet.jm.storage.ConnectionModeEditor
import com.par9uet.jm.storage.ConnectionModePreferences
import com.par9uet.jm.storage.LocalBrowseHistoryManager
import com.par9uet.jm.utils.log
import com.par9uet.jm.utils.logError
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Only the verified transition may use authenticated API calls while the local-mode flag is set. */
internal class LocalModeSyncRequest(val accountId: Int, val generation: Long) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LocalModeSyncRequest>
}

sealed interface LocalModeTransition {
    data object Idle : LocalModeTransition
    data object Entering : LocalModeTransition
    data class Exiting(val stage: String) : LocalModeTransition
    data class Failed(val message: String) : LocalModeTransition
}

/**
 * 本地模式切换协调。
 *
 * 进入：只改开关（收藏/历史写入路径随后自动走本地）。
 * 切回网络：确认登录态（手动登录可复用）→ 收藏补偿（远端比对 + 逐个 collect/uncollect）→ 强制全量同步
 * → 关该账号的开关 → 丢弃该账号本地浏览历史（远端覆盖）。
 */
class LocalModeCoordinator(
    private val connectionModeEditor: ConnectionModeEditor,
    private val connectionModePreferences: ConnectionModePreferences,
    private val localModeStatus: com.par9uet.jm.core.model.ConnectionModeStatus,
    private val userManager: UserManager,
    private val browseHistory: LocalBrowseHistoryManager,
    private val exitFavoriteSync: SyncLocalModeFavoritesOnExit,
    private val localFavoriteOperationGate: LocalFavoriteOperationGate,
    private val favoriteSession: FavoriteSession,
    private val refreshFavorites: suspend (FavoriteSessionSnapshot) -> NetWorkResult<FavoriteSyncReport>,
    private val localFavoriteChanges: com.par9uet.jm.storage.LocalFavoriteChangeManager,
    private val toastManager: ToastManager,
) : com.par9uet.jm.core.model.LocalModeExit {
    private val _transition = MutableStateFlow<LocalModeTransition>(LocalModeTransition.Idle)
    val transition = _transition.asStateFlow()
    private val exitLock = Mutex()
    private val lastAutomaticAttemptMillisByAccount = mutableMapOf<Int, Long>()

    val isLocalMode: Boolean get() = localModeStatus.isLocalMode

    fun isCurrentAccount(accountId: Int): Boolean =
        userManager.userState.value.data?.id == accountId

    fun enterLocalMode(expectedAccountId: Int? = null) {
        if (_transition.value is LocalModeTransition.Exiting) return
        if (isLocalMode) return
        val accountId = userManager.userState.value.data?.id ?: 0
        if (accountId <= 0 || userManager.authState.value != SessionReadiness.Authenticated ||
            (expectedAccountId != null && expectedAccountId != accountId)
        ) {
            fail("请先登录，再开启本地模式")
            return
        }
        if (!browseHistory.clear(accountId)) {
            fail("本地历史暂时无法准备，请重试")
            return
        }
        _transition.value = LocalModeTransition.Entering
        val ok = connectionModeEditor.setLocalModeEnabled(accountId, true)
        _transition.value = if (ok) {
            log("LocalMode", "entered local mode")
            LocalModeTransition.Idle
        } else {
            val message = "无法写入本地模式开关，请重试"
            fail(message)
            LocalModeTransition.Failed(message)
        }
    }

    override suspend fun exitLocalMode() = withFavoriteTransition { performExitLocalMode() }

    /** The foreground task may run after account changes; never apply A's request to B. */
    suspend fun exitLocalModeForAccount(accountId: Int) = withFavoriteTransition {
        if (isCurrentAccount(accountId)) performExitLocalMode()
    }

    override suspend fun exitLocalModeAfterLogin(accountId: Int, generation: Long) = withFavoriteTransition {
        performExitLocalMode(FavoriteSessionSnapshot(accountId, generation))
    }

    private suspend fun withFavoriteTransition(block: suspend () -> Unit) {
        if (!localFavoriteOperationGate.beginTransition()) return
        try {
            exitLock.withLock { localFavoriteOperationGate.withLock { block() } }
        } catch (cancelled: CancellationException) {
            _transition.value = if (isLocalMode) LocalModeTransition.Failed("同步已中断，仍停留在本地模式")
            else LocalModeTransition.Idle
            throw cancelled
        } catch (error: Exception) {
            logError("LocalMode", "transition failed: ${error.message}")
            fail("切换失败，请稍后重试")
        } finally {
            localFavoriteOperationGate.endTransition()
        }
    }

    /**
     * 强制收藏夹与远端对齐：丢弃本地未同步变更，拉全量覆盖本地；若在本地模式则一并关闭。
     * 不走补偿推送 —— 未上云的本地收藏会按说明弹窗提示可能丢失。
     */
    suspend fun forceAlignFavoritesWithRemote() = withFavoriteTransition { performForceAlignFavoritesWithRemote() }

    /**
     * 顺序不变量（与 [performExitLocalMode] 一致）：
     * 先确认登录并拉到远端快照，**成功之后**才丢弃待同步意图、再关本地模式。
     * 反序会在 relogin/刷新失败时永久丢掉未上云收藏，且可能停在「模式已关、列表未对齐」的中间态。
     * 本地模式下刷新走 [LocalModeSyncRequest] 闸门放行，避免先关模式才能打远端。
     */
    private suspend fun performForceAlignFavoritesWithRemote() {
        val accountId = userManager.userState.value.data?.id ?: 0
        log("LocalMode", "force align start account=$accountId local=$isLocalMode")
        _transition.value = LocalModeTransition.Exiting("正在重新登录")
        val login = relogin()
        if (login is NetWorkResult.Error) {
            logError("LocalMode", "force align relogin failed: ${login.message}")
            fail(login.message.ifBlank { "重新登录失败，请稍后重试" })
            return
        }
        val snapshot = favoriteSession.snapshot()
        if (snapshot.accountId <= 0) {
            fail("需要登录后才能对齐收藏夹")
            return
        }
        if (accountId > 0 && snapshot.accountId != accountId) {
            fail("登录账号已变化，请重试")
            return
        }
        _transition.value = LocalModeTransition.Exiting("正在对齐收藏夹")
        val refreshed = if (isLocalMode) {
            withContext(LocalModeSyncRequest(snapshot.accountId, snapshot.generation)) {
                refreshFavorites(snapshot)
            }
        } else {
            refreshFavorites(snapshot)
        }
        when (refreshed) {
            is NetWorkResult.Error -> {
                fail("强制刷新收藏夹失败：${refreshed.message}")
                return
            }
            is NetWorkResult.Success -> {
                log("LocalMode", "force align favorites replaced from remote")
            }
        }
        if (!localFavoriteChanges.clearAll()) {
            fail("本地收藏记录暂时无法清理，请重试")
            return
        }
        if (isLocalMode && accountId > 0) {
            _transition.value = LocalModeTransition.Exiting("正在切换网络模式")
            if (!connectionModeEditor.setLocalModeEnabled(accountId, false)) {
                fail("无法写入本地模式开关，请重试")
                return
            }
            if (!browseHistory.clear(accountId)) {
                logError("LocalMode", "local history cleanup deferred for account=$accountId")
            }
            log("LocalMode", "force align turned off local mode account=$accountId")
        }
        toastManager.showAsync("收藏夹已与远端对齐")
        _transition.value = LocalModeTransition.Idle
    }

    /** Retry after the entry day in off-peak hours, with a 30-minute cooldown per account. */
    suspend fun exitLocalModeIfOffPeak(nowMillis: Long = System.currentTimeMillis()) {
        val accountId = userManager.userState.value.data?.id ?: return
        if (!isLocalMode || !isOffPeak(nowMillis) || localFavoriteOperationGate.isTransitioning()) return
        val day = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), ZoneId.systemDefault()).toLocalDate()
        val enteredAt = connectionModePreferences.localModeEnteredAtByAccount.value[accountId]
        if (enteredAt != null &&
            !LocalDateTime.ofInstant(Instant.ofEpochMilli(enteredAt), ZoneId.systemDefault()).toLocalDate().isBefore(day)
        ) return
        val last = lastAutomaticAttemptMillisByAccount[accountId]
        if (last != null && nowMillis >= last && nowMillis - last < 30 * 60 * 1_000L) return
        lastAutomaticAttemptMillisByAccount[accountId] = nowMillis
        exitLocalMode()
    }

    private suspend fun performExitLocalMode(verifiedSession: FavoriteSessionSnapshot? = null) {
        if (!isLocalMode) return
        val accountId = userManager.userState.value.data?.id ?: return
        log("LocalMode", "exit start account=$accountId")
        if (verifiedSession != null) {
            if (verifiedSession.accountId != accountId ||
                favoriteSession.snapshot() != verifiedSession ||
                userManager.userState.value.isLoading ||
                userManager.authState.value != SessionReadiness.Authenticated
            ) {
                fail("登录账号或会话已变化，请重试")
                return
            }
            log("LocalMode", "reuse verified login account=$accountId gen=${verifiedSession.generation}")
        } else {
            _transition.value = LocalModeTransition.Exiting("正在重新登录")
            val login = relogin()
            if (login is NetWorkResult.Error) {
                logError("LocalMode", "exit relogin failed: ${login.message}")
                fail(login.message.ifBlank { "重新登录失败，仍停留在本地模式" })
                return
            }
            log("LocalMode", "exit relogin ok account=$accountId")
        }
        val snapshot = favoriteSession.snapshot()
        if (snapshot.accountId != accountId || (verifiedSession != null && snapshot != verifiedSession)) {
            fail("登录账号已变化，请切回原账号后重试")
            return
        }
        _transition.value = LocalModeTransition.Exiting("正在同步收藏")
        val report = withContext(LocalModeSyncRequest(accountId, snapshot.generation)) { exitFavoriteSync(snapshot) }
        when (report) {
            is NetWorkResult.Error -> {
                logError("LocalMode", "favorite compensate failed: ${report.message}")
                fail("收藏同步失败，仍停留在本地模式：${report.message}")
                return
            }
            is NetWorkResult.Success -> {
                log(
                    "LocalMode",
                    "favorite compensate collected=${report.data.collected} uncollected=${report.data.uncollected} " +
                        "skipped=${report.data.skippedAlreadyRemote} failed=${report.data.failed}",
                )
                if (report.data.failed > 0) {
                    // 不要再做全量 replace：会把未推送成功的本地收藏从列表里抹掉。
                    fail("${report.data.failed} 条收藏尚未同步，仍停留在本地模式，请稍后重试")
                    return
                }
            }
        }
        _transition.value = LocalModeTransition.Exiting("正在刷新收藏夹")
        when (val result = withContext(LocalModeSyncRequest(accountId, snapshot.generation)) { refreshFavorites(snapshot) }) {
            is NetWorkResult.Error -> {
                logError("LocalMode", "force refresh after exit failed: ${result.message}")
                fail("刷新收藏夹失败，仍停留在本地模式：${result.message}")
                return
            }
            is NetWorkResult.Success -> {
                log("LocalMode", "favorites force-refreshed after exit")
            }
        }
        if (!favoriteSession.isCurrent(snapshot) || !isLocalMode) {
            fail("登录账号或模式已变化，请重试")
            return
        }
        _transition.value = LocalModeTransition.Exiting("正在切换网络模式")
        if (!connectionModeEditor.setLocalModeEnabled(accountId, false)) {
            fail("无法写入本地模式开关，请重试")
            return
        }
        if (!browseHistory.clear(accountId)) {
            logError("LocalMode", "local history cleanup deferred for account=$accountId")
        }
        log("LocalMode", "exited to network mode")
        toastManager.showAsync("已切换到网络模式")
        _transition.value = LocalModeTransition.Idle
    }

    internal fun isOffPeak(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val hour = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone).hour
        return hour in 6..21
    }

    private fun fail(message: String) {
        _transition.value = LocalModeTransition.Failed(message)
        toastManager.showAsync(message)
    }

    private suspend fun relogin(): NetWorkResult<Unit> {
        val user = userManager.userState.value.data
        log(
            "LocalMode",
            "relogin begin hasUser=${user != null} hasName=${!user?.username.isNullOrBlank()} " +
                "hasPassword=${!user?.password.isNullOrEmpty()}",
        )
        if (user != null && user.username.isNotBlank() && user.password.isNotEmpty()) {
            return when (val result = userManager.login(user.username, user.password)) {
                is NetWorkResult.Error -> {
                    logError("LocalMode", "relogin failed: ${result.message} kind=${result.kind} code=${result.code}")
                    result
                }
                is NetWorkResult.Success -> {
                    log("LocalMode", "relogin success uid=${result.data.loginResponse.uid}")
                    NetWorkResult.Success(Unit)
                }
            }
        }
        logError("LocalMode", "relogin aborted: no stored credentials")
        return NetWorkResult.Error("需要重新登录后才能回到网络模式")
    }
}
