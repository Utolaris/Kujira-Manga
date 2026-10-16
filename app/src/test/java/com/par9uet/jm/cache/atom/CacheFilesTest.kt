package com.par9uet.jm.cache.atom

import com.par9uet.jm.cache.CacheArea
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CacheFilesTest {
    @Test fun `all removes normal caches but never bypasses reader leases`() = runTest {
        val root = Files.createTempDirectory("cache-cleanup").toFile()
        try {
            listOf("download", "common", "reader_pages", "http_cache").forEach {
                File(root, it).mkdirs()
                File(root, "$it/content").writeBytes(ByteArray(10))
            }
            val files = DeviceCacheFiles(root)
            assertEquals(40L, files.scan().single { it.area == CacheArea.ALL }.sizeBytes)
            assertEquals(10L, files.scan().single { it.area == CacheArea.COMMON }.sizeBytes)
            files.remove(setOf(CacheArea.ALL, CacheArea.DOWNLOAD))
            assertEquals(listOf("reader_pages"), root.list()!!.toList())
            files.remove(setOf(CacheArea.READER))
            assertTrue(File(root, "reader_pages/content").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun `downloads outside cacheDir still land in the download and total figures`() = runTest {
        val root = Files.createTempDirectory("cache-external-download").toFile()
        try {
            // 用户把下载目录指到 SAF 树后，cacheDir/download 只剩迁移遗留的空壳。
            File(root, "download").mkdirs()
            File(root, "download/leftover").writeBytes(ByteArray(10))
            File(root, "common").mkdirs()
            File(root, "common/cover.bin").writeBytes(ByteArray(20))

            val files = DeviceCacheFiles(
                root,
                ExternalDownloadSize { 500L },
            )
            val sizes = files.scan().associate { it.area to it.sizeBytes }

            assertEquals(510L, sizes[CacheArea.DOWNLOAD])
            // ALL 必须一并加上外部字节：否则残差 `ALL - 命名合计` 变负被吞，其他分区占比失真。
            assertEquals(530L, sizes[CacheArea.ALL])
            assertEquals(20L, sizes[CacheArea.COMMON])
        } finally { root.deleteRecursively() }
    }

    @Test fun `no custom path keeps the previous all-equals-local behaviour`() = runTest {
        val root = Files.createTempDirectory("cache-no-external").toFile()
        try {
            File(root, "download").mkdirs()
            File(root, "download/a.bin").writeBytes(ByteArray(10))
            val sizes = DeviceCacheFiles(root).scan().associate { it.area to it.sizeBytes }
            assertEquals(10L, sizes[CacheArea.DOWNLOAD])
            assertEquals(10L, sizes[CacheArea.ALL])
        } finally { root.deleteRecursively() }
    }
}