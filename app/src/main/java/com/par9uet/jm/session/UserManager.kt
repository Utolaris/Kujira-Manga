package com.par9uet.jm.session
import com.par9uet.jm.core.network.AuthenticatedSessionRequiredException
import com.par9uet.jm.core.SessionRecoveryException
import com.par9uet.jm.core.ToastManager
import com.par9uet.jm.core.model.CommonUIState
import com.par9uet.jm.core.model.User
import com.par9uet.jm.session.CandidateSession
import com.par9uet.jm.session.UserRepository
import com.par9uet.jm.core.network.AuthFailure
import com.par9uet.jm.core.network.AuthAttemptOrigin
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.core.network.NetworkErrorKind
import com.par9uet.jm.storage.CookieStorage
import com.par9uet.jm.storage.UserStorage
import com.par9uet.jm.utils.log
import com.par9uet.jm.utils.logError
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * 用户会话状态机。
 *
 * 会话正确性边界：sessionGeneration。任何“把结果应用到活动会话”的提交都必须在
 * loginMutex 内做 generation + 身份双重校验；登录/验证网络请求本身始终在锁外执行。
 * Favorites 的阻塞式远程请求由独立 gate 与 transition 串行化，transition 等待时不占用
 * loginMutex，因此 guarded local commit 与账号切换之间不存在 ABBA。
 *
 * Cookie 提交统一走 [UserRepository.activateVerifiedSession]：只有 generation 仍然有效时
 * 才把候选/登录会话的完整 cookie（内置 API 含 AVS）持久化并同步到活动客户端。
 */
class UserManager(
    private val userStorage: UserStorage,
    private val cookieStorage: CookieStorage,
    private val userRepository: UserRepository,
    private val sessionReadinessHolder: SessionReadinessHolder,
    private val nightLocalModePrompt: NightLocalModePrompt,
    private val connectionMode: com.par9uet.jm.storage.ConnectionModePreferences,
    /** 单调时钟：校时或时区变化不能延长/跳过冷却；测试可注入。 */
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) : AuthenticatedRequestExecutor {
    private val _userState = MutableStateFlow(CommonUIState<User>())
    val userState = _userState.asStateFlow()

    /** Authoritative UI authentication state; Compose callers must not supply a fake false initial value. */
    val authState = sessionReadinessHolder.state
    private val loginMutex = Mutex()
    private val sessionGeneration = AtomicLong(0L)
    private val _sessionState = MutableStateFlow(UserSessionSnapshot(0, 0L))
    val sessionState = _sessionState.asStateFlow()

    /** Serializes automatic session recovery so a burst of expired requests performs ONE login. */
    private val sessionRecoveryLock = Mutex()
    @Volatile
    private var lastRecovery: SessionRecoveryOutcome? = null

    /** Serializes session transitions with session-bound remote work (see [withBoundRemoteSession]). */
    private val boundRemoteGate = Mutex()

    /**
     * Background work is cancellable when possible, but generation checks remain the correctness
     * boundary because embedded JMComic calls are synchronous Java/OkHttp operations.
     */
    @Volatile
    private var backgroundJob: Job? = null

    fun currentSessionSnapshot(): UserSessionSnapshot = _sessionState.value

    fun isCurrentSession(accountId: Int, generation: Long): Boolean {
        val currentUser = _userState.value.data ?: return false
        return sessionGeneration.get() == generation && currentUser.id == accountId
    }

    suspend fun <T> withCurrentSession(
        accountId: Int,
        generation: Long,
        block: suspend () -> T,
    ): T? = loginMutex.withLock {
        if (!isCurrentSession(accountId, generation)) return@withLock null
        block()
    }

    /**
     * Every session transition takes locks in exactly this order. Bound Favorites work already
     * owns [boundRemoteGate] when it performs its guarded local commit through [loginMutex], so
     * every path follows the same `bound -> login` order.
     */
    private suspend fun <T> withSessionTransition(block: suspend () -> T): T =
        boundRemoteGate.withLock {
            loginMutex.withLock { block() }
        }

    /**
     * 会话绑定的远程执行原语：把“快照仍是当前会话”校验与“远程能力归属于该会话”合并为
     * 一个正确性边界。会话转换（[beginManualLogin] / [clearUser] / [commitLoginResult]）与
     * 绑定远程工作在同一把 [boundRemoteGate] 上串行化：
     *
     *  - 快照已过期 → 远程块一次都不会启动；
     *  - 快照有效   → 远程块独占执行直到返回；并发会话转换必须等它结束后才推进 generation，
     *    因此不会出现“共享客户端已经变成 B，A 的调用仍在半路排队”的交错。
     *
     * 注意：不再依赖协程取消去中断阻塞式 JMComic 调用 —— 同步请求要么完整地跑在 A 的
     * 会话内（B 的登录在锁外等待），要么根本没有开始。loginMutex 不被长网络请求占用。
     */
    suspend fun <T> withBoundRemoteSession(
        accountId: Int,
        generation: Long,
        block: suspend () -> T,
    ): T? {
        if (!isCurrentSession(accountId, generation)) return null
        // Restoration commits need boundRemoteGate too; wait before taking that gate.
        if (sessionReadinessHolder.awaitReady() != SessionReadiness.Authenticated) return null
        return boundRemoteGate.withLock {
            if (!isCurrentSession(accountId, generation)) return@withLock null
            val cached = cachedRecovery(accountId, generation)
            (cached?.result as? NetWorkResult.Error)?.let { throw SessionRecoveryException(it) }
            try {
                withContext(BoundAuthenticatedRequest) { block() }.also {
                    // Only an authenticated business request proves recovery worked.
                    lastRecovery?.takeIf { it.accountId == accountId && it.generation == generation }
                        ?.let { lastRecovery = it.copy(failureStreak = 0) }
                }
            } catch (error: AuthenticatedSessionRequiredException) {
                if (error !is com.par9uet.jm.core.network.LocalModeUnavailableException) {
                    nightLocalModePrompt.recordAuthFailure(accountId)
                }
                if (cached?.result is NetWorkResult.Success) {
                    val failure = temporarySessionFailure(error)
                    recordRecovery(accountId, generation, failure)
                    throw SessionRecoveryException(failure)
                }
                throw error
            }
        }
    }

    init {
        // Restoring the local identity is cheap and keeps the first frame consistent with the
        // last session. Network verification is deliberately started after the UI is ready.
        // null = Keystore 暂不可读：保留未登录占位，不把空身份当成已恢复。
        _userState.value = _userState.value.copy(data = runCatching { userStorage.getOrNull() }.getOrNull())
        publishSession()
        sessionReadinessHolder.set(readinessForCachedUser(_userState.value.data))
        sessionReadinessHolder.requestExecutor = this
    }

    override suspend fun <T> execute(block: suspend () -> T): T {
        val snapshot = currentSessionSnapshot()
        val syncRequest = coroutineContext[LocalModeSyncRequest.Key]
        val verifiedLocalSync = syncRequest?.accountId == snapshot.accountId &&
            syncRequest.generation == snapshot.generation
        if (snapshot.accountId in connectionMode.localModeAccountIds.value && !verifiedLocalSync) {
            logError(
                "LocalMode",
                "execute blocked by local mode account=${snapshot.accountId} gen=${snapshot.generation}",
            )
            throw com.par9uet.jm.core.network.LocalModeUnavailableException()
        }
        if (snapshot.accountId <= 0) throw AuthenticatedSessionRequiredException()
        fun isCurrent() = isCurrentSession(snapshot.accountId, snapshot.generation)
        val result = withAuthenticationRecovery(
            isCurrent = ::isCurrent,
            recover = {
                recoverExpiredSession(
                    snapshot.accountId,
                    snapshot.generation,
                    AuthAttemptOrigin.REQUEST_RECOVERY,
                )
            },
        ) {
            try {
                // Wrapping in Success keeps a legitimate nullable SDK result distinct from a
                // stale session. The same identity/generation owns both attempts.
                withBoundRemoteSession(snapshot.accountId, snapshot.generation) {
                    NetWorkResult.Success(block())
                } ?: if (isCurrent()) {
                    throw AuthenticatedSessionRequiredException()
                } else {
                    throw CancellationException("Authenticated request session changed")
                }
            } catch (error: AuthenticatedSessionRequiredException) {
                NetWorkResult.Error(error.message.orEmpty(), kind = NetworkErrorKind.Authentication, cause = error)
            }
        }
        coroutineContext.ensureActive()
        if (!isCurrent()) throw CancellationException("Authenticated request session changed")
        return when (result) {
            is NetWorkResult.Success -> result.data
            is NetWorkResult.Error -> throw SessionRecoveryException(result)
        }
    }

    suspend fun clearUser() {
        cancelBackgroundJob()
        withSessionTransition {
            sessionGeneration.incrementAndGet()
            clearIdentityWhileLocked()
            sessionReadinessHolder.set(SessionReadiness.Unauthenticated)
        }
    }

    /**
     * One isolated recovery per session, with exponential cooldown after failure.
     * Automatic failures retain identity and cookies. A successful login is provisional until
     * an authenticated business request succeeds; immediate rejection pauses remote work too.
     */
    suspend fun recoverExpiredSession(
        accountId: Int,
        generation: Long,
        origin: AuthAttemptOrigin = AuthAttemptOrigin.UNSPECIFIED,
    ): NetWorkResult<Unit>? {
        cachedRecovery(accountId, generation)?.let { return it.result }
        return sessionRecoveryLock.withLock {
            cachedRecovery(accountId, generation)?.let { return@withLock it.result }
            refreshRejectedSession(accountId, generation, origin)
        }
    }

    private fun cachedRecovery(accountId: Int, generation: Long): SessionRecoveryOutcome? {
        val cached = lastRecovery ?: return null
        if (cached.accountId != accountId || cached.generation != generation) return null
        val result = cached.result
        val window = if (result is NetWorkResult.Success) {
            SESSION_RECOVERY_FRESHNESS_MS
        } else {
            recoveryFailureCooldownMillis(cached.failureStreak)
        }
        return cached.takeIf { nowMillis() - it.atMillis < window }
    }

    /** Called while holding boundRemoteGate (or withSessionTransition). */
    private fun recordRecovery(accountId: Int, generation: Long, result: NetWorkResult<Unit>) {
        val previousStreak = lastRecovery
            ?.takeIf { it.accountId == accountId && it.generation == generation }?.failureStreak ?: 0
        lastRecovery = SessionRecoveryOutcome(
            accountId = accountId,
            generation = generation,
            atMillis = nowMillis(),
            result = result,
            failureStreak = previousStreak + if (result is NetWorkResult.Error) 1 else 0,
        )
    }

    private fun temporarySessionFailure(cause: Throwable? = null) = NetWorkResult.Error(
        message = "登录状态暂时无法确认，已保留账号，请稍后重试；也可手动重新登录",
        kind = NetworkErrorKind.Network,
        authFailure = AuthFailure.TemporaryFailure,
        cause = cause,
    )

    private suspend fun refreshRejectedSession(
        accountId: Int,
        generation: Long,
        origin: AuthAttemptOrigin,
    ): NetWorkResult<Unit>? {
        val snapshot = loginMutex.withLock {
            if (!isCurrentSession(accountId, generation) || _userState.value.isLoading) {
                return@withLock null
            }
            val user = _userState.value.data?.takeIf {
                it.username.isNotBlank() && it.password.isNotEmpty()
            } ?: return@withLock null
            SessionSnapshot(generation, user)
        } ?: return null

        log("Login", "自动重登开始 origin=$origin account=$accountId generation=$generation")
        // Do not hold either session lock across the isolated login request. Logout and manual
        // account changes must remain possible even when the SDK call cannot be cancelled.
        val result = userRepository.verifyLogin(
            snapshot.user.username,
            snapshot.user.password,
            origin,
        )
        coroutineContext.ensureActive()
        return withSessionTransition {
            if (!isCurrentSession(snapshot)) return@withSessionTransition null
            val recovery = when (result) {
                is NetWorkResult.Error -> {
                    logError("Login", "自动恢复失败 origin=$origin；保留身份并暂停认证请求")
                    // 只把认证类失败算作「401/被踢」；网络抖动不触发夜间引导。
                    if (result.kind == NetworkErrorKind.Authentication ||
                        result.authFailure == AuthFailure.InvalidCredentials ||
                        result.code == 401
                    ) {
                        nightLocalModePrompt.recordAuthFailure(accountId)
                    }
                    temporarySessionFailure(result.cause).copy(code = result.code)
                }
                is NetWorkResult.Success -> {
                    if (result.data.loginResponse.uid != snapshot.user.id) {
                        temporarySessionFailure(IllegalStateException("恢复的登录账号不一致"))
                    } else if (!commitVerifiedCandidate(result.data, password = snapshot.user.password)) {
                        temporarySessionFailure(IllegalStateException("登录会话写入失败"))
                    } else {
                        NetWorkResult.Success(Unit)
                    }
                }
            }
            // Keep identity available; the remote gate observes the cooldown separately.
            sessionReadinessHolder.set(SessionReadiness.Authenticated)
            recordRecovery(accountId, generation, recovery)
            recovery
        }
    }

    /** Performs a user-requested login without discarding the previous local identity on error. */
    suspend fun login(username: String, password: String): NetWorkResult<CandidateSession> {
        cancelBackgroundJob()
        val generation = beginManualLogin()
        log("Login", "login begin generation=$generation")
        val result = userRepository.login(username, password)
        val committed = commitLoginResult(
            generation = generation,
            password = password,
            result = result,
            clearUserOnError = false,
        )
        when (committed) {
            is NetWorkResult.Error ->
                logError("Login", "login failed generation=$generation: ${committed.message} code=${committed.code}")
            is NetWorkResult.Success ->
                log("Login", "login committed uid=${committed.data.loginResponse.uid} generation=$generation")
        }
        return committed
    }

    /** Probe saved cookies without login; recover only after explicit authentication rejection. */
    suspend fun verifyStoredLogin() {
        val snapshot = loginMutex.withLock {
            if (_userState.value.isLoading) return@withLock null
            if (cachedRecovery(currentSessionSnapshot().accountId, sessionGeneration.get())?.result is NetWorkResult.Error) {
                return@withLock null
            }
            val user = _userState.value.data?.takeIf {
                it.username.isNotEmpty() && it.password.isNotEmpty()
            } ?: return@withLock null
            SessionSnapshot(sessionGeneration.get(), user)
        } ?: return

        log("检测到已保存了用户登录信息，后台校验已有会话（不发送凭据）")
        runInBackground {
            if (!isCurrentSession(snapshot)) return@runInBackground
            loginMutex.withLock {
                if (isCurrentSession(snapshot)) {
                    _userState.update {
                        it.copy(isLoading = true, isError = false, errorMsg = "")
                    }
                }
            }

            // 只读探活：用共享客户端里恢复出来的 cookie，不产生登录，也不改会话。
            val probe = userRepository.probeActiveSession()
            coroutineContext.ensureActive()
            if (!isCurrentSession(snapshot)) return@runInBackground

            when (probe) {
                is NetWorkResult.Success -> withSessionTransition {
                    if (!isCurrentSession(snapshot)) return@withSessionTransition
                    sessionReadinessHolder.set(SessionReadiness.Authenticated)
                    _userState.update { it.copy(isLoading = false) }
                }

                is NetWorkResult.Error -> {
                    if (probe.kind == NetworkErrorKind.Authentication) {
                        nightLocalModePrompt.recordAuthFailure(snapshot.user.id)
                    }
                    if (probe.kind != NetworkErrorKind.Authentication) {
                        // 临时失败保留缓存身份与已持久化的会话；仍按“已认证”对待，
                        // 避免收藏等请求在探活失败后一直空等。
                        withSessionTransition {
                            if (!isCurrentSession(snapshot)) return@withSessionTransition
                            recordRecovery(snapshot.user.id, snapshot.generation, temporarySessionFailure(probe.cause))
                            sessionReadinessHolder.set(SessionReadiness.Authenticated)
                            _userState.update {
                                it.copy(isError = true, errorMsg = probe.message, isLoading = false)
                            }
                        }
                        return@runInBackground
                    }

                    // 服务端拒绝当前会话，可尝试恢复；不能据此认定本地凭据失效。
                    //
                    // 关键：先把 isLoading 摘掉。refreshRejectedSession() 开头有
                    // `|| _userState.value.isLoading` 守卫，探活阶段置上的 loading 会让它直接
                    // 返回 null（表现为「恢复登录被静默跳过」）。
                    withSessionTransition {
                        if (isCurrentSession(snapshot)) {
                            _userState.update { it.copy(isLoading = false) }
                        }
                    }
                    log("已有会话已失效，改用保存的凭据恢复登录")
                    val recovery = recoverExpiredSession(
                        snapshot.user.id,
                        snapshot.generation,
                        AuthAttemptOrigin.COLD_START_PROBE,
                    )
                    coroutineContext.ensureActive()
                    // 恢复流程已处理 readiness；这里只收尾 loading 和临时失败提示。
                    // null（账号已变 / 正在登录）不当作错误。
                    val errorToShow = recovery as? NetWorkResult.Error
                    withSessionTransition {
                        if (!isCurrentSession(snapshot)) return@withSessionTransition
                        _userState.update {
                            it.copy(
                                isLoading = false,
                                isError = errorToShow != null,
                                errorMsg = errorToShow?.message.orEmpty(),
                            )
                        }
                    }
                }
            }
        }
    }

    /** Runs automatic sign-in with the same generation guard as saved-login verification. */
    suspend fun autoSignInIfNeeded(enabled: Boolean, toastManager: ToastManager) = runInBackground {
        if (!enabled) return@runInBackground
        val snapshot = loginMutex.withLock {
            if (_userState.value.isLoading) return@withLock null
            _userState.value.data
                ?.takeIf { it.id > 0 }
                ?.let { SessionSnapshot(sessionGeneration.get(), it) }
        } ?: return@runInBackground
        if (!isCurrentSession(snapshot)) return@runInBackground

        val signData = when (val result = userRepository.getSignData(snapshot.user.id)) {
            is NetWorkResult.Error -> return@runInBackground
            is NetWorkResult.Success -> result.data
        }
        coroutineContext.ensureActive()
        if (!isCurrentSession(snapshot)) return@runInBackground
        val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH)
        if (signData.dateMap[today]?.isSign == true) return@runInBackground

        when (val result = userRepository.signIn(snapshot.user.id, signData.dailyId)) {
            is NetWorkResult.Success -> {
                coroutineContext.ensureActive()
                if (isCurrentSession(snapshot)) toastManager.showAsync(result.data.message)
            }

            is NetWorkResult.Error -> log("自动签到", "签到失败：" + result.message)
        }
    }

    /** Refreshes the active account and clears it only when the server rejects its credentials. */
    suspend fun refreshAuthenticatedUser(username: String, password: String) {
        cancelBackgroundJob()
        val generation = beginManualLogin()
        val result = userRepository.login(username, password)
        commitLoginResult(
            generation = generation,
            password = password,
            result = result,
            clearUserOnError = true,
        )
    }

    /**
     * Runs [block] as the tracked background job and suspends until it finishes. The caller that
     * cancels this job does not join it, so a blocking embedded call cannot delay manual actions.
     */
    private suspend fun runInBackground(block: suspend () -> Unit) {
        coroutineScope {
            // LAZY 启动保证 cancel 与启动之间不存在“任务已跑起来但句柄还没登记”的窗口
            val job = launch(start = CoroutineStart.LAZY) { block() }
            backgroundJob = job
            job.invokeOnCompletion {
                if (backgroundJob === job) backgroundJob = null
            }
            job.start()
            job.join()
        }
    }

    private fun cancelBackgroundJob() {
        backgroundJob?.cancel()
    }

    private suspend fun beginManualLogin(): Long = withSessionTransition {
        val generation = sessionGeneration.incrementAndGet()
        lastRecovery = null
        publishSession()
        _userState.update {
            it.copy(
                isLoading = true,
                isError = false,
                errorMsg = ""
            )
        }
        generation
    }

    /**
     * 真登录成功：候选会话通过 gate，且 activate 把 cookie 写进活动会话。
     * 任一失败都不得留下「本地已登录、业务不可用」的状态。
     */
    private fun commitVerifiedCandidate(candidate: CandidateSession, password: String): Boolean {
        LoginSessionGate.validateCandidate(candidate)?.let { gateError ->
            logError(LoginSessionGate.TAG, "commitVerifiedCandidate gate: ${gateError.message}")
            return false
        }
        if (!userRepository.activateVerifiedSession(candidate)) {
            logError(LoginSessionGate.TAG, "commitVerifiedCandidate activate failed; retaining identity")
            return false
        }
        persistUserWhileLocked(candidate.loginResponse.toUser(password = password))
        log(
            LoginSessionGate.TAG,
            "commitVerifiedCandidate OK uid=${candidate.loginResponse.uid} " +
                "username=${candidate.loginResponse.username}",
        )
        return true
    }

    private suspend fun commitLoginResult(
        generation: Long,
        password: String,
        result: NetWorkResult<CandidateSession>,
        clearUserOnError: Boolean,
    ): NetWorkResult<CandidateSession> {
        coroutineContext.ensureActive()
        return withSessionTransition {
            if (sessionGeneration.get() != generation) return@withSessionTransition result
            when (result) {
                is NetWorkResult.Error -> {
                    if (clearUserOnError && result.authFailure == AuthFailure.InvalidCredentials) {
                        clearIdentityWhileLocked(result.message)
                        sessionReadinessHolder.set(SessionReadiness.Unauthenticated)
                    } else {
                        _userState.update {
                            it.copy(
                                isError = true,
                                errorMsg = result.message,
                            )
                        }
                    }
                }

                is NetWorkResult.Success<CandidateSession> -> {
                    if (!commitVerifiedCandidate(result.data, password = password)) {
                        val failed = NetWorkResult.Error(
                            "登录会话写入失败，请重新登录",
                            kind = NetworkErrorKind.Authentication,
                        )
                        _userState.update {
                            it.copy(isError = true, errorMsg = failed.message, isLoading = false)
                        }
                        return@withSessionTransition failed
                    }
                    sessionReadinessHolder.set(SessionReadiness.Authenticated)
                }
            }
            if (result !is NetWorkResult.Error || result.authFailure != AuthFailure.InvalidCredentials || !clearUserOnError) {
                _userState.update { it.copy(isLoading = false) }
            }
            result
        }
    }

    private fun persistUserWhileLocked(user: User) {
        _userState.update {
            it.copy(
                data = user,
                isError = false,
                errorMsg = ""
            )
        }
        userStorage.set(user)
        publishSession()
    }

    private fun clearIdentityWhileLocked(errorMsg: String? = null) {
        lastRecovery = null
        _userState.update {
            it.copy(
                data = User.create(),
                isLoading = false,
                isError = errorMsg != null,
                errorMsg = errorMsg.orEmpty(),
            )
        }
        userRepository.clearSession()
        userStorage.remove()
        cookieStorage.remove()
        publishSession()
    }

    private fun publishSession() {
        _sessionState.value = UserSessionSnapshot(
            accountId = _userState.value.data?.id ?: 0,
            generation = sessionGeneration.get(),
        )
    }

    private fun readinessForCachedUser(user: User?): SessionReadiness {
        val hasIdentity = user != null &&
            user.id > 0 &&
            user.username.isNotEmpty() &&
            user.password.isNotEmpty()
        if (!hasIdentity) return SessionReadiness.Unauthenticated
        val hasEmbeddedAuthCookie = cookieStorage.getOrNull()?.any {
            it.name.equals("AVS", ignoreCase = true)
        } ?: true // Keystore 暂不可读时按「可能已有会话」处理，避免误判为未认证
        return if (hasEmbeddedAuthCookie) {
            SessionReadiness.Authenticated
        } else {
            SessionReadiness.Restoring
        }
    }

    private fun isCurrentSession(snapshot: SessionSnapshot): Boolean {
        val currentUser = _userState.value.data ?: return false
        return sessionGeneration.get() == snapshot.generation &&
            currentUser.id == snapshot.user.id &&
            currentUser.username == snapshot.user.username
    }

    private data class SessionSnapshot(
        val generation: Long,
        val user: User,
    )

    /** One automatic recovery attempt and the window during which its result may be reused. */
    private data class SessionRecoveryOutcome(
        val accountId: Int,
        val generation: Long,
        val atMillis: Long,
        val result: NetWorkResult<Unit>,
        /** Only successful authenticated business work resets the failure streak. */
        val failureStreak: Int = 0,
    )
}

/** Share a recent successful login while its business requests confirm usability. */
private const val SESSION_RECOVERY_FRESHNESS_MS = 60_000L

/** Pause authenticated requests for 30s → 60s → 120s → 240s → 300s after failures. */
private const val SESSION_RECOVERY_FAILURE_COOLDOWN_MS = 30_000L
private const val SESSION_RECOVERY_FAILURE_COOLDOWN_MAX_MS = 300_000L

internal fun recoveryFailureCooldownMillis(streak: Int): Long {
    if (streak <= 1) return SESSION_RECOVERY_FAILURE_COOLDOWN_MS
    val shift = (streak - 1).coerceIn(0, 16)
    val backoff = SESSION_RECOVERY_FAILURE_COOLDOWN_MS shl shift
    return if (backoff <= 0) SESSION_RECOVERY_FAILURE_COOLDOWN_MAX_MS
    else backoff.coerceAtMost(SESSION_RECOVERY_FAILURE_COOLDOWN_MAX_MS)
}

data class UserSessionSnapshot(
    val accountId: Int,
    val generation: Long,
)
