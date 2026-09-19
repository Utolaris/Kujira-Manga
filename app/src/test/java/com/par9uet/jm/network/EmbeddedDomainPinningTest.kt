package com.par9uet.jm.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * 域名固定策略：只有「被固定的那个域名连续实质失败」才允许重新竞速。
 *
 * 背景：SDK 的 `RetryAndDomainRedirectInterceptor` 每次重试都 `replaceHost(getBestDomain())`，
 * 而 `getBestDomain()` 是 argmin(failureCounts)（域名永不被判死）—— 后端整体变慢时
 * 每次失败都会换到另一个域名，导致 401 与"需要重新登录"反复出现。
 */
class EmbeddedDomainPinningTest {
    @Test
    fun collapseRequiresConsecutiveRealFailures() {
        val detector = DomainCollapseDetector(threshold = 3)
        assertFalse(detector.record(DomainAttemptOutcome.SERVER_ERROR))
        assertFalse(detector.record(DomainAttemptOutcome.TRANSPORT))
        // 第三次实质失败才触发
        assertTrue(detector.record(DomainAttemptOutcome.SERVER_ERROR))
        // 触发后计数归零，不会每次失败都炸一遍
        assertFalse(detector.record(DomainAttemptOutcome.SERVER_ERROR))
    }

    @Test
    fun successResetsTheStreak() {
        val detector = DomainCollapseDetector(threshold = 3)
        detector.record(DomainAttemptOutcome.SERVER_ERROR)
        detector.record(DomainAttemptOutcome.SERVER_ERROR)
        assertFalse(detector.record(DomainAttemptOutcome.SUCCESS))
        // 归零后重新累计，不再是「下一次就触发」
        assertFalse(detector.record(DomainAttemptOutcome.SERVER_ERROR))
        assertFalse(detector.record(DomainAttemptOutcome.SERVER_ERROR))
        assertTrue(detector.record(DomainAttemptOutcome.SERVER_ERROR))
    }

    @Test
    fun clientErrorsDoNotCountAsCollapse() {
        // 401/403 说明对端进程是活的（只是会话问题），绝不能据此换域名 ——
        // 那正是晚间「换域名 → 老会话不认 → 又 401」的死循环来源。
        val detector = DomainCollapseDetector(threshold = 2)
        assertFalse(detector.record(DomainAttemptOutcome.CLIENT_ERROR))
        assertFalse(detector.record(DomainAttemptOutcome.CLIENT_ERROR))
        assertFalse(detector.record(DomainAttemptOutcome.CLIENT_ERROR))
    }

    @Test
    fun clientErrorAlsoResetsAccumulatedFailures() {
        val detector = DomainCollapseDetector(threshold = 3)
        detector.record(DomainAttemptOutcome.SERVER_ERROR)
        detector.record(DomainAttemptOutcome.SERVER_ERROR)
        detector.record(DomainAttemptOutcome.CLIENT_ERROR)
        assertFalse(detector.record(DomainAttemptOutcome.SERVER_ERROR))
    }

    @Test
    fun thresholdMustBePositive() {
        runCatching { DomainCollapseDetector(threshold = 0) }
            .onSuccess { throw AssertionError("threshold = 0 应当被拒绝") }
    }

    @Test
    fun statusCodesMapToExpectedOutcomes() {
        assertEquals(DomainAttemptOutcome.SUCCESS, EmbeddedDomainPinning.outcomeForStatus(200))
        assertEquals(DomainAttemptOutcome.SUCCESS, EmbeddedDomainPinning.outcomeForStatus(204))
        assertEquals(DomainAttemptOutcome.CLIENT_ERROR, EmbeddedDomainPinning.outcomeForStatus(400))
        assertEquals(DomainAttemptOutcome.CLIENT_ERROR, EmbeddedDomainPinning.outcomeForStatus(401))
        assertEquals(DomainAttemptOutcome.CLIENT_ERROR, EmbeddedDomainPinning.outcomeForStatus(499))
        assertEquals(DomainAttemptOutcome.SERVER_ERROR, EmbeddedDomainPinning.outcomeForStatus(500))
        assertEquals(DomainAttemptOutcome.SERVER_ERROR, EmbeddedDomainPinning.outcomeForStatus(503))
        // 3xx 也是「对端应答了」——驱动换域名的理由必须是"后端崩了"，不是"这次没成"
        assertEquals(DomainAttemptOutcome.SUCCESS, EmbeddedDomainPinning.outcomeForStatus(302))
    }

    @Test
    fun transportErrorsMapToTransportOutcome() {
        assertEquals(
            DomainAttemptOutcome.TRANSPORT,
            EmbeddedDomainPinning.outcomeForError(SocketTimeoutException("timeout")),
        )
        assertEquals(
            DomainAttemptOutcome.TRANSPORT,
            EmbeddedDomainPinning.outcomeForError(IOException("connection reset")),
        )
    }
}
