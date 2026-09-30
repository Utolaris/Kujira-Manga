package com.par9uet.jm.favorites

import com.par9uet.jm.core.SessionRecoveryException
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.favorites.model.FavoriteSession
import com.par9uet.jm.favorites.model.FavoriteSessionSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [FavoriteSession] 的唯一忠实替身（此前有 4 个语义分叉的同名/近名实现）。
 *
 * 真实 `UserManager.withBoundRemoteSession` 把「快照仍是当前会话」与「远程能力归属于该会话」
 * 合成**一个原子边界**，并与所有会话转换在同一把 `boundRemoteGate` 上串行化。
 * 而接口给了它一个危险默认值：
 *
 * ```kotlin
 * suspend fun <T> withBoundRemoteSession(snapshot, block): T? = withCurrentSession(snapshot, block)
 * ```
 *
 * 旧替身没有重写它，于是退化成默认实现 —— 会话就绪门、与账号转换的串行化、
 * 缓存恢复错误上抛、`BoundAuthenticatedRequest` 上下文全部从回归范围里消失。
 * 后果已实测：把调用点从 `withBoundRemoteSession` 改成 `withCurrentSession`，
 * 全量 750 个用例仍然全绿（`SyncFavorites` 的绑定批次就是这样裸奔的）。
 *
 * 这里按契约建模四件事，任何一条被破坏都会让下面的用例变红：
 *  1. 快照过期 / 会话未就绪 -> `null`，远程块一次都不启动；
 *  2. 绑定块与 [switchAccount] / [signOut] / [reauthenticateSameAccount] 在同一把锁上
 *     串行化：批次进行中发起的会话转换必须等批次结束；
 *  3. 缓存了恢复失败时抛 [SessionRecoveryException]（冷却期语义）；
 *  4. 只有 `withBoundRemoteSession` 持有该锁 —— `withCurrentSession` 不持有，
 *     所以「把 bound 换成 current」会被 [switchAccount] 的时序断言抓住。
 */
internal class TestFavoriteSession(
    accountId: Int = 7,
    /** false 表示会话还没 restore 完（真实的 `awaitReady() != Authenticated`）。 */
    private val ready: Boolean = true,
) : FavoriteSession {
    /** 恢复行为；返回 null 表示不可续期。 */
    var recovery: suspend () -> NetWorkResult<Unit>? = { null }

    /** 恢复被调用的次数（用于断言「只重登一次」）。 */
    var recoveryCalls = 0
        private set

    /** 是否正处在绑定批次内。恢复必须发生在批次之外，否则会与批次抢同一把锁。 */
    var boundBatchOpen = false
        private set

    /** 非 null 表示「上次恢复失败被缓存」，进入绑定块时按冷却错误上抛。 */
    var cachedRecoveryError: NetWorkResult.Error? = null

    private val sessionGate = Mutex()
    private var generation = 0L
    private val _session = MutableStateFlow(FavoriteSessionSnapshot(accountId, generation))

    override val sessionFlow: StateFlow<FavoriteSessionSnapshot> = _session
    override val accountIdFlow: kotlinx.coroutines.flow.Flow<Int> = _session.map { it.accountId }

    /** 绑定批次实际启动的快照（用于「过期快照一个批次都不开」的断言）。 */
    val boundBatchesStarted = mutableListOf<FavoriteSessionSnapshot>()

    /** 所有尝试过绑定边界的快照，含被拒绝的。 */
    val boundAttempts = mutableListOf<FavoriteSessionSnapshot>()

    var snapshotCalls = 0
        private set

    /** 在下一个绑定块**返回且释放会话锁之后**执行一次。
     *
     * 这个位置对应真实系统里「远程调用已返回、本地提交尚未发生」的窗口，
     * 也是唯一能合法插入会话转换的时机（批次持有锁期间转换会被串行化）。 */
    var afterNextBound: (suspend () -> Unit)? = null

    override fun currentAccountId(): Int = _session.value.accountId

    override fun snapshot(): FavoriteSessionSnapshot {
        snapshotCalls++
        return _session.value
    }

    override fun isCurrent(snapshot: FavoriteSessionSnapshot): Boolean =
        snapshot.accountId > 0 && snapshot == _session.value

    override suspend fun recoverExpiredSession(snapshot: FavoriteSessionSnapshot): NetWorkResult<Unit>? {
        recoveryCalls++
        // 恢复必须在绑定批次之外发起，否则就是与批次抢会话锁。
        check(!boundBatchOpen)
        return recovery()
    }

    override suspend fun <T> withCurrentSession(
        snapshot: FavoriteSessionSnapshot,
        block: suspend () -> T,
    ): T? = if (isCurrent(snapshot)) block() else null

    override suspend fun <T> withBoundRemoteSession(
        snapshot: FavoriteSessionSnapshot,
        block: suspend () -> T,
    ): T? {
        boundAttempts += snapshot
        if (!ready || !isCurrent(snapshot)) return null
        var started = false
        val result = sessionGate.withLock {
            // 锁内二次校验：等待期间可能已经有会话转换排队成功。
            if (!isCurrent(snapshot)) return@withLock null
            cachedRecoveryError?.let { throw SessionRecoveryException(it) }
            boundBatchesStarted += snapshot
            started = true
            boundBatchOpen = true
            try {
                block()
            } finally {
                boundBatchOpen = false
            }
        }
        // 钩子放在锁外：会话转换在批次内不可能生效，只能在批次结束后插入。
        if (started) {
            afterNextBound?.also { hook ->
                afterNextBound = null
                hook()
            }
        }
        return result
    }

    /**
     * 会话转换：与绑定块串行化（真实实现里的 `boundRemoteGate`）。
     * 批次进行中调用会**挂起**直到批次结束，而不是中途改掉 generation。
     */
    suspend fun switchAccount(newAccountId: Int) = sessionGate.withLock {
        generation += 1
        _session.value = FavoriteSessionSnapshot(newAccountId, generation)
    }

    suspend fun signOut() = sessionGate.withLock {
        generation += 1
        _session.value = FavoriteSessionSnapshot(0, generation)
    }

    /** 同账号重新认证：账号不变、generation 前进（接口契约要求这条流也要发射）。 */
    suspend fun reauthenticateSameAccount() = sessionGate.withLock {
        generation += 1
        _session.value = FavoriteSessionSnapshot(_session.value.accountId, generation)
    }
}
