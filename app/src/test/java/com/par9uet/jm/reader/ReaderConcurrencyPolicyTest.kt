package com.par9uet.jm.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 解码并发只由硬件能力决定：低内存设备 1，其余设备 2。
 * 用户可调的「图片内存优化 / 并发解码数」设置已移除。
 */
class ReaderConcurrencyPolicyTest {

    @Test
    fun normalDeviceUsesHardwareDefaultOfTwo() {
        assertEquals(2, ReaderConcurrencyPolicy.imageWorkConcurrency(false, 512))
        assertEquals(2, ReaderConcurrencyPolicy.maxDecodeConcurrency(false, 512))
        assertEquals(
            2,
            ReaderConcurrencyPolicy.effectiveDecodeConcurrency(
                lowRamDevice = false,
                memoryClassMb = 512,
            ),
        )
    }

    @Test
    fun lowRamDeviceStaysAtOne() {
        assertEquals(1, ReaderConcurrencyPolicy.imageWorkConcurrency(true, 256))
        assertEquals(1, ReaderConcurrencyPolicy.maxDecodeConcurrency(true, 256))
        assertEquals(
            1,
            ReaderConcurrencyPolicy.effectiveDecodeConcurrency(
                lowRamDevice = true,
                memoryClassMb = 256,
            ),
        )
    }

    @Test
    fun smallMemoryClassClampedLikeLowRam() {
        // memoryClass < 384 与 lowRam 同样视为低内存。
        assertEquals(1, ReaderConcurrencyPolicy.imageWorkConcurrency(false, 256))
        assertEquals(
            1,
            ReaderConcurrencyPolicy.effectiveDecodeConcurrency(
                lowRamDevice = false,
                memoryClassMb = 256,
            ),
        )
    }

    @Test
    fun limiterFollowsHardwarePolicy() {
        val limiter = ReaderDynamicLimiter(
            ReaderConcurrencyPolicy.effectiveDecodeConcurrency(
                lowRamDevice = false,
                memoryClassMb = 512,
            ),
        )
        assertEquals(2, limiter.limit)
    }
}
