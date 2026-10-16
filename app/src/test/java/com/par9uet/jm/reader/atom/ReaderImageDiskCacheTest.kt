package com.par9uet.jm.reader.atom

import com.par9uet.jm.reader.ReaderDecodeProfile
import com.par9uet.jm.reader.ReaderPageKey
import com.par9uet.jm.reader.readerCacheKey
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ReaderImageDiskCacheTest {
    private val kB = 1024L
    private val mb = 1024L * kB
    @Test
    fun `clear defers active source deletion until its lease is released`() = runBlocking {
        withDiskCache { directory, cache ->
            val pageKey = pageKey()
            val sourceFile = File(directory, "${readerCacheKey(pageKey, "source")}.source")
            sourceFile.writeBytes(byteArrayOf(1, 2, 3))

            val source = cache.acquireCachedSource(pageKey)
            assertNotNull(source)
            cache.clear()
            assertTrue(sourceFile.exists())

            cache.releaseSource(requireNotNull(source))
            assertFalse(sourceFile.exists())
        }
    }

    @Test
    fun `clear removes inactive source and decoded files`() = runBlocking {
        withDiskCache { directory, cache ->
            val pageKey = pageKey()
            val sourceFile = File(directory, "${readerCacheKey(pageKey, "source")}.source")
                .apply { writeBytes(byteArrayOf(1)) }
            val decodedFile = cache.decodedFile(pageKey, ReaderDecodeProfile.HIGH)
                .apply { writeBytes(byteArrayOf(2)) }

            cache.clear()

            assertFalse(sourceFile.exists())
            assertFalse(decodedFile.exists())
        }
    }

    @Test
    fun `clear preserves an active temporary source until release`() = runBlocking {
        withDiskCache { _, cache ->
            val generation = cache.currentGeneration()
            val temporary = cache.createSourceTempFile().apply {
                writeBytes(byteArrayOf(1, 2, 3))
            }
            val source = cache.transientSource(
                pageKey = pageKey(),
                file = temporary,
                generation = generation,
                persistAfterValidation = true,
            )

            cache.clear()
            assertTrue(temporary.exists())

            cache.releaseSource(source)
            assertFalse(temporary.exists())
        }
    }

    @Test
    fun `lowering the quota trims the cache right away instead of waiting for the next write`() =
        runBlocking {
            withDiskCache(maxBytes = 512 * mb) { directory, cache ->
                val oldest = sparsePage(directory, "oldest.webp", 100 * mb, lastModified = 1_000L)
                val middle = sparsePage(directory, "middle.webp", 100 * mb, lastModified = 2_000L)
                val newest = sparsePage(directory, "newest.webp", 100 * mb, lastModified = 3_000L)
                assertEquals(3, directory.listFiles().orEmpty().count(File::isFile))

                // 上限 200MB / 目标 175MB：从最旧开始删到 100MB，只留最新一页。
                cache.updateMaxBytes(200 * mb)

                awaitFiles(directory, expected = 1)
                assertFalse(oldest.exists())
                assertFalse(middle.exists())
                assertTrue(newest.exists())
            }
        }

    @Test
    fun `raising or repeating the quota keeps cached pages`() = runBlocking {
        withDiskCache(maxBytes = 64 * mb) { directory, cache ->
            repeat(3) { index ->
                sparsePage(
                    directory = directory,
                    name = "page-$index.webp",
                    sizeBytes = 8 * mb,
                    lastModified = 1_000L * (index + 1),
                )
            }

            cache.updateMaxBytes(64 * mb)
            cache.updateMaxBytes(256 * mb)

            delay(150)
            assertEquals(3, directory.listFiles().orEmpty().count(File::isFile))
        }
    }

    /**
     * 只把逻辑长度撑到 [sizeBytes]（稀疏文件），裁剪只看 `File.length()`，
     * 避免测试真的写几百 MB 数据。注意配额有 32MB 内部下限，取值要留足余量。
     */
    private fun sparsePage(
        directory: File,
        name: String,
        sizeBytes: Long,
        lastModified: Long,
    ): File = File(directory, name).apply {
        RandomAccessFile(this, "rw").use { it.setLength(sizeBytes) }
        setLastModified(lastModified)
    }

    /** 裁剪跑在 IO 协程里，等目录收敛到期望数量。 */
    private suspend fun awaitFiles(directory: File, expected: Int, timeoutMillis: Long = 5_000L) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (directory.listFiles().orEmpty().count(File::isFile) == expected) return
            delay(10)
        }
        fail("磁盘缓存未在 ${timeoutMillis}ms 内裁剪到 $expected 个文件")
    }

    private suspend fun withDiskCache(
        maxBytes: Long = 64 * mb,
        block: suspend (directory: File, cache: ReaderImageDiskCache) -> Unit,
    ) {
        val directory = Files.createTempDirectory("reader-disk-cache-test").toFile()
        val job = SupervisorJob()
        val cache = ReaderImageDiskCache(
            directory,
            CoroutineScope(job + Dispatchers.IO),
            maxBytes = maxBytes,
        )
        try {
            block(directory, cache)
        } finally {
            cache.close()
            job.cancel()
            directory.deleteRecursively()
        }
    }

    private fun pageKey() = ReaderPageKey(
        comicId = 7,
        pageIndex = 2,
        sourceIdentity = "https://example.test/media/photos/7/00003.webp",
        scrambleId = 0,
        speed = "1",
    )
}
