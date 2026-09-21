package com.par9uet.jm.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheBudgetTest {

    @Test
    fun `unlimited sentinel survives coerce`() {
        assertTrue(CacheBudget.isUnlimited(CacheBudget.UNLIMITED_MB))
        assertEquals(CacheBudget.UNLIMITED_MB, CacheBudget.coerceTotalMb(CacheBudget.UNLIMITED_MB))
        assertEquals(CacheBudget.UNLIMITED_MB, CacheBudget.snapToDiscreteStop(CacheBudget.UNLIMITED_MB))
    }

    @Test
    fun `coerce snaps to discrete stops and keeps unlimited out of finite range`() {
        assertEquals(512, CacheBudget.coerceTotalMb(256))
        assertEquals(512, CacheBudget.coerceTotalMb(400))
        assertEquals(512, CacheBudget.coerceTotalMb(768)) // 等距取较小档
        assertEquals(2048, CacheBudget.coerceTotalMb(2048))
        assertEquals(8192, CacheBudget.coerceTotalMb(10000))
        assertFalse(CacheBudget.isUnlimited(CacheBudget.coerceTotalMb(1024)))
    }

    @Test
    fun `unlimited component quotas use max technical budget`() {
        val maxMb = CacheBudget.MAX_TOTAL_MB
        assertEquals(
            CacheBudget.coverDiskCacheMb(maxMb, downloadExempt = true),
            CacheBudget.coverDiskCacheMb(CacheBudget.UNLIMITED_MB, downloadExempt = true),
        )
        assertEquals(
            CacheBudget.readerDiskCacheBytes(maxMb, downloadExempt = true),
            CacheBudget.readerDiskCacheBytes(CacheBudget.UNLIMITED_MB, downloadExempt = true),
        )
        assertEquals(
            CacheBudget.totalBytes(maxMb),
            CacheBudget.totalBytes(CacheBudget.UNLIMITED_MB),
        )
        assertEquals(0, CacheBudget.downloadCacheMb(maxMb, downloadExempt = true))
        assertEquals(
            CacheBudget.downloadCacheMb(maxMb, downloadExempt = false),
            CacheBudget.downloadCacheMb(CacheBudget.UNLIMITED_MB, downloadExempt = false),
        )
    }

    @Test
    fun `component bytes do not re-apply share`() {
        val componentMb = CacheBudget.coverDiskCacheMb(CacheBudget.UNLIMITED_MB, downloadExempt = true)
        assertEquals(componentMb.toLong() * 1024L * 1024L, CacheBudget.componentMbToBytes(componentMb))
        assertEquals(
            CacheBudget.coverDiskCacheBytes(CacheBudget.UNLIMITED_MB, downloadExempt = true),
            CacheBudget.componentMbToBytes(componentMb),
        )
        assertNotEquals(
            CacheBudget.coverDiskCacheBytes(componentMb, downloadExempt = true),
            CacheBudget.componentMbToBytes(componentMb),
        )
    }

    @Test
    fun `exempt redistributes download share into controlled components`() {
        val total = 1024
        val coverExempt = CacheBudget.coverDiskCacheMb(total, downloadExempt = true)
        val readerExempt = CacheBudget.readerDiskCacheMb(total, downloadExempt = true)
        val otherExempt = CacheBudget.otherCacheMb(total, downloadExempt = true)
        val coverFixed = CacheBudget.coverDiskCacheMb(total, downloadExempt = false)
        val readerFixed = CacheBudget.readerDiskCacheMb(total, downloadExempt = false)

        // 豁免时受控组件技术上限应高于固定 20%/40%（原 30% 下载份额按比例并入）
        assertTrue(coverExempt > coverFixed)
        assertTrue(readerExempt > readerFixed)
        assertEquals(0, CacheBudget.downloadCacheMb(total, downloadExempt = true))

        // 受控组件份额之和接近总额度（允许取整误差）
        val controlledSum = coverExempt + readerExempt + otherExempt
        assertTrue(controlledSum >= total - 8 && controlledSum <= total + 8)
    }
}
