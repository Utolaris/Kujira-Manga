package com.par9uet.jm.cache.atom

import com.par9uet.jm.cache.CacheArea
import com.par9uet.jm.cache.CacheSize
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface CacheFiles {
    suspend fun scan(): List<CacheSize>
    /** Reader files are exclusively removed through the reader's cache generation protocol. */
    suspend fun remove(areas: Set<CacheArea>)
}

/**
 * 用户自定义下载目录（SAF）的字节数。未设置自定义路径时恒为 0。
 *
 * 设置了自定义目录后下载不再落在 `cacheDir` 下，只扫 `cacheDir` 会把下载统计成 0；
 * 该端口把外部目录的占用接回统计口径。没有自定义路径的实现返回 0，不改变原行为。
 */
fun interface ExternalDownloadSize {
    suspend fun sizeBytes(): Long
}

class DeviceCacheFiles(
    private val cacheDirectory: File,
    private val externalDownloadSize: ExternalDownloadSize = ExternalDownloadSize { 0L },
) : CacheFiles {
    override suspend fun scan(): List<CacheSize> = withContext(Dispatchers.IO) {
        // 外部目录不在 cacheDir 内，所以必须同时并入 DOWNLOAD 与 ALL：只加 DOWNLOAD 会让
        // `ALL - 命名合计` 的残差算出负数被吞掉，饼图里其他分区的占比跟着失真。
        val external = externalDownloadSize.sizeBytes()
        CacheArea.entries.map { area ->
            val local = size(directory(area))
            val total = if (area == CacheArea.DOWNLOAD || area == CacheArea.ALL) {
                local + external
            } else {
                local
            }
            CacheSize(area, total)
        }
    }

    override suspend fun remove(areas: Set<CacheArea>) = withContext(Dispatchers.IO) {
        val targets = if (CacheArea.ALL in areas) {
            cacheDirectory.listFiles().orEmpty().filterNot { it.name == "reader_pages" }
        } else areas.filterNot { it == CacheArea.READER }.map(::directory)
        targets.forEach { file ->
            check(!file.exists() || file.deleteRecursively()) { "部分缓存文件清理失败，请重试" }
        }
    }

    private fun directory(area: CacheArea): File = when (area) {
        CacheArea.ALL -> cacheDirectory
        else -> File(cacheDirectory, area.id)
    }

    private fun size(directory: File): Long = if (directory.exists()) {
        directory.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    } else 0L
}
