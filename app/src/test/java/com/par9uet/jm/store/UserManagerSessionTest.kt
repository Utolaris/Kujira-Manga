package com.par9uet.jm.store
import com.par9uet.jm.session.AuthenticatedSessionGate
import com.par9uet.jm.core.network.AuthenticatedSessionRequiredException
import com.par9uet.jm.session.CREDENTIAL_REJECTION_CONFIRMATIONS
import com.par9uet.jm.session.SessionReadiness
import com.par9uet.jm.session.SessionReadinessHolder
import com.par9uet.jm.core.SessionRecoveryException
import com.par9uet.jm.session.UserManager
import com.par9uet.jm.session.UserSessionSnapshot
import com.par9uet.jm.session.withAuthenticationRecovery
import com.par9uet.jm.favorites.sync.FavoriteSyncReport

import com.par9uet.jm.core.model.User
import com.par9uet.jm.session.CandidateSession
import com.par9uet.jm.session.UserRepository
import com.par9uet.jm.core.network.AuthAttemptOrigin
import com.par9uet.jm.core.network.AuthFailure
import com.par9uet.jm.retrofit.model.LoginResponse
import com.par9uet.jm.core.model.SignInData
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.core.network.NetworkErrorKind
import com.par9uet.jm.data.models.ActionResult
import com.par9uet.jm.data.models.ComicPage
import com.par9uet.jm.data.models.CommentPage
import com.par9uet.jm.storage.CookieStorage
import com.par9uet.jm.storage.UserStorage
import com.par9uet.jm.core.ToastManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 服务端真实报文形状（`/login` 被拒时）。注意 JSON 会把 `/` 转义成 `\/`，
 * 所以匹配规则只靠转义点之前的中文片段 —— 见 `isExplicitCredentialRejection`。
 */
private const val EXPLICIT_CREDENTIAL_REJECTION_MESSAGE =
    "内置API登录失败：Request failed with code: 401, error message: " +
        "{\"code\":401,\"data\":[],\"errorMsg\":\"無效的用戶名和\\/或密碼！\"}"

/** 单步跨过恢复冷却窗口（最坏 120s）所需的可注入时钟步进。 */
private const val SESSION_RECOVERY_WINDOW_STEP_MS = 10 * 60 * 1000L

/**
 * UserManager 会话状态机测试：验证 generation + 身份校验边界下，
 * 陈旧验证结果不能覆盖更新的登录/登出，临时失败保留身份，InvalidCredentials 才清除。
 *
 * 「掉登录态」的语义在 2026-09-20 收窄过：服务端把「真的凭据错误」和「对高频 /login 的
 * 软拒绝」压在同一个 401 里，所以改为**连续 [CREDENTIAL_REJECTION_CONFIRMATIONS] 次
 * 明确拒绝**才注销本地身份。见 `failedRecoveryPreservesNetworkError...`
 * 与 `repeatedExplicitCredentialRejectionClearsIdentityOnlyAfterConfirmationThreshold`。
 */
class UserManagerSessionTest {
    @Test
    fun identityWithoutCredentialsRequiresLoginInsteadOfCancellingRequest() = runBlocking {
        val cookies = FakeCookieStorage(listOf(avsCookie("expired")))
        val repository = GateUserRepository(cookies)
        val readiness = SessionReadinessHolder()
        manager(FakeUserStorage(user(1, "accountA", password = "")), cookies, repository, readiness)
        try {
            AuthenticatedSessionGate(readiness).run { error("Request must not start") }
            error("Expected login required")
        } catch (error: SessionRecoveryException) {
            assertTrue(error.error.message.contains("登录"))
        }
        assertFalse(repository.verifyStarted.isCompleted)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun accountSwitchWhileWaitingForReadinessNeverStartsOldRequest() = runTest {
        val cookies = FakeCookieStorage()
        val repository = GateUserRepository(cookies)
        repository.loginHandler = { _, _ -> NetWorkResult.Success(CandidateSession(
            loginResponse(2, "accountB"), listOf(avsCookie("B")),
        )) }
        val readiness = SessionReadinessHolder()
        val manager = manager(FakeUserStorage(user(1, "accountA")), cookies, repository, readiness)
        var calls = 0
        val request = async { AuthenticatedSessionGate(readiness).run { calls++ } }
        runCurrent()
        manager.login("accountB", "pwd")
        try { request.await(); error("Expected stale request cancellation") }
        catch (_: CancellationException) { }
        assertEquals(0, calls)
        assertEquals(2, manager.currentSessionSnapshot().accountId)
    }

    @Test
    fun authenticatedRequestRecoversOnceAndKeepsGeneration() = runBlocking {
        val cookies = FakeCookieStorage(listOf(avsCookie("expired")))
        val repository = GateUserRepository(cookies)
        repository.completeVerify(NetWorkResult.Success(CandidateSession(
            loginResponse(1, "accountA"), listOf(avsCookie("renewed")),
        )))
        val readiness = SessionReadinessHolder()
        val manager = manager(FakeUserStorage(user(1, "accountA")), cookies, repository, readiness)
        val snapshot = manager.currentSessionSnapshot()
        var calls = 0
        val result = AuthenticatedSessionGate(readiness).run {
            calls++
            if (cookies.get().single().value == "expired") {
                throw AuthenticatedSessionRequiredException("登录会话已失效，请重新登录")
            }
            "history or sign-in"
        }
        assertEquals("history or sign-in", result)
        assertEquals(2, calls)
        assertEquals(1, repository.activated.size)
        assertEquals(snapshot, manager.currentSessionSnapshot())
    }

    @Test
    fun failedRecoveryPreservesIdentityUntilExplicitRejectionIsConfirmed() = runBlocking {
        val failures = listOf(
            NetWorkResult.Error("offline", authFailure = AuthFailure.TemporaryFailure),
            NetWorkResult.Error(
                EXPLICIT_CREDENTIAL_REJECTION_MESSAGE,
                code = 401,
                authFailure = AuthFailure.InvalidCredentials,
            ),
        )
        for (failure in failures) {
            val cookies = FakeCookieStorage(listOf(avsCookie("expired")))
            val repository = GateUserRepository(cookies)
            repository.completeVerify(failure)
            val readiness = SessionReadinessHolder()
            val manager = manager(FakeUserStorage(user(1, "accountA")), cookies, repository, readiness)
            var calls = 0
            val error = try {
                AuthenticatedSessionGate(readiness).run<Unit> {
                    calls++
                    throw AuthenticatedSessionRequiredException("expired")
                }
                error("Expected recovery error")
            } catch (error: SessionRecoveryException) { error.error }
            assertEquals(1, calls)
            // 认证失败不再直接登出：一次拒绝只代表「未确认」。
            assertFalse(error.message.contains("请重新登录"))
            assertEquals(NetworkErrorKind.Network, error.kind)
            assertEquals(1, manager.currentSessionSnapshot().accountId)
            assertEquals("expired", cookies.get().single().value)
            assertEquals(SessionReadiness.Authenticated, readiness.state.value)
            assertEquals(SessionReadiness.Authenticated, manager.authState.value)
            // 恢复请求确实带着来源标注（日志与登录密度统计依赖它）。
            assertEquals(listOf(AuthAttemptOrigin.REQUEST_RECOVERY), repository.verifyOrigins)
        }
    }

    @Test
    fun retryIsBoundedAndOrdinaryNetworkFailureDoesNotStartRecovery() = runBlocking {
        val cookies = FakeCookieStorage(listOf(avsCookie("expired")))
        val repository = GateUserRepository(cookies)
        repository.completeVerify(
            NetWorkResult.Success(
                CandidateSession(loginResponse(1, "accountA"), listOf(avsCookie("renewed"))),
            ),
        )
        val readiness = SessionReadinessHolder()
        manager(FakeUserStorage(user(1, "accountA")), cookies, repository, readiness)
        val gate = AuthenticatedSessionGate(readiness)
        var calls = 0
        try {
            gate.run<Unit> { calls++; throw java.net.SocketTimeoutException("timeout") }
            error("Expected timeout")
        } catch (_: java.net.SocketTimeoutException) { }
        assertEquals(1, calls)
        assertFalse(repository.verifyStarted.isCompleted)
        calls = 0
        try {
            gate.run<Unit> { calls++; throw AuthenticatedSessionRequiredException("expired") }
            error("Expected auth failure")
        } catch (_: SessionRecoveryException) { }
        assertEquals(2, calls)
        assertEquals(1, repository.activated.size)
    }

    @Test
    fun requestCannotRetryAcrossLogoutOrAccountSwitch() = runBlocking {
        for (logout in listOf(true, false)) {
            val cookies = FakeCookieStorage(listOf(avsCookie("expired")))
            val repository = GateUserRepository(cookies)
            repository.loginHandler = { _, _ -> NetWorkResult.Success(CandidateSession(
                loginResponse(2, "accountB"), listOf(avsCookie("B")),
            )) }
            val readiness = SessionReadinessHolder()
            val manager = manager(FakeUserStorage(user(1, "accountA")), cookies, repository, readiness)
            var calls = 0
            val request = async {
                AuthenticatedSessionGate(readiness).run<Unit> {
                    calls++
                    throw AuthenticatedSessionRequiredException("expired")
                }
            }
            repository.verifyStarted.await()
            if (logout) manager.clearUser() else manager.login("accountB", "pwd")
            repository.completeVerify(NetWorkResult.Success(CandidateSession(
                loginResponse(1, "accountA"), listOf(avsCookie("renewed")),
            )))
            try { request.await(); error("Expected stale request cancellation") }
            catch (_: CancellationException) { }
            assertEquals(1, calls)
            assertEquals(if (logout) 0 else 2, manager.currentSessionSnapshot().accountId)
            assertFalse(repository.activated.any { it.loginResponse.uid == 1 })
        }
    }

    @Test
    fun nestedBoundRequestLeavesRecoveryToOwnerWithoutDeadlock() = runBlocking {
        val cookies = FakeCookieStorage(listOf(avsCookie("expired")))
        val repository = GateUserRepository(cookies)
        repository.completeVerify(NetWorkResult.Success(CandidateSession(
            loginResponse(1, "accountA"), listOf(avsCookie("renewed")),
        )))
        val readiness = SessionReadinessHolder()
        val manager = manager(FakeUserStorage(user(1, "accountA")), cookies, repository, readiness)
        val snapshot = manager.currentSessionSnapshot()
        var calls = 0
        val result = withTimeout(2_000) {
            withAuthenticationRecovery(
                isCurrent = { manager.isCurrentSession(snapshot.accountId, snapshot.generation) },
                recover = { manager.recoverExpiredSession(snapshot.accountId, snapshot.generation) },
            ) {
                try {
                    manager.withBoundRemoteSession(snapshot.accountId, snapshot.generation) {
                        AuthenticatedSessionGate(readiness).run {
                            calls++
                            if (cookies.get().single().value == "expired") {
                                throw AuthenticatedSessionRequiredException("expired")
                            }
                            NetWorkResult.Success(Unit)
                        }
                    }!!
                } catch (error: AuthenticatedSessionRequiredException) {
                    NetWorkResult.Error("expired", kind = com.par9uet.jm.core.network.NetworkErrorKind.Authentication)
                }
            }
        }
        assertTrue(result is NetWorkResult.Success)
        assertEquals(2, calls)
    }

    @Test
    fun expiredSessionRecoveryCannotRestoreLoggedOutAccount() = runBlocking {
        val cookies = FakeCookieStorage(listOf(avsCookie("expired")))
        val repository = GateUserRepository(cookies)
        val manager = manager(FakeUserStorage(user(1, "accountA")), cookies, repository)
        val snapshot = manager.currentSessionSnapshot()
        val recovery = async { manager.recoverExpiredSession(snapshot.accountId, snapshot.generation) }
        repository.verifyStarted.await()
        manager.clearUser()
        repository.completeVerify(NetWorkResult.Success(CandidateSession(
            loginResponse(1, "accountA"), listOf(avsCookie("renewed")),
        )))
        assertNull(recovery.await())
        assertTrue(repository.activated.isEmpty())
        assertTrue(cookies.get().isEmpty())
        assertEquals(0, manager.currentSessionSnapshot().accountId)
    }

    @Test
    fun expiredSessionRecoveryNeverLogsOutWithoutConfirmedRejection() = runBlocking {
        val cases: List<Triple<NetWorkResult<CandidateSession>, com.par9uet.jm.core.network.NetworkErrorKind, Boolean>> =
            listOf(
                // 恢复出来的是另一个账号：拒绝提升，身份保留。
                Triple(
                    NetWorkResult.Success(
                        CandidateSession(loginResponse(2, "accountB"), listOf(avsCookie("B"))),
                    ),
                    NetworkErrorKind.Authentication,
                    false,
                ),
                // 网络临时失败：保留身份与已持久化会话。
                Triple(
                    NetWorkResult.Error("offline", authFailure = AuthFailure.TemporaryFailure),
                    NetworkErrorKind.Network,
                    false,
                ),
                // 没有明确文案的 401：算「未确认」，同样保留身份。
                Triple(
                    NetWorkResult.Error("invalid", authFailure = AuthFailure.InvalidCredentials),
                    NetworkErrorKind.Network,
                    true,
                ),
            )
        for ((result, expectedKind, expectUnconfirmedMessage) in cases) {
            val cookies = FakeCookieStorage(listOf(avsCookie("expired")))
            val repository = GateUserRepository(cookies)
            repository.completeVerify(result)
            val manager = manager(FakeUserStorage(user(1, "accountA")), cookies, repository)
            val snapshot = manager.currentSessionSnapshot()
            val recovered = manager.recoverExpiredSession(snapshot.accountId, snapshot.generation)
            assertTrue(recovered is NetWorkResult.Error)
            assertEquals(expectedKind, (recovered as NetWorkResult.Error).kind)
            assertTrue(repository.activated.isEmpty())
            assertEquals("身份必须保留", "expired", cookies.get().single().value)
            assertEquals(snapshot, manager.currentSessionSnapshot())
            assertEquals(SessionReadiness.Authenticated, manager.authState.value)
            if (expectUnconfirmedMessage) {
                assertTrue(recovered.message.contains("无法确认"))
            }
        }
    }

    /**
     * 「连续明确拒绝」的确认阈值：`CREDENTIAL_REJECTION_CONFIRMATIONS` 次之内一律保留身份，
     * 达到阈值才注销。冷却窗口用可注入时钟跨过，避免测试真的等 5s + 10s。
     *
     * 这条测试是「已登录的 app 自动掉登录态」的回归防线：
     * 服务端对高频 `/login` 的软拒绝报文与真的凭据错误完全一致，
     * 若把一次拒绝当成凭据失效，用户就会在限流时段被反复踢下线。
     */
    @Test
    fun repeatedExplicitCredentialRejectionClearsIdentityOnlyAfterConfirmationThreshold() = runBlocking {
        val cookies = FakeCookieStorage(listOf(avsCookie("expired")))
        val repository = GateUserRepository(cookies)
        val readiness = SessionReadinessHolder()
        var clock = 0L
        val manager = UserManager(
            FakeUserStorage(user(1, "accountA")),
            cookies,
            repository,
            readiness,
            { clock },
        )
        val snapshot = manager.currentSessionSnapshot()
        repository.completeVerify(
            NetWorkResult.Error(
                EXPLICIT_CREDENTIAL_REJECTION_MESSAGE,
                code = 401,
                authFailure = AuthFailure.InvalidCredentials,
            ),
        )

        repeat(CREDENTIAL_REJECTION_CONFIRMATIONS - 1) { index ->
            val rejected = manager.recoverExpiredSession(snapshot.accountId, snapshot.generation)
            assertTrue(rejected is NetWorkResult.Error)
            assertEquals(
                "第 ${index + 1} 次明确拒绝不得登出",
                1,
                manager.currentSessionSnapshot().accountId,
            )
            assertEquals("expired", cookies.get().single().value)
            assertEquals(SessionReadiness.Authenticated, manager.authState.value)
            assertFalse((rejected as NetWorkResult.Error).message.contains("请重新登录"))
            clock += SESSION_RECOVERY_WINDOW_STEP_MS
        }
        assertEquals(CREDENTIAL_REJECTION_CONFIRMATIONS - 1, repository.verifyCalls)

        // 第 CREDENTIAL_REJECTION_CONFIRMATIONS 次：达到阈值，注销本地身份。
        val confirmed = manager.recoverExpiredSession(snapshot.accountId, snapshot.generation)
        assertEquals(CREDENTIAL_REJECTION_CONFIRMATIONS, repository.verifyCalls)
        assertTrue(confirmed is NetWorkResult.Error)
        assertTrue((confirmed as NetWorkResult.Error).message.contains("请重新登录"))
        assertEquals(UserSessionSnapshot(0, snapshot.generation), manager.currentSessionSnapshot())
        assertTrue(cookies.get().isEmpty())
        assertEquals(User.create(), manager.userState.value.data)
        assertEquals(SessionReadiness.Unauthenticated, manager.authState.value)
    }


    @Test
    fun concurrentExpiredRequestsShareOneRecoveryLogin() = runBlocking {
        val cookies = FakeCookieStorage(listOf(avsCookie("expired")))
        val repository = GateUserRepository(cookies)
        val manager = manager(FakeUserStorage(user(1, "accountA")), cookies, repository)
        val snapshot = manager.currentSessionSnapshot()

        val recoveries = (1..5).map {
            async(Dispatchers.Default) {
                manager.recoverExpiredSession(snapshot.accountId, snapshot.generation)
            }
        }
        repository.verifyStarted.await()
        repository.completeVerify(
            NetWorkResult.Success(
                CandidateSession(loginResponse(1, "accountA"), listOf(avsCookie("renewed"))),
            ),
        )

        val results = recoveries.map { it.await() }
        assertEquals("并发失效只应发一次登录，否则同一份凭据会被连发多次", 1, repository.verifyCalls)
        assertTrue(results.all { it is NetWorkResult.Success })
        assertEquals("renewed", cookies.get().single().value)
        assertEquals(1, manager.currentSessionSnapshot().accountId)
        assertEquals(SessionReadiness.Authenticated, manager.authState.value)
    }

    @Test
    fun failedSessionRecoveryIsNotRetriedWithinCooldown() = runBlocking {
        val cookies = FakeCookieStorage(listOf(avsCookie("expired")))
        val repository = GateUserRepository(cookies)
        val manager = manager(FakeUserStorage(user(1, "accountA")), cookies, repository)
        val snapshot = manager.currentSessionSnapshot()

        val first = async(Dispatchers.Default) {
            manager.recoverExpiredSession(snapshot.accountId, snapshot.generation)
        }
        repository.verifyStarted.await()
        repository.completeVerify(NetWorkResult.Error("offline", authFailure = AuthFailure.TemporaryFailure))
        assertTrue(first.await() is NetWorkResult.Error)

        val second = manager.recoverExpiredSession(snapshot.accountId, snapshot.generation)
        assertTrue(second is NetWorkResult.Error)
        assertEquals("冷却期内不应再发一次登录", 1, repository.verifyCalls)
        assertEquals("expired", cookies.get().single().value)
    }

    @Test
    fun expiredFavoritesSessionRecoversWithoutRestartOrIdentityChange() = runBlocking {
        val cookies = FakeCookieStorage(listOf(avsCookie("expired")))
        val repository = GateUserRepository(cookies)
        repository.completeVerify(NetWorkResult.Success(CandidateSession(
            loginResponse(1, "accountA"), listOf(avsCookie("renewed")),
        )))
        val manager = manager(FakeUserStorage(user(1, "accountA")), cookies, repository)
        val snapshot = manager.currentSessionSnapshot()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        var calls = 0
        try {
            val controller = com.par9uet.jm.favorites.sync.FavoriteSyncController(
                com.par9uet.jm.di.UserManagerFavoriteSession(manager),
                { _, _, _, _ ->
                    calls++
                    if (cookies.get().single().value == "expired") {
                        NetWorkResult.Error("登录会话已失效", kind = com.par9uet.jm.core.network.NetworkErrorKind.Authentication)
                    } else {
                        NetWorkResult.Success(FavoriteSyncReport(0, 0, 0, 0, 0))
                    }
                },
                scope,
            )
            controller.request(com.par9uet.jm.favorites.sync.FavoriteSyncRequestKind.MANUAL)
            withTimeout(2_000) {
                controller.state.first { !it.isSyncing }
            }
            assertNull(controller.state.value.errorMessage)
            assertEquals(2, calls)
            assertEquals("renewed", cookies.get().single().value)
            assertEquals(snapshot, manager.currentSessionSnapshot())
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun restoringSessionNeverFallsThroughToAuthenticatedWorkOnTimeout() = runTest {
        val readiness = SessionReadinessHolder().apply {
            set(SessionReadiness.Restoring)
        }
        val gate = AuthenticatedSessionGate(readiness)
        var requestCalls = 0
        val request = launch {
            gate.run { requestCalls++ }
        }

        advanceTimeBy(5_000)
        assertFalse(request.isCompleted)
        assertEquals(0, requestCalls)

        readiness.set(SessionReadiness.Authenticated)
        request.join()
        assertEquals(1, requestCalls)
    }

    @Test
    fun canceledAuthenticatedWaitNeverExecutesTheRequest() = runTest {
        val readiness = SessionReadinessHolder().apply {
            set(SessionReadiness.Restoring)
        }
        val gate = AuthenticatedSessionGate(readiness)
        var requestCalls = 0
        val request = launch {
            gate.run { requestCalls++ }
        }

        request.cancelAndJoin()
        readiness.set(SessionReadiness.Authenticated)

        assertTrue(request.isCancelled)
        assertEquals(0, requestCalls)
    }

    @Test
    fun unauthenticatedSessionRejectsRequestWithoutCallingIt() {
        val readiness = SessionReadinessHolder().apply {
            set(SessionReadiness.Unauthenticated)
        }
        val gate = AuthenticatedSessionGate(readiness)
        var requestCalls = 0

        assertThrows(AuthenticatedSessionRequiredException::class.java) {
            runBlocking { gate.run { requestCalls++ } }
        }
        assertEquals(0, requestCalls)
    }

    @Test
    fun authenticatedGatePropagatesCancellation() {
        val readiness = SessionReadinessHolder().apply {
            set(SessionReadiness.Authenticated)
        }
        val gate = AuthenticatedSessionGate(readiness)

        assertThrows(CancellationException::class.java) {
            runBlocking {
                gate.run<Unit> { throw CancellationException("stop") }
            }
        }
    }

    private class FakeUserStorage(initial: User = User.create()) : UserStorage {
        private val state = MutableStateFlow(initial)
        override fun get(): User = state.value
        override fun set(user: User) {
            state.value = user
        }

        override fun remove() {
            state.value = User.create()
        }
    }

    private class FakeCookieStorage(initial: List<Cookie> = emptyList()) : CookieStorage {
        private val _state = MutableStateFlow<List<Cookie>?>(initial)
        override val state: StateFlow<List<Cookie>?> = _state.asStateFlow()
        private val writes = mutableListOf<List<Cookie>>()

        override fun set(cookieStore: List<Cookie>): Boolean {
            writes.add(cookieStore)
            _state.value = cookieStore
            return true
        }

        override fun get(): List<Cookie> = _state.value ?: emptyList()
        override fun remove() {
            writes.add(emptyList())
            _state.value = emptyList()
        }

        fun writesCount(): Int = writes.size
    }


    /**
     * 可编排的网络替身。两个通道都要 gate：
     *
     * - [probeActiveSession] 是冷启动的**只读探活**（不发送凭据）。`verifyStoredLogin` 只用它。
     * - [verifyLogin] 是**真正的凭据登录**，只在探活判定会话失效后由
     *   `recoverExpiredSession` 调用。`verifyCalls` 统计它 —— 冷启动不该让它增加。
     *
     * 两者都模拟真实的不可取消阻塞网络调用：
     * gate 完成前即使外部 job 被取消，调用仍会完成（返回后由 ensureActive 中止提交）。
     */
    private class GateUserRepository(
        private val cookieStorage: CookieStorage,
    ) : UserRepository {
        val probeStarted = CompletableDeferred<Unit>()
        private val probeGate = CompletableDeferred<NetWorkResult<Unit>>()

        /** 真正发出的凭据登录次数：冷启动应为 0，并发失效恢复应被合并成 1。 */
        var verifyCalls = 0
            private set

        /** 每次凭据登录带的来源标注，用于断言 origin 确实传到了仓库层。 */
        val verifyOrigins = mutableListOf<AuthAttemptOrigin>()

        val verifyStarted = CompletableDeferred<Unit>()
        private val verifyGate = CompletableDeferred<NetWorkResult<CandidateSession>>()
        val activated = mutableListOf<CandidateSession>()
        var loginHandler: (suspend (String, String) -> NetWorkResult<CandidateSession>)? = null

        fun completeProbe(result: NetWorkResult<Unit>) {
            probeGate.complete(result)
        }

        fun completeVerify(result: NetWorkResult<CandidateSession>) {
            verifyGate.complete(result)
        }

        override suspend fun probeActiveSession(): NetWorkResult<Unit> {
            probeStarted.complete(Unit)
            return withContext(Dispatchers.Default + NonCancellable) {
                probeGate.await()
            }
        }

        override suspend fun verifyLogin(
            username: String,
            password: String,
            origin: AuthAttemptOrigin,
        ): NetWorkResult<CandidateSession> {
            verifyCalls++
            verifyOrigins += origin
            verifyStarted.complete(Unit)
            return withContext(Dispatchers.Default + NonCancellable) {
                verifyGate.await()
            }
        }

        override suspend fun login(
            username: String,
            password: String
        ): NetWorkResult<CandidateSession> {
            return checkNotNull(loginHandler).invoke(username, password)
        }

        override fun activateVerifiedSession(verified: CandidateSession): Boolean {
            activated += verified
            if (verified.embeddedCookies.isNotEmpty()) {
                cookieStorage.set(verified.embeddedCookies)
                return true
            }
            return false
        }

        override fun clearSession() = Unit

        override suspend fun getHistoryComicList(
            page: Int
        ): NetWorkResult<ComicPage> = NetWorkResult.Error("stub")

        override suspend fun deleteHistoryComic(id: Int): NetWorkResult<Unit> =
            NetWorkResult.Error("stub")

        override suspend fun getHistoryCommentList(
            page: Int,
            userId: Int
        ): NetWorkResult<CommentPage> = NetWorkResult.Error("stub")

        override suspend fun getSignData(userId: Int): NetWorkResult<SignInData> =
            NetWorkResult.Error("stub")

        override suspend fun signIn(userId: Int, dailyId: Int): NetWorkResult<ActionResult> =
            NetWorkResult.Error("stub")
    }

    private fun user(id: Int, name: String, password: String = "pwd"): User = User(
        id = id,
        username = name,
        password = password,
        avatar = "",
        level = 1,
        levelName = "M",
        currentLevelExp = 0,
        nextLevelExp = 100,
        currentCollectCount = 0,
        maxCollectCount = 100,
        jCoin = 0,
    )

    private fun loginResponse(id: Int, name: String): LoginResponse = LoginResponse(
        uid = id,
        username = name,
        email = "",
        photo = "",
        coin = "0",
        album_favorites = 0,
        level_name = "M",
        level = 1,
        nextLevelExp = 100,
        exp = 0,
        expPercent = 0.0,
        album_favorites_max = 100,
    )

    private fun avsCookie(value: String = "session-1"): Cookie = Cookie.Builder()
        .name("AVS")
        .value(value)
        .domain("18comic.vip")
        .path("/")
        .build()

    private fun manager(
        userStorage: UserStorage,
        cookieStorage: CookieStorage,
        repository: UserRepository,
        readiness: SessionReadinessHolder = SessionReadinessHolder(),
    ) = UserManager(userStorage, cookieStorage, repository, readiness)

    @Test
    fun staleProbeCannotOverwriteNewerManualLogin() = runBlocking {
        val userStorage = FakeUserStorage(user(1, "accountA"))
        val cookieStorage = FakeCookieStorage()
        val repository = GateUserRepository(cookieStorage)
        repository.loginHandler = { _, _ ->
            NetWorkResult.Success(
                CandidateSession(
                    loginResponse = loginResponse(2, "accountB"),
                    embeddedCookies = listOf(avsCookie("session-B")),
                )
            )
        }
        val readiness = SessionReadinessHolder()
        val manager = manager(userStorage, cookieStorage, repository, readiness)

        val verifier = launch { manager.verifyStoredLogin() }
        repository.probeStarted.await()

        // 探活 A 仍在进行时，用户手动登录 B 并完成。
        val loginResult = manager.login("accountB", "pwdB")
        assertTrue(loginResult is NetWorkResult.Success)

        // A 的探活随后返回「会话仍有效」—— 这是属于上一个 generation 的结论，必须被丢弃。
        repository.completeProbe(NetWorkResult.Success(Unit))
        verifier.join()

        // 活动身份仍然是 B；B 的会话 cookie 未被覆盖；A 也从未被激活过。
        assertEquals(2, manager.userState.value.data?.id)
        assertEquals("session-B", cookieStorage.get().single().value)
        assertTrue(repository.activated.any {
            it.embeddedCookies.any { c -> c.value == "session-B" }
        })
        // 冷启动探活不产生候选会话，因此 activated 里不该有任何来自 A 探活的条目。
        assertEquals(1, repository.activated.size)
        assertEquals(SessionReadiness.Authenticated, readiness.state.value)
    }

    @Test
    fun staleProbeCannotRestoreAfterLogout() = runBlocking {
        val userStorage = FakeUserStorage(user(1, "accountA"))
        val cookieStorage = FakeCookieStorage(listOf(avsCookie("session-A")))
        val repository = GateUserRepository(cookieStorage)
        val readiness = SessionReadinessHolder()
        val manager = manager(userStorage, cookieStorage, repository, readiness)

        val verifier = launch { manager.verifyStoredLogin() }
        repository.probeStarted.await()

        // 探活进行中用户登出。
        manager.clearUser()
        assertEquals(0, manager.userState.value.data?.id)
        val readinessAfterLogout = readiness.state.value

        repository.completeProbe(NetWorkResult.Success(Unit))
        verifier.join()

        // 登出保持有效：身份为空、cookie 存储被清空、readiness 不被陈旧探活改写。
        assertEquals(0, manager.userState.value.data?.id)
        assertTrue(cookieStorage.get().isEmpty())
        assertEquals(User.create(), userStorage.get())
        assertEquals(readinessAfterLogout, readiness.state.value)
        assertTrue(repository.activated.isEmpty())
    }

    @Test
    fun transientProbeFailureRetainsCachedIdentity() = runBlocking {
        val userStorage = FakeUserStorage(user(1, "accountA"))
        val cookieStorage = FakeCookieStorage(listOf(avsCookie("session-A")))
        val repository = GateUserRepository(cookieStorage)
        val readiness = SessionReadinessHolder()
        val manager = manager(userStorage, cookieStorage, repository, readiness)

        val verifier = launch { manager.verifyStoredLogin() }
        repository.probeStarted.await()
        repository.completeProbe(
            NetWorkResult.Error("网络连接超时", authFailure = AuthFailure.TemporaryFailure)
        )
        verifier.join()

        assertEquals(1, manager.userState.value.data?.id)
        assertEquals("session-A", cookieStorage.get().single().value)
        assertTrue(repository.activated.isEmpty())
        // 临时失败不是「会话失效」的证据：绝不能动用凭据。
        assertEquals(0, repository.verifyCalls)
        assertEquals(SessionReadiness.Authenticated, readiness.state.value)
        assertEquals(false, manager.userState.value.isLoading)
    }

    @Test
    fun expiredSessionFallsBackToCredentialLoginAndClearsOnlyAfterRepeatedRejection() = runBlocking {
        val userStorage = FakeUserStorage(user(1, "accountA"))
        val cookieStorage = FakeCookieStorage(listOf(avsCookie("session-A")))
        val repository = GateUserRepository(cookieStorage)
        val readiness = SessionReadinessHolder()
        var clock = 0L
        val manager = UserManager(userStorage, cookieStorage, repository, readiness) { clock }
        val snapshot = manager.currentSessionSnapshot()

        val verifier = launch { manager.verifyStoredLogin() }
        repository.probeStarted.await()
        // 探活明确判定会话失效 —— 只有这种情况才允许用凭据重新登录。
        repository.completeProbe(
            NetWorkResult.Error("登录会话已失效", kind = NetworkErrorKind.Authentication)
        )
        repository.verifyStarted.await()
        repository.completeVerify(
            NetWorkResult.Error(
                EXPLICIT_CREDENTIAL_REJECTION_MESSAGE,
                code = 401,
                authFailure = AuthFailure.InvalidCredentials,
            )
        )
        verifier.join()

        // 冷启动这条路径确实动了凭据，并标了来源。
        assertEquals(1, repository.verifyCalls)
        assertEquals(listOf(AuthAttemptOrigin.COLD_START_PROBE), repository.verifyOrigins)
        // 一次明确拒绝不足以登出：身份、持久化会话、readiness 全部保住。
        assertEquals(1, manager.userState.value.data?.id)
        assertEquals("session-A", cookieStorage.get().single().value)
        assertEquals(SessionReadiness.Authenticated, readiness.state.value)
        assertTrue(
            "应提示「稍后重试」而不是要求重新登录",
            manager.userState.value.errorMsg.orEmpty().contains("无法确认"),
        )

        // 继续被拒，达到确认阈值后才注销。
        repeat(CREDENTIAL_REJECTION_CONFIRMATIONS - 1) {
            clock += SESSION_RECOVERY_WINDOW_STEP_MS
            manager.recoverExpiredSession(snapshot.accountId, snapshot.generation)
        }
        assertEquals(0, manager.userState.value.data?.id)
        assertEquals(User.create(), userStorage.get())
        assertTrue(cookieStorage.get().isEmpty())
        assertEquals(SessionReadiness.Unauthenticated, readiness.state.value)
    }

    @Test
    fun coldStartWithHealthySessionNeverLogsIn() = runBlocking {
        val userStorage = FakeUserStorage(user(1, "accountA"))
        val cookieStorage = FakeCookieStorage(listOf(avsCookie("session-A")))
        val repository = GateUserRepository(cookieStorage)
        val readiness = SessionReadinessHolder()
        val manager = manager(userStorage, cookieStorage, repository, readiness)

        val verifier = launch { manager.verifyStoredLogin() }
        repository.probeStarted.await()
        // 探活只说「会话还有效」，不带任何凭据。
        repository.completeProbe(NetWorkResult.Success(Unit))
        verifier.join()

        // 这是 R1 的核心不变量：冷启动**一次凭据登录都不发**。
        assertEquals(0, repository.verifyCalls)
        assertTrue(repository.activated.isEmpty())
        // 已持久化的会话原样保留，没有被重写。
        assertEquals("session-A", cookieStorage.get().single().value)
        assertEquals(1, manager.userState.value.data?.id)
        assertEquals(SessionReadiness.Authenticated, readiness.state.value)
        assertEquals(false, manager.userState.value.isLoading)
    }

    @Test
    fun expiredSessionRecoveryPromotesFreshSessionWithAVS() = runBlocking {
        val userStorage = FakeUserStorage(user(1, "accountA"))
        val cookieStorage = FakeCookieStorage(listOf(avsCookie("stale-session")))
        val repository = GateUserRepository(cookieStorage)
        val readiness = SessionReadinessHolder()
        val manager = manager(userStorage, cookieStorage, repository, readiness)

        val verifier = launch { manager.verifyStoredLogin() }
        repository.probeStarted.await()
        repository.completeProbe(
            NetWorkResult.Error("登录会话已失效", kind = NetworkErrorKind.Authentication)
        )
        repository.verifyStarted.await()
        repository.completeVerify(
            NetWorkResult.Success(
                CandidateSession(
                    loginResponse = loginResponse(1, "accountA"),
                    embeddedCookies = listOf(avsCookie("session-A")),
                )
            )
        )
        verifier.join()

        // 过期才用凭据，且新会话（含 AVS）被提升为活动会话。
        assertEquals(1, repository.verifyCalls)
        assertEquals(1, manager.userState.value.data?.id)
        val activeCookie = cookieStorage.get().single()
        assertEquals("AVS", activeCookie.name)
        assertEquals("session-A", activeCookie.value)
        assertEquals(SessionReadiness.Authenticated, readiness.state.value)
    }

    @Test
    fun savedActiveSessionSurvivesProcessStyleReconstruction() = runBlocking {
        val savedUser = user(1, "accountA")
        val savedCookies = listOf(avsCookie("session-A"))
        val userStorage = FakeUserStorage(savedUser)
        val cookieStorage = FakeCookieStorage(savedCookies)
        val repository = GateUserRepository(cookieStorage)
        val readiness = SessionReadinessHolder()

        // 模拟进程重启：用同一持久化存储重建 UserManager。
        val manager = manager(userStorage, cookieStorage, repository, readiness)

        assertEquals(1, manager.userState.value.data?.id)
        assertEquals("accountA", manager.userState.value.data?.username)
        assertEquals("session-A", cookieStorage.get().single().value)
        // 持久化 cookie 可被 EmbeddedClientManager 同步恢复，认证功能可立即使用。
        assertEquals(SessionReadiness.Authenticated, readiness.state.value)
        assertEquals(SessionReadiness.Authenticated, manager.authState.value)
    }

    @Test
    fun cachedIdentityWithoutRestoredSessionIsRestoringNotLoggedOut() {
        val userStorage = FakeUserStorage(user(1, "accountA"))
        val cookieStorage = FakeCookieStorage()
        val repository = GateUserRepository(cookieStorage)
        val readiness = SessionReadinessHolder()

        val manager = manager(userStorage, cookieStorage, repository, readiness)

        assertEquals(1, manager.userState.value.data?.id)
        assertEquals(SessionReadiness.Restoring, manager.authState.value)
        assertFalse(manager.authState.value == SessionReadiness.Unauthenticated)
    }

    @Test
    fun noCachedIdentityIsUnauthenticatedAfterConstruction() {
        val userStorage = FakeUserStorage()
        val cookieStorage = FakeCookieStorage()
        val repository = GateUserRepository(cookieStorage)
        val manager = manager(userStorage, cookieStorage, repository)

        assertEquals(SessionReadiness.Unauthenticated, manager.authState.value)
    }

    @Test
    fun boundRemoteWorkRunsOnlyWhileSnapshotStaysCurrent() = runBlocking {
        val userStorage = FakeUserStorage(user(1, "accountA"))
        val cookieStorage = FakeCookieStorage(listOf(avsCookie()))
        val repository = GateUserRepository(cookieStorage)
        val manager = manager(userStorage, cookieStorage, repository)
        val snapshot = manager.currentSessionSnapshot()
        var remoteCalls = 0

        val committed = manager.withBoundRemoteSession(
            accountId = snapshot.accountId,
            generation = snapshot.generation,
        ) {
            remoteCalls++
            "result-A"
        }

        assertEquals("result-A", committed)
        assertEquals(1, remoteCalls)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun restorationCompletesWhileBoundFavoriteWaitsForReadiness() = runTest {
        val userStorage = FakeUserStorage(user(1, "accountA"))
        val cookies = FakeCookieStorage()
        val readiness = SessionReadinessHolder()
        val repository = object : UserRepository by GateUserRepository(cookies) {
            // 冷启动探活遇到临时网络问题：保留缓存身份，不需要（也不允许）动用凭据。
            override suspend fun probeActiveSession(): NetWorkResult<Unit> =
                NetWorkResult.Error("Temporary offline")
        }
        val manager = manager(userStorage, cookies, repository, readiness)
        val snapshot = manager.currentSessionSnapshot()
        val authGate = AuthenticatedSessionGate(readiness)
        val mutation = backgroundScope.async {
            manager.withBoundRemoteSession(snapshot.accountId, snapshot.generation) {
                authGate.run { "collected" }
            }
        }
        runCurrent()
        val verify = backgroundScope.launch { manager.verifyStoredLogin() }
        withTimeout(1_000) {
            verify.join()
            assertEquals("collected", mutation.await())
        }
        assertEquals(SessionReadiness.Authenticated, readiness.state.value)
    }

    @Test
    fun staleSnapshotNeverObtainsBoundRemoteCapability() = runBlocking {
        val userStorage = FakeUserStorage(user(1, "accountA"))
        val cookieStorage = FakeCookieStorage()
        val repository = GateUserRepository(cookieStorage)
        val manager = manager(userStorage, cookieStorage, repository)
        val staleGeneration = manager.currentSessionSnapshot().generation - 1L
        var remoteCalls = 0

        val committed = manager.withBoundRemoteSession(
            accountId = 1,
            generation = staleGeneration,
        ) {
            remoteCalls++
            "result"
        }

        assertNull(committed)
        assertEquals(0, remoteCalls)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sessionTransitionCannotDeadlockBoundRemoteLocalCommit() = runTest {
        val userStorage = FakeUserStorage(user(1, "accountA"))
        val cookieStorage = FakeCookieStorage(listOf(avsCookie()))
        val repository = GateUserRepository(cookieStorage)
        val manager = manager(userStorage, cookieStorage, repository)
        val snapshotA = manager.currentSessionSnapshot()
        val blockStarted = CompletableDeferred<Unit>()
        val allowLocalCommit = CompletableDeferred<Unit>()

        val boundJob = async {
            manager.withBoundRemoteSession(
                accountId = snapshotA.accountId,
                generation = snapshotA.generation,
            ) {
                blockStarted.complete(Unit)
                allowLocalCommit.await()
                manager.withCurrentSession(
                    accountId = snapshotA.accountId,
                    generation = snapshotA.generation,
                ) { "committed-A" }
            }
        }

        blockStarted.await()
        val logoutJob = async { manager.clearUser() }
        // Let logout reach its first contested lock before the bound work requests loginMutex.
        runCurrent()
        allowLocalCommit.complete(Unit)

        withTimeout(1_000) {
            assertEquals("committed-A", boundJob.await())
            logoutJob.await()
        }
        assertEquals(0, manager.userState.value.data?.id)
    }

    @Test
    fun manualLoginPromotesCandidateOnlyAfterItsNetworkResultReturns() = runTest {
        val userStorage = FakeUserStorage(user(1, "accountA"))
        val cookieStorage = FakeCookieStorage(listOf(avsCookie("session-A")))
        val repository = GateUserRepository(cookieStorage)
        val candidateStarted = CompletableDeferred<Unit>()
        val candidateResult = CompletableDeferred<NetWorkResult<CandidateSession>>()
        repository.loginHandler = { _, _ ->
            candidateStarted.complete(Unit)
            candidateResult.await()
        }
        val manager = manager(userStorage, cookieStorage, repository)

        val loginJob = async { manager.login("accountB", "pwdB") }
        candidateStarted.await()

        // The candidate network request is in flight. No shared-session promotion or identity
        // write may happen before UserManager enters its generation-checked commit.
        assertTrue(repository.activated.isEmpty())
        assertEquals(1, manager.userState.value.data?.id)
        assertEquals("session-A", cookieStorage.get().single().value)

        candidateResult.complete(
            NetWorkResult.Success(
                CandidateSession(
                    loginResponse = loginResponse(2, "accountB"),
                    embeddedCookies = listOf(avsCookie("session-B")),
                )
            )
        )

        assertTrue(loginJob.await() is NetWorkResult.Success)
        assertEquals(2, manager.userState.value.data?.id)
        assertEquals("session-B", cookieStorage.get().single().value)
        assertEquals(1, repository.activated.size)
    }

    @Test
    fun nonCancellableBoundRemoteFinishesBeforeManualLoginCandidateStarts() = runBlocking {
        val userStorage = FakeUserStorage(user(1, "accountA"))
        val cookieStorage = FakeCookieStorage(listOf(avsCookie("session-A")))
        val repository = GateUserRepository(cookieStorage)
        val candidateStarted = CompletableDeferred<Unit>()
        repository.loginHandler = { _, _ ->
            candidateStarted.complete(Unit)
            NetWorkResult.Success(
                CandidateSession(
                    loginResponse = loginResponse(2, "accountB"),
                    embeddedCookies = listOf(avsCookie("session-B")),
                )
            )
        }
        val manager = manager(userStorage, cookieStorage, repository)
        val snapshotA = manager.currentSessionSnapshot()
        val remoteStarted = CountDownLatch(1)
        val releaseRemote = CountDownLatch(1)

        val remoteJob = async(Dispatchers.IO) {
            manager.withBoundRemoteSession(snapshotA.accountId, snapshotA.generation) {
                remoteStarted.countDown()
                // Deliberately blocking Java primitive: coroutine cancellation cannot release it.
                check(releaseRemote.await(2, TimeUnit.SECONDS))
                "remote-A"
            }
        }
        assertTrue(remoteStarted.await(1, TimeUnit.SECONDS))

        val loginJob = async(Dispatchers.Default) { manager.login("accountB", "pwdB") }
        assertNull(withTimeoutOrNull(150) { candidateStarted.await() })

        releaseRemote.countDown()
        assertEquals("remote-A", withTimeout(2_000) { remoteJob.await() })
        withTimeout(2_000) { candidateStarted.await() }
        assertTrue(withTimeout(2_000) { loginJob.await() } is NetWorkResult.Success)
        assertEquals(2, manager.userState.value.data?.id)
    }
}
