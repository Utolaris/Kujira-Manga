package com.par9uet.jm.network

import com.par9uet.jm.utils.log
import com.par9uet.jm.utils.logError
import io.github.jukomu.jmcomic.core.net.provider.DomainProbe
import io.github.jukomu.jmcomic.core.net.provider.JmDomainManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/** 一次请求尝试的结果，只用于判断「这个域名是不是真的崩了」。 */
internal enum class DomainAttemptOutcome {
    /** 2xx：域名健康。 */
    SUCCESS,

    /** 4xx：服务器活着，只是这次请求被拒（例如会话失效）。**不算崩溃**。 */
    CLIENT_ERROR,

    /** 5xx：后端有问题。 */
    SERVER_ERROR,

    /** 连接/读超时等传输层失败。 */
    TRANSPORT,
}

/**
 * 连续失败计数。只有 [DomainAttemptOutcome.SERVER_ERROR] / [DomainAttemptOutcome.TRANSPORT]
 * 会累加，任何一次 2xx 或 4xx 都清零 —— 4xx 说明对端进程是活的，不能据此判定「域名崩了」。
 *
 * 纯策略，不碰 SDK，便于单测。
 */
internal class DomainCollapseDetector(
    private val threshold: Int,
) {
    init {
        require(threshold >= 1) { "threshold must be >= 1" }
    }

    private var consecutiveFailures = 0

    /** @return true 表示达到阈值，应当重新竞速换域名。 */
    fun record(outcome: DomainAttemptOutcome): Boolean {
        return when (outcome) {
            DomainAttemptOutcome.SUCCESS, DomainAttemptOutcome.CLIENT_ERROR -> {
                consecutiveFailures = 0
                false
            }

            DomainAttemptOutcome.SERVER_ERROR, DomainAttemptOutcome.TRANSPORT -> {
                consecutiveFailures++
                if (consecutiveFailures >= threshold) {
                    consecutiveFailures = 0
                    true
                } else {
                    false
                }
            }
        }
    }

    fun reset() {
        consecutiveFailures = 0
    }
}

/**
 * 把 SDK 的「每次重试都重新竞速选域名」收敛成：
 * **冷启动竞速一次 → 本次 client 生命周期内只用那一个域名 → 只有它实质崩溃才重新竞速。**
 *
 * 为什么需要它（2026-09-19 晚间问题）：SDK 的 `RetryAndDomainRedirectInterceptor` 每次尝试都
 * `domainManager.getBestDomain()` 并 `replaceHost()`，而 `getBestDomain()` 是
 * **argmin(failureCounts)**（`DEAD_MARK` ≈ Integer.MAX_VALUE/2，域名永远不会被判死）。
 * 后端整体变慢时，每次失败都把当前域名推成最差，于是**重试必然换到另一个域名**。
 * 而服务端会话疑似按域名隔离 —— 换域名后老会话不被承认 → 401 → 恢复登录（又登在别的域名）
 * → 重放（再换）→ 又 401，表现为「一直弹需要重新登录」。
 *
 * 这里只用 SDK 的 public API：`getDomains` / `updateDomains` / `getBestDomain` /
 * `isInitialized` / `probeAllDomains`。**不修改 SDK**。
 */
internal class EmbeddedDomainPinning(
    private val domainManager: JmDomainManager,
    private val scope: CoroutineScope,
    private val probe: DomainProbe,
    private val readyTimeoutMs: Long = DEFAULT_READY_TIMEOUT_MS,
    collapseThreshold: Int = DEFAULT_COLLAPSE_THRESHOLD,
) {
    private val lock = Any()
    private val detector = DomainCollapseDetector(collapseThreshold)

    /** 固定前的完整候选池，用于「崩了」时重新竞速。 */
    private var candidatePool: List<String> = emptyList()
    private var pinnedDomain: String? = null
    private var reRacing = false

    /**
     * 等 SDK 自己的后台初始化（`updateDomains` + `probeAllDomains`）跑完 —— 那就是"冷启动竞速一次"，
     * 然后把它选出来的域名固定下来。
     *
     * 必须等初始化完成才 `getBestDomain()`：SDK 的 `blockUntilInitialized()` **没有超时**，
     * 如果初始化中途抛异常（`setInitialized(true)` 只在成功路径上），之后任何
     * `getBestDomain()` 都会永久阻塞线程。所以这里先有界地等 `isInitialized()`，
     * 等不到就放弃固定（保持现状，不会比现在更糟）。
     */
    fun start() {
        scope.launch {
            val ready = awaitInitialized()
            if (!ready) {
                logError(
                    "EmbeddedDomain",
                    "SDK 域名初始化 ${readyTimeoutMs}ms 内未完成，本次生命周期不做域名固定",
                )
                return@launch
            }
            synchronized(lock) {
                val pool = domainManager.domains.toList()
                val winner = domainManager.getBestDomain()
                if (pool.isEmpty() || winner.isNullOrBlank()) {
                    logError("EmbeddedDomain", "域名池为空，跳过固定")
                    return@launch
                }
                candidatePool = pool
                domainManager.updateDomains(listOf(winner))
                pinnedDomain = winner
                detector.reset()
                log("EmbeddedDomain", "已固定域名 $winner（候选池 ${pool.size} 个，本次生命周期不再切换）")
            }
        }
    }

    /** 供 OkHttp 拦截器按**每次尝试**调用。 */
    fun onAttempt(host: String, outcome: DomainAttemptOutcome) {
        val shouldRerace = synchronized(lock) {
            // 只在被固定的那个域名上判定。
            if (pinnedDomain == null || host != pinnedDomain) return
            detector.record(outcome)
        }
        if (shouldRerace) reRace()
    }

    /** 传输层异常（连接失败/超时）走这里 —— 拦截器拿不到 Response。 */
    fun onTransportFailure(host: String) = onAttempt(host, DomainAttemptOutcome.TRANSPORT)

    fun pinnedDomainOrNull(): String? = synchronized(lock) { pinnedDomain }

    private suspend fun awaitInitialized(): Boolean {
        val deadline = System.currentTimeMillis() + readyTimeoutMs
        while (!domainManager.isInitialized()) {
            if (System.currentTimeMillis() >= deadline) return false
            delay(POLL_INTERVAL_MS)
        }
        return true
    }

    /**
     * 固定域名连续失败到阈值 —— 才重新竞速一次。
     *
     * 顺序：恢复候选池 → `probeAllDomains`（同步探完所有候选）→ 取新的最优 → 再固定。
     * `probeAllDomains` 之后 `failureCounts` 里可达的域名归 0、不可达的累加，
     * 因此 `getBestDomain()` 会给出「候选池里第一个可达的域名」，确定且不会无限漂移。
     */
    private fun reRace() {
        synchronized(lock) {
            if (reRacing) return
            reRacing = true
        }
        scope.launch {
            val outcome = runCatching {
                val pool = synchronized(lock) { candidatePool }
                if (pool.isEmpty()) return@runCatching null
                domainManager.updateDomains(pool)
                domainManager.probeAllDomains(probe)
                domainManager.getBestDomain()
            }
            synchronized(lock) {
                val next = outcome.getOrNull()
                if (!next.isNullOrBlank()) {
                    domainManager.updateDomains(listOf(next))
                    pinnedDomain = next
                    detector.reset()
                    log("EmbeddedDomain", "固定域名疑似崩溃，重新竞速后改用 $next")
                } else {
                    // 重新竞速失败：保留原固定值，等下一次累计到阈值再试。
                    logError(
                        "EmbeddedDomain",
                        "重新竞速未选出可用域名：${outcome.exceptionOrNull()?.message}",
                    )
                }
                reRacing = false
            }
        }
    }

    internal companion object {
        /** 等 SDK 初始化的上限。超过就放弃固定（保持现状）。 */
        const val DEFAULT_READY_TIMEOUT_MS = 15_000L

        /** 连续多少次服务端/传输失败才认定「这个域名崩了」。 */
        const val DEFAULT_COLLAPSE_THRESHOLD = 6

        private const val POLL_INTERVAL_MS = 100L

        /**
         * 把 HTTP 状态码映射成尝试结果。
         *
         * 判据是「对端进程是否还活着」，不是「这次业务成不成功」：
         * - 2xx/3xx → 服务器活着（3xx 也是应答，SDK 默认还会跟随重定向）
         * - 4xx → 服务器活着，只是拒绝了这次请求（会话失效就属这类）
         * - 5xx 及其它 → 后端有问题
         */
        fun outcomeForStatus(code: Int): DomainAttemptOutcome = when {
            code in 200..399 -> DomainAttemptOutcome.SUCCESS
            code in 400..499 -> DomainAttemptOutcome.CLIENT_ERROR
            else -> DomainAttemptOutcome.SERVER_ERROR
        }

        /** 传输层异常（超时/连接失败）也算后端不可用。 */
        fun outcomeForError(error: Throwable): DomainAttemptOutcome =
            if (error is IOException) DomainAttemptOutcome.TRANSPORT else DomainAttemptOutcome.SERVER_ERROR
    }
}
