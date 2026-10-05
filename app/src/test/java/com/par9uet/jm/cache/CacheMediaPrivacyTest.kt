package com.par9uet.jm.cache

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class CacheMediaPrivacyTest {
    @Test fun `marker is idempotent and does not hide the parent or change images`() {
        val parent = Files.createTempDirectory("comic-media-privacy").toFile()
        try {
            val comic = File(parent, "JM7").also(File::mkdirs)
            val image = File(comic, "0.webp").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
            ensureNoMediaFile(comic)
            ensureNoMediaFile(comic)
            assertTrue(File(comic, ".nomedia").isFile)
            assertEquals(0L, File(comic, ".nomedia").length())
            assertFalse(File(parent, ".nomedia").exists())
            assertArrayEquals(byteArrayOf(1, 2, 3), image.readBytes())
        } finally { parent.deleteRecursively() }
    }

    @Test fun `a directory named nomedia cannot be treated as a working marker`() {
        val comic = Files.createTempDirectory("invalid-media-marker").toFile()
        try {
            File(comic, ".nomedia").mkdir()
            assertTrue(runCatching { ensureNoMediaFile(comic) }.isFailure)
        } finally { comic.deleteRecursively() }
    }
}
