package com.par9uet.jm.cache

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** R06: config index writes must never publish a truncated file as the readable index. */
class CacheConfigWriteTest {

    @Test
    fun `atomic text replace updates content and leaves no temporary files`() {
        val dir = Files.createTempDirectory("config-atomic").toFile()
        try {
            val config = File(dir, "config.json")
            writeTextAtomically(config, """{"v":1}""")
            assertEquals("""{"v":1}""", config.readText())
            writeTextAtomically(config, """{"v":2}""")
            assertEquals("""{"v":2}""", config.readText())
            assertEquals(listOf("config.json"), dir.list()!!.sorted())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `atomic text replace creates missing parents`() {
        val dir = Files.createTempDirectory("config-atomic-parent").toFile()
        try {
            val config = File(dir, "JM1/config.json")
            writeTextAtomically(config, """{"v":1}""")
            assertTrue(config.isFile)
            assertEquals("""{"v":1}""", config.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `failed staging leaves the previous committed index readable`() {
        val dir = Files.createTempDirectory("config-atomic-fail").toFile()
        val permissions = Files.getPosixFilePermissions(dir.toPath())
        try {
            val config = File(dir, "config.json")
            config.writeText("previous")
            // Existing target remains writable; staging a sibling must fail. A direct
            // overwrite would succeed and destroy the previously committed content.
            Files.setPosixFilePermissions(dir.toPath(), setOf(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE,
            ))
            org.junit.Assume.assumeFalse("Requires an unprivileged POSIX filesystem", Files.isWritable(dir.toPath()))
            assertTrue(config.canWrite())
            assertThrows(IOException::class.java) {
                writeTextAtomically(config, "replacement")
            }
            assertEquals("previous", config.readText())
            assertEquals(listOf("config.json"), dir.list()!!.sorted())
        } finally {
            Files.setPosixFilePermissions(dir.toPath(), permissions)
            dir.deleteRecursively()
        }
    }
}
