package com.par9uet.jm.cache

/**
 * 总缓存配额与内部分项。用户只调一个总预算；分项份额不对用户展示。
 *
 * 下载漫画默认不受缓存控制（`downloadExempt=true`）：
 * - 用户额度语义：受控已用（不含下载）与总预算比较。
 * - 组件技术上限：原 DOWNLOAD 30% 按比例并入 COMMON/READER，使受控组件用满预算。
 *
 * 下载受控时：COMMON 7/30 / READER 14/30 / DOWNLOAD 30%。
 *
 * 历史上存在过第三个受控分项 OTHER 10%：`pic_decode`（解码临时文件）与 `pdf_export`
 * 都在阅读器管线重写（f211db9）与 PDF 直写用户目录之后失去了写入方，分区已删除。
 * 这 10% 按 COMMON:READER 原比例（1:2）精确拆分并入两者，比例与合计均未变。
 *
 * 额度 UI 使用离散档位；「无限制」以 [UNLIMITED_MB] 哨兵值持久化。
 * 无限制时关闭超额语义，组件磁盘缓存仍按 [MAX_TOTAL_MB] 份额取技术上限。
 */
object CacheBudget {
    const val DEFAULT_TOTAL_MB = 1024
    const val MIN_TOTAL_MB = 256
    const val MAX_TOTAL_MB = 8192

    /** 无限制哨兵；不得被 coerce 成正数额度。 */
    const val UNLIMITED_MB = -1

    /** 设置页离散档位（MB，UNLIMITED_MB 除外）。 */
    val DISCRETE_STOPS_MB: List<Int> = listOf(512, 1024, 2048, 4096, 8192)

    private const val MB = 1024L * 1024L

    /** 下载受控时的推荐份额（相对总预算）。 */
    const val COVER_SHARE = 7f / 30f
    const val READER_SHARE = 14f / 30f
    const val DOWNLOAD_SHARE = 0.30f

    /** 受控组件合计份额（下载受控时 = 1 - DOWNLOAD_SHARE）。 */
    private val CONTROLLED_SHARE = COVER_SHARE + READER_SHARE

    fun isUnlimited(totalMb: Int): Boolean = totalMb == UNLIMITED_MB

    /** 归一到合法额度：无限哨兵保留；其余夹到 [MIN_TOTAL_MB]..[MAX_TOTAL_MB] 再 snap 离散档。 */
    fun coerceTotalMb(mb: Int): Int {
        if (isUnlimited(mb)) return UNLIMITED_MB
        val clamped = mb.coerceIn(MIN_TOTAL_MB, MAX_TOTAL_MB)
        return snapToDiscreteStop(clamped)
    }

    /** 距离相等时取较小档。 */
    fun snapToDiscreteStop(mb: Int): Int {
        if (isUnlimited(mb)) return UNLIMITED_MB
        return DISCRETE_STOPS_MB.minBy { stop -> kotlin.math.abs(stop - mb) }
    }

    /** 组件侧技术预算：无限时按 MAX 份额，避免磁盘缓存无界。 */
    private fun technicalTotalMb(totalMb: Int): Int =
        if (isUnlimited(totalMb)) MAX_TOTAL_MB else coerceFiniteTotalMb(totalMb)

    private fun coerceFiniteTotalMb(mb: Int): Int {
        if (isUnlimited(mb)) return MAX_TOTAL_MB
        val clamped = mb.coerceIn(MIN_TOTAL_MB, MAX_TOTAL_MB)
        return snapToDiscreteStop(clamped)
    }

    /**
     * 下载豁免时把 DOWNLOAD 份额按比例并入受控分项，避免 30% 悬空。
     * 受控时保持原固定份额。
     */
    private fun shareOf(base: Float, downloadExempt: Boolean): Float =
        if (downloadExempt) base / CONTROLLED_SHARE else base

    fun totalBytes(totalMb: Int): Long = technicalTotalMb(totalMb).toLong() * MB

    /** Same accounting for Settings and cold-start enforcement, including unnamed residuals. */
    fun controlledUsedBytes(scanned: List<CacheSize>, downloadExempt: Boolean): Long {
        val sizes = scanned.associate { it.area to it.sizeBytes }
        val named = listOf(CacheArea.COMMON, CacheArea.READER, CacheArea.DOWNLOAD).sumOf { sizes[it] ?: 0L }
        val total = sizes[CacheArea.ALL]?.takeIf { it > 0L } ?: named
        return if (downloadExempt) (total - (sizes[CacheArea.DOWNLOAD] ?: 0L)).coerceAtLeast(0L) else total
    }

    fun coverDiskCacheMb(totalMb: Int = DEFAULT_TOTAL_MB, downloadExempt: Boolean = true): Int =
        (technicalTotalMb(totalMb) * shareOf(COVER_SHARE, downloadExempt)).toInt().coerceAtLeast(64)

    fun readerDiskCacheMb(totalMb: Int = DEFAULT_TOTAL_MB, downloadExempt: Boolean = true): Int =
        (technicalTotalMb(totalMb) * shareOf(READER_SHARE, downloadExempt)).toInt().coerceAtLeast(64)

    /** 组件配额 MB → 字节。入参已是份额结果，禁止再按总额度二次分享额。 */
    fun componentMbToBytes(componentMb: Int): Long =
        componentMb.coerceAtLeast(0).toLong() * MB

    fun readerDiskCacheBytes(totalMb: Int = DEFAULT_TOTAL_MB, downloadExempt: Boolean = true): Long =
        readerDiskCacheMb(totalMb, downloadExempt).toLong() * MB
}
