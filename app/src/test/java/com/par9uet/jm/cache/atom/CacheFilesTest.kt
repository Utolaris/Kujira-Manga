package com.par9uet.jm.cache.atom

import com.par9uet.jm.cache.CacheArea
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CacheFilesTest {
    @Test fun `single subtree totals preserve nested caches unknown folders and loose files`() = runTest {
        val root = Files.createTempDirectory("cache-single-pass").toFile()
        try {
            mapOf(
                "common/deep/a" to 11, "download/chapter/b" to 13,
                "reader_pages/book/page" to 17, "http_cache/entry" to 19, "loose" to 23,
                "total/other" to 29,
            ).forEach { (path, bytes) ->
                File(root, path).apply { checkNotNull(parentFile).mkdirs(); writeBytes(ByteArray(bytes)) }
            }
            var externalReads = 0
            val sizes = DeviceCacheFiles(root, ExternalDownloadSize { externalReads++; 31L })
                .scan().associate { it.area to it.sizeBytes }
            assertEquals(11L, sizes[CacheArea.COMMON])
            assertEquals(44L, sizes[CacheArea.DOWNLOAD])
            assertEquals(17L, sizes[CacheArea.READER])
            assertEquals(143L, sizes[CacheArea.ALL])
            assertEquals(1, externalReads)
        } finally { root.deleteRecursively() }
    }

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
            files.remove(setOf(CacheArea.READER))
            assertEquals(40L, files.scan().single { it.area == CacheArea.ALL }.sizeBytes)
            File(root, "stray.tmp").writeBytes(ByteArray(8))
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

    @Test fun `missing areas are zero and removing one area preserves the rest`() = runTest {
        val root = Files.createTempDirectory("cache-single-area").toFile()
        try {
            File(root, "common").mkdirs()
            File(root, "common/a").writeBytes(ByteArray(100))
            val files = DeviceCacheFiles(root)
            val initial = files.scan().associate { it.area to it.sizeBytes }
            assertEquals(100L, initial[CacheArea.COMMON])
            assertEquals(0L, initial[CacheArea.DOWNLOAD])
            assertEquals(0L, initial[CacheArea.READER])
            File(root, "download").mkdirs()
            File(root, "download/b").writeBytes(ByteArray(16))
            files.remove(setOf(CacheArea.COMMON))
            assertFalse(File(root, "common").exists())
            assertEquals(16L, files.scan().single { it.area == CacheArea.DOWNLOAD }.sizeBytes)
        } finally { root.deleteRecursively() }
    }
}
