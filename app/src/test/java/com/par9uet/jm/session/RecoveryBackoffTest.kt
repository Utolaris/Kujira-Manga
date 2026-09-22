package com.par9uet.jm.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 恢复登录失败冷却必须**指数退避**。
 *
 * 固定 5 秒在后端拥塞时段是有害的：401 会成批出现，于是本机每隔几秒就发一次带明文凭据的
 * 登录请求 —— 既救不回会话，也是最容易被风控当成自动化攻击的形态。
 */
class RecoveryBackoffTest {
    @Test
    fun cooldownDoublesWithConsecutiveFailures() {
        assertEquals(30_000L, recoveryFailureCooldownMillis(1))
        assertEquals(60_000L, recoveryFailureCooldownMillis(2))
        assertEquals(120_000L, recoveryFailureCooldownMillis(3))
        assertEquals(240_000L, recoveryFailureCooldownMillis(4))
        assertEquals(300_000L, recoveryFailureCooldownMillis(5))
    }

    @Test
    fun cooldownIsCapped() {
        // 封顶 300s：再往后不该无限增长，否则一次偶发后端故障会锁死恢复能力两小时。
        assertEquals(300_000L, recoveryFailureCooldownMillis(6))
        assertEquals(300_000L, recoveryFailureCooldownMillis(10))
        assertEquals(300_000L, recoveryFailureCooldownMillis(Int.MAX_VALUE))
    }

    @Test
    fun zeroOrNegativeStreakFallsBackToBase() {
        // 成功会清零 streak，下一次失败必须立刻回到基础冷却，而不是接着退避。
        assertEquals(30_000L, recoveryFailureCooldownMillis(0))
        assertEquals(30_000L, recoveryFailureCooldownMillis(-3))
    }

    @Test
    fun cooldownIsMonotonicNonDecreasing() {
        var previous = 0L
        for (streak in 1..20) {
            val current = recoveryFailureCooldownMillis(streak)
            assertTrue("streak=$streak 应不小于前一次", current >= previous)
            previous = current
        }
    }
}
