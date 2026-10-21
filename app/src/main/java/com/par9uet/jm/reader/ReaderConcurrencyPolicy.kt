package com.par9uet.jm.reader

/**
 * 阅读解码并发策略（纯函数，便于单元测试）。
 *
 * 并发只由硬件能力决定：低内存设备 1，其余设备 2。不再暴露用户可调的
 * 「图片内存优化 / 并发解码数」设置——那套开关不降低网络分辨率，只是误导。
 */
internal object ReaderConcurrencyPolicy {
    /** 图片工作默认并发：低内存设备始终为 1。 */
    fun imageWorkConcurrency(lowRamDevice: Boolean, memoryClassMb: Int): Int =
        if (lowRamDevice || memoryClassMb < 384) 1 else 2
}
