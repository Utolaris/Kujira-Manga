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
import com.par9uet.jm.core.network.isExplicitCredentialRejection
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
    /** 可注入时钟。只用于让测试能跨越恢复冷却窗口；生产一律用系统时钟。 */
    private val nowMillis: () -> Long = System::currentTimeMillis,
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
    private var lastRecovery: SessionRecoveryOutcome? = null

    /**
     * 连续「服务端**明确**说用户名/密码错误」的次数。
     *
     * 只有连续达到 [CREDENTIAL_REJECTION_CONFIRMATIONS] 次，才认定凭据真的失效并注销本地身份。
     *
     * 为什么不能一次就登出：18comic 把「真的凭据错误」和「对高频 `/login` 的软拒绝 / 风控」
     * 压在**同一个 401** 里，报文也一致（`無效的用戶名和\/或密碼！`）。一次拒绝即登出，
     * 等于让一次限流把用户踢下线 —— 这就是「已登录的 app 自动掉登录态」的成因。
     * 而官方实现根本不靠 401 管理登录态（见 [AuthAttemptOrigin] 的说明）。
     *
     * 任何一次成功、或任何非「明确拒绝」的结果都清零。所有读写都在
     * `boundRemoteGate -> loginMutex` 内，与 [lastRecovery] 同域。
     */
    private var credentialRejectionStreak = 0

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
            withContext(BoundAuthenticatedRequest) { block() }
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
        // Invalid credentials clear the identity without advancing generation, just like the
        // startup verifier. Deliver that error, but discard results across manual transitions.
        val invalidatedHere = result is NetWorkResult.Error &&
            result.authFailure == AuthFailure.InvalidCredentials &&
            currentSessionSnapshot() == UserSessionSnapshot(0, snapshot.generation)
        if (!isCurrent() && !invalidatedHere) throw CancellationException("Authenticated request session changed")
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
     * Refresh cookies for a rejected session without changing identity or invalidating Favorites.
     *
     * **单飞 + 冷却。** 会话失效通常会让多个在途请求同时失败（被踢后收藏同步、历史、签到一起报错），
     * 若每个请求各自登录，同一份凭据会被连发多次，而且后一次登录会把前一次刚恢复的会话再踢掉。
     * 这里只让排头发一次 [UserRepository.verifyLogin]，同时在锁外等待的调用者复用它的结果：
     * - 成功后在 [SESSION_RECOVERY_FRESHNESS_MS] 内复用成功结果（并发的兄弟请求直接重放自己的请求）；
     * - 失败后在一段**递增**冷却内复用失败结果，不再重试 —— 服务端已经拒绝或网络不通时，
     *   反复把密码发出去只会更糟。冷却按连续失败次数指数增长
     *   （[SESSION_RECOVERY_FAILURE_COOLDOWN_MS] 起步，翻倍，封顶
     *   [SESSION_RECOVERY_FAILURE_COOLDOWN_MAX_MS]）。
     *
     *   为什么不能是固定 5 秒：后端拥塞的时段（例如晚间），401 会成批出现，
     *   固定短冷却等于让本机每隔几秒就发一次带明文凭据的登录 —— 既是无效重试，
     *   也是最容易被风控盯上的形态。成功一次即清零，恢复正常节奏。
     *
     * 结果按 (accountId, generation) 归档：手动登录会推进 generation，登出会清空身份，
     * 因此换账号 / 重新登录后绝不会复用上一个会话的结论。
     *
     * @param origin 本次恢复的触发来源。只进日志与登录密度统计 —— 见 [AuthAttemptOrigin]：
     *   服务端对高频 `/login` 的软拒绝与「凭据真的失效」报文一致，必须靠频率与来源区分。
     */
    suspend fun recoverExpiredSession(
        accountId: Int,
        generation: Long,
        origin: AuthAttemptOrigin = AuthAttemptOrigin.UNSPECIFIED,
    ): NetWorkResult<Unit>? {
        cachedRecovery(accountId, generation)?.let { return it.result }
        return sessionRecoveryLock.withLock {
            cachedRecovery(accountId, generation)?.let { return@withLock it.result }
            val previousStreak = (lastRecovery?.takeIf { it.generation == generation }?.failureStreak ?: 0)
            val result = refreshRejectedSession(accountId, generation, origin)
            val succeeded = result is NetWorkResult.Success
            lastRecovery = SessionRecoveryOutcome(
                accountId = accountId,
                generation = generation,
                atMillis = nowMillis(),
                result = result,
                // 成功清零；失败累加（null 不缓存、也不累加）。
                failureStreak = when {
                    succeeded -> 0
                    result == null -> previousStreak
                    else -> previousStreak + 1
                },
            )
            result
        }
    }

    private fun cachedRecovery(accountId: Int, generation: Long): SessionRecoveryOutcome? {
        val cached = lastRecovery ?: return null
        if (cached.accountId != accountId || cached.generation != generation) return null
        // 「条件不满足」不是服务端的结论，缓存住会把恢复能力一起冻掉。
        val result = cached.result ?: return null
        val window = if (result is NetWorkResult.Success) {
            SESSION_RECOVERY_FRESHNESS_MS
        } else {
            recoveryFailureCooldownMillis(cached.failureStreak)
        }
        return cached.takeIf { nowMillis() - it.atMillis < window }
    }

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
            when (result) {
                is NetWorkResult.Error -> {
                    // 只有「服务端明确说用户名/密码错误」才算凭据失效的证据。
                    // 同一个 401 也可能是对高频 /login 的软拒绝，报文完全一致。
                    val explicitRejection = result.authFailure == AuthFailure.InvalidCredentials &&
                        result.message.isExplicitCredentialRejection()
                    credentialRejectionStreak =
                        if (explicitRejection) credentialRejectionStreak + 1 else 0
                    val confirmsLogout = explicitRejection &&
                        credentialRejectionStreak >= CREDENTIAL_REJECTION_CONFIRMATIONS
                    val unconfirmedRejection =
                        result.authFailure == AuthFailure.InvalidCredentials && !confirmsLogout
                    if (confirmsLogout) {
                        logError(
                            "Login",
                            "连续 $CREDENTIAL_REJECTION_CONFIRMATIONS 次被服务端明确拒绝" +
                                "（origin=$origin），认定凭据失效并注销本地身份",
                        )
                        clearIdentityWhileLocked(result.message)
                        sessionReadinessHolder.set(SessionReadiness.Unauthenticated)
                    } else if (unconfirmedRejection) {
                        logError(
                            "Login",
                            "自动重登被拒但未确认凭据失效" +
                                "（第 $credentialRejectionStreak/$CREDENTIAL_REJECTION_CONFIRMATIONS 次，" +
                                "origin=$origin）；**保留本地身份**。服务端可能只是在软拒绝高频登录。",
                        )
                    }
                    result.copy(
                        message = when {
                            confirmsLogout -> "登录会话已失效，请重新登录"
                            unconfirmedRejection -> "登录状态暂时无法确认，请稍后重试"
                            else -> result.message
                        },
                        kind = when {
                            confirmsLogout -> NetworkErrorKind.Authentication
                            unconfirmedRejection -> NetworkErrorKind.Network
                            result.authFailure == AuthFailure.TemporaryFailure -> NetworkErrorKind.Network
                            else -> result.kind
                        },
                        authFailure = if (unconfirmedRejection) {
                            // 降级为临时失败：上层据此提示「稍后重试」，而不是把用户踢到登录页。
                            AuthFailure.TemporaryFailure
                        } else {
                            result.authFailure
                        },
                    )
                }
                is NetWorkResult.Success -> {
                    if (result.data.loginResponse.uid != snapshot.user.id) {
                        return@withSessionTransition NetWorkResult.Error(
                            "恢复的登录账号不一致，请重新登录",
                            kind = NetworkErrorKind.Authentication,
                        )
                    }
                    if (!commitVerifiedCandidate(result.data, password = snapshot.user.password)) {
                        return@withSessionTransition NetWorkResult.Error(
                            "登录会话写入失败，请重新登录",
                            kind = NetworkErrorKind.Authentication,
                        )
                    }
                    sessionReadinessHolder.set(SessionReadiness.Authenticated)
                    NetWorkResult.Success(Unit)
                }
            }
        }
    }

    /** Performs a user-requested login without discarding the previous local identity on error. */
    suspend fun login(username: String, password: String): NetWorkResult<CandidateSession> {
        cancelBackgroundJob()
        val generation = beginManualLogin()
        val result = userRepository.login(username, password)
        return commitLoginResult(
            generation = generation,
            password = password,
            result = result,
            clearUserOnError = false,
        )
    }

    /**
     * 冷启动校验已有会话 —— **不发凭据**。
     *
     * 旧实现直接调 `userRepository.verifyLogin()`，而它与 `login()` 是同一个函数
     * （都走 `authenticateCandidate` → `POST /login`），于是**每次冷启动都会用明文密码登录一次**。
     * 对一个每天启停多次的客户端来说，这是最容易被风控当成自动化行为的形态；
     * 官方 app 的 JWT 有效期 1 小时，冷启动根本不发登录请求。
     *
     * 现在改为两段式：
     * 1. 用共享客户端里已持久化的 cookie 调 [UserRepository.probeActiveSession] 探活（1 个只读请求，无凭据）；
     * 2. 只有探活**明确判定会话失效**（`kind == Authentication`）才走 [recoverExpiredSession]
     *    —— 它自带单飞 + 冷却，并且本来就是这个用途。
     *
     * 身份处置沿用旧语义：只有明确 `InvalidCredentials` 才注销本地身份；
     * 离线/超时等临时错误保留缓存身份，避免「秒开时暂时没网 → 后台校验失败 → 用户被突然登出」。
     *
     * 注意：[recoverExpiredSession] 内部会自己取 `boundRemoteGate → loginMutex` 并提交状态，
     * 所以**不能**把它包在 [withSessionTransition] 里（Mutex 不可重入，会死锁）。
     */
    suspend fun verifyStoredLogin() {
        val snapshot = loginMutex.withLock {
            if (_userState.value.isLoading) return@withLock null
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
                    if (probe.kind != NetworkErrorKind.Authentication) {
                        // 临时失败保留缓存身份与已持久化的会话；仍按“已认证”对待，
                        // 避免收藏等请求在探活失败后一直空等。
                        withSessionTransition {
                            if (!isCurrentSession(snapshot)) return@withSessionTransition
                            sessionReadinessHolder.set(SessionReadiness.Authenticated)
                            _userState.update {
                                it.copy(isError = true, errorMsg = probe.message, isLoading = false)
                            }
                        }
                        return@runInBackground
                    }

                    // 会话确实已失效 —— 这才是唯一需要用凭据的场景。
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
                    // 恢复成功时的 readiness 与身份提交、以及明确 InvalidCredentials 时的
                    // 注销，都已经由 recoverExpiredSession 内部完成；这里只收尾 loading 与提示。
                    // null（账号已变 / 正在登录）不当作错误。
                    val errorToShow = (recovery as? NetWorkResult.Error)
                        ?.takeIf { it.authFailure != AuthFailure.InvalidCredentials }
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
            logError(LoginSessionGate.TAG, "commitVerifiedCandidate activate failed; clearing identity")
            clearIdentityWhileLocked("登录会话写入失败，请重新登录")
            return false
        }
        persistUserWhileLocked(candidate.loginResponse.toUser(password = password))
        // 真登录成功即重置「被明确拒绝」的连续计数：用户刚证明过凭据可用。
        credentialRejectionStreak = 0
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
        // 身份消失后「连续被拒次数」失去意义：下一次登录是全新的一轮。
        credentialRejectionStreak = 0
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
        val result: NetWorkResult<Unit>?,
        /** 连续失败次数（成功即清零）。用于把失败冷却做成指数退避。 */
        val failureStreak: Int = 0,
    )
}

/**
 * 并发失效请求复用同一次恢复成功的窗口。取得比一次登录往返更大的时间，才能让同一批
 * 一起失败的请求都只跟着重放、不再各自登录。
 */
private const val SESSION_RECOVERY_FRESHNESS_MS = 1_500L

/**
 * 恢复失败后的冷却窗口：期间不再尝试登录，避免请求风暴把凭据反复发给后端。
 * 按连续失败次数**指数增长**（5s → 10s → 20s → 40s → 80s → 封顶 120s），成功一次即清零。
 *
 * 固定 5 秒在后端拥塞时段（晚间 401 成批出现）等于每隔几秒发一次带明文凭据的登录，
 * 既是无效重试，也是最像自动化攻击的形态。
 */
private const val SESSION_RECOVERY_FAILURE_COOLDOWN_MS = 5_000L
private const val SESSION_RECOVERY_FAILURE_COOLDOWN_MAX_MS = 120_000L

/**
 * 「服务端**明确**说用户名/密码错误」要连续确认几次，才允许注销本地身份。
 *
 * 取 3 而不是 1 的理由：服务端把「真的凭据错误」与「对高频 `/login` 的软拒绝 / 风控」
 * 压在同一个 401 里（报文也一致），一次拒绝即登出等于让一次限流把用户踢下线。
 * 另一方面，真的改过密码的用户不该永远停在「登录状态无法确认」——
 * 配合指数退避冷却（5s → 10s → 20s …），连续 3 次至少跨约 15 秒，
 * 足以滤掉一次瞬时软拒绝，又不至于让凭据失效率悬太久。
 */
internal const val CREDENTIAL_REJECTION_CONFIRMATIONS = 3

/**
 * 指数退避：`base << (streak - 1)`，封顶 [SESSION_RECOVERY_FAILURE_COOLDOWN_MAX_MS]。
 * `streak <= 0`（成功或从未失败）时退回基础值。
 */
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
